"""Build the pinned, already-extracted APM source into isolated file probes."""
import argparse
import hashlib
import json
import platform
import subprocess
import time
from pathlib import Path


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(args):
    assert platform.system() == "Darwin", "This research builder is verified on macOS only"
    args.root.mkdir(parents=True, exist_ok=True)
    source = args.source.resolve()
    archive = source.parent / "webrtc-audio-processing-1.3.tar.gz"
    assert sha(archive) == "95552fc17faa0202133707bbb3727e8c2cf64d4266fe31bfdb2298d769c1db75"
    assert (source / "meson.build").exists()
    subprojects = source / "subprojects/packagecache"
    for name, expected in {
        "abseil-cpp-20230125.1.tar.gz": "81311c17599b3712069ded20cca09a62ab0bf2a89dfa16993786c8782b7ed145",
        "abseil-cpp_20230125.1-4_patch.zip": "112ee72052049d930396c2778fc1c6e184137905dd75d60a97dcfc386426610d",
    }.items():
        assert sha(subprojects / name) == expected, name
    cpp = Path(__file__).with_name("probe-apm.cc").resolve()
    report = {"scope": "isolated build/link only, no Android execution", "apm_version": "1.3",
              "source_archive_sha256": sha(archive), "probe_source_sha256": sha(cpp),
              "platform": platform.platform(), "machine": platform.machine(), "jobs": 4, "builds": []}
    for target in ["host", "android"]:
        build = args.root / ("build-" + target + "-verified")
        cold = not build.exists()
        setup = ["meson", "setup", str(build), str(source), "--wrap-mode=nodownload",
                 "--buildtype=release", "--default-library=static", "-Dgnustl=disabled"]
        if target == "android":
            tools = args.ndk.resolve() / "toolchains/llvm/prebuilt/darwin-x86_64/bin"
            cross = args.root / "verified-android.ini"
            binaries = {"c": tools / "aarch64-linux-android29-clang", "cpp": tools / "aarch64-linux-android29-clang++",
                        "ar": tools / "llvm-ar", "strip": tools / "llvm-strip"}
            assert all(x.exists() for x in binaries.values())
            cross.write_text("[binaries]\n" + "".join(f"{key} = {str(value)!r}\n" for key, value in binaries.items()) +
                "[host_machine]\nsystem = 'android'\ncpu_family = 'aarch64'\ncpu = 'aarch64'\nendian = 'little'\n" +
                "[properties]\nneeds_exe_wrapper = true\n")
            setup += ["--cross-file", str(cross)]
        started = time.monotonic()
        with (args.root / (target + "-verified-build.log")).open("w") as log:
            subprocess.run(setup, stdout=log, stderr=subprocess.STDOUT, check=True)
            subprocess.run(["meson", "compile", "-C", str(build), "-j", "4"], stdout=log, stderr=subprocess.STDOUT, check=True)
        build_seconds = time.monotonic() - started
        libs = sorted(build.rglob("*.a"))
        main = build / "webrtc/modules/audio_processing/libwebrtc-audio-processing-1.a"
        libs = [main] + [x for x in libs if x != main]
        binary = args.root / ("probe-apm-" + target + "-verified")
        command = ["c++" if target == "host" else str(binaries["cpp"]), "-std=c++17", "-O2", "-DNDEBUG", "-DWEBRTC_APM_DEBUG_DUMP=0", "-DWEBRTC_POSIX",
                   "-I" + str(source / "webrtc"), "-I" + str(source / "subprojects/abseil-cpp-20230125.1"), str(cpp)]
        command += ["-DWEBRTC_MAC"] if target == "host" else ["-DWEBRTC_ANDROID", "-DWEBRTC_LINUX", "-Wl,--start-group"]
        command += [str(x) for x in libs]
        command += ["-framework", "Foundation"] if target == "host" else ["-Wl,--end-group", "-llog"]
        command += ["-o", str(binary)]
        started = time.monotonic()
        with (args.root / (target + "-verified-link.log")).open("w") as log:
            subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
        row = {"target": target, "cold_build_directory": cold, "build_seconds": build_seconds,
               "link_seconds": time.monotonic() - started, "archives": len(libs),
               "archive_bytes": sum(x.stat().st_size for x in libs), "probe_bytes": binary.stat().st_size,
               "probe_sha256": sha(binary)}
        report["builds"].append(row)
        print(json.dumps(row), flush=True)
    (args.root / "build-results.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--ndk", type=Path, required=True)
    run(parser.parse_args())
