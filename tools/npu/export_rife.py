"""Converts RIFE's flow network to ONNX for the Snapdragon NPU.

RIFE (MIT licence) estimates, for a moment t between two frames, where every pixel of the
in-between frame comes from in each of them. For the app's hybrid design only that part runs
on the NPU, at low resolution:

    inputs : img0, img1  (1x3xHxW, RGB 0..1)   timestep (1x1xHxW, filled with t in 0..1)
    outputs: flow (1x4xHxW; xy offsets into img0, then xy offsets into img1, in pixels)
             mask (1x1xHxW; 0..1 blend weight of the img0 side)

The GPU then warps the full-resolution frames with the (upscaled) flow and blends them.

Model code and weights come from HolyWu/vs-rife (MIT), which mirrors Practical-RIFE releases.
"""

import argparse
import json
import os
import sys
import time
import urllib.request

import numpy as np
import torch
import torch.nn as nn

VSRIFE_RAW = "https://raw.githubusercontent.com/HolyWu/vs-rife/master/vsrife/"
WEIGHTS_URL = "https://github.com/HolyWu/vs-rife/releases/download/model/flownet_v{ver}.pkl"

# (module file, weights version)
MODELS = {
    "rife422lite": ("IFNet_HDv3_v4_22_lite", "4.22.lite"),
}
# width x height; both must be multiples of 32
SIZES = [(448, 256), (640, 352)]


def fetch(url, path):
    if not os.path.exists(path):
        print("downloading", url)
        urllib.request.urlretrieve(url, path)


class FlowNet(nn.Module):
    """RIFE's coarse-to-fine loop, returning flow and mask instead of the blended picture."""

    def __init__(self, net, warp, width, height):
        super().__init__()
        self.net = net
        self.warp = warp
        self.register_buffer("div", torch.tensor([(width - 1.0) / 2.0, (height - 1.0) / 2.0]))
        hor = torch.linspace(-1.0, 1.0, width).view(1, 1, 1, width).expand(-1, -1, height, -1)
        ver = torch.linspace(-1.0, 1.0, height).view(1, 1, height, 1).expand(-1, -1, -1, width)
        self.register_buffer("grid", torch.cat([hor, ver], 1).contiguous())

    def forward(self, img0, img1, timestep):
        n = self.net
        img0 = img0.clamp(0.0, 1.0)
        img1 = img1.clamp(0.0, 1.0)
        f0 = n.encode(img0)
        f1 = n.encode(img1)
        blocks = [n.block0, n.block1, n.block2, n.block3]
        flow = mask = feat = None
        w0, w1 = img0, img1
        for i in range(4):
            if flow is None:
                flow, mask, feat = blocks[i](
                    torch.cat((img0, img1, f0, f1, timestep), 1), None, scale=n.scale_list[i]
                )
            else:
                wf0 = self.warp(f0, flow[:, :2], self.div, self.grid)
                wf1 = self.warp(f1, flow[:, 2:4], self.div, self.grid)
                fd, mask, feat = blocks[i](
                    torch.cat((w0, w1, wf0, wf1, timestep, mask, feat), 1), flow, scale=n.scale_list[i]
                )
                flow = flow + fd
            if i < 3:
                w0 = self.warp(img0, flow[:, :2], self.div, self.grid)
                w1 = self.warp(img1, flow[:, 2:4], self.div, self.grid)
        return flow, torch.sigmoid(mask)


def shifted_pair(width, height, dx):
    """A smooth random texture and the same texture moved right by dx pixels."""
    g = torch.Generator().manual_seed(0)
    base = torch.rand(1, 3, height // 8, (width + 64) // 8, generator=g)
    base = torch.nn.functional.interpolate(base, size=(height, width + 64), mode="bicubic").clamp(0, 1)
    return base[:, :, :, 32:32 + width], base[:, :, :, 32 - dx:32 - dx + width]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="out")
    ap.add_argument("--work", default="work")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    pkg = os.path.join(args.work, "vsrife_min")
    os.makedirs(pkg, exist_ok=True)
    open(os.path.join(pkg, "__init__.py"), "w").close()
    fetch(VSRIFE_RAW + "warplayer.py", os.path.join(pkg, "warplayer.py"))
    sys.path.insert(0, args.work)

    import importlib
    import onnx
    import onnxruntime as ort

    info = {"models": []}
    for key, (module_name, ver) in MODELS.items():
        fetch(VSRIFE_RAW + module_name + ".py", os.path.join(pkg, module_name + ".py"))
        weights = os.path.join(args.work, f"flownet_v{ver}.pkl")
        fetch(WEIGHTS_URL.format(ver=ver), weights)
        module = importlib.import_module("vsrife_min." + module_name)
        warp = importlib.import_module("vsrife_min.warplayer").warp

        state = torch.load(weights, map_location="cpu")
        state = {k.replace("module.", ""): v for k, v in state.items() if "module." in k}
        net = module.IFNet()
        missing, unexpected = net.load_state_dict(state, strict=False)
        print(key, "missing keys:", list(missing), "unexpected:", list(unexpected))
        if missing:
            raise SystemExit("weights do not match the network definition")
        net.eval()
        params = sum(p.numel() for p in net.parameters())

        for width, height in SIZES:
            model = FlowNet(net, warp, width, height).eval()
            img0, img1 = shifted_pair(width, height, dx=6)
            t = torch.full((1, 1, height, width), 0.5)
            with torch.no_grad():
                flow, mask = model(img0, img1, t)
            centre = flow[0, :, height // 4: -height // 4, width // 4: -width // 4].mean(dim=(1, 2))
            print(f"{key} {width}x{height}: content moved +6 px in x between frames; "
                  f"flow at t=0.5 -> into img0 {centre[:2].tolist()}, into img1 {centre[2:].tolist()}")

            name = f"{key}_{width}x{height}"
            path = os.path.join(args.out, name + ".onnx")
            torch.onnx.export(
                model, (img0, img1, t), path,
                opset_version=17, do_constant_folding=True, dynamo=False,
                input_names=["img0", "img1", "timestep"], output_names=["flow", "mask"],
            )
            try:
                import onnxsim
                simplified, ok = onnxsim.simplify(onnx.load(path))
                if ok:
                    onnx.save(simplified, path)
            except Exception as e:  # simplification is optional
                print("onnxsim skipped:", e)

            graph = onnx.load(path).graph
            ops = {}
            for node in graph.node:
                ops[node.op_type] = ops.get(node.op_type, 0) + 1

            sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
            feed = {"img0": img0.numpy(), "img1": img1.numpy(), "timestep": t.numpy()}
            out = sess.run(None, feed)
            start = time.time()
            for _ in range(3):
                sess.run(None, feed)
            cpu_ms = (time.time() - start) / 3 * 1000
            err = float(np.abs(out[0] - flow.numpy()).max())
            print(f"{name}: onnx vs torch max flow error {err:.4f} px, CPU {cpu_ms:.0f} ms, ops {ops}")

            info["models"].append({
                "name": name, "file": name + ".onnx", "width": width, "height": height,
                "rife_version": ver, "parameters": params, "ops": ops,
                "size_mb": round(os.path.getsize(path) / 1e6, 1),
                "onnx_vs_torch_max_err_px": err, "github_runner_cpu_ms": round(cpu_ms),
                "shift_test_flow_into_img0": [round(v, 2) for v in centre[:2].tolist()],
                "shift_test_flow_into_img1": [round(v, 2) for v in centre[2:].tolist()],
            })

    with open(os.path.join(args.out, "models.json"), "w") as f:
        json.dump(info, f, indent=2)


if __name__ == "__main__":
    main()
