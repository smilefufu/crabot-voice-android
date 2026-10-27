"""Fixed publisher-labelled Chinese corpus, not real household pause acceptance.

Uses the same Pipecat 0.0.102 Whisper frontend as probe-public-features.py.
No capture, playback, ASR or LLM; signed download URLs never enter the report.
"""
import argparse
import hashlib
import json
import math
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
selection = json.loads((root / 'public-selection.json').read_text())
downloads = json.loads((root / 'labeled-download-results.json').read_text())
assert len(downloads) == 20 and all(x['ok'] for x in downloads)
assert len({x['id'] for x in downloads}) == 20
model = root / 'smart-turn-v3.2-cpu.onnx'
model_hash = '2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f'
assert hashlib.sha256(model.read_bytes()).hexdigest() == model_hash
extractor = WhisperFeatureExtractor(chunk_length=8)
folder = root / 'labeled-features'
folder.mkdir(exist_ok=True)
cases, files = [], []
for selected in selection['rows']:
    row = selected['row']
    source = next(x for x in downloads if x['id'] == row['id'])
    path = root / (row['id'] + '.flac')
    assert row['language'] == 'zho' and row['endpoint_bool'] == source['label']
    assert hashlib.sha256(path.read_bytes()).hexdigest() == source['sha256']
    audio, rate = sf.read(path, dtype='float32')
    assert audio.ndim == 1 and np.isfinite(audio).all()
    if rate != 16000:
        divisor = math.gcd(rate, 16000)
        audio = resample_poly(audio, 16000 // divisor, rate // divisor).astype(np.float32)
    for pause in [0.0, 0.2, 0.8, 1.0, 2.0, 3.0]:
        value = np.concatenate([audio, np.zeros(round(pause * 16000), dtype=np.float32)])[-128000:]
        value = np.pad(value, (128000 - len(value), 0))
        inputs = extractor(value, sampling_rate=16000, return_tensors='np', padding='max_length',
                           max_length=128000, truncation=True, do_normalize=True)
        features = np.asarray(inputs.input_features, dtype=np.float32)
        assert features.shape == (1, 80, 800) and np.isfinite(features).all()
        target = folder / f'{len(cases):03}.bin'
        features.tofile(target)
        files.append(str(target))
        cases.append({'id': row['id'], 'row_index': selected['row_idx'],
                      'expected_complete': row['endpoint_bool'], 'synthetic': row['synthetic'],
                      'source_sha256': source['sha256'], 'source_rate': rate,
                      'added_silence_seconds': pause,
                      'feature_sha256': hashlib.sha256(target.read_bytes()).hexdigest()})
result = subprocess.run([str(root / 'probe-ort'), str(model), *files],
                        text=True, capture_output=True, check=True, timeout=60)
lines = result.stdout.splitlines()
assert len(lines) == len(cases)
for index, (line, case) in enumerate(zip(lines, cases)):
    returned_index, probability, elapsed = line.split()
    assert int(returned_index) == index
    case.update(probability=float(probability), predicted_complete=float(probability) > 0.5,
                inference_ms=float(elapsed))
summary = []
for pause in [0.0, 0.2, 0.8, 1.0, 2.0, 3.0]:
    group = [x for x in cases if x['added_silence_seconds'] == pause]
    summary.append({'added_silence_seconds': pause,
                    'incomplete_false_positive': sum(x['predicted_complete'] for x in group if not x['expected_complete']),
                    'incomplete_total': sum(not x['expected_complete'] for x in group),
                    'complete_false_negative': sum(not x['predicted_complete'] for x in group if x['expected_complete']),
                    'complete_total': sum(x['expected_complete'] for x in group)})
report = {'scope': 'Publisher-labelled clips and artificial trailing silence; not natural household thinking pauses or full endpoint timing',
          'dataset': selection['dataset'], 'selection': selection['selection'],
          'model_sha256': model_hash, 'threshold': 0.5,
          'summary': summary, 'cases': cases}
(root / 'labeled-results.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({k: v for k, v in report.items() if k != 'cases'}))
