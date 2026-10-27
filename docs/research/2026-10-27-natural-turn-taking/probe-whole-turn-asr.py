"""Transcribe only the fixed public corpus; no microphone or running service."""
import argparse
import hashlib
import importlib.metadata
import json
from pathlib import Path
import time

import sherpa_onnx
import soundfile as sf

parser = argparse.ArgumentParser()
parser.add_argument('--root', required=True, type=Path)
parser.add_argument('--service-root', required=True, type=Path)
parser.add_argument('--corpus', required=True, type=Path)
args = parser.parse_args()
models = args.service_root / 'models' / 'sensevoice'
expected = {'model.int8.onnx': 'c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51',
            'tokens.txt': 'f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc'}
for name, sha in expected.items():
    assert hashlib.sha256((models / name).read_bytes()).hexdigest() == sha
start = time.perf_counter()
recognizer = sherpa_onnx.OfflineRecognizer.from_sense_voice(
    model=str(models / 'model.int8.onnx'), tokens=str(models / 'tokens.txt'),
    num_threads=1, language='zh', use_itn=True)
load_ms = (time.perf_counter() - start) * 1000
corpus = json.loads(args.corpus.read_text())
results = []
for entry in corpus['audio']:
    path = args.root / (entry['id'] + '.flac')
    assert hashlib.sha256(path.read_bytes()).hexdigest() == entry['sha256']
    audio, rate = sf.read(path, dtype='float32')
    assert rate == 16000 and audio.ndim == 1
    for variant, samples in [('full', audio), ('tail8', audio[-8 * rate:])]:
        start = time.perf_counter()
        stream = recognizer.create_stream()
        stream.accept_waveform(rate, samples)
        recognizer.decode_stream(stream)
        elapsed = (time.perf_counter() - start) * 1000
        result = {'id': entry['id'], 'source_row': entry['row_index'],
                  'label_complete': entry['label'], 'audio_sha256': entry['sha256'],
                  'source_seconds': len(audio) / rate,
                  'exceeds_product_15s_capacity': len(audio) > 15 * rate,
                  'variant': variant, 'input_seconds': len(samples) / rate,
                  'text': stream.result.text, 'asr_ms': elapsed}
        results.append(result)
        print(json.dumps(result, ensure_ascii=False), flush=True)
report = {'scope': 'Public synthetic audio only; ASR text is not a trusted reference transcript',
          'model_files_sha256': expected, 'language': 'zh', 'use_itn': True,
          'threads': 1, 'model_load_ms': load_ms,
          'sherpa_onnx_version': importlib.metadata.version('sherpa-onnx'),
          'corpus_sha256': hashlib.sha256(args.corpus.read_bytes()).hexdigest(),
          'cases': results}
(args.root / 'whole-turn-asr.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
