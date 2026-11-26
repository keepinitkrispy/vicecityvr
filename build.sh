#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/sdk}}"
JAR="$SDK/platforms/android-34/android.jar"
BT="$SDK/build-tools/34.0.0"
mkdir -p build/classes build/dex
javac --release 11 -cp "$JAR" -d build/classes $(find src -name '*.java')
"$BT/d8" --min-api 34 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -o build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
KEY="${VCVR_KEYSTORE:-$HOME/.android/debug.keystore}"
mkdir -p "$(dirname "$KEY")"
if [ ! -f "$KEY" ]; then keytool -genkeypair -keystore "$KEY" -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug,O=Android,C=US'; fi
"$BT/apksigner" sign --ks "$KEY" --ks-pass pass:android --key-pass pass:android --out build/vicecityvr-clean.apk build/aligned.apk
"$BT/apksigner" verify build/vicecityvr-clean.apk
