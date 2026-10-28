"""Build pinned WebRTC APM for the app; no global tool installs or device access.

Requires meson 1.9.1, ninja 1.13.0 and the project's pinned Android NDK on PATH.
--cache may reuse the isolated research cache; all three source archives are verified.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    'webrtc-audio-processing-1.3.tar.gz': ('https://freedesktop.org/software/pulseaudio/webrtc-audio-processing/webrtc-audio-processing-1.3.tar.gz', '95552fc17faa0202133707bbb3727e8c2cf64d4266fe31bfdb2298d769c1db75'),
    'abseil-cpp-20230125.1.tar.gz': ('https://github.com/abseil/abseil-cpp/archive/refs/tags/20230125.1.tar.gz', '81311c17599b3712069ded20cca09a62ab0bf2a89dfa16993786c8782b7ed145'),
    'abseil-cpp_20230125.1-4_patch.zip': ('https://github.com/mesonbuild/wrapdb/releases/download/abseil-cpp_20230125.1-4/abseil-cpp_20230125.1-4_patch.zip', '112ee72052049d930396c2778fc1c6e184137905dd75d60a97dcfc386426610d'),
}

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def verify_tree(archive, parent):
    with tarfile.open(archive) as files:
        for member in files:
            if member.isfile():
                target = parent / member.name
                if not target.is_file() or hashlib.sha256(files.extractfile(member).read()).hexdigest() != digest(target):
                    raise RuntimeError('Extracted source differs from pinned archive: ' + member.name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, default=ROOT / '.apm-cache')
    args = parser.parse_args()
    cache = args.cache.resolve(); cache.mkdir(parents=True, exist_ok=True)
    for name, (url, expected) in SOURCES.items():
        path = cache / name
        if not path.exists():
            # Old research cache keeps Abseil archives under the extracted package.
            old = cache / 'webrtc-audio-processing-1.3/subprojects/packagecache' / name
            if old.exists():
                shutil.copyfile(old, path)
            else:
                temporary = path.with_suffix('.download')
                with urllib.request.urlopen(url, timeout=60) as response, temporary.open('wb') as output:
                    shutil.copyfileobj(response, output)
                if digest(temporary) != expected:
                    temporary.unlink(); raise RuntimeError('Source checksum mismatch: ' + name)
                temporary.replace(path)
        if digest(path) != expected:
            raise RuntimeError('Source checksum mismatch: ' + name)
    source = cache / 'webrtc-audio-processing-1.3'
    if not source.exists():
        with tarfile.open(cache / 'webrtc-audio-processing-1.3.tar.gz') as archive:
            archive.extractall(cache, filter='data')
    packages = source / 'subprojects/packagecache'; packages.mkdir(exist_ok=True)
    for name in list(SOURCES)[1:]:
        if (cache / name).resolve() != (packages / name).resolve():
            shutil.copyfile(cache / name, packages / name)
    ndk = os.environ.get('ANDROID_NDK_HOME')
    if not ndk:
        ndk = str(Path(os.environ['ANDROID_HOME']) / 'ndk/28.2.13676358')
    host = {'Darwin': 'darwin-x86_64', 'Linux': 'linux-x86_64'}[platform.system()]
    tools = Path(ndk) / 'toolchains/llvm/prebuilt' / host / 'bin'
    binaries = {key: tools / name for key, name in {
        'c': 'aarch64-linux-android29-clang', 'cpp': 'aarch64-linux-android29-clang++', 'ar': 'llvm-ar', 'strip': 'llvm-strip'}.items()}
    if not all(path.exists() for path in binaries.values()):
        raise RuntimeError('Missing pinned Android NDK tools')
    if subprocess.check_output(['meson', '--version'], text=True).strip() != '1.9.1':
        raise RuntimeError('Expected meson 1.9.1')
    if subprocess.check_output(['ninja', '--version'], text=True).strip() != '1.13.0.git.kitware.jobserver-pipe-1':
        raise RuntimeError('Expected ninja 1.13.0')
    cross = cache / 'app-android.ini'
    cross.write_text('[binaries]\n' + ''.join(f'{key} = {str(path)!r}\n' for key, path in binaries.items()) +
        "[host_machine]\nsystem = 'android'\ncpu_family = 'aarch64'\ncpu = 'aarch64'\nendian = 'little'\n[properties]\nneeds_exe_wrapper = true\n")
    build = cache / 'build-android-app'
    if not build.exists():
        subprocess.run(['meson', 'setup', str(build), str(source), '--cross-file', str(cross), '--wrap-mode=nodownload',
            '--buildtype=release', '--default-library=static', '-Dgnustl=disabled'], check=True)
    verify_tree(cache / 'webrtc-audio-processing-1.3.tar.gz', cache)
    verify_tree(cache / 'abseil-cpp-20230125.1.tar.gz', source / 'subprojects')
    with zipfile.ZipFile(cache / 'abseil-cpp_20230125.1-4_patch.zip') as patch:
        for name in patch.namelist():
            if not name.endswith('/'):
                if (source / 'subprojects' / name).read_bytes() != patch.read(name):
                    raise RuntimeError('Extracted Meson patch differs: ' + name)
    subprocess.run(['meson', 'compile', '-C', str(build), '-j', '4'], check=True)
    output = ROOT / 'native-apm'; output.mkdir(exist_ok=True)
    libraries = sorted(build.rglob('*.a'))
    if len(libraries) != 24:
        raise RuntimeError('Unexpected APM static library set')
    headers = output / 'include'; headers.mkdir(exist_ok=True)
    for tree in [source / 'webrtc', source / 'subprojects/abseil-cpp-20230125.1']:
        for header in tree.rglob('*.h'):
            target = headers / header.relative_to(tree)
            target.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(header, target)
    libdir = output / 'lib'; libdir.mkdir(exist_ok=True)
    hashes = {}
    for library in libraries:
        target = libdir / library.name
        if target.name in hashes:
            raise RuntimeError('Duplicate library name')
        # Meson uses thin archives whose members live in its build tree. Materialize
        # regular archives before packaging; copying the thin index breaks JNI linking.
        if library.read_bytes()[:8] == b'!<arch>\n':
            shutil.copyfile(library, target); hashes[target.name] = digest(target)
            continue
        members = subprocess.check_output([str(binaries['ar']), 't', str(library)], text=True).splitlines()
        objects = [(library.parent / member).resolve() for member in members]
        if not objects or not all(obj.is_file() for obj in objects):
            raise RuntimeError('Missing static archive object')
        temporary = target.with_suffix('.tmp.a')
        if temporary.exists():
            temporary.unlink()
        subprocess.run([str(binaries['ar']), 'qc', str(temporary), *map(str, objects)], check=True)
        subprocess.run([str(binaries['ar']), 's', str(temporary)], check=True)
        temporary.replace(target); hashes[target.name] = digest(target)
    (output / 'manifest.json').write_text(json.dumps({'sources': {k: v[1] for k, v in SOURCES.items()}, 'libraries': hashes}, indent=2) + '\n')
    print('Prepared', len(libraries), 'pinned APM archives in', output)

if __name__ == '__main__':
    main()
