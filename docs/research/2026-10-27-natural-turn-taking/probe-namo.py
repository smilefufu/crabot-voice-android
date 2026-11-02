"""Fixed public ASR-text quality reference. No audio, service, device or product change."""
import argparse
import hashlib
import importlib.metadata
import json
from pathlib import Path
import time
import urllib.request

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

HERE = Path(__file__).resolve().parent


def sha(data):
    return hashlib.sha256(data).hexdigest()


def download(url, target, expected=None):
    if not target.exists():
        temporary = target.with_suffix(target.suffix + '.part')
        with urllib.request.urlopen(url, timeout=60) as response, temporary.open('wb') as out:
            while block := response.read(1024 * 1024): out.write(block)
        if expected: assert sha(temporary.read_bytes()) == expected, target.name
        temporary.replace(target)
    if expected: assert sha(target.read_bytes()) == expected, target.name


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, required=True)
    args = parser.parse_args(); cache = args.cache.resolve(); cache.mkdir(parents=True, exist_ok=True)
    plan_bytes = (HERE / 'namo-evaluation-plan.json').read_bytes(); plan = json.loads(plan_bytes)
    original = (HERE / plan['inputs']['file']).read_bytes()
    assert sha(original) == plan['inputs']['sha256']
    rows = json.loads(original)['cases']; assert len(rows) == 40 and len({x['id'] for x in rows}) == 20
    report = {'scope': plan['scope'], 'plan_sha256': sha(plan_bytes), 'input_sha256': sha(original),
              'versions': {name: importlib.metadata.version(name) for name in ['numpy', 'onnxruntime', 'tokenizers']}, 'models': []}
    for model in plan['models']:
        folder = cache / model['repo'].split('/')[-1]; folder.mkdir(exist_ok=True)
        for name in ['model_quant.onnx', 'tokenizer.json', 'tokenizer_config.json', 'special_tokens_map.json', 'config.json', 'LICENSE']:
            download('https://huggingface.co/' + model['repo'] + '/resolve/' + model['revision'] + '/' + name + '?download=true',
                     folder / name, model['model_sha256'] if name == 'model_quant.onnx' else None)
        print(json.dumps({'downloaded': model['repo'], 'revision': model['revision']}), flush=True)
        options = ort.SessionOptions(); options.intra_op_num_threads = 1; options.inter_op_num_threads = 1
        options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        started = time.perf_counter()
        session = ort.InferenceSession(str(folder / 'model_quant.onnx'), sess_options=options, providers=['CPUExecutionProvider'])
        tokenizer = Tokenizer.from_file(str(folder / 'tokenizer.json'))
        # Single input: upstream tokenizer uses no batch padding. Reject rather than silently truncate.
        tokenizer.no_truncation(); tokenizer.no_padding()
        load_ms = (time.perf_counter() - started) * 1000
        assert {x.name for x in session.get_inputs()} == {'input_ids', 'attention_mask'}
        results = []
        for repeat in range(3):
            for row in rows:
                started = time.perf_counter()
                encoded = tokenizer.encode(row['text'], add_special_tokens=True)
                assert len(encoded.ids) <= model['max_tokens'], row['id']
                logits = session.run(None, {'input_ids': np.asarray([encoded.ids], dtype=np.int64),
                                           'attention_mask': np.asarray([encoded.attention_mask], dtype=np.int64)})[0][0]
                assert logits.shape == (2,) and np.isfinite(logits).all()
                probabilities = np.exp(logits - np.max(logits)); probabilities /= probabilities.sum()
                label = int(np.argmax(probabilities))
                elapsed = (time.perf_counter() - started) * 1000
                results.append({'id': row['id'], 'variant': row['variant'], 'repeat': repeat,
                    'label_complete': row['label_complete'], 'complete': label == 1, 'probabilities': probabilities.tolist(),
                    'tokens': len(encoded.ids), 'text_sha256': sha(row['text'].encode()),
                    'exceeds_product_15s_capacity': row['exceeds_product_15s_capacity'], 'wall_ms': elapsed})
        first = results[:40]
        summaries = {}
        for variant in plan['inputs']['variants']:
            selected = [x for x in first if x['variant'] == variant]
            summaries[variant] = {'incomplete': sum(not x['label_complete'] for x in selected),
                'premature': [x['id'] for x in selected if not x['label_complete'] and x['complete']],
                'complete': sum(x['label_complete'] for x in selected),
                'misses': [x['id'] for x in selected if x['label_complete'] and not x['complete']]}
        changes = sum(x['complete'] != first[i % 40]['complete'] for i, x in enumerate(results))
        timing = {key: float(value) for key, value in zip(['p50', 'p95', 'max'], np.percentile([x['wall_ms'] for x in results[40:]], [50, 95, 100]))}
        item = {**model, 'files': {p.name: sha(p.read_bytes()) for p in folder.iterdir() if p.is_file()},
                'load_ms': load_ms, 'first_call_ms': results[0]['wall_ms'], 'warm_classifier_wall_ms': timing,
                'action_changes': changes, 'summary': summaries, 'results': results}
        report['models'].append(item)
        (cache / 'results.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps({k: item[k] for k in ['repo', 'load_ms', 'first_call_ms', 'warm_classifier_wall_ms', 'action_changes', 'summary']}), flush=True)
    (cache / 'results.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
