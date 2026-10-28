"""Run the actual app APM C++/JNI on the host; no Android device or private audio.

Requires the fixed isolated APM research cache and its pinned NumPy environment.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import numpy as np

ROOT = Path(__file__).resolve().parents[3]

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def pcm(values):
    scaled = values.astype(np.float64) * 32768
    return np.clip(np.sign(scaled) * np.floor(np.abs(scaled) + .5), -32768, 32767).astype('<i2')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    cache = args.cache.resolve(); output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    source = cache / 'webrtc-audio-processing-1.3'
    assert sha(cache / 'webrtc-audio-processing-1.3.tar.gz') == '95552fc17faa0202133707bbb3727e8c2cf64d4266fe31bfdb2298d769c1db75'
    cpp = ROOT / 'app/src/main/cpp'
    java = Path(os.environ['JAVA_HOME'])
    library = output / 'libcrabot_voice.dylib'
    command = ['c++', '-std=c++17', '-O2', '-DNDEBUG', '-DWEBRTC_POSIX', '-DWEBRTC_MAC', '-DWEBRTC_APM_DEBUG_DUMP=0',
        '-I' + str(source / 'webrtc'), '-I' + str(source / 'subprojects/abseil-cpp-20230125.1'),
        '-I' + str(java / 'include'), '-I' + str(java / 'include/darwin'),
        str(cpp / 'echo_processor.cpp'), str(cpp / 'echo_jni.cpp'),
        *map(str, sorted((cache / 'build-host-verified').rglob('*.a'))),
        '-framework', 'Foundation', '-dynamiclib', '-o', str(library)]
    subprocess.run(command, check=True)
    package = output / 'com/crabot/voice'; package.mkdir(parents=True, exist_ok=True)
    (package / 'NativeEchoProcessor.java').write_text('''package com.crabot.voice;
import java.nio.*;
import java.nio.file.*;
public class NativeEchoProcessor {
  native long create(); native void destroy(long handle);
  native void render(long handle, short[] samples);
  native short[] capture(long handle, short[] samples, int delay);
  public static void main(String[] args) throws Exception {
    System.load(args[0]);
    NativeEchoProcessor processor = new NativeEchoProcessor();
    long handle = processor.create();
    try {
      int errors = 0;
      try { processor.render(handle, new short[239]); } catch (IllegalStateException e) { errors++; }
      try { processor.capture(handle, new short[159], 0); } catch (IllegalStateException e) { errors++; }
      try { processor.capture(handle, new short[160], -1); } catch (IllegalStateException e) { errors++; }
      if (errors != 3) throw new AssertionError("JNI boundaries");
    } finally { processor.destroy(handle); }
    handle = processor.create();
    try {
      ShortBuffer render = ByteBuffer.wrap(Files.readAllBytes(Path.of(args[1]))).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
      ShortBuffer capture = ByteBuffer.wrap(Files.readAllBytes(Path.of(args[2]))).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
      ByteBuffer output = ByteBuffer.allocate(capture.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN);
      while (capture.hasRemaining()) {
        short[] far = new short[240], near = new short[160]; render.get(far); capture.get(near);
        processor.render(handle, far);
        for (short sample : processor.capture(handle, near, 80)) output.putShort(sample);
      }
      if (render.hasRemaining()) throw new AssertionError("frame duration");
      Files.write(Path.of(args[3]), output.array());
    } finally { processor.destroy(handle); }
  }
}
''')
    subprocess.run([str(java / 'bin/javac'), str(package / 'NativeEchoProcessor.java')], check=True)
    manifest = json.loads((ROOT / 'docs/research/2026-10-27-natural-turn-taking/apm-input-manifest.json').read_text())
    cases = []
    for case in manifest['cases']:
        if not case['aec']:
            continue
        name = case['name']
        for kind in ['render', 'capture']:
            path = cache / 'controls-verified/inputs' / (name + '-' + kind + '.f32')
            assert sha(path) == case[kind + '_sha256']
            samples = pcm(np.fromfile(path, '<f4'))
            samples.tofile(output / (kind + '.pcm'))
            (samples.astype(np.float32) / np.float32(32768)).tofile(output / (kind + '.f32'))
        baseline = output / (name + '-baseline.f32')
        subprocess.run([str(cache / 'probe-apm-host-verified'), str(output / 'render.f32'), str(output / 'capture.f32'),
            str(baseline), '1', '80'], stdout=subprocess.DEVNULL, check=True, timeout=30)
        actual = output / (name + '-app.pcm')
        start = time.monotonic()
        subprocess.run([str(java / 'bin/java'), '-cp', str(output), 'com.crabot.voice.NativeEchoProcessor', str(library),
            str(output / 'render.pcm'), str(output / 'capture.pcm'), str(actual)], check=True, timeout=30)
        elapsed = time.monotonic() - start
        expected = pcm(np.fromfile(baseline, '<f4'))
        received = np.fromfile(actual, '<i2')
        assert np.array_equal(expected, received), name
        cases.append({'name': name, 'samples': len(received), 'frames': len(received) // 160,
                      'pcm_exactly_equal': True, 'app_pcm_sha256': sha(actual), 'host_jvm_process_seconds': elapsed})
    report = {'scope': 'Actual app APM C++ and JNI on host, fixed PCM16 public controls; not Android hardware or EOT acceptance',
              'product_sources': {path.name: sha(path) for path in [cpp / 'echo_processor.cpp', cpp / 'echo_processor.h', cpp / 'echo_jni.cpp']},
              'jni_error_checks_per_case': 3, 'cases': cases}
    (output / 'results.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))

if __name__ == '__main__':
    main()
