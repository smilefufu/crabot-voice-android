"""Audit existing predictions and replay fixed public audio with upstream VAD.

No microphone, playback, business API, threshold search, or product controller.
"""
import argparse
from collections import Counter
import hashlib
import io
import json
import math
from pathlib import Path
import sys
import time
from types import SimpleNamespace
import zipfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def first_check_comparison(points, model):
    holds = [p for p in points if not p['eot']]
    first = [p for p in holds if p['pause_s'] == .2]
    return {
        'scorable_hold_spans': len(first),
        'first_check_positive': sum(p[model]['complete'] for p in first),
        'any_check_positive': len({(p['id'], p['span']) for p in holds if p[model]['complete']}),
    }


def classify(at, spans):
    if at is None:
        return 'no_complete_before_eof'
    for index, span in enumerate(spans):
        if span['start'] - 1e-8 <= at <= span['end'] + 1e-8:
            return 'endpoint' if index == len(spans) - 1 else 'premature'
    return 'outside_annotated_silence'


def summarize(replay, points):
    summary = {}
    for model in ['smart', 'int8']:
        adequate = [row for row in replay if row['terminal_silence_s'] >= 1.5]
        summary[model] = {
            'old_policy': first_check_comparison(points, model),
            'first_commit_outcomes': dict(Counter(row[model]['first_commit_status'] for row in replay)),
            'final_vad_stop_missing': sum(row[model]['final_vad_stop'] is None for row in replay),
            'adequate_terminal_turns': len(adequate),
            'adequate_final_vad_missing': sum(row[model]['final_vad_stop'] is None for row in adequate),
            'adequate_final_vad_model_hold': sum(row[model]['final_decision'] is not None and not row[model]['final_decision']['complete'] for row in adequate),
        }
    return summary


def rescore(args):
    """Correct annotation gaps using stored outputs, without rerunning models."""
    evidence = Path(__file__).parent
    result = json.loads(args.rescore_from.read_text())
    original_summary = result['summary']
    selection = json.loads((evidence / 'turnsense-real-selection.json').read_text())['selected']
    spans = {row['id']: row['spans'] for row in selection}
    for row in result['turns']:
        for model in ['smart', 'int8']:
            row[model]['first_commit_status'] = classify(row[model]['first_commit_at'], spans[row['id']])
    points = [json.loads(line) for line in (evidence / 'turnsense-real-points.jsonl').read_text().splitlines()]
    result['summary'] = summarize(result['turns'], points)
    result['scoring_correction'] = {
        'original_result_sha256': sha(args.rescore_from), 'scoring_runner_sha256': sha(Path(__file__)),
        'original_summary_before_correction': original_summary,
        'reason': 'A VAD event outside all source candidate-silence intervals has no supplied label. It must be unscored, not automatically premature because it precedes the final interval.',
        'no_new_inference': True,
    }
    args.out.mkdir(parents=True, exist_ok=True)
    target = args.out / 'audit-results.json'
    assert not target.exists()
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result['summary']), flush=True)


