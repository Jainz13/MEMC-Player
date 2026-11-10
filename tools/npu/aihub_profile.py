"""Compiles the exported models for the Galaxy S24 Ultra's NPU on Qualcomm AI Hub, runs them
on a real phone in Qualcomm's device lab, and writes report.md / results.json.

Needs the QAI_HUB_API_TOKEN secret (configured by the workflow). Without it only the ONNX
export is published.
"""

import argparse
import json
import os
import traceback

# qnn_dlc / precompiled_qnn_onnx run through Qualcomm's own runtime, which has a native
# GridSample (warp) operator. TFLite was measured once: all layers on the NPU but 338 ms at
# 640x352, and the 448x256 build failed to run, so it is no longer tested.
RUNTIMES = ["qnn_dlc", "precompiled_qnn_onnx"]
EXT = {"qnn_dlc": ".dlc", "precompiled_qnn_onnx": ".onnx.zip", "tflite": ".tflite"}


def pick_device(hub):
    devices = hub.get_devices()
    for want in ("Samsung Galaxy S24 Ultra", "Samsung Galaxy S24 (Family)", "Samsung Galaxy S24"):
        for d in devices:
            if d.name == want:
                return d
    for d in devices:
        if "S24" in d.name:
            return d
    return hub.Device(attributes="chipset:qualcomm-snapdragon-8gen3")


def status_of(job):
    status = job.wait()
    ok = bool(getattr(status, "success", False))
    message = getattr(status, "message", None) or getattr(status, "code", "")
    return ok, str(message)


def summarise(profile):
    summary = profile.get("execution_summary", {})
    detail = profile.get("execution_detail", [])
    units, off_npu = {}, {}
    for layer in detail:
        unit = str(layer.get("compute_unit", "?"))
        units[unit] = units.get(unit, 0) + 1
        if unit.upper() != "NPU":
            kind = str(layer.get("type", "?"))
            off_npu[kind] = off_npu.get(kind, 0) + 1
    time_us = summary.get("estimated_inference_time")
    return {
        "inference_ms": round(time_us / 1000, 2) if time_us else None,
        "peak_memory_mb": round(summary.get("estimated_inference_peak_memory", 0) / 1e6, 1),
        "layers_by_unit": units,
        "layer_types_not_on_npu": off_npu,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default="out")
    args = ap.parse_args()
    with open(os.path.join(args.dir, "models.json")) as f:
        models = json.load(f)["models"]

    lines = ["# RIFE flow network on the Snapdragon NPU", ""]
    lines += ["## Exported models", "", "| model | size | params | CPU (GitHub runner) | ops |", "|---|---|---|---|---|"]
    for m in models:
        ops = ", ".join(f"{k}×{v}" for k, v in sorted(m["ops"].items(), key=lambda kv: -kv[1]))
        lines.append(f"| {m['name']} | {m['size_mb']} MB | {m['parameters'] / 1e6:.1f} M | {m['github_runner_cpu_ms']} ms | {ops} |")
    lines += ["", f"Shift test (content moved +6 px in x): flow into img0 {models[0]['shift_test_flow_into_img0']}, "
              f"into img1 {models[0]['shift_test_flow_into_img1']}", ""]

    results = {"device": None, "runs": []}
    if not os.environ.get("QAI_HUB_API_TOKEN"):
        lines += ["## AI Hub", "", "Skipped: the `QAI_HUB_API_TOKEN` repository secret is not set."]
    else:
        import qai_hub as hub
        device = pick_device(hub)
        results["device"] = device.name
        lines += ["## AI Hub results", "", f"Device: **{device.name}**", ""]

        jobs = []
        for m in models:
            for runtime in RUNTIMES:
                run = {"model": m["name"], "runtime": runtime}
                try:
                    run["compile"] = hub.submit_compile_job(
                        model=os.path.join(args.dir, m["file"]), device=device,
                        name=f"{m['name']}-{runtime}", options=f"--target_runtime {runtime}",
                    )
                except Exception as e:
                    run["error"] = f"compile submit failed: {e}"
                    traceback.print_exc()
                jobs.append(run)

        for run in jobs:
            job = run.get("compile")
            if job is None:
                continue
            run["compile_url"] = job.url
            ok, message = status_of(job)
            if not ok:
                run["error"] = f"compile failed: {message}"
                continue
            try:
                target = job.get_target_model()
                run["target"] = target
                run["profile"] = hub.submit_profile_job(model=target, device=device, name=f"{run['model']}-{run['runtime']}")
            except Exception as e:
                run["error"] = f"profile submit failed: {e}"
                traceback.print_exc()

        lines += ["| model | runtime | time per inference | peak memory | layers by unit | not on NPU |", "|---|---|---|---|---|---|"]
        for run in jobs:
            row = {"model": run["model"], "runtime": run["runtime"], "compile_url": run.get("compile_url")}
            job = run.get("profile")
            if job is not None:
                row["profile_url"] = job.url
                ok, message = status_of(job)
                if ok:
                    try:
                        row.update(summarise(job.download_profile()))
                        path = os.path.join(args.dir, f"{run['model']}-{run['runtime']}{EXT[run['runtime']]}")
                        run["target"].download(path)
                        row["compiled_file"] = os.path.basename(path)
                    except Exception as e:
                        row["error"] = f"reading profile failed: {e}"
                        traceback.print_exc()
                else:
                    row["error"] = f"profile failed: {message}"
            elif "error" in run:
                row["error"] = run["error"]
            results["runs"].append(row)
            if "error" in row:
                lines.append(f"| {row['model']} | {row['runtime']} | — | — | — | {row['error'][:300]} |")
            else:
                lines.append(
                    f"| {row['model']} | {row['runtime']} | **{row['inference_ms']} ms** | {row['peak_memory_mb']} MB | "
                    f"{row['layers_by_unit']} | {row['layer_types_not_on_npu'] or 'none'} |"
                )
        lines += ["", "Job links (sign in to AI Hub to open):", ""]
        for row in results["runs"]:
            lines.append(f"- {row['model']} / {row['runtime']}: {row.get('profile_url') or row.get('compile_url')}")

    with open(os.path.join(args.dir, "report.md"), "w") as f:
        f.write("\n".join(lines) + "\n")
    with open(os.path.join(args.dir, "results.json"), "w") as f:
        json.dump(results, f, indent=2, default=str)
    print("\n".join(lines))


if __name__ == "__main__":
    main()
