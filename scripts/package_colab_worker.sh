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
./gradlew -p headless test installDist --no-daemon
python3 -m unittest discover -s scripts -p test_colab_notebook.py
cp -a headless/build/install/ExtrusionExtraction-headless/lib/. "$worker/lib/"
cp -a build/colab-skintokens/host-install/common/. "$worker/native/"
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
export LD_LIBRARY_PATH="$root/native${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
exec "$root/runtime/bin/java" -Djava.awt.headless=true -Dcompose.application.resources.dir="$root/native" -cp "$root/lib/*" com.hereliesaz.extrusionextraction.desktop.HeadlessKt "$@"
SH
chmod +x "$worker/run"
"$worker/run" --check
tar -C "$stage" -czf "$stage/worker.tar.gz" worker
# Smoke test the exact archived payload, not just the build tree.
mkdir "$stage/extracted"
tar -C "$stage/extracted" -xzf "$stage/worker.tar.gz"
"$stage/extracted/worker/run" --check
mv "$stage/worker.tar.gz" "$output"
printf 'Packaged %s\n' "$output"
