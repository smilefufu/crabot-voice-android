from pathlib import Path
import argparse, hashlib, json
import numpy as np
import sherpa_onnx as sherpa
import soundfile as sf

p = argparse.ArgumentParser()
p.add_argument('--model', type=Path, required=True)
p.add_argument('--audio', type=Path, required=True)
a = p.parse_args()
x, sr = sf.read(a.audio, dtype='float32')
assert sr == 16000 and x.ndim == 1
rms = np.array([np.sqrt(np.mean(x[i:i+320]**2)) for i in range(0,len(x),320)])
active = np.flatnonzero(rms > rms.max() * .04)
start = int(active[0] * 320); x = x[start:]
extractor = sherpa.SpeakerEmbeddingExtractor(sherpa.SpeakerEmbeddingExtractorConfig(model=str(a.model), num_threads=2))
rows = []
for seconds in [.1, .15, .2, .25, .3]:
    stream = extractor.create_stream()
    stream.accept_waveform(16000, np.ascontiguousarray(x[:round(seconds * 16000)])); stream.input_finished()
    ready = extractor.is_ready(stream)
    v = np.asarray(extractor.compute(stream)) if ready else np.array([])
    rows.append(dict(seconds=seconds, ready=ready, dimension=len(v), finite=bool(len(v) and np.isfinite(v).all()), norm=float(np.linalg.norm(v))))
print(json.dumps(dict(audio=a.audio.name, audio_sha256=hashlib.sha256(a.audio.read_bytes()).hexdigest(), source_start_sample=start, model_sha256=hashlib.sha256(a.model.read_bytes()).hexdigest(), sherpa_version=sherpa.__version__, microphone_used=False, limitation='Extraction only, no identity accuracy evaluation or Android validation.', rows=rows), indent=2))
