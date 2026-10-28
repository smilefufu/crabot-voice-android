"""Summarize all frozen rows, including misses and unscorable short pauses."""
import argparse
import json
import math
from pathlib import Path
import statistics


def stats(values):
    values = sorted(values)
    return {'n': len(values), 'median': statistics.median(values) if values else None,
            'p95': values[math.ceil(.95 * len(values)) - 1] if values else None,
            'max': max(values) if values else None}


def analyze(root):
    fixed = json.loads((root / 'fixed-results.json').read_text())['cases']
    real = json.loads((root / 'real-results.json').read_text())
    points = [json.loads(line) for line in (root / 'real-points.jsonl').read_text().splitlines()]
    keys = {(p['id'], p['span']) for p in points}
    selected = json.loads((root / 'real-selection.json').read_text())
    assert {x['id'] for x in real} == {x['id'] for x in selected['selected']}
    assert len(real) == len(selected['selected'])
    summary = {'scope': 'Fixed component comparison, not deployment or household acceptance', 'fixed': [], 'real': {}}
    for silence in [0, .2, .8, 1, 2, 3]:
        cases = [c for c in fixed if c['added_silence_s'] == silence]
        assert len(cases) == 20
        models = ['int8', 'smart', *(['fp32'] if silence == 0 else [])]
        for model in models:
            summary['fixed'].append({'added_silence_s': silence, 'model': model,
                'premature_rows': [c['row'] for c in cases if not c['expected_complete'] and c[model]['complete']],
                'missed_complete_rows': [c['row'] for c in cases if c['expected_complete'] and not c[model]['complete']],
                'pipeline_ms': stats([c[model]['pipeline_ms'] for c in cases])})
    summary['int8_fp32_original_disagreement_rows'] = [c['row'] for c in fixed if 'fp32' in c and c['fp32']['label'] != c['int8']['label']]
    summary['real'].update(source_rows=len(selected['selected']) + len(selected['excluded']), turns=len(real),
        excluded_rows=len(selected['excluded']), point_count=len(points),
        raw_hold_spans=sum(not s['eot'] for row in real for s in row['int8']['spans']))
    summary['real']['scored_hold_spans'] = sum(not s['eot'] and (row['id'], s['span']) in keys for row in real for s in row['int8']['spans'])
    summary['real']['short_unscored_hold_spans'] = summary['real']['raw_hold_spans'] - summary['real']['scored_hold_spans']
    for model in ['int8', 'smart']:
        spans = [(row['id'], s) for row in real for s in row[model]['spans'] if not s['eot'] and (row['id'], s['span']) in keys]
        ending = [row[model]['endpoint_decision_ms'] for row in real]
        adequate = [row for row in real if row[model]['spans'][-1]['duration'] >= 1.5 - 1e-8]
        long_holds = [s for _, s in spans if 1 - 1e-8 <= s['duration'] < 3 - 1e-8]
        summary['real'][model] = {
            'premature_spans': sum(s['decision_ms'] is not None for _, s in spans),
            'premature_turns': sum(row[model]['premature_span_count'] > 0 for row in real),
            'missed_until_recording_end': sum(x is None for x in ending),
            'adequate_1_5s_terminal_silence_turns': len(adequate),
            'not_completed_within_1_5s_in_adequate_turns': sum(row[model]['endpoint_decision_ms'] is None or row[model]['endpoint_decision_ms'] > 1500 for row in adequate),
            'clean_completed_turns': sum(row[model]['premature_span_count'] == 0 and row[model]['endpoint_decision_ms'] is not None for row in real),
            'long_1_3s_hold_spans': len(long_holds),
            'long_1_3s_hold_premature': sum(s['decision_ms'] is not None for s in long_holds),
            'endpoint_ms_successful_only': stats([x for x in ending if x is not None]),
            'pipeline_ms_all_points': stats([p[model]['pipeline_ms'] for p in points]),
            'invalid_points': sum(p[model].get('label') == 'invalid' for p in points)}
    new, corrected = [], []
    for row in real:
        for a, b in zip(row['int8']['spans'], row['smart']['spans']):
            assert a['span'] == b['span'] and a['eot'] == b['eot']
            if not a['eot'] and a['decision_ms'] is not None and b['decision_ms'] is None:
                new.append({'id': row['id'], 'span': a['span']})
            if not a['eot'] and a['decision_ms'] is None and b['decision_ms'] is not None:
                corrected.append({'id': row['id'], 'span': a['span']})
    summary['real']['int8_new_premature_spans_vs_smart'] = new
    summary['real']['int8_corrected_premature_spans_vs_smart'] = corrected
    cadence = json.loads((root / 'real-cadence.json').read_text())
    early, terminal, missing = [], [], []
    for row in cadence:
        end = next((p for p in row['observations'] if p['eot'] and p['complete']), None)
        if end:
            terminal.append(end['wall_decision_ms'])
        else:
            missing.append(row['id'])
        if any(not p['eot'] and p['complete'] for p in row['observations']):
            early.append(row['id'])
    summary['real_cadence'] = {'turns': len(cadence), 'premature_turn_ids': early, 'missed_turn_ids': missing,
                              'endpoint_wall_ms_successful_only': stats(terminal)}
    (root / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, required=True)
    analyze(parser.parse_args().root)
