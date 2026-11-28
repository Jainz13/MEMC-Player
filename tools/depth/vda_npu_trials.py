"""Finds a form of Video Depth Anything's 32-frame temporal part that the Snapdragon NPU
compiler can build. The straightforward export (vda_s_tmp.onnx) never finished compiling on a
Galaxy S24 or on Qualcomm AI Hub. This exports a few variants, checks each gives the same result
as the model on the CPU, and compiles and times them on AI Hub in parallel (30 minutes at most).

  mm3_orig     one temporal attention module alone, as exported today (is attention the problem?)
  refine_only  the 32-frame part without its attention modules (are the convolutions the problem?)
  tmp_4d       whole part, attention as 4-D matrix products (positions x heads as batch)
  tmp_unrolled whole part, attention written out frame by frame with plain multiply-and-sum
               (no huge batched matrix products, no layout shuffles)

Writes trials/trials-report.md and the variant models.
"""

import os
import sys
import time
import traceback

import numpy as np
import onnxruntime as ort
import torch
import torch.nn.functional as F

sys.path.insert(0, os.path.dirname(__file__))
import export_vda as base  # noqa: E402

OUT = "trials"


def attention_4d(self, hidden_states, encoder_hidden_states=None, attention_mask=None, video_length=None, cached_hidden_states=None):
    # (b f) d c -> attention over f for every position d, as 4-D tensors.
    f = video_length
    x = hidden_states  # (f, P, C)
    P, C = x.shape[1], x.shape[2]
    x = x.permute(1, 0, 2)  # (P, f, C)
    x = x + self.pos_encoder.pe[:, :f].to(x.dtype)
    h = self.heads
    e = C // h
    q = self.to_q(x).reshape(P, f, h, e).permute(0, 2, 1, 3)
    k = self.to_k(x).reshape(P, f, h, e).permute(0, 2, 3, 1)
    v = self.to_v(x).reshape(P, f, h, e).permute(0, 2, 1, 3)
    p = torch.softmax(torch.matmul(q, k) * self.scale, dim=-1)
    o = torch.matmul(p, v).permute(0, 2, 1, 3).reshape(P, f, C)
    o = self.to_out[1](self.to_out[0](o))
    return o.permute(1, 0, 2), None


def attention_unrolled(self, hidden_states, encoder_hidden_states=None, attention_mask=None, video_length=None, cached_hidden_states=None):
    # Same maths frame by frame: for each other frame, multiply and sum over each head's channels.
    f = video_length
    x = hidden_states  # (f, P, C): frames first, so no reshuffling is needed
    P, C = x.shape[1], x.shape[2]
    x = x + self.pos_encoder.pe[0, :f].unsqueeze(1).to(x.dtype)
    h = self.heads
    e = C // h
    q = (self.to_q(x) * self.scale).reshape(f, P, h, e)
    k = self.to_k(x).reshape(f, P, h, e)
    v = self.to_v(x).reshape(f, P, h, e)
    scores = torch.cat([(q * k[j:j + 1]).sum(dim=-1, keepdim=True) for j in range(f)], dim=-1)  # (f, P, h, f)
    p = torch.softmax(scores, dim=-1)
    o = p[..., 0:1] * v[0:1]
    for j in range(1, f):
        o = o + p[..., j:j + 1] * v[j:j + 1]
    o = self.to_out[1](self.to_out[0](o.reshape(f, P, C)))
    return o, None


class MotionOnly(torch.nn.Module):
    """Motion module 3 alone (the biggest: 42x74 positions, 64 channels)."""

    def __init__(self, m):
        super().__init__()
        self.mm = m.head.motion_modules[3]

    def forward(self, x):  # x: (32, 64, 42, 74)
        B, T = 1, x.shape[0]
        y, _ = self.mm(x.unflatten(0, (B, T)).permute(0, 2, 1, 3, 4), None, None, None)
        return y.permute(0, 2, 1, 3, 4).flatten(0, 1)


class RefineOnly(torch.nn.Module):
    """The 32-frame part with its attention modules left out."""

    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, l3, l4):
        s = self.m.head.scratch
        l3rn = s.layer3_rn(l3)
        l4rn = s.layer4_rn(l4)
        path_4 = s.refinenet4(l4rn, size=l3rn.shape[2:])
        return s.refinenet3(path_4, l3rn, size=(base.PH * 2, base.PW * 2))


def export(module, inputs, names, path):
    torch.onnx.export(module, inputs, path, input_names=names, output_names=["out"], opset_version=17,
                      do_constant_folding=True, dynamo=False)
    try:
        import onnx
        from onnxsim import simplify
        model, ok = simplify(onnx.load(path))
        if ok:
            onnx.save(model, path)
    except Exception as e:
        print("onnxsim skipped:", e)


