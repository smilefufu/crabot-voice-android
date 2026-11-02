"""Compare the probe tokenizer against the pinned official AutoTokenizer, offline."""
import argparse
import json
from pathlib import Path

from tokenizers import Tokenizer
from transformers import AutoTokenizer

HERE = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, required=True)
    args = parser.parse_args()
    rows = json.loads((HERE / 'whole-turn-asr.json').read_text())['cases']
    results = []
    for model in json.loads((HERE / 'namo-evaluation-plan.json').read_text())['models']:
        folder = args.cache / model['repo'].split('/')[-1]
        official = AutoTokenizer.from_pretrained(folder, local_files_only=True)
        probe = Tokenizer.from_file(str(folder / 'tokenizer.json'))
        probe.no_truncation()
        probe.no_padding()
        for row in rows:
            expected = official(row['text'], add_special_tokens=True, padding=False, truncation=False)
            actual = probe.encode(row['text'], add_special_tokens=True)
            assert actual.ids == expected['input_ids'], row['id']
            assert actual.attention_mask == expected['attention_mask'], row['id']
        results.append({'model': folder.name, 'cases': len(rows),
                        'input_ids_and_attention_mask_exact': True,
                        'official_class': type(official).__name__, 'truncation': False, 'padding': False})
    (args.cache / 'tokenizer-parity.json').write_text(json.dumps(results, indent=2) + '\n')
    print(json.dumps(results))


if __name__ == '__main__':
    main()
