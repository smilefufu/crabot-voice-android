"""Mechanism/performance smoke only: these public speaker clips have no EOT labels.

Preprocessing follows Pipecat 0.0.102 LocalSmartTurnAnalyzerV3 (BSD-2-Clause,
Daily 2024-2026): last eight seconds, left zero padding, Whisper normalization.
This script does not import Pipecat, capture audio or use personal voice records.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time

import numpy as np
import soundfile as sf
from transformers import WhisperFeatureExtractor

parser = argparse.ArgumentParser()
parser.add_argument('--root', type=Path, required=True)
parser.add_argument('--public-audio', type=Path, required=True)
parser.add_argument('--sources', type=Path, required=True)
args = parser.parse_args()
root = args.root
model = root / 'smart-turn-v3.2-cpu.onnx'
expected = '2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f'
assert hashlib.sha256(model.read_bytes()).hexdigest() == expected
sources = json.loads(args.sources.read_text())
features_root = root / 'features'
features_root.mkdir(exist_ok=True)
extractor = WhisperFeatureExtractor(chunk_length=8)
cases = []
files = []
for name in ['fangjun-sr-1.wav', 'fangjun-sr-2.wav', 'fangjun-sr-3.wav']:
    path = args.public_audio / name
    assert hashlib.sha256(path.read_bytes()).hexdigest() == sources[name]['sha256']
    audio, rate = sf.read(path, dtype='float32')
    assert rate == 16000 and audio.ndim == 1
    for fraction in [0.5, 1.0]:
        for pause in [0.2, 0.8, 1.0, 2.0, 3.0]:
            value = np.concatenate([audio[:int(len(audio) * fraction)], np.zeros(round(pause * rate), dtype=np.float32)])
            value = value[-128000:]
            value = np.pad(value, (128000 - len(value), 0))
            began = time.perf_counter()
            inputs = extractor(value, sampling_rate=rate, return_tensors='np', padding='max_length',
                               max_length=128000, truncation=True, do_normalize=True)
            features = np.asarray(inputs.input_features, dtype=np.float32)
            elapsed = (time.perf_counter() - began) * 1000
            assert features.shape == (1, 80, 800) and np.isfinite(features).all()
            target = features_root / f'{len(cases):03}.bin'
            features.tofile(target)
            files.append(str(target))
            cases.append({'public_source': name, 'source_sha256': sources[name]['sha256'],
                          'prefix_fraction': fraction, 'added_silence_seconds': pause,
                          'expected_complete': None, 'feature_ms': elapsed,
                          'feature_sha256': hashlib.sha256(target.read_bytes()).hexdigest()})
result = subprocess.run([str(root / 'probe-ort'), str(model), *files], text=True,
                        capture_output=True, check=True, timeout=45)
lines = result.stdout.splitlines()
assert len(lines) == len(cases)
for expected_index, (line, case) in enumerate(zip(lines, cases)):
    index, probability, elapsed = line.split()
    assert int(index) == expected_index
    case.update(probability=float(probability), inference_ms=float(elapsed))
report = {'scope': 'Public audio mechanism/performance only; no EOT accuracy labels or real thinking pauses',
          'model_sha256': expected, 'host_ort_version': '1.28.2', 'cpu_threads': 1,
          'samples': cases, 'count': len(cases),
          'inference_p95_ms': float(np.percentile([x['inference_ms'] for x in cases], 95)),
          'feature_p95_ms': float(np.percentile([x['feature_ms'] for x in cases], 95))}
(root / 'host-smoke.json').write_text(json.dumps(report, indent=2))
print(json.dumps({k: v for k, v in report.items() if k != 'samples'}))
