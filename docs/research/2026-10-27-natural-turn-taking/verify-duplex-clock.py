"""Replay real Kotlin timeline, APM JNI and two VADs on fixed public files; no devices."""
import argparse
import hashlib
import importlib.util
import importlib.metadata
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request

import numpy as np
from scipy.signal import resample_poly

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
spec = importlib.util.spec_from_file_location('controls', HERE / 'probe-apm.py')
controls = importlib.util.module_from_spec(spec); spec.loader.exec_module(controls)


def pcm(values):
    scaled = values.astype(np.float64) * 32768
    return np.clip(np.sign(scaled) * np.floor(np.abs(scaled) + .5), -32768, 32767).astype('<i2')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(command, **kwargs):
    return subprocess.run(list(map(str, command)), check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assert importlib.metadata.version('sherpa-onnx') == '1.13.8'
    cache = args.cache.resolve(); output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    source = cache / 'webrtc-audio-processing-1.3'
    assert sha(cache / 'webrtc-audio-processing-1.3.tar.gz') == '95552fc17faa0202133707bbb3727e8c2cf64d4266fe31bfdb2298d769c1db75'
    manifest = json.loads((HERE / 'apm-input-manifest.json').read_text())
    public = output / 'public'; public.mkdir(exist_ok=True)
    for name, item in manifest['sources'].items():
        target = public / name
        if not target.exists():
            local = ROOT / '.model-cache/short-speaker' / name
            if local.exists(): target.write_bytes(local.read_bytes())
            else:
                with urllib.request.urlopen(item['url'], timeout=60) as response: target.write_bytes(response.read())
        assert sha(target) == item['sha256'], name
    sources = output / 'sources.json'; sources.write_text(json.dumps(manifest['sources']))
    fixed = output / 'fixed'
    controls.run(argparse.Namespace(root=fixed, public_audio=public, sources=sources, prepare_only=True))
    assert json.loads((fixed / 'input-manifest.json').read_text()) == manifest
    model = ROOT / 'app/src/main/assets/models/silero.onnx'
    assert sha(model) == '9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6'
    java = Path(os.environ['JAVA_HOME'])
    sherpa = cache / 'build-tools/lib/python3.12/site-packages/sherpa_onnx'
    include = ['-I' + str(source / 'webrtc'), '-I' + str(source / 'subprojects/abseil-cpp-20230125.1')]
    common = ['c++', '-std=c++17', '-O2', '-DNDEBUG', '-DWEBRTC_POSIX', '-DWEBRTC_MAC', '-DWEBRTC_APM_DEBUG_DUMP=0', *include]
    libraries = sorted((cache / 'build-host-app').rglob('*.a')); assert len(libraries) == 24
    cpp = ROOT / 'app/src/main/cpp'
    run([*common, '-I' + str(java / 'include'), '-I' + str(java / 'include/darwin'), '-I' + str(sherpa / 'include'),
         cpp / 'echo_processor.cpp', cpp / 'echo_jni.cpp', cpp / 'speech_activity.cpp', *libraries,
         '-L' + str(sherpa / 'lib'), '-lsherpa-onnx-cxx-api', '-lsherpa-onnx-c-api',
         '-Wl,-rpath,' + str(sherpa / 'lib'), '-framework', 'Foundation', '-dynamiclib', '-o', output / 'libcrabot_voice.dylib'])
    run([*common, HERE / 'probe-apm.cc', *libraries, '-framework', 'Foundation', '-o', output / 'probe'])
    stdlib = list((Path.home() / '.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.1.20').glob('*/kotlin-stdlib-2.1.20.jar'))
    assert len(stdlib) == 1
    classpath = os.pathsep.join(map(str, [ROOT / 'app/build/tmp/kotlin-classes/debug', stdlib[0], output]))
    run([java / 'bin/javac', '-cp', classpath, '-d', output, HERE / 'DuplexClockProbe.java'])
    cases = []
    for item in manifest['cases']:
        if not item['aec']: continue
        name = item['name']; render = np.fromfile(fixed / 'inputs' / (name + '-render.f32'), '<f4')
        capture = np.fromfile(fixed / 'inputs' / (name + '-capture.f32'), '<f4')
        for suffix in ['render', 'capture']:
            assert sha(fixed / 'inputs' / (name + '-' + suffix + '.f32')) == item[suffix + '_sha256']
        cases.append((name, 'continuous', pcm(render), pcm(capture), pcm(render)))
    logical = pcm(np.fromfile(fixed / 'inputs/double_talk_on-render.f32', '<f4'))
    physical = np.concatenate([logical[:12000 * 24], np.zeros(300 * 24, '<i2'), logical[12000 * 24:]])
    far = resample_poly(physical.astype(np.float64) / 32768, 2, 3)
    near = np.pad(np.fromfile(fixed / 'inputs/near_only_on-capture.f32', '<f4'), (0, 4800))
    echo = np.zeros_like(far)
    for delay, gain in manifest['echo_taps_ms_gain']:
        offset = delay * 16; echo[offset:] += far[:-offset] * gain
    for name, capture, rendered in [('near_pause', near, np.zeros_like(physical)), ('echo_pause', echo, physical), ('double_pause', near + echo, physical)]:
        source_pcm = np.zeros_like(logical) if name == 'near_pause' else logical
        cases.append((name, 'pause', source_pcm, pcm(capture), rendered))
    for mode in ['clock_wait', 'jitter']:
        cases.append(('double_' + mode, mode, logical, pcm(near + echo), physical))
    report = {'scope': 'Production Kotlin timeline, JNI APM and VADs on public controls with simulated hardware clocks; no physical or Chinese EOT acceptance',
              'public_manifest_sha256': sha(HERE / 'apm-input-manifest.json'), 'silero_sha256': sha(model), 'cases': []}
    for name, mode, accepted, capture, expected_render in cases:
        folder = output / name; folder.mkdir(exist_ok=True)
        accepted.tofile(folder / 'accepted.pcm'); capture.tofile(folder / 'capture.pcm')
        before = time.monotonic()
        result = run([java / 'bin/java', '-Djava.library.path=' + str(output), '-cp', classpath, 'com.crabot.voice.DuplexClockProbe',
                      folder / 'accepted.pcm', folder / 'capture.pcm', folder, mode, model.parent], capture_output=True, text=True, timeout=60)
        timing = json.loads(result.stdout); elapsed = time.monotonic() - before
        actual_render = np.fromfile(folder / 'render.pcm', '<i2')
        assert np.array_equal(actual_render, expected_render), name + ': unplayed or missing render'
        for kind, samples in [('render', expected_render), ('capture', capture)]:
            (samples.astype(np.float32) / np.float32(32768)).tofile(folder / (kind + '.f32'))
        run([output / 'probe', folder / 'render.f32', folder / 'capture.f32', folder / 'baseline.f32', '1', '0'], stdout=subprocess.DEVNULL, timeout=30)
        assert np.array_equal(pcm(np.fromfile(folder / 'baseline.f32', '<f4')), np.fromfile(folder / 'clean.pcm', '<i2')), name + ': APM discontinuity'
        activity = np.fromfile(folder / 'activity.bin', 'u1')
        row = {'name': name, 'mode': mode, **timing, 'seconds': len(capture) / 16000,
               'render_exact': True, 'raw_identity_exact': True, 'apm_pcm_exact': True,
               'clean_sha256': sha(folder / 'clean.pcm'), 'candidate_20ms_frames': int(np.count_nonzero(activity & 1)),
               'confirmed_20ms_frames': int(np.count_nonzero(activity & 2)), 'host_jvm_seconds': elapsed}
        report['cases'].append(row); print(json.dumps(row), flush=True)
    for mode in ['clock_wait', 'jitter']:
        assert sha(output / ('double_' + mode) / 'clean.pcm') == sha(output / 'double_pause/clean.pcm')
    (output / 'results.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
