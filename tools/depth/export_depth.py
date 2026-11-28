"""Prepares the AI depth model for 2D-to-3D playback.

Downloads Depth Anything V2 Small (Apache-2.0) as ONNX from Hugging Face, fixes its input to
one 518x294 picture (16:9-ish, multiples of the model's 14-pixel patch) so the Snapdragon NPU
can compile it, simplifies it, checks it on the CPU, and (when the QAI_HUB_API_TOKEN secret is
set) times it on a real Galaxy S24 on Qualcomm AI Hub. Writes the model and report.md to --out.
"""

import argparse
import json
import os
import sys
import time
import traceback
import urllib.request

import numpy as np
import onnx
import onnxruntime as ort
from onnxsim import simplify

SOURCES = [
    "https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model.onnx",
    "https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model_fp32.onnx",
]
WIDTH, HEIGHT = 518, 294
NAME = f"depth_v2s_{WIDTH}x{HEIGHT}.onnx"


def download(work):
    os.makedirs(work, exist_ok=True)
    path = os.path.join(work, "source.onnx")
    for url in SOURCES:
        try:
            print("Downloading", url)
            urllib.request.urlretrieve(url, path)
            return path
        except Exception as e:
            print("  failed:", e)
    raise SystemExit("Could not download the depth model")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="out")
    ap.add_argument("--work", default="work")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    src = download(args.work)
    model = onnx.load(src)
    inp = model.graph.input[0].name
    outp = model.graph.output[0].name
    print("input", inp, "output", outp)
    model, ok = simplify(model, overwrite_input_shapes={inp: [1, 3, HEIGHT, WIDTH]})
    if not ok:
        raise SystemExit("onnxsim could not validate the simplified model")
    dst = os.path.join(args.out, NAME)
    onnx.save(model, dst)

    sess = ort.InferenceSession(dst, providers=["CPUExecutionProvider"])
    x = np.random.rand(1, 3, HEIGHT, WIDTH).astype(np.float32)
    sess.run(None, {inp: x})
    t0 = time.time()
    for _ in range(3):
        y = sess.run(None, {inp: x})[0]
    cpu_ms = (time.time() - t0) / 3 * 1000
    print("output shape", y.shape, "cpu ms", round(cpu_ms, 1))

    ops = {}
    for node in model.graph.node:
        ops[node.op_type] = ops.get(node.op_type, 0) + 1
    info = {
        "file": NAME, "input": inp, "output": outp, "width": WIDTH, "height": HEIGHT,
        "output_shape": list(y.shape), "size_mb": round(os.path.getsize(dst) / 1e6, 1),
        "github_runner_cpu_ms": round(cpu_ms, 1), "ops": ops,
        # Normalisation the model was trained with (ImageNet), applied by the app.
        "mean": [0.485, 0.456, 0.406], "std": [0.229, 0.224, 0.225],
    }
    lines = ["# Depth Anything V2 Small for 2D-to-3D", "",
             f"- file: `{NAME}` ({info['size_mb']} MB), input `{inp}` 1x3x{HEIGHT}x{WIDTH}, output `{outp}` {list(y.shape)}",
             f"- CPU on the GitHub runner: {info['github_runner_cpu_ms']} ms", ""]

    if os.environ.get("QAI_HUB_API_TOKEN"):
        sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "npu"))
        try:
            import qai_hub as hub
            from aihub_profile import pick_device, status_of, summarise
            device = pick_device(hub)
            lines += [f"## AI Hub ({device.name})", ""]
            for runtime in ("qnn_dlc", "precompiled_qnn_onnx"):
                try:
                    job = hub.submit_compile_job(model=dst, device=device, name=f"depth-{runtime}",
                                                 options=f"--target_runtime {runtime}")
                    ok, msg = status_of(job)
                    if not ok:
                        lines.append(f"- {runtime}: compile failed: {msg[:300]}")
                        continue
                    prof = hub.submit_profile_job(model=job.get_target_model(), device=device, name=f"depth-{runtime}")
                    ok, msg = status_of(prof)
                    if not ok:
                        lines.append(f"- {runtime}: profile failed: {msg[:300]}")
                        continue
                    s = summarise(prof.download_profile())
                    info[f"aihub_{runtime}"] = s
                    lines.append(f"- {runtime}: **{s['inference_ms']} ms**, not on NPU: {s['layer_types_not_on_npu'] or 'none'}")
                except Exception as e:
                    traceback.print_exc()
                    lines.append(f"- {runtime}: error {e}")
        except Exception as e:
            traceback.print_exc()
            lines.append(f"AI Hub step failed: {e}")
    else:
        lines.append("AI Hub timing skipped (no QAI_HUB_API_TOKEN secret).")

    with open(os.path.join(args.out, "depth.json"), "w") as f:
        json.dump(info, f, indent=2)
    with open(os.path.join(args.out, "depth-report.md"), "w") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
