"""Observe the standard linear tap without changing the frozen AEC controls."""
import argparse
import importlib.util
import json
import subprocess
from pathlib import Path

import numpy as np

spec = importlib.util.spec_from_file_location("probe_apm", Path(__file__).with_name("probe-apm.py"))
metrics = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metrics)


def run(args):
    original = json.loads((args.controls / "results.json").read_text())
    cases = {row["name"]: row for row in original["cases"]}
    args.output.mkdir(parents=True, exist_ok=True)
    reference = np.fromfile(args.controls / "inputs/near_only_on-capture.f32", dtype="<f4")
    result = {"scope": "Diagnostic only; original cases/scores retained. No tuning or product integration.",
              "manifest_sha256": metrics.digest(args.controls / "input-manifest.json"),
              "probe_sha256": metrics.digest(args.probe), "cases": []}
    for name in ["near_only_on", "echo_only_on", "double_talk_on"]:
        normal, linear = [args.output / (name + suffix) for suffix in ["-normal.f32", "-linear.f32"]]
        completed = subprocess.run([str(args.probe), str(args.controls / ("inputs/" + name + "-render.f32")),
            str(args.controls / ("inputs/" + name + "-capture.f32")), str(normal), "1", "80", str(linear)],
            capture_output=True, text=True, check=True, timeout=30)
        (args.output / (name + "-raw.json")).write_text(completed.stdout)
        assert metrics.digest(normal) == cases[name]["output_sha256"], "export altered normal output"
        audio = np.fromfile(linear, dtype="<f4").astype(float)
        assert len(audio) == 480000 and np.all(np.isfinite(audio))
        row = {"name": name, "normal_output_unchanged": True, "linear_sha256": metrics.digest(linear),
               "linear_peak": float(np.max(np.abs(audio)))}
        if name == "echo_only_on":
            capture = np.fromfile(args.controls / ("inputs/" + name + "-capture.f32"), dtype="<f4").astype(float)
            row["linear_attenuation_db"] = {f"{a}-{b}s": metrics.db(np.mean(capture[a*16000:b*16000]**2),
                np.mean(audio[a*16000:b*16000]**2)) for a, b in [(0, 5), (5, 10), (15, 20), (25, 30)]}
        else:
            row["linear_near_windows"] = [metrics.near_metrics(reference[a*16000:b*16000], audio[a*16000:b*16000])
                                           for a, b in [(10, 16), (22, 28)]]
        result["cases"].append(row)
    (args.output / "results.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--controls", type=Path, required=True)
    parser.add_argument("--probe", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    run(parser.parse_args())
