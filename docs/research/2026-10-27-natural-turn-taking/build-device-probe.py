"""Build a separate offline audio probe from exact product sources/libraries and public fixtures. Never installs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SOURCES = ['AudioClock.kt', 'AudioIo.kt', 'DuplexAudio.kt', 'DuplexAudioSession.kt', 'EchoProcessor.kt',
           'PlaybackDevice.kt', 'PlaybackProgress.kt', 'RenderTimeline.kt', 'SpeechActivity.kt', 'Wire.kt']


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    # Every destination is disposable, but do not overwrite an unrelated project.
    marker = output / 'probe-source-manifest.json'
    assert not output.exists() or marker.exists(), 'Use a new directory or a previous probe build'
    source = output / 'src/main/java/com/crabot/voice'
    source.mkdir(parents=True, exist_ok=True)
    records = {}
    for name in SOURCES:
        original = ROOT / 'app/src/main/java/com/crabot/voice' / name
        shutil.copy2(original, source / name)
        records['product/' + name] = sha(original)
    templates = HERE / 'device-probe'
    shutil.copy2(templates / 'src/AudioProbeActivity.kt', source / 'AudioProbeActivity.kt')
    shutil.copy2(templates / 'AndroidManifest.xml', output / 'src/main/AndroidManifest.xml')
    for name in ['settings.gradle.kts', 'build.gradle.kts']:
        shutil.copy2(templates / name, output / name)
    (output / 'gradle.properties').write_text('org.gradle.jvmargs=-Xmx2g\nandroid.useAndroidX=true\nkotlin.code.style=official\n')
    assets = output / 'src/main/assets'
    assets.mkdir(exist_ok=True)
    wave = ROOT / 'app/src/androidTest/assets/voice-service-test.wav'
    assert sha(wave) == '1d83d9eee52f4f876bbbfa8aa8bd7d6ca385bbda30d183ba896b8e54b70a83a5', 'Public fixture changed'
    subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-y', '-i', str(wave), '-ar', '24000', '-ac', '1',
                    '-c:a', 'pcm_s16le', str(assets / 'reference.wav')], check=True)
    model = ROOT / 'app/src/main/assets/models/silero.onnx'
    assert sha(model) == '9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6'
    shutil.copy2(model, assets / 'silero.onnx')
    apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    with zipfile.ZipFile(apk) as archive:
        libraries = [name for name in archive.namelist() if name.startswith('lib/arm64-v8a/') and name.endswith('.so')]
        assert any(name.endswith('/libcrabot_voice.so') for name in libraries)
        for name in libraries:
            data = archive.read(name)
            target = output / 'src/main/jniLibs/arm64-v8a' / Path(name).name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            records['native/' + target.name] = hashlib.sha256(data).hexdigest()
        for name in archive.namelist():
            if name.startswith('assets/licenses/') and not name.endswith('/'):
                target = assets / 'licenses' / Path(name).name
                target.parent.mkdir(parents=True, exist_ok=True)
                data = archive.read(name)
                target.write_bytes(data)
                records['license/' + target.name] = hashlib.sha256(data).hexdigest()
    shutil.copy2(ROOT / 'LICENSE', assets / 'licenses/crabot-LICENSE')
    shutil.copy2(ROOT / 'app/src/androidTest/assets/README.md', assets / 'licenses/public-reference.md')
    records['public_reference'] = sha(wave)
    records['render_reference'] = sha(assets / 'reference.wav')
    records['silero'] = sha(model)
    records['product_apk'] = sha(apk)
    records['probe_activity'] = sha(templates / 'src/AudioProbeActivity.kt')
    marker.write_text(json.dumps(records, indent=2) + '\n')
    subprocess.run([str(ROOT / 'gradlew'), '-p', str(output), '--offline', '--no-daemon', '--max-workers=2', 'assembleDebug'],
                   check=True, env=os.environ.copy())
    result = output / 'build/outputs/apk/debug/CrabotAudioProbe-debug.apk'
    assert result.exists()
    print(json.dumps({'application_id': 'com.crabot.voice.audioprobe', 'apk': str(result), 'sha256': sha(result),
                      'installed': False, 'source_manifest_sha256': sha(marker)}))


if __name__ == '__main__':
    main()
