"""Verify upstream VAD swallowed no errors; do not call any endpoint model."""
import argparse
import hashlib
import io
import json
from pathlib import Path
from types import SimpleNamespace


def run(args):
    import pyarrow.parquet as pq
    import soundfile as sf
    from loguru import logger
    from pipecat.audio.vad import silero
    from pipecat.audio.vad.vad_analyzer import VADState

    assert not args.out.exists()
    evidence = Path(__file__).parent
    reference = json.loads(args.reference.read_text())
    selection = json.loads((evidence / 'turnsense-real-selection.json').read_text())['selected']
    rows = pq.read_table(args.root / 'zh.parquet').to_pylist()
    logger.remove()
    errors = []
    logger.add(lambda message: errors.append(str(message)), level='ERROR')
    clock = [1000.0]
    silero.time = SimpleNamespace(time=lambda: clock[0])
    vad = silero.SileroVADAnalyzer(sample_rate=16000)
    vad.set_sample_rate(16000)
    assert vad.params.model_dump() == reference['vad_params']
    verified = []
    for item, prior in zip(selection, reference['turns']):
        row = rows[item['row']]
        raw = row['audio']['bytes']
        assert row['id'] == prior['id'] == item['id']
        assert hashlib.sha256(raw).hexdigest() == item['audio_sha256']
        pcm, rate = sf.read(io.BytesIO(raw), dtype='int16')
        assert rate == 16000 and pcm.ndim == 1
        vad.set_params(vad.params)
        vad._model.reset_states()
        vad._vad_buffer, vad._prev_volume, vad._last_reset_time = b'', 0, 0
        active, stops = False, []
        for begin in range(0, len(pcm) - 511, 512):
            at = (begin + 512) / 16000
            clock[0] = 1000 + at
            state = vad._run_analyzer(pcm[begin:begin + 512].tobytes())
            if state == VADState.SPEAKING and not active:
                active = True
            if state == VADState.QUIET and active:
                active = False
                stops.append(at)
        assert stops == [e['at'] for e in prior['events']], row['id']
        verified.append({'id': row['id'], 'stop_events': len(stops), 'matches_reference': True})
    vad._executor.shutdown()
    assert not errors, errors
    assert len(verified) == len(selection) == len(reference['turns']) == 256
    result = {'scope': 'VAD-only repeat to check logged/swallowed inference errors and exact source-clock event parity; no endpoint-model rerun',
              'reference_sha256': hashlib.sha256(args.reference.read_bytes()).hexdigest(),
              'runner_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'rows': verified, 'errors': errors, 'verified_turns': len(verified),
              'verified_stop_events': sum(x['stop_events'] for x in verified)}
    args.out.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({k: result[k] for k in ['verified_turns', 'verified_stop_events', 'errors']}), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', required=True, type=Path)
    parser.add_argument('--reference', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    run(parser.parse_args())
