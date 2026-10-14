"""Prepare ignored public WAV fixtures; never accepts user recordings."""
import argparse, array, hashlib, json, math, sys, wave
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--source-dir', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
manifest = json.loads(Path(__file__).with_name('sources.json').read_text())
a.output.mkdir(parents=True, exist_ok=True)
enroll = ['fangjun-sr-1.wav', 'fangjun-sr-2.wav', 'fangjun-sr-3.wav']
queries = [('short-a.wav', 'fangjun-test-sr-1.wav', 3.), ('short-b.wav', 'leijun-test-sr-1.wav', 2.), ('short-c.wav', 'liudehua-test-sr-1.wav', 1.5)]
for name in enroll + [x[1] for x in queries]:
    path = a.source_dir / name
    assert hashlib.sha256(path.read_bytes()).hexdigest() == manifest[name]['sha256'], name
for name in enroll:
    (a.output / name).write_bytes((a.source_dir / name).read_bytes())
for output, source, offset in queries:
    with wave.open(str(a.source_dir / source)) as f:
        assert f.getframerate() == 16000 and f.getnchannels() == 1 and f.getsampwidth() == 2
        samples = array.array('h', f.readframes(f.getnframes()))
    if sys.byteorder != 'little': samples.byteswap()
    rms = [math.sqrt(sum(v*v for v in samples[i:i+320])/len(samples[i:i+320])) for i in range(0,len(samples),320)]
    start = next(i for i, level in enumerate(rms) if level > max(rms)*.04)*320 + round(offset*16000)
    clip = samples[start:start+18880]
    assert len(clip) == 18880
    # A finite turn ends in silence, as in the app. This is excluded by diarization,
    # not added to the voice evidence used for identity matching.
    clip.extend([0] * 12800)
    if sys.byteorder != 'little': clip.byteswap()
    with wave.open(str(a.output / output), 'wb') as f:
        f.setnchannels(1); f.setsampwidth(2); f.setframerate(16000); f.writeframes(clip.tobytes())
print('Verified public enrollment and independent short queries with turn-end silence prepared')
