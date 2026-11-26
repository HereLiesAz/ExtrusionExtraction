#!/usr/bin/env bash
# Build the standalone Linux worker from the same shared JVM and native sources.
# No model weights, credentials or references are included.
set -euo pipefail
cd "$(dirname "$0")/.."
[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] || { echo "Linux x86_64 build host required" >&2; exit 1; }
output="${1:-$PWD/headless/build/distributions/colab-worker-linux-x86_64.tar.gz}"
output="$(realpath -m "$output")"
stage="$(mktemp -d)"
trap 'rm -rf -- "$stage"' EXIT
worker="$stage/worker"
mkdir -p "$worker/lib" "$worker/native" "$(dirname "$output")"
python3 scripts/build_skintokens.py --portable-linux --work-dir build/colab-skintokens
./gradlew -p headless test installDist stageColabCuda --no-daemon
python3 -m unittest discover -s scripts -p test_colab_notebook.py
cp -a headless/build/install/ExtrusionExtraction-headless/lib/. "$worker/lib/"
cp -a build/colab-skintokens/host-install/common/. "$worker/native/"
mkdir "$worker/gpu"
cp headless/build/colab-cuda/onnxruntime_gpu-1.22.0.jar "$worker/gpu/"
# Vulkan is a separate native overlay: normal CPU workers must not require libvulkan.
# Explicitly enabled only on a builder with compatible Vulkan headers and glslc.
case "${EE_COLAB_BUILD_VULKAN:-0}" in
    0) ;;
    1)
        python3 scripts/build_skintokens.py --portable-linux --vulkan --work-dir build/colab-skintokens-vulkan
        mkdir "$worker/vulkan"
        cp -a build/colab-skintokens-vulkan/host-install/common/. "$worker/vulkan/"
        ;;
    *) echo "EE_COLAB_BUILD_VULKAN must be 0 or 1" >&2; exit 1 ;;
esac
# Include the compiler runtime to avoid requiring a particular system libstdc++ version.
for library in libstdc++.so.6 libgcc_s.so.1; do
    source="$(${CXX:-c++} -print-file-name="$library")"
    test -f "$source"
    cp -L "$source" "$worker/native/$library"
done
# The bundled runtime supplies Java APIs required by ONNX, PNG decoding and HTTPS.
"${JAVA_HOME:?JDK 17 required}/bin/jlink" --add-modules java.se,jdk.unsupported,jdk.crypto.ec,jdk.zipfs --strip-debug --no-header-files --no-man-pages --output "$worker/runtime"
cp docs/MODEL_PROVENANCE.md "$worker/"
git rev-parse HEAD > "$worker/source-revision.txt"
cat > "$worker/run" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] || { echo "Worker requires Linux x86_64" >&2; exit 1; }
native="$root/native"
case "${EE_COLAB_SKINTOKENS:-cpu}" in
    cpu) ;;
    vulkan) native="$root/vulkan"; test -f "$native/libee_skintokens.so" || { echo "Vulkan overlay not packaged" >&2; exit 1; } ;;
    *) echo "Invalid SkinTokens device" >&2; exit 1 ;;
esac
classpath=""
case "${EE_COLAB_ONNX:-cpu}" in
    cpu) classpath="$root/lib/*" ;;
    cuda)
        cuda="$root/gpu/onnxruntime_gpu-1.22.0.jar"
        test -f "$cuda" || { echo "CUDA overlay not packaged" >&2; exit 1; }
        classpath="$cuda"
        for jar in "$root"/lib/*.jar; do
            case "$(basename "$jar")" in onnxruntime-*.jar) ;; *) classpath="$classpath:$jar" ;; esac
        done
        ;;
    *) echo "Invalid ONNX device" >&2; exit 1 ;;
esac
export LD_LIBRARY_PATH="$native:$root/native${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
exec "$root/runtime/bin/java" -Djava.awt.headless=true -Dcompose.application.resources.dir="$native" -cp "$classpath" com.hereliesaz.extrusionextraction.desktop.HeadlessKt "$@"
SH
chmod +x "$worker/run"
EE_COLAB_ONNX=cpu EE_COLAB_SKINTOKENS=cpu "$worker/run" --check
tar -C "$stage" -czf "$stage/worker.tar.gz" worker
# Keep publication within the notebook's explicit compressed/expanded budgets.
python3 - "$stage/worker.tar.gz" <<'PY'
import pathlib, sys, tarfile
archive = pathlib.Path(sys.argv[1])
assert 0 < archive.stat().st_size <= 1024**3, "Worker archive exceeds notebook download budget."
with tarfile.open(archive, "r:gz") as package:
    assert sum(member.size for member in package.getmembers()) <= 2 * 1024**3, "Worker exceeds notebook extraction budget."
PY
# Smoke test the exact archived payload, not just the build tree.
mkdir "$stage/extracted"
tar -C "$stage/extracted" -xzf "$stage/worker.tar.gz"
EE_COLAB_ONNX=cpu EE_COLAB_SKINTOKENS=cpu "$stage/extracted/worker/run" --check
mv "$stage/worker.tar.gz" "$output"
printf 'Packaged %s\n' "$output"
