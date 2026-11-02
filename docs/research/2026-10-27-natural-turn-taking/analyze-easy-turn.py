"""Score saved raw outputs; preserve the strict parser result and audit the known EOS marker."""
import argparse
import json
from pathlib import Path
import re

EOS = '<|endoftext|>'


def tag(text):
    value = text.rstrip()
    if value.endswith(EOS):
        value = value[:-len(EOS)].rstrip()
    matches = list(re.finditer(r'<(complete|incomplete|backchannel|wait)>', value, re.IGNORECASE))
    if len(matches) != 1 or value[matches[0].end():].strip() or EOS in value:
        return 'invalid'
    return matches[0].group(1).lower()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    original = json.loads(args.input.read_text())
    rows = [{**row, 'decoded_tag': tag(row['text']) if 'text' in row else 'error'} for row in original['results']]
    report = {'scope': 'Saved raw predictions, no inference rerun or threshold change',
              'parser_audit': 'Original strict tag field retained. Only one trailing official generation EOS marker may be removed; no other content is discarded.',
              'cases': rows, 'summary': {}}
    first = [row for row in rows if row['repeat'] == 0]
    for variant in dict.fromkeys(row['variant'] for row in first):
        selected = [row for row in first if row['variant'] == variant]
        report['summary'][variant] = {
            'cases': len(selected),
            'strict_invalid': sum(row['tag'] == 'invalid' for row in selected),
            'decoded_invalid': [row['id'] for row in selected if row['decoded_tag'] in ['invalid', 'error']],
            'premature': [row['id'] for row in selected if not row['label_complete'] and row['decoded_tag'] == 'complete'],
            'misses': [row['id'] for row in selected if row['label_complete'] and row['decoded_tag'] != 'complete'],
            'other_tags': [row['id'] for row in selected if row['decoded_tag'] in ['wait', 'backchannel']]}
    originals = {(row['id'], row['variant']): row['decoded_tag'] for row in first}
    report['repeat_action_changes'] = [row for row in rows if row['repeat'] > 0 and
                                      row['decoded_tag'] != originals[(row['id'], row['variant'])]]
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({k: v for k, v in report.items() if k != 'cases'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
