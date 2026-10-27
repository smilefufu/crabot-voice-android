"""Isolated context ablations of the pre-existing public failure, not a fix.

Unchanged threshold and official eight-second frontend. Prefixes/splices and
pitch-changing compression are diagnostic only, not relabelled acceptance data.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

import numpy as np
from scipy.signal import resample_poly
import soundfile as sf
from transformers import WhisperFeatureExtractor

parser = argparse.ArgumentParser()
parser.add_argument('--root', type=Path, required=True)
args = parser.parse_args()
root = args.root
identity = '0384164c-d1e3-4881-861b-1bf5494cd90e'
path = root / (identity + '.flac')
assert hashlib.sha256(path.read_bytes()).hexdigest() == 'b965ea72b84a3a62c4bad885a87562147fc5730381949e893021dd85acf893aa'
audio, rate = sf.read(path, dtype='float32')
assert rate == 16000 and audio.ndim == 1 and len(audio) == 173440
folder = root / 'context-boundary'
folder.mkdir(exist_ok=True)
extractor = WhisperFeatureExtractor(chunk_length=8)
cases = [('original_official_tail8', audio, 'Original labelled incomplete; official truncation')]
for quarter in range(16, 33):
    seconds = quarter / 4
    cases.append((f'prefix{seconds:g}', audio[:round(seconds * rate)],
                  'Fixed 0.25-second scan from 4 to 8 seconds, all available context; not separately publisher-labelled'))
for head in [2, 3, 4]:
    cases.append((f'head{head}_tail{8-head}', np.concatenate([audio[:head * rate], audio[-(8-head) * rate:]]),
                  'Artificial splice; missing middle audio, not causal proof'))
compressed = resample_poly(audio, 200, 271).astype(np.float32)
assert len(compressed) == 128000
cases.append(('compressed_full8', compressed, 'All audio resampled to eight seconds; pitch/prosody changed, not a product solution'))
muted = compressed.copy()
muted[:round((len(audio) - 128000) * 200 / 271)] = 0
cases.append(('compressed_muted_head', muted, 'Same compressed tail; missing head replaced with silence; normalization also changes'))
tempo = subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-threads', '1', '-i', str(path),
                       '-af', 'atempo=1.4', '-ar', '16000', '-ac', '1', '-f', 'f32le', 'pipe:1'],
                      capture_output=True, check=True, timeout=20)
tempo_audio = np.frombuffer(tempo.stdout, dtype='<f4').copy()
assert 100000 < len(tempo_audio) <= 128000
cases.append(('pitch_preserved_full_under8', tempo_audio,
              'All words time-compressed with ffmpeg atempo=1.4, no pitch shift; timing still changed, not causal proof or a product solution'))
files, metadata = [], []
for name, value, scope in cases:
    value = value[-128000:]
    value = np.pad(value, (128000 - len(value), 0))
    features = extractor(value, sampling_rate=rate, return_tensors='np', padding='max_length',
                         max_length=128000, truncation=True, do_normalize=True).input_features
    target = folder / (name + '.bin')
    np.asarray(features, dtype=np.float32).tofile(target)
    files.append(str(target))
    metadata.append({'case': name, 'scope': scope, 'feature_sha256': hashlib.sha256(target.read_bytes()).hexdigest()})
long_features = WhisperFeatureExtractor(chunk_length=11)(audio, sampling_rate=rate, return_tensors='np',
    padding='max_length', max_length=len(audio), truncation=True, do_normalize=True).input_features
assert long_features.shape == (1, 80, 1084)
long_path = folder / 'untruncated1084.bin'
np.asarray(long_features, dtype=np.float32).tofile(long_path)
reports = []
models = {'smart-turn-v3.2-cpu.onnx': '2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f',
          'smart-turn-v3.1-cpu.onnx': 'fb68d55c2d542ce79e44b12013bfd571e90df8594ab096d757198e851b0c6594',
          'smart-turn-v3.2-gpu.onnx': 'ab8dc64b88713f90b571c15b714bd1330e6c883cad8763dacf65c9376dc539be'}
for model, expected_hash in models.items():
    assert hashlib.sha256((root / model).read_bytes()).hexdigest() == expected_hash
    runner = [str(root / 'probe-ort'), str(root / model)]
    shape = subprocess.run(runner, text=True, capture_output=True, check=True, timeout=15)
    result = subprocess.run(runner + files, text=True, capture_output=True, check=True, timeout=30)
    lines = result.stdout.splitlines()
    assert len(lines) == len(metadata)
    outputs = []
    for index, (line, case) in enumerate(zip(lines, metadata)):
        got, probability, elapsed = line.split()
        assert int(got) == index
        outputs.append({**case, 'probability': float(probability), 'complete': float(probability) > 0.5})
    long = subprocess.run(runner + ['--frames', '1084', str(long_path)], text=True, capture_output=True, timeout=15)
    reports.append({'model': model, 'model_sha256': expected_hash,
                    'input_metadata': shape.stdout.strip(), 'cases': outputs,
                    'long_input_returncode': long.returncode, 'long_input_stdout': long.stdout.strip(),
                    'long_input_stderr': long.stderr.strip()})
report = {'scope': 'Diagnostic ablations only; no gate relabelling, threshold or timeout changes',
          'source_id': identity, 'source_samples': len(audio), 'source_rate': rate,
          'pitch_preserved_sample_count': len(tempo_audio),
          'ffmpeg_version': subprocess.run(['ffmpeg', '-version'], text=True, capture_output=True,
                                           check=True, timeout=5).stdout.splitlines()[0],
          'models': reports}
(root / 'context-boundary.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