def run(args):
    import numpy as np
    import onnxruntime as ort
    import pyarrow.parquet as pq
    import soundfile as sf
    from transformers import WhisperFeatureExtractor

    root, out, evidence = args.root, args.out, Path(__file__).parent
    out.mkdir(parents=True, exist_ok=True)
    assert not (out / 'audit-results.json').exists(), 'Use a fresh output directory'
    plan = evidence / 'turn-audit-plan.json'
    old_files = ['turnsense-fixed-results.json', 'turnsense-real-points.jsonl',
                 'turnsense-real-results.json', 'turnsense-real-selection.json']
    before = {name: sha(evidence / name) for name in old_files}
    assert sha(root / 'model-int8.onnx') == '9ffa8fd0bb423986065afdedcb822fc957bb0794bcb6023c94575d8d82dd8e92'
    assert sha(root / 'model-fp32.onnx') == '82256247dd38f0d9f2f213beec7ede1507347f84422fa48f29d565b613d89976'
    assert sha(root / 'zh.parquet') == '5fd7a30919721832642bea7201ea3d9b09fa6ebce13ae7902ec21ad2dbc9f0f8'
    assert sha(root.parent / 'smart-turn-v3.2-cpu.onnx') == '2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f'
    sys.path.insert(0, str(root / 'publisher'))
    for name, expected in [('infer.py', 'ee39d33f59bae90e45605976d4154cc78a8459da'),
                           ('frontend/audio_frontend.py', 'c53c033235cf5ee2fadab24ef9a485b4aac2c8b8')]:
        source = (root / 'publisher' / name).read_bytes()
        assert hashlib.sha1(b'blob ' + str(len(source)).encode() + b'\0' + source).hexdigest() == expected
    import infer as publisher

    def session(path, use_cuda=False):
        options = ort.SessionOptions()
        options.intra_op_num_threads = options.inter_op_num_threads = 1
        return ort.InferenceSession(str(path), options, providers=['CPUExecutionProvider'])

    publisher.build_session = session
    config = {**publisher.DEFAULT_FRONTEND_CONF, 'cmvn_file': str(root / 'am.mvn')}
    models = {name: publisher.AudioClassifierInfer(str(root / file), publisher.LABELS, frontend_conf=config)
              for name, file in [('int8', 'model-int8.onnx'), ('fp32', 'model-fp32.onnx')]}
    smart = session(root.parent / 'smart-turn-v3.2-cpu.onnx')
    extractor = WhisperFeatureExtractor(chunk_length=8)

    def predict(audio, which):
        started = time.perf_counter()
        if which == 'smart':
            value = np.asarray(audio[-128000:], dtype=np.float32)
            value = np.pad(value, (128000 - len(value), 0))
            features = extractor(value, sampling_rate=16000, return_tensors='np', padding='max_length',
                                 max_length=128000, truncation=True, do_normalize=True).input_features
            probability = float(smart.run(None, {'input_features': features})[0].reshape(-1)[0])
            result = {'complete': probability > .5, 'scores': [probability]}
        else:
            model = models[which]
            value = publisher.truncate_audio(audio, model.audio_seconds, 16000, model.clip_mode)
            probs, labels = publisher.process_predictions(model._run_model(value))
            label = publisher.LABELS[int(labels[0])]
            result = {'complete': label == 'complete', 'label': label, 'scores': probs[0].tolist()}
        return {**result, 'pipeline_ms': (time.perf_counter() - started) * 1000}

    corpus = json.loads((evidence / 'labeled-corpus.json').read_text())['audio']
    previous = json.loads((evidence / 'turnsense-fixed-results.json').read_text())['cases']
    previous = {x['id']: x for x in previous if x['added_silence_s'] == 0}
    repeats = []
    for row in corpus:
        path = root.parent / (row['id'] + '.flac')
        assert sha(path) == row['sha256']
        audio, rate = sf.read(path, dtype='float32')
        assert rate == 16000 and audio.ndim == 1
        for model in ['smart', 'int8', 'fp32']:
            results = [predict(audio, model) for _ in range(3)]
            prior = previous[row['id']][model]
            prior_scores = prior['probabilities'] if 'probabilities' in prior else [prior['probability']]
            repeats.append({'id': row['id'], 'model': model, 'expected_complete': row['label'],
                            'results': results,
                            'action_changes': sum(x['complete'] != results[0]['complete'] for x in results[1:]),
                            'max_score_drift': max(abs(a - b) for x in results[1:] for a, b in zip(results[0]['scores'], x['scores'])),
                            'prior_action_agrees': prior['complete'] == results[0]['complete'],
                            'prior_score_delta': max(abs(a - b) for a, b in zip(prior_scores, results[0]['scores']))})
    (out / 'audio-repeatability.json').write_text(json.dumps(repeats, indent=2) + '\n')
    print(json.dumps({'phase': 'audio_repeats', 'cases': len(repeats), 'action_changes': sum(x['action_changes'] for x in repeats)}), flush=True)

    # Import the installed, unmodified wheel rather than reconstructing VAD.
    from loguru import logger
    logger.remove()
    vad_errors = []
    logger.add(lambda message: vad_errors.append(str(message)), level='ERROR')
    from pipecat.audio.vad import silero
    from pipecat.audio.vad.vad_analyzer import VADState
    upstream = {}
    with zipfile.ZipFile(args.wheel) as wheel:
        for name in ['pipecat/audio/vad/silero.py', 'pipecat/audio/vad/vad_analyzer.py',
                     'pipecat/audio/utils.py', 'pipecat/audio/vad/data/silero_vad.onnx']:
            installed = args.dependencies / name
            assert installed.read_bytes() == wheel.read(name), name
            upstream[name] = sha(installed)
    clock = [1000.0]
    silero.time = SimpleNamespace(time=lambda: clock[0])  # Source clock, no process-wide patch.
    vad = silero.SileroVADAnalyzer(sample_rate=16000)
    vad.set_sample_rate(16000)
    params = vad.params.model_dump()
    assert params == {'confidence': .7, 'start_secs': .2, 'stop_secs': .2, 'min_volume': .6}
    rows = pq.read_table(root / 'zh.parquet').to_pylist()
    selection = json.loads((evidence / 'turnsense-real-selection.json').read_text())['selected']
    replay, contexts = [], []
    old_summary = json.loads((evidence / 'turnsense-summary.json').read_text())['real']
    new_keys = {(x['id'], x['span']) for x in old_summary['int8_new_premature_spans_vs_smart']}
    for index, item in enumerate(selection):
        row = rows[item['row']]
        raw = row['audio']['bytes']
        assert row['id'] == item['id'] and hashlib.sha256(raw).hexdigest() == item['audio_sha256']
        info = sf.info(io.BytesIO(raw))
        assert info.samplerate == 16000 and info.channels == 1 and info.subtype == 'PCM_16'
        pcm, _ = sf.read(io.BytesIO(raw), dtype='int16')
        audio = pcm.astype(np.float32) / 32768
        original, _ = sf.read(io.BytesIO(raw), dtype='float32')
        assert np.array_equal(audio, original)
        vad.set_params(vad.params)
        vad._model.reset_states()
        vad._vad_buffer = b''
        vad._prev_volume = 0
        vad._last_reset_time = 0
        active, first_start, buffer_start, events, frame_times = False, None, None, [], []
        for begin in range(0, len(pcm) - 511, 512):
            end = begin + 512
            at = end / 16000
            clock[0] = 1000 + at
            started = time.perf_counter()
            state = vad._run_analyzer(pcm[begin:end].tobytes())
            frame_times.append({'at': at, 'ms': (time.perf_counter() - started) * 1000})
            if state == VADState.SPEAKING and not active:
                active = True
                if first_start is None:
                    first_start = at
                    start_time = at - .5 - params['start_secs']
                    buffer_start = max(0, math.ceil(start_time * 16000 / 512) - 1) * 512
            if state == VADState.QUIET and active:
                active = False
                assert buffer_start is not None
                available = audio[buffer_start:end]
                events.append({'at': at, 'buffer_start_s': buffer_start / 16000,
                               **{model: predict(available, model) for model in ['smart', 'int8']}})
        final = row['silence_spans'][-1]
        outcomes = {}
        for model in ['smart', 'int8']:
            commit = next((x for x in events if x[model]['complete']), None)
            final_event = next((x for x in events if x['at'] >= final['start']), None)
            status = classify(commit['at'] if commit else None, row['silence_spans'])
            outcomes[model] = {
                'first_commit_status': status, 'first_commit_at': commit['at'] if commit else None,
                'final_vad_stop': final_event['at'] if final_event else None,
                'final_decision': final_event[model] if final_event else None,
                'endpoint_ms': (commit['at'] - final['start']) * 1000 + commit[model]['pipeline_ms'] if status == 'endpoint' else None,
            }
        replay.append({'id': row['id'], 'duration': row['duration'], 'final_start': final['start'],
                       'terminal_silence_s': final['end'] - final['start'], 'first_vad_start': first_start,
                       'unprocessed_eof_samples': len(pcm) % 512, 'events': events,
                       'vad_pipeline_ms': sum(x['ms'] for x in frame_times), **outcomes})
        for span_index, span in enumerate(row['silence_spans'][:-1]):
            length = span['end'] - span['start']
            if (row['id'], span_index) in new_keys or 1 <= length < 3:
                words = row['words']
                contexts.append({'id': row['id'], 'span': span_index, 'start': span['start'], 'end': span['end'],
                                 'old_new_premature': (row['id'], span_index) in new_keys,
                                 'words': words, 'review_scope': 'Source transcript and timings only; labels unchanged, no independent listening'})
        if (index + 1) % 32 == 0:
            print(json.dumps({'phase': 'vad_replay', 'done': index + 1, 'total': len(selection)}), flush=True)
    vad._executor.shutdown()
    assert not vad_errors, vad_errors
    points = [json.loads(line) for line in (evidence / 'turnsense-real-points.jsonl').read_text().splitlines()]
    summary = summarize(replay, points)
    assert before == {name: sha(evidence / name) for name in old_files}
    result = {'scope': 'Fixed public audio VAD-triggered model audit, not full upstream conversation or product acceptance',
              'plan_sha256': sha(plan), 'runner_sha256': sha(Path(__file__)), 'source_evidence_sha256': before,
              'wheel_sha256': sha(args.wheel), 'upstream_installed_equals_wheel': upstream, 'vad_params': params,
              'audio_repeatability': {'cases': len(repeats), 'calls': len(repeats) * 3,
                  'action_changes': sum(x['action_changes'] for x in repeats),
                  'max_score_drift': max(x['max_score_drift'] for x in repeats),
                  'prior_action_disagreements': sum(not x['prior_action_agrees'] for x in repeats)},
              'summary': summary, 'turns': replay}
    (out / 'audit-results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    (out / 'source-label-contexts.json').write_text(json.dumps(contexts, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'phase': 'finished', 'repeatability': result['audio_repeatability'], 'summary': summary}), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path)
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('--wheel', type=Path)
    parser.add_argument('--dependencies', type=Path)
    parser.add_argument('--rescore-from', type=Path)
    args = parser.parse_args()
    if args.rescore_from:
        rescore(args)
    else:
        if not all([args.root, args.wheel, args.dependencies]):
            parser.error('--root, --wheel and --dependencies are required for inference')
        run(args)
