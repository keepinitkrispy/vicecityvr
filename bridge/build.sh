#!/usr/bin/env bash
# Builds vcvr-bridge.apk (desktop Linux or Termux). Needs: android.jar + build-tools, the ONNX Runtime Android AAR,
# and the Depth Anything V2 Small model. Downloads the last two on first run.
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_SDK:-$HOME/sdk}"
JAR="$(ls "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
D8="${D8:-$BT/d8}"; AAPT2="${AAPT2:-$BT/aapt2}"; ZIPALIGN="${ZIPALIGN:-$BT/zipalign}"; APKSIGNER="${APKSIGNER:-$BT/apksigner}"
mkdir -p deps assets
[ -f deps/ort.aar ] || curl -sSL -o deps/ort.aar https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.20.0/onnxruntime-android-1.20.0.aar
for a in api provider shared aidl; do
  [ -f deps/shizuku-$a.aar ] || curl -sSL -o deps/shizuku-$a.aar https://repo1.maven.org/maven2/dev/rikka/shizuku/$a/13.1.5/$a-13.1.5.aar
done
[ -f assets/depth.onnx ] || curl -sSL -o assets/depth.onnx https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model_quantized.onnx
rm -rf build && mkdir -p build/classes build/dex build/ort build/apk/lib/arm64-v8a
(cd build/ort && python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall('.')" ../../deps/ort.aar)
LIBS=build/ort/classes.jar
for a in api provider shared aidl; do
  mkdir -p build/shz-$a && (cd build/shz-$a && python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall('.')" ../../deps/shizuku-$a.aar)
  LIBS="$LIBS:build/shz-$a/classes.jar"
done
javac --release 11 -nowarn -cp "$JAR:$LIBS" -d build/classes $(find src -name '*.java')
"$D8" --release --min-api 30 --lib "$JAR" --output build/dex $(find build/classes -name '*.class') ${LIBS//:/ }
"$AAPT2" compile --dir res -o build/res.zip
"$AAPT2" link -I "$JAR" --manifest AndroidManifest.xml build/res.zip -A assets -0 onnx -o build/unsigned.apk
cp build/ort/jni/arm64-v8a/*.so build/apk/lib/arm64-v8a/
cp build/dex/classes.dex build/apk/
python3 - <<'PY'
import zipfile, os
z = zipfile.ZipFile('build/unsigned.apk', 'a')
z.write('build/apk/classes.dex', 'classes.dex', zipfile.ZIP_DEFLATED)
for f in os.listdir('build/apk/lib/arm64-v8a'):
    z.write('build/apk/lib/arm64-v8a/' + f, 'lib/arm64-v8a/' + f, zipfile.ZIP_DEFLATED)
z.close()
PY
"$ZIPALIGN" -f -p 4 build/unsigned.apk build/aligned.apk
"$APKSIGNER" sign --ks ../stream/vcvr-debug.keystore --ks-pass pass:android --key-pass pass:android --out vcvr-bridge.apk build/aligned.apk
"$APKSIGNER" verify vcvr-bridge.apk
echo "built $(pwd)/vcvr-bridge.apk ($(du -h vcvr-bridge.apk | cut -f1))"
