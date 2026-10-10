#!/usr/bin/env python3
"""Download official, hash-pinned inference assets; never copy experiment recordings/vectors."""
import hashlib
import json
import shutil
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / ".model-cache"
ASSETS = ROOT / "app/src/main/assets/models"
LIBS = ROOT / "app/src/main/jniLibs/arm64-v8a"
BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/"
SEGMENTATION = "sherpa-onnx-pyannote-segmentation-3-0"
NATIVE = "sherpa-onnx-v1.13.8-android-aarch64-termux-shared"
KWS = "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile"

def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()

def download(name, url, sha):
    path = CACHE / name
    if not path.exists() or digest(path) != sha:
        temp = path.with_suffix(".part")
        with urllib.request.urlopen(url, timeout=60) as source, temp.open("wb") as target:
            shutil.copyfileobj(source, target)
        if digest(temp) != sha:
            temp.unlink()
            raise ValueError(f"SHA-256 mismatch: {name}")
        temp.replace(path)
    return path

def copy_member(archive, name, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    member = archive.getmember(name)
    if not member.isfile():
        raise ValueError(f"Invalid archive member: {name}")
    with archive.extractfile(member) as source, target.open("wb") as out:
        shutil.copyfileobj(source, out)

def main():
    for folder in [CACHE, ASSETS, LIBS]:
        folder.mkdir(parents=True, exist_ok=True)
    native = download("native.tar.bz2", BASE + "v1.13.8/" + NATIVE + ".tar.bz2", "ad7bac42adc6295293fe6d36475936afb227cdc757cf293c02a706f6aca4e0a2")
    with tarfile.open(native) as archive:
        for member in archive.getmembers():
            prefix = NATIVE + "/include/"
            if member.isfile() and member.name.startswith(prefix):
                relative = Path(member.name[len(NATIVE) + 1:])
                if ".." in relative.parts:
                    raise ValueError("Invalid archive path")
                copy_member(archive, member.name, ROOT / "native-deps" / relative)
        for name in ["libsherpa-onnx-c-api.so", "libsherpa-onnx-cxx-api.so", "libonnxruntime.so"]:
            copy_member(archive, NATIVE + "/lib/" + name, LIBS / name)
        # AGP/CMake packages libc++_shared from the pinned NDK; it is not in this archive.
    segmentation = download("segmentation.tar.bz2", BASE + "speaker-segmentation-models/" + SEGMENTATION + ".tar.bz2", "24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488")
    with tarfile.open(segmentation) as archive:
        copy_member(archive, SEGMENTATION + "/model.onnx", ASSETS / "segmentation.onnx")
    camp = download("campplus.onnx", BASE + "speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx", "f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11")
    shutil.copyfile(camp, ASSETS / "campplus.onnx")
    vad = download("silero.onnx", BASE + "asr-models/silero_vad.onnx", "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6")
    shutil.copyfile(vad, ASSETS / "silero.onnx")
    kws = download("kws.tar.bz2", BASE + "kws-models/" + KWS + ".tar.bz2", "b812a043aef628a6915f89cb9a94e55f8e87e89ff904b516f822d7e0a3e6de2b")
    with tarfile.open(kws) as archive:
        for name, suffix in [("encoder", ".int8.onnx"), ("decoder", ".onnx"), ("joiner", ".int8.onnx")]:
            copy_member(archive, KWS + "/" + name + "-epoch-12-avg-2-chunk-16-left-64" + suffix, ASSETS / (name + ".onnx"))
        copy_member(archive, KWS + "/tokens.txt", ASSETS / "tokens.txt")
    manifest = json.loads((ASSETS.parent / "model-files.json").read_text())
    for name, expected in manifest.items():
        if digest(ASSETS / name) != expected:
            raise ValueError(f"Model file differs from frozen manifest: {name}")
    print("Official models and native assets verified; no experiment data copied.")

if __name__ == "__main__":
    main()
