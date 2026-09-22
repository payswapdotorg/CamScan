#!/usr/bin/env python3
"""CAMSCAN-004 ABI probe 17 — adb install-multiple (host paths) as PRIMARY.

PROBE-16 GROUND TRUTH (verdict-marked, 10:39 UTC):
  - install-create succeeds; every install-write throws a Binder exception
    (execTransact frames); commit verdict = "INSTALL_FAILED_INVALID_APK:
    Session N. No packages staged in /data/app/vmdlN.tmp".
  => pm install-write streams the 162MB base through BINDER transactions —
     under TCG slowness those transactions die. NOTHING was ever staged.
     Every earlier "Success" (probes 9/15) was the install-create string
     false-positive; no probe has ever actually installed CamScanner.
  - probe 16's install-multiple fallback failed ONLY because it passed
    DEVICE paths to a HOST-side command (adb stats them on the host).
  - system_server SURVIVED the entire run (verify dexopt filter holding).

PROBE 17 DESIGN:
  PRIMARY: `adb install-multiple -r <HOST paths>` — adb streams APKs over
  its own wire protocol (not binder), one small commit call at the end.
  Retry ×4 with flap backoff. Then registry check, resolve poll, dynamic
  launcher resolution, patient launch, full forensics — as probe 16.
"""
from __future__ import annotations

import json
import os
import re
import sys
import time
from pathlib import Path

REPO = Path("/home/z/lead-staging/CamScan")
sys.path.insert(0, str(REPO))

from lab.providers.types import EnvironmentSpec  # noqa: E402
from lab.providers.e2b import E2BProvider, E2BProviderConfig  # noqa: E402
from lab.providers.e2b.bootstrap import ADB  # noqa: E402

OUT = Path("/home/z/lead-staging/camscan004_abi_probe")
XAPK_KEY = "reference/camscanner/camscanner-7.25.5.xapk"
RESULT_PATH = OUT / "abi_probe17_result.json"


def log(m):
    print(f"[{time.strftime('%H:%M:%S')}] {m}", flush=True)


def presigned_get() -> str:
    import boto3
    from botocore.client import Config
    s3 = boto3.client("s3", endpoint_url=os.environ["R2_ENDPOINT"],
                      aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
                      aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"],
                      config=Config(signature_version="s3v4"))
    return s3.generate_presigned_url(
        "get_object", Params={"Bucket": os.environ["R2_BUCKET"], "Key": XAPK_KEY},
        ExpiresIn=5400)


def sh(provider, env_id, cmd, timeout=240):
    return provider.execute(env_id, cmd, timeout=timeout)


