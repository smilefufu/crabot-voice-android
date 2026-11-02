"""Fixed public-audio reference using audited upstream Easy Turn math, offline."""
import argparse
import ast
import hashlib
import importlib.metadata
import json
from pathlib import Path
import re
import sys
import time
from unittest.mock import patch

import librosa
import numpy as np
import torch
import torch.nn.functional as F
import torchaudio
from transformers import AutoConfig, AutoModelForCausalLM, GenerationConfig
import yaml

HERE = Path(__file__).resolve().parent


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--publisher', action='store_true', help='Run the separately preselected official controls only')
    args = parser.parse_args()
    cache, source = args.cache.resolve(), args.source.resolve()
    plan_path = HERE / 'easy-turn-evaluation-plan.json'
    plan = json.loads(plan_path.read_text())
    model_path = source / 'wenet/llm_asr/llmasr_model.py'
    feature_path = source / 'wenet/dataset/processor.py'
    assert sha(model_path) == plan['source']['model_file_sha256']
    assert sha(feature_path) == plan['source']['feature_file_sha256']
    checkpoint_path = cache / plan['weights']['file']
    assert checkpoint_path.stat().st_size == plan['weights']['bytes']
    assert sha(checkpoint_path) == plan['weights']['sha256']
    assert json.loads((cache / 'qwen/revision.json').read_text())['revision'] == plan['base']['revision']
    torch.set_num_threads(2)
    torch.set_num_interop_threads(1)
    torch.manual_seed(777)
    sys.path.insert(0, str(source))
    from wenet.llm_asr.llmasr_model import LLMASR_Model
    from wenet.transformer.encoder import TransformerEncoder

    # Execute the exact upstream feature function, without importing training/SoX setup.
    function = next(node for node in ast.parse(feature_path.read_text()).body
                    if isinstance(node, ast.FunctionDef) and node.name == 'compute_log_mel_spectrogram')
    namespace = {'torch': torch, 'F': F, 'librosa': librosa}
    exec(compile(ast.Module(body=[function], type_ignores=[]), str(feature_path), 'exec'), namespace)
    features = namespace['compute_log_mel_spectrogram']
    config_path = source / 'examples/wenetspeech/whisper/conf/train.yaml'
    prompt_path = source / 'examples/wenetspeech/whisper/conf/prompt.yaml'
    config = yaml.safe_load(config_path.read_text())
    prompt = yaml.safe_load(prompt_path.read_text())['<TRANSCRIBE> <BACKCHANNEL> <COMPLETE>'][0]
    report = {'scope': plan['scope'], 'plan_sha256': sha(plan_path), 'manifest_sha256': sha(HERE / plan['inputs']['manifest']),
              'source': plan['source'], 'weights': plan['weights'], 'base': plan['base'],
              'versions': {name: importlib.metadata.version(name) for name in
                           ['numpy', 'torch', 'torchaudio', 'transformers', 'peft', 'librosa']},
              'config_sha256': sha(config_path), 'prompt_file_sha256': sha(prompt_path),
              'prompt_sha256': hashlib.sha256(prompt.encode()).hexdigest(), 'prompt': prompt,
              'threads': {'intra': torch.get_num_threads(), 'inter': torch.get_num_interop_threads()},
              'device': 'cpu', 'results': []}
    if args.publisher:
        report['publisher_selection'] = json.loads((cache / 'publisher/selection.json').read_text())
        report['scope'] = 'Official eight fixed controls for adapter qualification, not a replacement corpus'

    def save():
        output = 'results-publisher.json' if args.publisher else 'results.json'
        (cache / output).write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')

    started = time.perf_counter()
    base_config = AutoConfig.from_pretrained(cache / 'qwen', local_files_only=True, trust_remote_code=False)

    def construct_base(*unused, **kwargs):
        result = AutoModelForCausalLM.from_config(base_config, torch_dtype=kwargs['torch_dtype'], trust_remote_code=False)
        result.config.output_hidden_states = kwargs['output_hidden_states']
        result.generation_config = GenerationConfig.from_pretrained(cache / 'qwen', local_files_only=True)
        return result

    # Avoid downloading duplicate base weights; every initialized value is overwritten below.
    with patch.object(AutoModelForCausalLM, 'from_pretrained', side_effect=construct_base):
        model = LLMASR_Model(encoder=TransformerEncoder(config['input_dim'], global_cmvn=None, **config['encoder_conf']),
                            encoder_output_dim=config['encoder_conf']['output_size'], llm_path=str(cache / 'qwen'),
                            lora=config['use_lora'], is_inference=True, downsample_rate=config['downsample_rate'],
                            adapter_type=config['adapter_type'], speech_token_num=config['speech_token_num'],
                            train_speech_out=True)
    state = torch.load(str(checkpoint_path), map_location='cpu', weights_only=True, mmap=True)
    for alias in ['embed_tokens.weight', 'lm_head.weight', 'llama_model.lm_head.weight']:
        assert torch.equal(state[alias], state['llama_model.model.embed_tokens.weight']), alias
    loaded = model.load_state_dict(state, strict=True)
    assert not loaded.missing_keys and not loaded.unexpected_keys
    report['strict_load'] = {'tensors': len(state), 'missing': [], 'unexpected': [],
                             'checkpoint_dtypes': sorted({str(t.dtype) for t in state.values()}),
                             'encoder_dtype': str(next(model.encoder.parameters()).dtype),
                             'llm_dtype': str(model.embed_tokens.weight.dtype)}
    del state
    model.eval()
    report['model_load_ms'] = (time.perf_counter() - started) * 1000
    save()
    print(json.dumps({'loaded': report['strict_load'], 'model_load_ms': report['model_load_ms']}), flush=True)
    rows = json.loads((HERE / plan['inputs']['manifest']).read_text())['audio']
    repeated = [next(row for row in rows if row['id'] == plan['inputs']['original_failure_id'])]
    repeated += [row for row in rows if not row['label'] and row not in repeated][:2]
    repeated += [row for row in rows if row['label']][:2]
    if args.publisher:
        calls = [({'id': item['row']['key'], 'bytes': item['bytes'], 'sha256': item['sha256'],
                   'label': item['expected'] == 'complete', 'expected_tag': item['expected']}, 'full', 0)
                 for item in report['publisher_selection']['rows']]
    else:
        calls = [(row, variant, 0) for row in rows for variant in plan['inputs']['variants']]
        calls += [(row, 'full', repeat) for repeat in [1, 2] for row in repeated]
    for row, variant, repeat in calls:
        path = cache / ('publisher' if args.publisher else 'audio') / (row['id'] + ('.wav' if args.publisher else '.flac'))
        assert path.stat().st_size == row['bytes'] and sha(path) == row['sha256'], row['id']
        result = {'id': row['id'], 'variant': variant, 'repeat': repeat, 'label_complete': row['label']}
        if args.publisher:
            result['expected_tag'] = row['expected_tag']
        started = time.perf_counter()
        waveform, rate = torchaudio.load(path)
        if waveform.shape[0] != 1:
            result.update({'tag': 'input_not_run', 'channels': waveform.shape[0],
                           'reason': 'Official feature function requires mono; no unplanned downmix'})
            report['results'].append(result)
            save()
            print(json.dumps(result), flush=True)
            continue
        if rate != 16000:
            waveform = torchaudio.transforms.Resample(orig_freq=rate, new_freq=16000)(waveform)
        if variant == 'tail8':
            waveform = waveform[:, -128000:]
        result['audio_samples'] = waveform.shape[-1]
        batch = next(features(iter([{'sample_rate': 16000, 'wav': waveform, 'key': row['id'], 'label': []}]),
                              num_mel_bins=80, n_fft=400, hop_length=160, padding=0))['feat'].unsqueeze(0)
        result['feature_ms'] = (time.perf_counter() - started) * 1000
        started = time.perf_counter()
        try:
            with torch.inference_mode():
                outputs = model.generate(batch, torch.tensor([batch.shape[1]]), prompt)
            assert len(outputs) == 1
            text = outputs[0]
            matches = list(re.finditer(r'<(complete|incomplete|backchannel|wait)>', text, re.IGNORECASE))
            valid = len(matches) == 1 and not text[matches[0].end():].strip()
            result.update({'text': text, 'tag': matches[0].group(1).lower() if valid else 'invalid'})
        except Exception as error:
            result.update({'tag': 'error', 'error': type(error).__name__ + ': ' + str(error)})
        result['generation_ms'] = (time.perf_counter() - started) * 1000
        result['reference_wall_ms'] = result['feature_ms'] + result['generation_ms']
        report['results'].append(result)
        save()
        print(json.dumps(result, ensure_ascii=False), flush=True)
        if result['tag'] == 'error':
            report['stopped'] = 'Compatibility failure; no silent dtype or model change'
            break
    save()


if __name__ == '__main__':
    main()
