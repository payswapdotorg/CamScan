#!/bin/sh
# Probe APK builder — runs INSIDE the E2B sandbox (pushed by the acceptance
# gate via provider.transfer(), exercising the push path).
#
# Builds a minimal, deterministic, observable test APK with the already
# installed SDK only (aapt + apksigner; no Gradle, no network beyond the SDK
# that bootstrap installed):
#   - package org.camscan.lab.probeapp, versionName 1.0
#   - android:hasCode="false" — the launchable activity is android.app.Activity
#     itself, so launching is observable (window + process + logcat) with
#     zero app code.
# The lab's REAL application comes from CAMSCAN-001 (Worker 1, Gradle).
set -e
SDK=/opt/android-sdk
BT=$SDK/build-tools/33.0.2
PLATFORM=$SDK/platforms/android-30/android.jar
WORK=/root/probeapk
MANIFEST=$WORK/AndroidManifest.xml

rm -rf "$WORK" && mkdir -p "$WORK"
cat > "$MANIFEST" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="org.camscan.lab.probeapp"
    android:versionCode="1"
    android:versionName="1.0">
  <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="30"/>
  <application android:label="CamScan Probe" android:hasCode="false">
    <activity android:name="android.app.Activity" android:label="CamScan Probe"
              android:exported="true">
      <intent-filter>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LAUNCHER"/>
      </intent-filter>
    </activity>
  </application>
</manifest>
XML

# compile resources + package (hasCode=false: no dex needed)
"$BT/aapt" package -f -M "$MANIFEST" -F "$WORK/base.apk" -I "$PLATFORM"

# debug keystore (deterministic enough for a probe; regenerated per sandbox)
KS=$WORK/debug.keystore
rm -f "$KS"
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
"$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -storepass android \
  -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 \
  -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1

# sign (v1 scheme for hasCode=false APKs on API 30)
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$WORK/probe.apk" "$WORK/base.apk"

ls -la "$WORK/probe.apk"
sha256sum "$WORK/probe.apk"
echo PROBE_APK_DONE
