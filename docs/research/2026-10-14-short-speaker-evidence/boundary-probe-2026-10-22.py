from pathlib import Path
import argparse, collections, json, hashlib
import numpy as np
import sherpa_onnx as s
import soundfile as sf
parser=argparse.ArgumentParser(description="Public-only boundary sensitivity probe; no microphone or real enrollment access")
parser.add_argument('--fixtures',type=Path,required=True)
parser.add_argument('--models',type=Path,required=True)
parser.add_argument('--baseline',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
root=args.fixtures
models=args.models
extractor=s.SpeakerEmbeddingExtractor(s.SpeakerEmbeddingExtractorConfig(model=str(models/'campplus.onnx'),num_threads=2))
diarizer=s.OfflineSpeakerDiarization(s.OfflineSpeakerDiarizationConfig(segmentation=s.OfflineSpeakerSegmentationModelConfig(pyannote=s.OfflineSpeakerSegmentationPyannoteModelConfig(model=str(models/'segmentation.onnx')),num_threads=2),embedding=s.SpeakerEmbeddingExtractorConfig(model=str(models/'campplus.onnx'),num_threads=2),clustering=s.FastClusteringConfig(num_clusters=-1,threshold=.5),min_duration_on=0,min_duration_off=0))
def load(name):
 x,sr=sf.read(root/name,dtype='int16');assert sr==16000 and x.ndim==1
 return x.astype(np.float32)/32768

def emb(x):
 stream=extractor.create_stream();stream.accept_waveform(16000,np.ascontiguousarray(x));stream.input_finished()
 assert extractor.is_ready(stream)
 return np.array(extractor.compute(stream),dtype=np.float32)

def cosine(a,b):return float(np.dot(a,b)/np.linalg.norm(a)/np.linalg.norm(b))

def verdict(scores):
 v=sorted(scores.items(),key=lambda p:-p[1])
 return 'unknown' if v[0][1]<.5 else 'uncertain' if v[0][1]-v[1][1]<.08 else v[0][0]

profiles={k:np.mean([emb(load(f)) for f in names],axis=0) for k,names in {'A':['fangjun-sr-1.wav','fangjun-sr-2.wav','fangjun-sr-3.wav'],'B':['leijun-sr-1.wav','leijun-sr-2.wav']}.items()}
previous=json.loads(args.baseline.read_text())
rows=[]; skipped=0
for old in previous['rows']:
 x=load(old['file']); start=old['start'];duration=old['duration']
 if duration:x=x[round(start*16000):round((start+duration)*16000)]
 captured=np.concatenate([np.zeros(4000,dtype=np.float32),x,np.zeros(12800,dtype=np.float32)])
 ranges=[(round(seg.start*16000),round(seg.end*16000),seg.speaker) for seg in diarizer.process(np.ascontiguousarray(captured)).sort_by_start_time()]
 if len({r[2] for r in ranges})!=1:skipped+=1;continue
 # Previous corpus has only one row per these single-speaker captures.
 joined=np.concatenate([captured[a:b] for a,b,_ in ranges]);base=emb(joined)
 variants={'current':joined,'whole':captured}
 for milliseconds in [100,200]:
  padding=milliseconds*16; merged=[]
  for a,b,_ in ranges:
   a=max(0,a-padding);b=min(len(captured),b+padding)
   if merged and a<=merged[-1][1]:merged[-1]=(merged[-1][0],max(b,merged[-1][1]))
   else:merged.append((a,b))
  variants[f'real_{milliseconds}']=np.concatenate([captured[a:b] for a,b in merged])
  variants[f'zeros_{milliseconds}']=np.pad(joined,(padding,padding))
 result={}
 for name,audio in variants.items():
  v=base if name=='current' else emb(audio)
  scores={k:cosine(v,p) for k,p in profiles.items()}
  result[name]={'samples':len(audio),'scores':scores,'prediction':verdict(scores),'cosine_to_current':cosine(v,base)}
 assert abs(result['current']['scores']['A']-old['current']['A'])<.0001
 rows.append({k:old[k] for k in ['truth','file','duration','start','voiced_s']}|{'ranges':ranges,'variants':result})
summary={}
for variant in rows[0]['variants']:
 known=[r for r in rows if r['truth']!='unknown'];unknown=[r for r in rows if r['truth']=='unknown']
 delta=[r['variants'][variant]['scores'][r['truth']]-r['variants']['current']['scores'][r['truth']] for r in known]
 summary[variant]={'known':len(known),'correct':sum(r['variants'][variant]['prediction']==r['truth'] for r in known),'wrong_known':sum(r['variants'][variant]['prediction'] in profiles and r['variants'][variant]['prediction']!=r['truth'] for r in known),'unknown':len(unknown),'false_accept':sum(r['variants'][variant]['prediction'] in profiles for r in unknown),'known_score_delta_mean':float(np.mean(delta)),'known_score_delta_min':float(min(delta)),'known_score_delta_max':float(max(delta))}
out={'scope':'Public fixed independent enrollment/test recordings only. Overlapping crops of three people; not household accuracy or user voice evidence. Synthetic-zero variants diagnose sensitivity and are not a product proposal.','skipped_multi_speaker_rows':skipped,'summary':summary,'rows':rows}
out['embedding_sha256']=hashlib.sha256((models/'campplus.onnx').read_bytes()).hexdigest()
out['segmentation_sha256']=hashlib.sha256((models/'segmentation.onnx').read_bytes()).hexdigest()
out['sherpa_onnx_version']=s.__version__
args.output.write_text(json.dumps(out,indent=2))
print(json.dumps(summary,indent=2))
