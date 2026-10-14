from pathlib import Path
import argparse, hashlib, json, time
import numpy as np
import sherpa_onnx as sherpa
import soundfile as sf

p = argparse.ArgumentParser()
p.add_argument('--model', type=Path, required=True)
p.add_argument('--data', type=Path, required=True)
a = p.parse_args()
extractor = sherpa.SpeakerEmbeddingExtractor(sherpa.SpeakerEmbeddingExtractorConfig(model=str(a.model), num_threads=2, provider='cpu'))
def audio(name):
    x, sr = sf.read(a.data / name, dtype='float32')
    assert sr == 16000 and x.ndim == 1
    return np.ascontiguousarray(x)
def embedding(x):
    stream = extractor.create_stream()
    stream.accept_waveform(16000, np.ascontiguousarray(x)); stream.input_finished()
    return np.asarray(extractor.compute(stream), dtype=np.float32) if extractor.is_ready(stream) else None
profiles = {}
for person, names in {'A': ['fangjun-sr-1.wav', 'fangjun-sr-2.wav', 'fangjun-sr-3.wav'], 'B': ['leijun-sr-1.wav', 'leijun-sr-2.wav']}.items():
    vectors = [embedding(audio(n)) for n in names]
    assert all(v is not None for v in vectors)
    v = np.mean(vectors, axis=0); profiles[person] = v / np.linalg.norm(v)
rows = []
for truth, name in [('A', 'fangjun-test-sr-1.wav'), ('A', 'fangjun-test-sr-2.wav'), ('B', 'leijun-test-sr-1.wav'), ('B', 'leijun-test-sr-2.wav'), ('unknown', 'liudehua-test-sr-1.wav')]:
    x = audio(name)
    # Remove external quiet only; retain internal pauses and independent test files.
    rms = np.array([np.sqrt(np.mean(x[i:i+320]**2)) for i in range(0,len(x),320)])
    active = np.flatnonzero(rms > rms.max() * .04)
    x = x[active[0]*320:min(len(x),(active[-1]+1)*320)]
    for duration in [.3, .5, .75, 1., 1.18, 1.5, 2., 3.]:
        n = round(duration * 16000)
        for start in range(0, len(x)-n+1, 8000):
            t = time.perf_counter(); v = embedding(x[start:start+n])
            scores = {} if v is None else {k: float(np.dot(v/np.linalg.norm(v),p)) for k,p in profiles.items()}
            sorted_scores = sorted(scores.items(), key=lambda p:p[1], reverse=True)
            predicted = 'uncertain' if not sorted_scores else 'unknown' if sorted_scores[0][1] < .50 else 'uncertain' if sorted_scores[0][1]-sorted_scores[1][1] < .08 else sorted_scores[0][0]
            single = 'uncertain' if v is None else 'A' if scores['A'] >= .50 else 'unknown'
            rows.append(dict(source=name,truth=truth,duration=duration,start=start/16000,ready=v is not None,predicted=predicted,single=single,scores=scores,ms=(time.perf_counter()-t)*1000))
summary = []
for duration in [.3,.5,.75,1.,1.18,1.5,2.,3.]:
    r=[x for x in rows if x['duration']==duration];known=[x for x in r if x['truth']!='unknown'];unknown=[x for x in r if x['truth']=='unknown']
    summary.append(dict(duration=duration,n=len(r),ready=sum(x['ready'] for x in r),known=len(known),correct=sum(x['predicted']==x['truth'] for x in known),wrong_known=sum(x['predicted'] in ('A','B') and x['predicted']!=x['truth'] for x in known),unknown=len(unknown),false_accept=sum(x['predicted'] in ('A','B') for x in unknown),single_A_correct=sum(x['single']=='A' for x in r if x['truth']=='A'),single_A_total=sum(x['truth']=='A' for x in r),single_A_false_accept=sum(x['single']=='A' for x in r if x['truth']!='A')))
out=dict(model_sha256=hashlib.sha256(a.model.read_bytes()).hexdigest(),sherpa_version=sherpa.__version__,score=.50,margin=.08,limitations='Overlapping crops of five public test files; not independent subjects or household calibration. No microphone or user vectors.',summary=summary,rows=rows)
(a.data/'results.json').write_text(json.dumps(out,ensure_ascii=False,indent=2))
print(json.dumps({k:v for k,v in out.items() if k!='rows'},ensure_ascii=False,indent=2))
