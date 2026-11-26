#!/usr/bin/env python3
"""Prepare pinned native rigging dependencies and build a host-native package.

Use --prepare-only for Android; the printed Gradle arguments select the NDK
build. No model weights are downloaded or executed by this build tool.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile

RUNTIME = "43aac7b0fe45f5cd802365e6964c13bfc5e5fb52"
GGML = "8c63e70982c95ceb862e3a1073a2c1beef75d60a"
JSON = "9cca280a4d0ccf0c08f47a99aa71d1b0e52f8d03"


def run(*args, capture=False):
    """Run an argument-vector command, failing on nonzero status."""
    return subprocess.run(args, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def verify_checkout(path, revision):
    """Reject wrong or locally modified dependency trees; never reset them."""
    actual = run("git", "-C", str(path), "rev-parse", "HEAD", capture=True).strip()
    if actual != revision:
        raise RuntimeError(f"Wrong revision at {path}: {actual}, expected {revision}")
    status = run("git", "-C", str(path), "status", "--porcelain",
                 "--untracked-files=all", "--ignore-submodules=none", capture=True)
    if status.strip():
        raise RuntimeError(f"Dependency checkout is dirty: {path}\n{status}")


def checkout(path, url, revision, submodule=None):
    """Fetch an exact commit into staging; publish only a verified checkout."""
    if path.exists():
        verify_checkout(path, revision)
        if submodule:
            verify_checkout(path / submodule[0], submodule[1])
        return
    with tempfile.TemporaryDirectory(prefix="fetch-", dir=path.parent) as tmp:
        source = Path(tmp) / "source"
        run("git", "init", str(source))
        run("git", "-C", str(source), "remote", "add", "origin", url)
        run("git", "-C", str(source), "fetch", "--depth=1", "origin", revision)
        run("git", "-C", str(source), "checkout", "--detach", "FETCH_HEAD")
        if submodule:
            run("git", "-C", str(source), "submodule", "update", "--init", "--depth=1", "--", submodule[0])
            verify_checkout(source / submodule[0], submodule[1])
        verify_checkout(source, revision)
        source.rename(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-dir", type=Path, default=Path("build/skintokens"))
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--portable-linux", action="store_true", help="Disable host-specific CPU instruction selection for the Colab worker.")
    args = parser.parse_args()
    root = args.work_dir.resolve()
    root.mkdir(parents=True, exist_ok=True)
    runtime, json = root / "runtime", root / "json"
    checkout(runtime, "https://github.com/localai-org/skin-tokens.cpp.git", RUNTIME, ("ggml", GGML))
    checkout(json, "https://github.com/nlohmann/json.git", JSON)
    json_prefix = root / "json-install"
    run("cmake", "-S", str(json), "-B", str(root / "json-build"),
        "-DJSON_BuildTests=OFF", "-DJSON_Install=ON", f"-DCMAKE_INSTALL_PREFIX={json_prefix}")
    run("cmake", "--install", str(root / "json-build"), "--config", "Release")
    configs = list(json_prefix.rglob("nlohmann_jsonConfig.cmake"))
    if len(configs) != 1:
        raise RuntimeError("Expected exactly one installed nlohmann-json CMake config")
    config = configs[0].parent
    print(f'Android Gradle arguments: "-PskinTokensSource={runtime}" "-PskinTokensJsonDir={config}"', flush=True)
    if args.prepare_only:
        return
    source = Path(__file__).resolve().parents[1] / "native" / "skintokens"
    build, stage = root / "host-build", root / "host-install"
    portable = ["-DGGML_NATIVE=OFF", "-DGGML_AVX=OFF", "-DGGML_AVX2=OFF", "-DGGML_FMA=OFF", "-DGGML_F16C=OFF", "-DGGML_BMI2=OFF", "-DGGML_AVX512=OFF"] if args.portable_linux else []
    run("cmake", "-S", str(source), "-B", str(build), "-DCMAKE_BUILD_TYPE=Release",
        f"-DSKINTOKENS_SOURCE_DIR={runtime}", f"-Dnlohmann_json_DIR={config}", *portable)
    run("cmake", "--build", str(build), "--config", "Release", "--target", "ee_skintokens", "--parallel", "2")
    run("cmake", "--install", str(build), "--config", "Release", "--prefix", str(stage), "--component", "ee_runtime")
    print(f'Desktop Gradle argument: "-PskinTokensNativeDir={stage}"', flush=True)


if __name__ == "__main__":
    main()