def main() -> int:
    result = {"probe": "camscan004-abi-17-install-multiple-hostpaths",
              "started_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
    url = presigned_get()
    provider = E2BProvider(E2BProviderConfig())
    env_id = None
    try:
        env = provider.provision(EnvironmentSpec(
            purpose="refabi17", memory_mb=4096,
            system_image="system-images;android-30;google_apis;x86_64",
            extra_sdk_packages=("system-images;android-30;google_apis;x86_64",)))
        env_id = env.env_id
        log(f"provisioned {env_id}")
        provider.start(env_id)
        log("boot_completed")
        abi = sh(provider, env_id,
                 f"{ADB} shell getprop ro.product.cpu.abilist; "
                 f"{ADB} shell getprop ro.dalvik.vm.native.bridge", timeout=180)
        result["device_abilist"] = abi.stdout.strip()[:200]
        for i in range(20):
            pr = sh(provider, env_id, f"{ADB} shell pm list packages 2>&1 | head -1", timeout=180)
            if pr.stdout.strip().startswith("package:"):
                break
            time.sleep(30)
        time.sleep(60)
        log("package service ready + settled")

        # root ONCE for setprop, then UNROOT (proven probe 15/16)
        r = sh(provider, env_id, f"""
{ADB} root 2>&1 | head -1
sleep 5
{ADB} wait-for-device
{ADB} shell setprop pm.dexopt.install verify && echo SETPROP_OK
{ADB} shell getprop pm.dexopt.install
{ADB} unroot 2>&1 | head -1
sleep 5
{ADB} wait-for-device
{ADB} shell id | head -1
""", timeout=300)
        result["root_dexopt"] = r.stdout.strip()[:400]

        # fetch + unzip XAPK on the sandbox HOST (host paths for adb)
        r = sh(provider, env_id, f"""
set -e
cd /root
curl -fsSL -o camscanner.xapk '{url}' 2>/tmp/curl.err || {{ tail -3 /tmp/curl.err; exit 10; }}
mkdir -p xapk && cd xapk && unzip -o -q ../camscanner.xapk
ls -la *.apk
""", timeout=900)
        if r.exit_code != 0:
            raise RuntimeError(f"xapk fetch failed: {r.stdout[-300:]}")
        result["host_apks"] = r.stdout.strip()[-400:]
        log("XAPK fetched + unzipped (host paths ready)")

        # --- P17 PRIMARY: adb install-multiple with HOST paths
        install_ok = False
        install_log = []
        for attempt in range(4):
            r = sh(provider, env_id, f"""
{ADB} logcat -c
echo "=== P17 INSTALL-MULTIPLE attempt marker ==="
IM_RAW=$({ADB} install-multiple -r /root/xapk/com.intsig.camscanner.apk /root/xapk/config.arm64_v8a.apk /root/xapk/config.en.apk 2>&1 | tail -4)
echo "IM_VERDICT: $IM_RAW"
case "$IM_RAW" in
  *Success*) echo "IM_OK=1";;
  *) echo "IM_OK=0";;
esac
sleep 3
echo "=== immediate registry check ==="
{ADB} shell pm path com.intsig.camscanner 2>&1
{ADB} shell pm list packages 2>&1 | grep -i camscan || echo "PM_LIST: no camscan package"
""", timeout=2400)
            install_log.append(r.stdout.strip()[-2500:])
            if "IM_OK=1" in r.stdout:
                install_ok = True
                log(f"install-multiple attempt {attempt+1}: SUCCESS")
                break
            log(f"install-multiple attempt {attempt+1} failed: {r.stdout.strip()[-200:]!r}")
            time.sleep(90)
        result["install_multiple"] = install_log[-1]
        result["install_attempts"] = len(install_log)
        result["install_ok"] = install_ok
        log(f"install_ok={install_ok}")

        if install_ok:
            # resolution poll
            resolved = False
            resolve_log = []
            for i in range(60):
                r2 = sh(provider, env_id,
                        f"{ADB} shell pm path com.intsig.camscanner 2>&1 | head -1", timeout=60)
                p = r2.stdout.strip()
                resolve_log.append(p[:40])
                if "package:" in p:
                    resolved = True
                    log(f"pm path resolved after {i * 5}s: {p[:80]}")
                    break
                time.sleep(5)
            result["package_resolved"] = resolved
            result["resolve_log"] = resolve_log[-8:]

            # package info + dynamic launcher resolution
            r3 = sh(provider, env_id, f"""
{ADB} shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p com.intsig.camscanner 2>&1 | tail -3
{ADB} shell dumpsys package com.intsig.camscanner 2>&1 | grep -E 'versionName|codePath|primaryCpuAbi' | head -5
""", timeout=300)
            result["package_info"] = r3.stdout.strip()[:800]
            log(f"package info: {result['package_info'][:300]!r}")

            # patient launch via resolved component
            launch_logs = []
            launched = False
            comp = None
            m = re.search(r"com\.intsig\.camscanner/\S+", r3.stdout)
            if m:
                comp = m.group(0).strip()
            if not comp:
                comp = "com.intsig.camscanner/com.intsig.camscanner.mainmenu.mainactivity.MainActivity"
            log(f"launching component: {comp}")
            for attempt in range(3):
                r = sh(provider, env_id, f"""
{ADB} shell am start -W -n {comp} 2>&1 | head -10
""", timeout=600)
                launch_logs.append(r.stdout.strip()[:400])
                log(f"launch attempt {attempt+1}: {r.stdout.strip()[:180]!r}")
                time.sleep(60)
                r2 = sh(provider, env_id, f"""
{ADB} shell ps -A | grep -E 'camscanner' | head -3
{ADB} shell dumpsys activity activities 2>&1 | grep -iE 'camscanner|mResumed' | head -4
""", timeout=300)
                launch_logs.append(r2.stdout.strip()[:400])
                if "com.intsig.camscanner" in r2.stdout:
                    launched = True
                    break
            result["launch"] = launch_logs
            result["launched"] = launched
            log(f"launched={launched}")
            sh(provider, env_id, f"{ADB} exec-out screencap -p > /root/cap/abi17-launch.png",
               timeout=300)
            try:
                provider.transfer(env_id, "pull", OUT / "abi17-launch.png",
                                  "/root/cap/abi17-launch.png")
                result["screenshot"] = str(OUT / "abi17-launch.png")
            except Exception as e:  # noqa: BLE001
                result["screenshot"] = f"pull failed: {e}"

        # crash forensics regardless
        r = sh(provider, env_id, f"""
echo '=== crash buffer ==='
{ADB} shell logcat -b crash -d 2>&1 | head -60
echo '=== PackageManager verdicts ==='
{ADB} shell logcat -d | grep -iE 'PackageManager|installd|DefContainer' | tail -40
""", timeout=600)
        result["crash_forensics"] = r.stdout.strip()[:9000]
        log(f"crash forensics head: {result['crash_forensics'][:250]!r}")

        result["finished_utc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        ok = True
    except Exception as e:  # noqa: BLE001
        result["error"] = f"{type(e).__name__}: {e}"
        ok = False
        log(f"[!] PROBE FAILURE: {e}")
    finally:
        try:
            if env_id:
                provider.destroy(env_id)
                log(f"destroyed {env_id}")
        except Exception:  # noqa: BLE001
            pass

    RESULT_PATH.write_text(json.dumps(result, indent=2))
    log(f"result -> {RESULT_PATH}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
