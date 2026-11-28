"""Prepares Video Depth Anything Small (ByteDance, Apache-2.0) for the 3D export.

Video Depth Anything looks at 32 frames at a time, so depth stays steady from frame to frame.
Run as one 32-frame graph it needs far more memory than the phone's NPU has (the per-frame
encoder's attention alone would be ~230 MB at fp16), so it is split into three graphs at the
places where frames are independent:

  enc  (1 frame)   picture 1x3x294x518 -> the four feature maps the head needs
  tmp  (32 frames) the two deep feature maps of 32 frames -> temporally mixed features
                   (all four temporal attention blocks live here; they only mix frames)
  dec  (1 frame)   mixed features + the frame's two fine feature maps -> depth 1x294x518

Running enc on each frame, tmp once per 32-frame window and dec on each frame gives exactly
what the full model gives; this script checks that on the real weights before saving.

Writes vda_s_{enc,tmp,dec}.onnx, vda.json and vda-report.md to --out. With the
QAI_HUB_API_TOKEN secret set it also times the three graphs on a Galaxy S24 on AI Hub.
"""

import argparse
import json
import os
import subprocess
import sys
import time
import traceback
import urllib.request

import numpy as np
import onnx
import onnxruntime as ort
import torch
import torch.nn as nn
import torch.nn.functional as F

VDA_REPO = "https://github.com/DepthAnything/Video-Depth-Anything.git"
VDA_COMMIT = "4f5ae23172ba60fd7bc11ef671cca678842c7072"
CKPT_URL = "https://huggingface.co/depth-anything/Video-Depth-Anything-Small/resolve/main/video_depth_anything_vits.pth"
WIDTH, HEIGHT, FRAMES = 518, 294, 32
PH, PW = HEIGHT // 14, WIDTH // 14


def fetch_code(work):
    src = os.path.join(work, "Video-Depth-Anything")
    if not os.path.exists(src):
        subprocess.check_call(["git", "clone", "-q", VDA_REPO, src])
    subprocess.check_call(["git", "-C", src, "checkout", "-q", VDA_COMMIT])
    sys.path.insert(0, src)
    return src


def load(work, random_weights=False):
    from video_depth_anything.video_depth import VideoDepthAnything
    from video_depth_anything.motion_module import attention as mm_attention

    # Plain attention (q @ k^T): exports cleanly and maps straight onto the NPU.
    def plain_attention(self, query, key, value, attention_mask=None):
        scores = torch.matmul(query, key.transpose(-1, -2)) * self.scale
        probs = scores.softmax(dim=-1)
        return self.reshape_batch_dim_to_heads(torch.matmul(probs, value))

    mm_attention.CrossAttention._attention = plain_attention

    m = VideoDepthAnything(encoder="vits", features=64, out_channels=[48, 96, 192, 384])
    if random_weights:
        # Local test without the checkpoint: make every layer (including the zero-initialised
        # temporal output layers) do something, so the equivalence check means something.
        with torch.no_grad():
            for p in m.parameters():
                p.normal_(0, 0.02)
    else:
        ckpt = os.path.join(work, "video_depth_anything_vits.pth")
        if not os.path.exists(ckpt):
            print("Downloading", CKPT_URL)
            urllib.request.urlretrieve(CKPT_URL, ckpt)
        m.load_state_dict(torch.load(ckpt, map_location="cpu", weights_only=True), strict=True)
    m.eval()

    # The position embedding is resized to the patch grid with a bicubic resize every call;
    # at our fixed size it is a constant, so bake it in.
    vit = m.pretrained
    with torch.no_grad():
        tokens = torch.zeros(1, PH * PW + 1, vit.embed_dim)
        fixed = vit.interpolate_pos_encoding(tokens, HEIGHT, WIDTH).detach().clone()
    vit.register_buffer("fixed_pos_embed", fixed, persistent=False)
    vit.interpolate_pos_encoding = lambda x, w, h: vit.fixed_pos_embed
    return m


class Enc(nn.Module):
    """One frame -> l1rn, l2rn (fine maps for the decoder), l3, l4 (deep maps for tmp)."""

    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, x):
        m = self.m
        head = m.head
        feats = m.pretrained.get_intermediate_layers(x, m.intermediate_layer_idx["vits"], return_class_token=True)
        out = []
        for i, f in enumerate(feats):
            t = f[0]
            t = t.permute(0, 2, 1).reshape((t.shape[0], t.shape[-1], PH, PW))
            t = head.projects[i](t)
            t = head.resize_layers[i](t)
            out.append(t)
        l1, l2, l3, l4 = out
        return head.scratch.layer1_rn(l1), head.scratch.layer2_rn(l2), l3, l4


