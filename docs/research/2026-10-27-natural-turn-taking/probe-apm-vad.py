"""Replay frozen APM outputs through the existing app VAD configuration."""
import argparse
import hashlib
import json
import time
from pathlib import Path

import numpy as np
import sherpa_onnx as sherpa


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(args):
    model_hash = digest(args.model)
    assert model_hash == "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"
    assert sherpa.__version__ == "1.13.8"
    ordinary = json.loads((args.controls / "results.json").read_text())
    linear = json.loads((args.linear / "results.json").read_text())
    config = sherpa.VadModelConfig()
    config.silero_vad.model = str(args.model)
    config.silero_vad.threshold = 0.5
    config.silero_vad.min_speech_duration = 0.05
    config.silero_vad.min_silence_duration = 0.05
    config.silero_vad.max_speech_duration = 16
    config.num_threads = 1
    items = [(row["name"], args.controls / (row["name"] + "-output.f32"), row["output_sha256"]) for row in ordinary["cases"]]
    items += [(row["name"] + "_linear", args.linear / (row["name"] + "-linear.f32"), row["linear_sha256"]) for row in linear["cases"]]
    result = {"scope": "Public software echo path and app-matching VAD only; no business turn, device, ASR, identity or listening acceptance",
              "model_sha256": model_hash, "sherpa_version": sherpa.__version__,
              "config_without_model_path": str(config).replace(str(args.model), "<fixed-model>"),
              "input": "round/clamp to PCM16, then divide by 32768 as NativeModels; no loudness normalization",
              "frame_samples": 320, "cases": []}
    for name, path, expected in items:
        assert digest(path) == expected
        audio = np.fromfile(path, dtype="<f4")
        assert len(audio) == 480000 and np.all(np.isfinite(audio))
        audio = (np.clip(np.rint(audio * 32768), -32768, 32767) / 32768).astype(np.float32)
        vad = sherpa.VoiceActivityDetector(config, buffer_size_in_seconds=1)
        flags, times = [], []
        for offset in range(0, len(audio), 320):
            started = time.perf_counter()
            vad.accept_waveform(audio[offset:offset + 320])
            while not vad.empty():
                vad.pop()
            flags.append(bool(vad.is_speech_detected()))
            times.append((time.perf_counter() - started) * 1000)
        intervals = []
        begin = None
        for i, flag in enumerate(flags + [False]):
            if flag and begin is None:
                begin = i
            if not flag and begin is not None:
                intervals.append([begin * 0.02, i * 0.02]); begin = None
        row = {"name": name, "source_sha256": expected, "frames": len(flags),
               "positive_frames": sum(flags), "positive_seconds": sum(flags) * 0.02,
               "positive_intervals_seconds": intervals,
               "frame_ms": {k: float(v) for k, v in zip(["p50", "p95", "max"], np.percentile(times, [50, 95, 100]))}}
        if not name.startswith("echo_only"):
            row["near_windows"] = []
            for start in [10, 22]:
                selected = flags[start*50:(start+6)*50]
                already = flags[start*50-1]
                row["near_windows"].append({"start_seconds": start, "positive_frames": sum(selected),
                    "already_positive_before_near": already,
                    "first_positive_after_start_ms": None if already or not any(selected) else (selected.index(True)+1)*20})
        result["cases"].append(row)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"cases": [{k:v for k,v in row.items() if k != "positive_intervals_seconds"} for row in result["cases"]]}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--controls", type=Path, required=True)
    parser.add_argument("--linear", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    run(parser.parse_args())
