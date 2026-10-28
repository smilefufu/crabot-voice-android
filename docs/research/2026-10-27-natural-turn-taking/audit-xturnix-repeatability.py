"""Repeat unchanged official XTurnix calls on the frozen ASR text, offline."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import time


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(args):
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer

    assert not args.out.exists(), 'Do not overwrite prior evidence'
    folder = args.root / 'xturnix-model'
    provenance = json.loads((args.root / 'xturnix-qualification.json').read_text())
    assert provenance['revision'] == 'b69ac8a4d98e93332ca3452c569a77dd499287a9'
    for entry in provenance['checked_files']:
        assert digest(folder / entry['file']) == entry['sha256']
    spec = importlib.util.spec_from_file_location('upstream_xturnix', folder / 'turn_pipeline.py')
    upstream = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(upstream)
    torch.set_num_threads(1)
    model = AutoModelForCausalLM.from_pretrained(folder, dtype=torch.float32, local_files_only=True).eval()
    tokenizer = AutoTokenizer.from_pretrained(folder, local_files_only=True)
    pipe = upstream.TurnTakingPipeline(model=model, tokenizer=tokenizer, device=torch.device('cpu'))
    source = args.root / 'whole-turn-asr.json'
    source_sha = digest(source)
    cases = [x for x in json.loads(source.read_text())['cases'] if x['variant'] == 'full']
    assert len(cases) == 20
    prior = json.loads((args.root / 'xturnix-results-cpu-float32.json').read_text())['cases']
    prior = {x['id']: x for x in prior if x['variant'] == 'full'}
    rows = []
    with torch.inference_mode():
        for case in cases:
            observations = []
            for _ in range(3):
                start = time.perf_counter()
                prediction = pipe(case['text'], state='listening')
                observations.append({**prediction, 'ms': (time.perf_counter() - start) * 1000})
            first = observations[0]
            rows.append({'id': case['id'], 'expected_complete': case['label_complete'],
                         'text_sha256': hashlib.sha256(case['text'].encode()).hexdigest(),
                         'observations': observations,
                         'action_changes': sum(x['action'] != first['action'] for x in observations[1:]),
                         'max_score_drift': max(abs(value - x['scores'][token]) for x in observations[1:] for token, value in first['scores'].items()),
                         'prior_action_agrees': prior[case['id']]['action'] == first['action']})
            print(json.dumps({'done': len(rows), 'action_changes': rows[-1]['action_changes']}), flush=True)
    assert source_sha == digest(source)
    result = {'scope': 'CPU float32 deterministic official two-action logits, not sampled generation or ASR stability',
              'source_sha256': source_sha, 'model_revision': provenance['revision'], 'rows': rows,
              'summary': {'cases': len(rows), 'calls': len(rows) * 3,
                  'action_changes': sum(x['action_changes'] for x in rows),
                  'max_score_drift': max(x['max_score_drift'] for x in rows),
                  'prior_action_disagreements': sum(not x['prior_action_agrees'] for x in rows)}}
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result['summary']), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    run(parser.parse_args())