class Tmp(nn.Module):
    """32 frames of l3, l4 -> path_3 (the temporally mixed map the decoder starts from)."""

    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, l3, l4):
        head = self.m.head
        B, T = 1, l3.shape[0]
        l3, _ = head.motion_modules[0](l3.unflatten(0, (B, T)).permute(0, 2, 1, 3, 4), None, None, None)
        l3 = l3.permute(0, 2, 1, 3, 4).flatten(0, 1)
        l4, _ = head.motion_modules[1](l4.unflatten(0, (B, T)).permute(0, 2, 1, 3, 4), None, None, None)
        l4 = l4.permute(0, 2, 1, 3, 4).flatten(0, 1)
        l3rn = head.scratch.layer3_rn(l3)
        l4rn = head.scratch.layer4_rn(l4)
        path_4 = head.scratch.refinenet4(l4rn, size=l3rn.shape[2:])
        path_4, _ = head.motion_modules[2](path_4.unflatten(0, (B, T)).permute(0, 2, 1, 3, 4), None, None, None)
        path_4 = path_4.permute(0, 2, 1, 3, 4).flatten(0, 1)
        path_3 = head.scratch.refinenet3(path_4, l3rn, size=(PH * 2, PW * 2))
        path_3, _ = head.motion_modules[3](path_3.unflatten(0, (B, T)).permute(0, 2, 1, 3, 4), None, None, None)
        return path_3.permute(0, 2, 1, 3, 4).flatten(0, 1)


class Dec(nn.Module):
    """One frame's path_3, l2rn, l1rn -> depth 1x294x518 (bigger = nearer)."""

    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, path_3, l2rn, l1rn):
        s = self.m.head.scratch
        path_2 = s.refinenet2(path_3, l2rn, size=l1rn.shape[2:])
        path_1 = s.refinenet1(path_2, l1rn)
        out = s.output_conv1(path_1)
        out = F.interpolate(out, (HEIGHT, WIDTH), mode="bilinear", align_corners=True)
        out = s.output_conv2(out)
        return F.relu(out)[:, 0]


def split_run(enc, tmp, dec, x):
    """x: 1,T,3,H,W -> T,H,W via the three parts."""
    parts = [enc(x[:, i]) for i in range(x.shape[1])]
    l1 = torch.cat([p[0] for p in parts])
    l2 = torch.cat([p[1] for p in parts])
    l3 = torch.cat([p[2] for p in parts])
    l4 = torch.cat([p[3] for p in parts])
    p3 = tmp(l3, l4)
    return torch.cat([dec(p3[i:i + 1], l2[i:i + 1], l1[i:i + 1]) for i in range(x.shape[1])])


def export(module, args, names_in, names_out, path):
    torch.onnx.export(module, args, path, input_names=names_in, output_names=names_out,
                      opset_version=17, do_constant_folding=True, dynamo=False)
    model = onnx.load(path)
    try:
        from onnxsim import simplify
        model, ok = simplify(model)
        if ok:
            onnx.save(model, path)
    except Exception as e:
        print("onnxsim skipped:", e)
    return onnx.load(path)


