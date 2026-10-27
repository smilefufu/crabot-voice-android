"""Run the hash-pinned upstream pipeline on fixed public ASR output only."""
import argparse
from collections.abc import Mapping
import hashlib
import importlib.metadata
import importlib.util
import json
from pathlib import Path
import platform
import time

import numpy as np
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

parser = argparse.ArgumentParser()
parser.add_argument('--root', required=True, type=Path)
parser.add_argument('--device', choices=['mps', 'cpu'], default='mps')
args = parser.parse_args()
folder = args.root / 'xturnix-model'
provenance = json.loads((args.root / 'xturnix-qualification.json').read_text())
assert provenance['revision'] == 'b69ac8a4d98e93332ca3452c569a77dd499287a9'
assert provenance['weight_present_and_verified'], 'Full hash-verified weights required; no predictions have run'
for entry in provenance['checked_files']:
    assert hashlib.sha256((folder / entry['file']).read_bytes()).hexdigest() == entry['sha256']
spec = importlib.util.spec_from_file_location('upstream_xturnix', folder / 'turn_pipeline.py')
upstream = importlib.util.module_from_spec(spec)
spec.loader.exec_module(upstream)
torch.set_num_threads(1)
device = torch.device(args.device)
assert args.device != 'mps' or torch.backends.mps.is_available()
tokenizer = AutoTokenizer.from_pretrained(folder, local_files_only=True)
start = time.perf_counter()
model = AutoModelForCausalLM.from_pretrained(folder, dtype=torch.bfloat16, local_files_only=True)
model = model.to(device).eval()
pipe = upstream.TurnTakingPipeline(model=model, tokenizer=tokenizer, device=device)
load_ms = (time.perf_counter() - start) * 1000

def sync():
    if args.device == 'mps':
        torch.mps.synchronize()

def predict(text):
    sync()
    start = time.perf_counter()
    encoded = pipe.preprocess(text, state='listening')
    token_count = encoded['input_ids'].shape[-1]
    # No history is supplied; the upstream must retain the entire current user.
    messages, _ = upstream.prepare_messages(text, state='listening')
    original = pipe._apply_chat_template(messages, add_generation_prompt=True, return_tensors=False)
    if isinstance(original, Mapping):
        original = original['input_ids']
    assert token_count == len(original)
    before_forward = time.perf_counter()
    outputs = pipe.forward(encoded)
    sync()
    after_forward = time.perf_counter()
    result = pipe.postprocess(outputs)
    elapsed = (time.perf_counter() - start) * 1000
    assert result['action'] in {'<|start|>', '<|keep|>'}
    return {**result, 'tokens': token_count, 'truncated': False,
            'preprocess_ms': (before_forward - start) * 1000,
            'forward_ms': (after_forward - before_forward) * 1000,
            'detector_ms': elapsed}

with torch.inference_mode():
    warmups = [predict(text) for text in ['你好', '请等一下我还没说完', '我想问你一件事']]
    inputs = json.loads((args.root / 'whole-turn-asr.json').read_text())
    cases = []
    for case in inputs['cases']:
        result = predict(case['text'])
        complete = result['action'] == '<|start|>'
        record = {**case, **result, 'predicted_complete': complete,
                  'correct': complete == case['label_complete'],
                  'serial_asr_detector_ms': case['asr_ms'] + result['detector_ms']}
        cases.append(record)
        print(json.dumps({'id': case['id'], 'variant': case['variant'],
                          'action': result['action'], 'scores': result['scores'],
                          'correct': record['correct'], 'ms': result['detector_ms']}, ensure_ascii=False), flush=True)
    failure = next(c for c in cases if c['id'] == '0384164c-d1e3-4881-861b-1bf5494cd90e' and c['variant'] == 'full')
    # Verify the manual timing path against the standard upstream call as well.
    normal = pipe(failure['text'], state='listening')
    assert normal['action'] == failure['action']
    for token, score in normal['scores'].items():
        assert abs(score - failure['scores'][token]) < 1e-6

def stats(values):
    return {'median': float(np.median(values)), 'p95': float(np.percentile(values, 95)), 'max': max(values)}

summary = {}
for variant in ['full', 'tail8']:
    rows = [c for c in cases if c['variant'] == variant]
    summary[variant] = {
        'cases': len(rows),
        'premature': sum(not c['label_complete'] and c['predicted_complete'] for c in rows),
        'missed_complete': sum(c['label_complete'] and not c['predicted_complete'] for c in rows),
        'input_tokens': stats([c['tokens'] for c in rows]),
        'asr_ms': stats([c['asr_ms'] for c in rows]),
        'detector_ms': stats([c['detector_ms'] for c in rows]),
        'serial_asr_detector_ms': stats([c['serial_asr_detector_ms'] for c in rows])}
report = {'scope': 'Fixed synthetic public corpus, file-only component test; not field acceptance',
          'device': args.device, 'dtype': 'bfloat16', 'threads': torch.get_num_threads(),
          'system': platform.platform(), 'python': platform.python_version(),
          'packages': {name: importlib.metadata.version(name) for name in ['torch', 'transformers', 'tokenizers', 'safetensors']},
          'model_load_ms': load_ms, 'model_revision': provenance['revision'],
          'model_files': provenance['checked_files'], 'max_length': pipe._configured_max_length(),
          'warmups': warmups, 'standard_pipeline_parity': True,
          'whole_turn_asr_sha256': hashlib.sha256((args.root / 'whole-turn-asr.json').read_bytes()).hexdigest(),
          'summary': summary, 'cases': cases}
(args.root / ('xturnix-results-' + args.device + '.json')).write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(summary, ensure_ascii=False, indent=2))
