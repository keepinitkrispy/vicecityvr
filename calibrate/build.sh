#!/data/data/com.termux/files/usr/bin/bash
# Builds calibrate.apk on the phone in Termux (javac, d8, aapt2, zipalign, apksigner from Termux packages).
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_SDK:-$HOME/android-sdk}"
JAR="$(ls "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
[ -f "$JAR" ] || { echo "android.jar not found under $SDK/platforms"; exit 1; }
KS="$HOME/.config/psvr-control/debug.jks"
mkdir -p "$(dirname "$KS")"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias psvr \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=psvr-control" >/dev/null
fi
rm -rf build && mkdir -p build/classes build/dex
javac --release 11 -nowarn -cp "$JAR" -d build/classes $(find src -name '*.java')
d8 --release --min-api 26 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
aapt2 link -I "$JAR" --manifest AndroidManifest.xml -o build/unsigned.apk
(cd build/dex && zip -q -j ../unsigned.apk classes.dex)
zipalign -f -p 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out calibrate.apk build/aligned.apk
apksigner verify calibrate.apk
echo "built $(pwd)/calibrate.apk"
