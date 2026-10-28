"""Fixed public-file AEC controls. No device, network, VAD or business input."""
import argparse
import hashlib
import json
import math
import subprocess
import time
from pathlib import Path

import numpy as np
import scipy.signal as signal
import soundfile as sf


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def db(numerator, denominator):
    return float(10 * np.log10((numerator + 1e-20) / (denominator + 1e-20)))


def near_metrics(reference, actual):
    reference, actual = reference.astype(float), actual.astype(float)
    correlation = signal.correlate(actual, reference, method="fft")
    center = len(reference) - 1
    lag = int(np.argmax(correlation[center - 160:center + 161]) - 160)
    if lag > 0:
        aligned, target = actual[lag:], reference[:-lag]
    elif lag < 0:
        aligned, target = actual[:lag], reference[-lag:]
    else:
        aligned, target = actual, reference
    def cosine(x, y):
        return float(np.dot(x, y) / np.sqrt(np.dot(x, x) * np.dot(y, y) + 1e-20))
    scaled_target = target * np.dot(aligned, target) / (np.dot(target, target) + 1e-20)
    return {"unaligned_cosine": cosine(actual, reference), "aligned_cosine": cosine(aligned, target),
            "alignment_samples_at_16k": lag, "alignment_ms": lag / 16,
            "output_vs_clean_energy_db": db(np.mean(actual ** 2), np.mean(reference ** 2)),
            "aligned_si_sdr_db": db(np.sum(scaled_target ** 2), np.sum((aligned - scaled_target) ** 2))}


def run(args):
    args.root.mkdir(parents=True, exist_ok=True)
    inputs = args.root / "inputs"
    inputs.mkdir(exist_ok=True)
    sources = json.loads(args.sources.read_text())
    names = ["fangjun-sr-1.wav", "leijun-test-sr-1.wav"]
    clips = []
    for name in names:
        path = args.public_audio / name
        assert digest(path) == sources[name]["sha256"], name
        audio, rate = sf.read(path, dtype="float64", always_2d=True)
        assert audio.shape[1] == 1 and np.all(np.isfinite(audio))
        common = math.gcd(rate, 16000)
        clip = signal.resample_poly(audio[:, 0], 16000 // common, rate // common)
        clip -= clip.mean()
        clips.append(clip * (0.05 / np.sqrt(np.mean(clip ** 2))))
    count = 30 * 16000
    far = np.tile(clips[0], int(np.ceil(count / len(clips[0]))))[:count]
    far[14 * 16000:int(14.6 * 16000)] = 0
    near = np.zeros(count)
    for start in [10, 22]:
        clip = clips[1][:6 * 16000]
        near[start * 16000:start * 16000 + len(clip)] = clip
    echo = np.zeros(count)
    for delay, gain in [(80, 0.45), (95, 0.15), (110, 0.08)]:
        offset = delay * 16
        echo[offset:] += far[:-offset] * gain
    render = signal.resample_poly(far, 3, 2)
    definitions = [("near_only_off", near, False, False), ("near_only_on", near, True, False),
                   ("echo_only_off", echo, False, False), ("echo_only_on", echo, True, False),
                   ("echo_only_on_no_reference", echo, True, True),
                   ("double_talk_off", near + echo, False, False), ("double_talk_on", near + echo, True, False)]
    manifest = {"sources": {name: sources[name] for name in names}, "rate_capture": 16000, "rate_render": 24000,
                "seconds": 30, "echo_taps_ms_gain": [[80, 0.45], [95, 0.15], [110, 0.08]],
                "reported_delay_ms": 80, "near_intervals_seconds": [[10, 16], [22, 28]],
                "far_pause_seconds": [14, 14.6], "clip_rms": 0.05,
                "config": "APM defaults with echo_canceller.enabled varied; mobile_mode=false; no AGC or NS",
                "cases": []}
    for name, capture, enabled, no_reference in definitions:
        for suffix, audio in [("capture", capture), ("render", np.zeros_like(render) if no_reference or name.startswith("near_only") else render)]:
            assert np.all(np.isfinite(audio)) and np.max(np.abs(audio)) <= 1
            np.asarray(audio, dtype="<f4").tofile(inputs / (name + "-" + suffix + ".f32"))
        manifest["cases"].append({"name": name, "aec": enabled,
            **{suffix + "_sha256": digest(inputs / (name + "-" + suffix + ".f32")) for suffix in ["capture", "render"]}})
    plan = args.root / "input-manifest.json"
    if plan.exists():
        assert json.loads(plan.read_text()) == manifest, "fixed input changed"
    else:
        assert args.prepare_only, "prepare fixed inputs before running"
        plan.write_text(json.dumps(manifest, indent=2) + "\n")
    if args.prepare_only:
        print(json.dumps({"prepared_cases": len(definitions), "manifest_sha256": digest(plan)}))
        return
    assert args.probe
    result = {"scope": "Software echo path on public audio only; not physical AEC, VAD, identity or turn acceptance",
              "manifest_sha256": digest(plan), "probe_sha256": digest(args.probe), "cases": []}
    for name, capture, enabled, no_reference in definitions:
        output = args.root / (name + "-output.f32")
        started = time.monotonic()
        completed = subprocess.run([str(args.probe), str(inputs / (name + "-render.f32")),
            str(inputs / (name + "-capture.f32")), str(output), str(int(enabled)), "80"],
            capture_output=True, text=True, check=True, timeout=60)
        elapsed = time.monotonic() - started
        timing = json.loads(completed.stdout)
        (args.root / (name + "-raw.json")).write_text(completed.stdout)
        samples = np.fromfile(output, dtype="<f4").astype(float)
        assert len(samples) == count and np.all(np.isfinite(samples))
        row = {"name": name, "samples": len(samples), "output_sha256": digest(output),
               "raw_timing_sha256": digest(args.root / (name + "-raw.json")),
               "init_ms": timing["init_ms"], "process_wall_seconds": elapsed,
               "render_max_difference": timing["render_max_difference"],
               "reported_delay_ms": timing["last_reported_delay_ms"],
               "frame_count": timing["frames"],
               "frame_us": {k: float(v) for k, v in zip(["p50", "p95", "max"], np.percentile(timing["processing_us"], [50, 95, 100]))}}
        if name.startswith("echo_only"):
            row["attenuation_db"] = {}
            for start, end in [(0, 5), (5, 10), (15, 20), (25, 30)]:
                selected = slice(start * 16000, end * 16000)
                row["attenuation_db"][f"{start}-{end}s"] = db(np.mean(capture[selected] ** 2), np.mean(samples[selected] ** 2))
        else:
            row["near_windows"] = [near_metrics(near[start * 16000:end * 16000], samples[start * 16000:end * 16000]) for start, end in [(10, 16), (22, 28)]]
        if not enabled:
            row["bypass_max_difference"] = float(np.max(np.abs(samples - np.asarray(capture, dtype="<f4"))))
        result["cases"].append(row)
    (args.root / "results.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--public-audio", type=Path, required=True)
    parser.add_argument("--sources", type=Path, required=True)
    parser.add_argument("--probe", type=Path)
    parser.add_argument("--prepare-only", action="store_true")
    run(parser.parse_args())