def time_on_aihub(out):
    """Compiles and times the three parts on a Galaxy S24 (AI Hub). Runs after publishing, so
    a slow AI Hub queue never holds the model back."""
    if not os.environ.get("QAI_HUB_API_TOKEN"):
        print("AI Hub timing skipped (no QAI_HUB_API_TOKEN secret).")
        return
    sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "npu"))
    import qai_hub as hub
    from aihub_profile import pick_device, status_of, summarise
    device = pick_device(hub)
    lines = [f"## AI Hub ({device.name})", ""]
    for part in ("enc", "tmp", "dec"):
        path = os.path.join(out, f"vda_s_{part}.onnx")
        try:
            job = hub.submit_compile_job(model=path, device=device, name=f"vda-{part}",
                                         options="--target_runtime precompiled_qnn_onnx")
            ok, msg = status_of(job)
            if not ok:
                lines.append(f"- {part}: compile failed: {msg[:300]}")
                continue
            prof = hub.submit_profile_job(model=job.get_target_model(), device=device, name=f"vda-{part}")
            ok, msg = status_of(prof)
            if not ok:
                lines.append(f"- {part}: profile failed: {msg[:300]}")
                continue
            s = summarise(prof.download_profile())
            lines.append(f"- {part}: **{s['inference_ms']} ms**, not on NPU: {s['layer_types_not_on_npu'] or 'none'}")
        except Exception as ex:
            traceback.print_exc()
            lines.append(f"- {part}: error {ex}")
        print(lines[-1], flush=True)
    with open(os.path.join(out, "vda-npu.md"), "w") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="out")
    ap.add_argument("--work", default="work")
    ap.add_argument("--random-weights", action="store_true", help="local test without the checkpoint")
    ap.add_argument("--time-only", action="store_true", help="only time the already exported parts on AI Hub")
    args = ap.parse_args()
    if args.time_only:
        return time_on_aihub(args.out)
    os.makedirs(args.out, exist_ok=True)
    os.makedirs(args.work, exist_ok=True)
    torch.set_grad_enabled(False)

    fetch_code(args.work)
    m = load(args.work, args.random_weights)
    enc, tmp, dec = Enc(m).eval(), Tmp(m).eval(), Dec(m).eval()

    # 1. The split must match the full model.
    torch.manual_seed(0)
    x = torch.randn(1, FRAMES, 3, HEIGHT, WIDTH)
    t0 = time.time()
    full = m(x)[0]
    full_s = time.time() - t0
    t0 = time.time()
    split = split_run(enc, tmp, dec, x)
    split_s = time.time() - t0
    err = (full - split).abs().max().item()
    scale = full.abs().max().item()
    print(f"full {full_s:.1f}s split {split_s:.1f}s, max diff {err:.2e} (of {scale:.3f})")
    if err > 1e-3 * max(scale, 1e-3):
        raise SystemExit("The split model doesn't match the full model")

    # 2. Export the three parts with fixed shapes.
    f_enc = os.path.join(args.out, "vda_s_enc.onnx")
    f_tmp = os.path.join(args.out, "vda_s_tmp.onnx")
    f_dec = os.path.join(args.out, "vda_s_dec.onnx")
    p = enc(x[:, 0])
    export(enc, (x[:, 0],), ["image"], ["l1rn", "l2rn", "l3", "l4"], f_enc)
    l3 = torch.randn(FRAMES, *p[2].shape[1:])
    l4 = torch.randn(FRAMES, *p[3].shape[1:])
    export(tmp, (l3, l4), ["l3", "l4"], ["path3"], f_tmp)
    p3 = tmp(l3, l4)
    export(dec, (p3[:1], p[1], p[0]), ["path3", "l2rn", "l1rn"], ["depth"], f_dec)

    # 3. The ONNX parts must match too (CPU), and time them.
    so = ort.SessionOptions()
    s_enc = ort.InferenceSession(f_enc, so, providers=["CPUExecutionProvider"])
    s_tmp = ort.InferenceSession(f_tmp, so, providers=["CPUExecutionProvider"])
    s_dec = ort.InferenceSession(f_dec, so, providers=["CPUExecutionProvider"])
    xs = x.numpy()
    ms = {}
    t0 = time.time()
    e = [s_enc.run(None, {"image": xs[:, i]}) for i in range(FRAMES)]
    ms["enc"] = (time.time() - t0) / FRAMES * 1000
    t0 = time.time()
    p3o = s_tmp.run(None, {"l3": np.concatenate([a[2] for a in e]), "l4": np.concatenate([a[3] for a in e])})[0]
    ms["tmp"] = (time.time() - t0) * 1000
    t0 = time.time()
    d = np.concatenate([s_dec.run(None, {"path3": p3o[i:i + 1], "l2rn": e[i][1], "l1rn": e[i][0]})[0] for i in range(FRAMES)])
    ms["dec"] = (time.time() - t0) / FRAMES * 1000
    onnx_err = float(np.abs(d - full.numpy()).max())
    print("onnx max diff", onnx_err, "cpu ms", ms)
    if onnx_err > 2e-3 * max(scale, 1e-3):
        raise SystemExit("The ONNX parts don't match the model")

    def shapes(sess):
        return {i.name: i.shape for i in sess.get_inputs()}, {o.name: o.shape for o in sess.get_outputs()}

    info = {
        "width": WIDTH, "height": HEIGHT, "frames": FRAMES,
        "files": {"enc": os.path.basename(f_enc), "tmp": os.path.basename(f_tmp), "dec": os.path.basename(f_dec)},
        "shapes": {"enc": shapes(s_enc), "tmp": shapes(s_tmp), "dec": shapes(s_dec)},
        "size_mb": {k: round(os.path.getsize(f) / 1e6, 1) for k, f in (("enc", f_enc), ("tmp", f_tmp), ("dec", f_dec))},
        "split_max_diff": err, "onnx_max_diff": onnx_err, "github_runner_cpu_ms": {k: round(v, 1) for k, v in ms.items()},
        "mean": [0.485, 0.456, 0.406], "std": [0.229, 0.224, 0.225],
        "windows": {"infer_len": 32, "overlap": 10, "interp_len": 8, "keyframes": [0, 12, 24, 25, 26, 27, 28, 29, 30, 31]},
        "source": f"{VDA_REPO}@{VDA_COMMIT}, weights {CKPT_URL} (Apache-2.0)",
    }
    lines = ["# Video Depth Anything Small for the 3D export", "",
             f"- split vs full model: max diff {err:.2e}; ONNX vs model: {onnx_err:.2e}",
             f"- sizes (MB): {info['size_mb']}",
             f"- CPU on the GitHub runner: {info['github_runner_cpu_ms']} ms (enc and dec per frame, tmp per window)", ""]

    lines.append("NPU timing: see the AI Hub step of this workflow run.")

    with open(os.path.join(args.out, "vda.json"), "w") as f:
        json.dump(info, f, indent=2)
    with open(os.path.join(args.out, "vda-report.md"), "w") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
