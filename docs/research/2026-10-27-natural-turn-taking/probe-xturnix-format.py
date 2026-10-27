"""Verify the upstream text format without loading weights or making predictions."""
import argparse
import ast
from collections.abc import Mapping, Sequence
import hashlib
import importlib.metadata
import json
from pathlib import Path
import unicodedata

from transformers import AutoTokenizer

parser = argparse.ArgumentParser()
parser.add_argument('--root', required=True, type=Path)
args = parser.parse_args()
folder = args.root / 'xturnix-model'
metadata = json.loads((args.root / 'xturnix-files.json').read_text())
checked = []
for entry in metadata:
    path = folder / entry['path']
    if not path.is_file():
        continue
    data = path.read_bytes()
    assert len(data) == entry['size']
    if 'lfs' in entry:
        assert hashlib.sha256(data).hexdigest() == entry['lfs']['oid']
    else:
        assert hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest() == entry['oid']
    checked.append({'file': entry['path'], 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
tree = ast.parse((folder / 'turn_pipeline.py').read_text())
functions = {'strip_trailing_punctuation', 'normalize_state', 'state_from_system_prompt', 'prepare_messages'}
nodes = [n for n in tree.body if isinstance(n, ast.Assign) or isinstance(n, ast.FunctionDef) and n.name in functions]
namespace = {'Mapping': Mapping, 'Sequence': Sequence, 'Any': object, 'unicodedata': unicodedata}
code = 'from __future__ import annotations\n' + ast.unparse(ast.Module(body=nodes, type_ignores=[]))
exec(compile(code, 'hash-verified-upstream-format', 'exec'), namespace)
tokenizer = AutoTokenizer.from_pretrained(folder, local_files_only=True)
cases = []
for case in json.loads((args.root / 'whole-turn-asr.json').read_text())['cases']:
    messages, state = namespace['prepare_messages'](case['text'], state='listening')
    encoded = tokenizer.apply_chat_template(messages, tokenize=True, add_generation_prompt=True, enable_thinking=False, return_dict=True)['input_ids']
    complete = tokenizer.apply_chat_template([*messages, {'role': 'assistant', 'content': '<|start|>'}],
                                             tokenize=True, add_generation_prompt=False, enable_thinking=False, return_dict=True)['input_ids']
    assert len(complete) <= 2048
    # Current user is preserved exactly except the documented trailing punctuation.
    assert messages[-1]['content'] == namespace['strip_trailing_punctuation'](case['text'])
    assert messages[-1]['content'] in tokenizer.decode(encoded)
    assert state == '<|listening|>'
    cases.append({'id': case['id'], 'variant': case['variant'], 'input_tokens': len(encoded),
                  'complete_tokens': len(complete), 'truncated': False})
action_ids = {}
for action in ['<|start|>', '<|keep|>', '<|stop|>']:
    ids = tokenizer.encode(action, add_special_tokens=False)
    assert len(ids) == 1 and tokenizer.convert_ids_to_tokens(ids[0]) == action
    action_ids[action] = ids[0]
weight = next(e for e in metadata if e['path'] == 'model.safetensors')
partial = folder / 'model.safetensors.part'
report = {'status': 'format-verified-inference-not-run',
          'repo': 'xcczach/xturnix-zh-base', 'revision': 'b69ac8a4d98e93332ca3452c569a77dd499287a9',
          'license': 'Apache-2.0', 'checked_files': checked,
          'weight_bytes_required': weight['size'], 'weight_sha256_required': weight['lfs']['oid'],
          'weight_present_and_verified': any(e['file'] == 'model.safetensors' for e in checked),
          'partial_download_bytes': partial.stat().st_size if partial.exists() else 0,
          'asr_cases': len(cases), 'max_seq_length': 2048, 'action_token_ids': action_ids,
          'format_only_packages': {name: importlib.metadata.version(name) for name in ['transformers', 'tokenizers', 'jinja2', 'markupsafe']},
          'cases': cases, 'predictions': None}
(args.root / 'xturnix-qualification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({k: v for k, v in report.items() if k not in {'checked_files', 'cases'}}, ensure_ascii=False, indent=2))
print('token_range', min(c['input_tokens'] for c in cases), max(c['input_tokens'] for c in cases))