def check(path, module, inputs, names):
    with torch.no_grad():
        ref = module(*inputs).numpy()
    s = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    feed = {n: t.numpy() for n, t in zip(names, inputs)}
    t0 = time.time()
    got = s.run(None, feed)[0]
    ms = (time.time() - t0) * 1000
    err = float(np.abs(got - ref).max() / max(np.abs(ref).max(), 1e-6))
    return err, ms


def main():
    os.makedirs(OUT, exist_ok=True)
    torch.set_grad_enabled(False)
    base.fetch_code("work")
    m = base.load("work", random_weights="--random-weights" in sys.argv)
    from video_depth_anything.motion_module import motion_module as mm
    original = mm.TemporalAttention.forward

    torch.manual_seed(0)
    l3 = torch.randn(32, 192, base.PH, base.PW)
    l4 = torch.randn(32, 384, (base.PH + 1) // 2, (base.PW + 1) // 2)
    p3 = torch.randn(32, 64, base.PH * 2, base.PW * 2)
    with torch.no_grad():
        want = base.Tmp(m)(l3, l4).numpy()

    variants = []
    lines = ["# Video Depth Anything temporal part: NPU compile trials", ""]

    def add(name, module, inputs, names, compare_full=False):
        path = os.path.join(OUT, f"{name}.onnx")
        export(module.eval(), tuple(inputs), names, path)
        err, ms = check(path, module, inputs, names)
        if compare_full:
            s = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
            got = s.run(None, {n: t.numpy() for n, t in zip(names, inputs)})[0]
            err = float(np.abs(got - want).max() / max(np.abs(want).max(), 1e-6))
        lines.append(f"- {name}: {os.path.getsize(path) / 1e6:.1f} MB, CPU {ms:.0f} ms, error vs model {err:.2e}")
        variants.append((name, path))
        print(lines[-1], flush=True)

    mm.TemporalAttention.forward = original
    add("mm3_orig", MotionOnly(m), [p3], ["x"])
    add("refine_only", RefineOnly(m), [l3, l4], ["l3", "l4"])
    mm.TemporalAttention.forward = attention_4d
    add("tmp_4d", base.Tmp(m), [l3, l4], ["l3", "l4"], compare_full=True)
    mm.TemporalAttention.forward = attention_unrolled
    add("tmp_unrolled", base.Tmp(m), [l3, l4], ["l3", "l4"], compare_full=True)
    mm.TemporalAttention.forward = original

    lines += ["", "## AI Hub", ""]
    if not os.environ.get("QAI_HUB_API_TOKEN"):
        lines.append("skipped (no QAI_HUB_API_TOKEN)")
    else:
        sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "npu"))
        import qai_hub as hub
        from aihub_profile import pick_device, summarise
        device = pick_device(hub)
        lines.append(f"Device: {device.name}")
        jobs = {}
        for name, path in variants:
            try:
                jobs[name] = hub.submit_compile_job(model=path, device=device, name=f"vda-trial-{name}",
                                                    options="--target_runtime precompiled_qnn_onnx")
            except Exception as e:
                lines.append(f"- {name}: submit failed: {e}")
        deadline = time.time() + 30 * 60
        compiled = {}
        pending = dict(jobs)
        started = time.time()
        while pending and time.time() < deadline:
            for name, job in list(pending.items()):
                try:
                    st = job.get_status()
                except Exception:
                    continue
                if st.finished:
                    mins = (time.time() - started) / 60
                    if st.success:
                        compiled[name] = job
                        lines.append(f"- {name}: compiled (within {mins:.0f} min)")
                    else:
                        lines.append(f"- {name}: compile FAILED: {str(st.message)[:300]}")
                    print(lines[-1], flush=True)
                    del pending[name]
            time.sleep(30)
        for name in pending:
            lines.append(f"- {name}: still compiling after 30 min (gave up)")
        profs = {}
        for name, job in compiled.items():
            try:
                profs[name] = hub.submit_profile_job(model=job.get_target_model(), device=device, name=f"vda-trial-{name}")
            except Exception as e:
                lines.append(f"- {name}: profile submit failed: {e}")
        for name, prof in profs.items():
            try:
                prof.wait(timeout=20 * 60)
                s = summarise(prof.download_profile())
                lines.append(f"- {name}: **{s['inference_ms']} ms** on the NPU, not on NPU: {s['layer_types_not_on_npu'] or 'none'}")
            except Exception as e:
                traceback.print_exc()
                lines.append(f"- {name}: profile failed: {e}")
            print(lines[-1], flush=True)

    with open(os.path.join(OUT, "trials-report.md"), "w") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
