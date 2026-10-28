"""Fixed public-audio comparison. Never captures, plays, or contacts an API.

Uses pinned publisher TurnSense feature/decision code, unchanged weights and
argmax. The real-turn replay exposes only each timestamp's available prefix.
"""
import argparse
import hashlib
import io
import json
import math
from pathlib import Path
import sys
import time


def pause_points(spans, duration):
    """Keep original pause labels; 200ms is a fixed observation cadence."""
    result = []
    for index, span in enumerate(spans):
        start, end = float(span['start']), float(span['end'])
        if not 0 <= start < end <= duration + .001:
            raise ValueError('Invalid original silence span')
        for step in range(1, math.floor((end - start + 1e-8) / .2) + 1):
            result.append({'span': index, 'at': round(start + step * .2, 7),
                           'pause_s': round(step * .2, 7), 'eot': index == len(spans) - 1})
    return result


def first_decision(points, predictions, span):
    for point, prediction in zip(points, predictions):
        if point['span'] == span and prediction['complete']:
            return point['pause_s'] * 1000 + prediction['pipeline_ms']
    return None  # Missing is not a timeout-generated successful commit.


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(root, realtime_count):
    import importlib.metadata
    import numpy as np
    import onnxruntime as ort
    import pyarrow.parquet as pq
    import soundfile as sf
    from transformers import WhisperFeatureExtractor

    parent = root.parent
    plan = json.loads((root / 'evaluation-plan.json').read_text())
    assert plan['primary']['file'] == 'TurnSense1.0/model_int8.onnx'
    assert digest(root / 'model-int8.onnx') == '9ffa8fd0bb423986065afdedcb822fc957bb0794bcb6023c94575d8d82dd8e92'
    assert digest(root / 'model-fp32.onnx') == '82256247dd38f0d9f2f213beec7ede1507347f84422fa48f29d565b613d89976'
    assert digest(root / 'zh.parquet') == '5fd7a30919721832642bea7201ea3d9b09fa6ebce13ae7902ec21ad2dbc9f0f8'
    corpus = json.loads(Path(__file__).with_name('labeled-corpus.json').read_text())
    for row in corpus['audio']:
        assert digest(parent / (row['id'] + '.flac')) == row['sha256']

    rows = pq.read_table(root / 'zh.parquet').to_pylist()
    selected, excluded = [], []
    for index, row in enumerate(rows):
        reason = None
        if row['language'] != 'zh':
            reason = 'not_Chinese'
        elif not 0 < row['duration'] <= 15:
            reason = 'outside_existing_15s_input_capacity'
        elif not row['silence_spans']:
            reason = 'no_original_pause_labels'
        else:
            pause_points(row['silence_spans'], row['duration'])
        if reason:
            excluded.append({'id': row['id'], 'row': index, 'duration': row['duration'], 'reason': reason})
        else:
            raw = row['audio']['bytes']
            assert raw, row['id']
            info = sf.info(io.BytesIO(raw))
            assert info.samplerate == 16000 and info.channels == 1 and info.frames <= 240000, row['id']
            assert abs(info.duration - row['duration']) < .001, row['id']
            selected.append({'id': row['id'], 'row': index, 'duration': row['duration'],
                             'audio_sha256': hashlib.sha256(raw).hexdigest(), 'spans': row['silence_spans']})
    selection = {'dataset': plan['real_dataset'], 'dataset_sha256': digest(root / 'zh.parquet'),
                 'selection_before_any_model_prediction': True, 'selected': selected, 'excluded': excluded,
                 'real_cadence_ids': [x['id'] for x in selected[:realtime_count]], 'runner_sha256': digest(Path(__file__))}
    selection_path = root / 'real-selection.json'
    if selection_path.exists():
        previous = json.loads(selection_path.read_text())
        assert previous['selected'] == selected and previous['real_cadence_ids'] == selection['real_cadence_ids']
    else:
        selection_path.write_text(json.dumps(selection, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'phase': 'selection_frozen', 'total': len(rows), 'selected': len(selected), 'excluded': len(excluded)}), flush=True)

    # Import reviewed, pinned code only. No Hugging Face remote-code loading.
    sys.path.insert(0, str(root / 'publisher'))
    import infer as publisher
    assert hashlib.sha1(b'blob ' + str(len((root / 'publisher/infer.py').read_bytes())).encode() + b'\0' + (root / 'publisher/infer.py').read_bytes()).hexdigest() == 'ee39d33f59bae90e45605976d4154cc78a8459da'

    def session(path, use_cuda=False):
        options = ort.SessionOptions()
        options.intra_op_num_threads = 1
        options.inter_op_num_threads = 1
        return ort.InferenceSession(str(path), options, providers=['CPUExecutionProvider'])

    publisher.build_session = session  # Resource limit only; model and numerical pipeline unchanged.
    config = {**publisher.DEFAULT_FRONTEND_CONF, 'cmvn_file': str(root / 'am.mvn')}
    cold = {}
    start = time.perf_counter()
    primary = publisher.AudioClassifierInfer(str(root / 'model-int8.onnx'), publisher.LABELS, frontend_conf=config)
    cold['int8_load_ms'] = (time.perf_counter() - start) * 1000
    start = time.perf_counter()
    reference = publisher.AudioClassifierInfer(str(root / 'model-fp32.onnx'), publisher.LABELS, frontend_conf=config)
    cold['fp32_load_ms'] = (time.perf_counter() - start) * 1000
    baseline_model = parent / 'smart-turn-v3.2-cpu.onnx'
    assert digest(baseline_model) == '2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f'
    baseline = session(baseline_model)
    extractor = WhisperFeatureExtractor(chunk_length=8)

    def predict(audio, which):
        start = time.perf_counter()
        if which == 'smart':
            value = np.asarray(audio[-128000:], dtype=np.float32)
            value = np.pad(value, (128000 - len(value), 0))
            features = extractor(value, sampling_rate=16000, return_tensors='np', padding='max_length',
                                 max_length=128000, truncation=True, do_normalize=True).input_features
            raw = baseline.run(None, {baseline.get_inputs()[0].name: features})[0]
            probability = float(np.asarray(raw).reshape(-1)[0])
            result = {'complete': probability > .5, 'probability': probability}
        else:
            model = primary if which == 'int8' else reference
            value = publisher.truncate_audio(audio, model.audio_seconds, 16000, model.clip_mode)
            raw = model._run_model(value)
            probabilities, predictions = publisher.process_predictions(raw)
            assert probabilities.shape == (1, 3) and np.isfinite(probabilities).all()
            label = publisher.LABELS[int(predictions[0])]
            result = {'complete': label == 'complete', 'label': label, 'probabilities': probabilities[0].tolist()}
        return {**result, 'pipeline_ms': (time.perf_counter() - start) * 1000}

    def decode(raw):
        audio, rate = sf.read(io.BytesIO(raw), dtype='float32')
        assert rate == 16000 and audio.ndim == 1 and np.isfinite(audio).all()
        return audio

    for which in ['int8', 'fp32', 'smart']:
        cold[which + '_first_pipeline'] = predict(np.zeros(16000, np.float32), which)
        for _ in range(2):
            predict(np.zeros(16000, np.float32), which)
    metadata = {'runtime': ort.__version__, 'providers': primary.session.get_providers(),
                'threads': 1, 'cold': cold, 'plan_sha256': digest(root / 'evaluation-plan.json'),
                'model_inputs': {m: [{'name': i.name, 'shape': i.shape, 'type': i.type} for i in s.get_inputs()]
                                 for m, s in [('int8', primary.session), ('fp32', reference.session)]},
                'dependencies': {p: importlib.metadata.version(p) for p in ['numpy', 'onnxruntime', 'kaldi-native-fbank', 'soundfile', 'pyarrow', 'transformers', 'librosa']}}
    (root / 'environment.json').write_text(json.dumps(metadata, indent=2))
    fixed, loader_parity = [], []
    for row in corpus['audio']:
        path = parent / (row['id'] + '.flac')
        audio = decode(path.read_bytes())
        original = publisher.load_audio(str(path), 16000, 8, 'tail')
        assert np.array_equal(original, audio[-128000:]), row['id']
        loader_parity.append(row['id'])
        for silence in [0, .2, .8, 1, 2, 3]:
            value = np.concatenate([audio, np.zeros(round(silence * 16000), np.float32)])
            case = {'id': row['id'], 'row': row['row_index'], 'expected_complete': row['label'],
                    'source_sha256': row['sha256'], 'added_silence_s': silence,
                    'int8': predict(value, 'int8'), 'smart': predict(value, 'smart')}
            if silence == 0:
                case['fp32'] = predict(value, 'fp32')
            fixed.append(case)
    (root / 'fixed-results.json').write_text(json.dumps({'cases': fixed, 'publisher_loader_exact_parity_ids': loader_parity}, indent=2) + '\n')
    print(json.dumps({'phase': 'fixed_finished', 'cases': len(fixed)}), flush=True)

    results, cadence = [], []
    with (root / 'real-points.jsonl').open('w') as log:
        for selected_index, selected_row in enumerate(selected):
            row = rows[selected_row['row']]
            audio = decode(row['audio']['bytes'])
            points = pause_points(row['silence_spans'], min(row['duration'], len(audio) / 16000))
            predictions = {'int8': [], 'smart': []}
            for point in points:
                prefix = audio[:round(point['at'] * 16000)]
                assert len(prefix) <= round(point['at'] * 16000)
                record = {'id': row['id'], **point, 'prefix_samples': len(prefix)}
                for which in predictions:
                    result = predict(prefix, which)
                    predictions[which].append(result)
                    record[which] = result
                log.write(json.dumps(record) + '\n')
            summaries = {}
            for which, decisions in predictions.items():
                spans = [{'span': i, 'eot': i == len(row['silence_spans']) - 1,
                          'duration': span['end'] - span['start'],
                          'decision_ms': first_decision(points, decisions, i)}
                         for i, span in enumerate(row['silence_spans'])]
                summaries[which] = {'spans': spans,
                    'premature_span_count': sum(s['decision_ms'] is not None for s in spans if not s['eot']),
                    'endpoint_decision_ms': spans[-1]['decision_ms'],
                    'invalid_points': sum(d.get('label') == 'invalid' for d in decisions)}
            results.append({'id': row['id'], 'duration': row['duration'], 'point_count': len(points), **summaries})
            if selected_index < realtime_count:
                # Real-time paced reference, same source prefixes and fixed policy.
                epoch = time.perf_counter()
                observations = []
                for point in points:
                    remaining = epoch + point['at'] - time.perf_counter()
                    if remaining > 0:
                        time.sleep(remaining)
                    result = predict(audio[:round(point['at'] * 16000)], 'int8')
                    observations.append({**point, **result, 'wall_at_s': time.perf_counter() - epoch,
                                         'wall_decision_ms': (time.perf_counter() - epoch - (point['at'] - point['pause_s'])) * 1000})
                cadence.append({'id': row['id'], 'observations': observations})
            if (selected_index + 1) % 10 == 0:
                print(json.dumps({'phase': 'real_progress', 'turns': selected_index + 1, 'total': len(selected)}), flush=True)
    (root / 'real-results.json').write_text(json.dumps(results, indent=2) + '\n')
    (root / 'real-cadence.json').write_text(json.dumps(cadence, indent=2) + '\n')
    print(json.dumps({'phase': 'finished', 'real_turns': len(results), 'cadence_turns': len(cadence)}), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--realtime-count', type=int, default=10)
    args = parser.parse_args()
    run(args.root, args.realtime_count)
