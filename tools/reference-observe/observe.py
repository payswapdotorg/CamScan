#!/usr/bin/env python3
"""CAMSCAN-004 — reference environment observation driver (Worker 2).

Drives the OFFICIAL CamScanner application through the e2b LabProvider in the
ISOLATED reference environment (AVD `camscan-reference` — never shared with
the implementation environment / Worker 1) and produces the reference evidence
bundle for the capability-discovery session.

Observable behavior only — no reverse engineering, no decompiling, no copying
of proprietary source/assets. The APK is treated as a black box: install,
launch, interact via adb, capture screen/UI/logcat, inspect the app's
OUTSIDE-visible effects (package state, granted permissions, files it writes
to shared storage).

Phases (each recorded in observation-result.json; explicit timeouts everywhere):
  0  preflight     — E2B_API_KEY present? APK present? sha256 recorded
  1  provision     — EnvironmentSpec(purpose="reference") -> AVD camscan-reference
  2  start         — TCG emulator boot (provider-owned budget, measured 410 s)
  3  env metadata  — health report: android/device/locale/timezone/resolution
  4  install       — push + `adb install -r` the official APK (record output)
  5  package facts — dumpsys package: versionName/versionCode, requested
                     permissions, install-time grants, main activity
  6  first run     — launch, wait for process, capture screenshot + ui dump +
                     logcat at 0/15/30 s (onboarding / login-gate observation)
  7  state scan    — external storage before/after (files the app creates),
                     permission grant state after first run, observable
                     network domains from logcat
  8  offline probe — wifi+data off, relaunch, capture error/empty states,
                     restore network
  9  second run    — relaunch online (state persistence / re-onboarding?)
 10  evidence      — collect_evidence (hash-verified bundle) + health report
 11  destroy       — reference env is ephemeral: always destroyed at the end

Exit code 0 only when every phase completes. Partial results (including a
BLOCKED preflight) are still written to <out>/<run_id>/observation-result.json
with a per-phase record — the caller (lab host station) holds E2B_API_KEY in
the environment; it is NEVER printed, logged, or stored.

Usage:
    E2B_API_KEY=... python3 tools/reference-observe/observe.py \
        --apk /path/to/camscanner-official.apk \
        --apk-source-url "https://…" \
        [--package com.intsig.camscanner] \
        [--out runs/]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import time
import traceback
from pathlib import Path
from typing import Any, Optional

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT))

APK_PACKAGE_DEFAULT = "com.intsig.camscanner"

from lab.providers.types import CaptureKind, EnvironmentSpec, Interaction  # noqa: E402
from lab.providers.e2b import E2BProvider, E2BProviderConfig  # noqa: E402
from lab.providers.e2b.bootstrap import ADB  # noqa: E402

# Storage locations the app may write outside its private dirs (observed set —
# extend as discovered; `ls`-only, no proprietary content pulled).
EXTERNAL_DIRS = (
    "/sdcard/Android/data/{pkg}/files",
    "/sdcard/Android/data/{pkg}/cache",
    "/sdcard/CamScanner",
    "/sdcard/DCIM/CamScanner",
    "/sdcard/Pictures/CamScanner",
    "/sdcard/Documents/CamScanner",
)


class BlockedExit(Exception):
    """Raised to stop the phase chain while keeping the verdict already set.
    Never escapes main(): the observation-result.json is always written."""


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def extract_split_bundle(xapk: Path, dest: Path) -> list[Path]:
    """Extract an .xapk/.apks split bundle (ZIP) into its member APKs.

    Mirrors distribute Play-listing split sets: `base` + config.* splits
    (CAMSCAN-004 discovery: the official CamScanner 7.25.5 bundle is
    base + config.arm64_v8a + config.en — installed with
    `adb install-multiple`, NOT `adb install`). Raises on a bundle with no
    base apk. Observable-artifact handling only (unzipping our own APK file).
    """
    import zipfile
    dest.mkdir(parents=True, exist_ok=True)
    members: list[Path] = []
    with zipfile.ZipFile(xapk) as z:
        for name in z.namelist():
            if name.endswith(".apk"):
                target = dest / Path(name).name
                target.write_bytes(z.read(name))
                members.append(target)
    # a split bundle needs exactly one base apk (id 'base' in XAPK manifest,
    # i.e. the member not named config.*)
    with zipfile.ZipFile(xapk) as z:
        meta = json.loads(z.read("manifest.json").decode())
    base = [s["file"] for s in meta.get("split_apks", []) if s.get("id") == "base"]
    if base and not (dest / base[0]).exists():
        raise RuntimeError(f"base split {base[0]!r} missing after extraction")
    if not base and not any("config" not in m.name for m in members):
        raise RuntimeError(f"no base apk found in split bundle {xapk}")
    return sorted(members)


def ls_snapshot(provider: E2BProvider, env_id: str, pkg: str) -> str:
    """Listing of every app-visible external dir (empty dirs reported as such)."""
    parts = []
    for tmpl in EXTERNAL_DIRS:
        d = tmpl.format(pkg=pkg)
        res = provider.execute(
            env_id, f"{ADB} shell \"ls -laR {d} 2>/dev/null || echo ABSENT: {d}\"",
            timeout=180)
        parts.append(res.stdout.strip())
    return "\n".join(parts)


def network_domains_from_logcat(logcat_text: str) -> list[str]:
    """Best-effort extraction of hostnames the app contacted at first run.

    Observable behavior only: what the device's own logs say about the app's
    connections (lines mentioning the app and a hostname-looking token).
    """
    domains: list[str] = []
    for line in logcat_text.splitlines():
        low = line.lower()
        if any(k in low for k in ("camscanner", "intsig", "csdk")):
            for token in line.replace(",", " ").replace(";", " ").split():
                if token.count(".") >= 1 and any(
                        token.lower().endswith(sfx) for sfx in
                        (".com", ".net", ".cn", ".io", ".org")):
                    cand = token.strip("(){}[]'\"")
                    if "/" in cand:
                        cand = cand.split("/")[0]
                    if cand and cand not in domains and not cand[0].isdigit():
                        domains.append(cand)
    return domains[:40]


def main() -> int:
    ap = argparse.ArgumentParser(description="CAMSCAN-004 reference observation driver")
    ap.add_argument("--apk", required=True, help="local path to the official CamScanner APK")
    ap.add_argument("--apk-source-url", default="",
                    help="exact source URL the APK was obtained from (recorded)")
    ap.add_argument("--package", default="com.intsig.camscanner",
                    help="expected package name (verified post-install)")
    ap.add_argument("--out", default=str(REPO_ROOT / "runs"),
                    help="output root; session lands in <out>/<run_id>/")
    ap.add_argument("--keep-env", action="store_true",
                    help="do NOT destroy the sandbox at the end (debugging only)")
    args = ap.parse_args()

    run_id = "camscan004-reference-" + time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    workdir = Path(args.out).resolve() / run_id
    workdir.mkdir(parents=True, exist_ok=True)
    apk_path = Path(args.apk).resolve()

    result: dict[str, Any] = {
        "gate": "CAMSCAN-004",
        "run_id": run_id,
        "started_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "phases": [],
        "apk": {"path": str(apk_path), "source_url": args.apk_source_url,
                "exists": apk_path.exists()},
        "package_expected": args.package,
        "verdict": "BLOCKED",
    }
    if apk_path.exists():
        result["apk"].update({"bytes": apk_path.stat().st_size,
                              "sha256": sha256_file(apk_path)})

    def phase(name: str, ok: bool, detail: Optional[dict] = None) -> bool:
        result["phases"].append({"name": name, "ok": ok,
                                 "detail": detail or {},
                                 "ts_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())})
        log(f"phase {name}: {'ok' if ok else 'FAIL/BLOCKED'}")
        return ok

    provider: Optional[E2BProvider] = None
    try:
        # -- 0 preflight ------------------------------------------------------
        provider = E2BProvider(E2BProviderConfig(
            local_workdir=str(workdir),
            boot_budget_s=int(os.environ.get("CAMSCAN_BOOT_BUDGET_S", "2400"))))
        key_err = ""
        try:
            provider._api_key()  # presence check only — value never leaves env
        except RuntimeError as e:
            key_err = str(e)
        if key_err:
            phase("preflight", False, {"reason": key_err})
            result["verdict"] = "BLOCKED"
            result["blocker"] = (
                "reference substrate unavailable in this worker sandbox: "
                + key_err)
            raise BlockedExit()
        if not apk_path.exists():
            phase("preflight", False, {"reason": f"APK not found: {apk_path}"})
            result["verdict"] = "BLOCKED"
            result["blocker"] = "official CamScanner APK not supplied"
            raise BlockedExit()
        phase("preflight", True, {"apk_sha256": result["apk"].get("sha256"),
                                  "apk_bytes": result["apk"].get("bytes")})

        # -- 1 provision (isolated reference env) ------------------------------
        # google_apis image (not the provider default): CamScanner ships
        # arm64-v8a-only splits; the google_apis x86_64 image carries
        # libndk_translation.so (abilist includes arm64-v8a) so the splits
        # match. The default image lacks it -> INSTALL_FAILED_NO_MATCHING_ABIS
        # (run-002 lesson, 2026-09-22; probe-17 proof).
        env = provider.provision(EnvironmentSpec(
            purpose="reference", memory_mb=4096,
            system_image="system-images;android-30;google_apis;x86_64",
            extra_sdk_packages=("system-images;android-30;google_apis;x86_64",)))
        env_id = env.env_id
        assert env.avd_name == "camscan-reference", (
            f"reference env must use its own AVD, got {env.avd_name}")
        phase("provision", True, {"env_id": env.env_id, "sandbox_id": env.sandbox_id,
                                  "avd": env.avd_name,
                                  "provision_s": env.timings.get("provision_total_s")})

        # -- 2 start (TCG boot) -------------------------------------------------
        boot = provider.start(env_id)
        phase("start", True, {"boot_s": boot.get("boot_s"),
                              "android_version": boot.get("android_version"),
                              "adb_serial": boot.get("adb_serial")})

        # -- 3 environment metadata ----------------------------------------------
        health = provider.report(env_id)
        (workdir / "health_report.json").write_text(json.dumps(health, indent=2))
        result["environment"] = {
            "android_version": health.get("android_version"),
            "device_model": health.get("device_model"),
            "resolution": health.get("resolution"),
            "density": health.get("density"),
            "locale": health.get("locale"),
            "timezone": health.get("timezone"),
            "avd_name": health.get("avd_name"),
            "provider_slug": "e2b",
        }
        phase("env-metadata", bool(health.get("ok")), result["environment"])

        # -- 3b dexopt filter (probe-17 recipe, 2026-09-22) ----------------------
        # setprop pm.dexopt.install verify: cheap filter spares system_server
        # the dexopt monitor storm that killed it in probe 9 during/after big
        # installs. root once, then unroot (shell identity for install).
        dex = provider.execute(env_id, f"""
{ADB} root 2>&1 | head -1
sleep 5
{ADB} wait-for-device
{ADB} shell setprop pm.dexopt.install verify && echo SETPROP_OK
{ADB} unroot 2>&1 | head -1
sleep 5
{ADB} wait-for-device
""", timeout=300)
        result["dexopt_filter"] = dex.stdout.strip()[-200:]

        # -- 4 install -----------------------------------------------------------
        # Split bundles (.xapk/.apks from reputable mirrors) install via
        # install-multiple; plain APKs via install -r.
        remote_files: list[str] = []
        if apk_path.suffix.lower() in (".xapk", ".apks"):
            splits_dir = workdir / "splits"
            splits = extract_split_bundle(apk_path, splits_dir)
            result["install_splits"] = [
                {"file": s.name, "bytes": s.stat().st_size,
                 "sha256": sha256_file(s)} for s in splits]
            for s in splits:
                remote = f"/root/{s.name}"
                provider.transfer(env_id, "push", s, remote)
                remote_files.append(remote)
            install_cmd = f"{ADB} install-multiple -r " + " ".join(remote_files)
        else:
            remote_apk = f"/root/{apk_path.name}"
            push = provider.transfer(env_id, "push", apk_path, remote_apk)
            remote_files = [remote_apk]
            install_cmd = f"{ADB} install -r {remote_apk}"
        install_ok = False
        install = None
        install_diag = []
        for attempt in range(5):
            # diagnostics: files present? package service up? (verdicts that
            # die on stderr are invisible otherwise — run-001 lesson)
            diag = provider.execute(env_id, f"""
echo "=== install attempt {attempt + 1} diagnostics ==="
ls -la /root/*.apk 2>&1 | head -5
{ADB} shell pm list packages 2>&1 | head -1
""", timeout=120)
            install_diag.append(diag.stdout.strip()[-500:])
            install = provider.execute(env_id, install_cmd, timeout=1200)
            install_ok = "Success" in (install.stdout or "")
            if not install_ok:
                # adb writes failure verdicts to stderr — capture both streams
                install_diag.append(
                    f"[attempt {attempt + 1}] exit={install.exit_code} "
                    f"stdout={install.stdout.strip()[-200:]!r} "
                    f"stderr={install.stderr.strip()[-400:]!r}")
            if install_ok:
                break
            # run-003 lesson: the device package service can die MID-STREAM
            # (broken pipe / Can't find service) — random flap, minutes-scale.
            # Gated backoff: wait for the service to respond again + 30s
            # settle before the next attempt (probe 17 succeeded on attempt 2).
            for _ in range(8):  # up to ~120 s service re-settle
                probe = provider.execute(env_id,
                                         f"{ADB} shell pm list packages 2>&1 | head -1",
                                         timeout=60)
                if (probe.stdout or "").strip().startswith("package:"):
                    break
                time.sleep(15)
            time.sleep(30)
        result["install_diagnostics"] = install_diag
        result["install"] = {"cmd": install_cmd.split(ADB)[-1][:200],
                             "stdout": (install.stdout or "").strip()[-800:],
                             "stderr": (install.stderr or "").strip()[-800:],
                             "exit_code": install.exit_code,
                             "attempts": attempt + 1}
        if not install_ok:
            phase("install", False, result["install"])
            result["verdict"] = "FAIL"
            raise BlockedExit()
        phase("install", True, {"files": [Path(r).name for r in remote_files]})

        # -- 5 package facts ------------------------------------------------------
        pkg = args.package
        facts = {}
        for label, cmd in (
            ("version", f"{ADB} shell dumpsys package {pkg} | grep -E "
                        "'versionName|versionCode' | head -4"),
            ("requested_permissions", f"{ADB} shell dumpsys package {pkg} | "
                                      "sed -n '/requested permissions:/,/install permissions:/p' | head -40"),
            ("runtime_permissions", f"{ADB} shell dumpsys package {pkg} | "
                                    "sed -n '/runtime permissions:/,/Queries:/p' | head -40"),
            ("main_activity", f"{ADB} shell cmd package resolve-activity --brief "
                              f"-c android.intent.category.LAUNCHER {pkg} | tail -2"),
            ("first_install_time", f"{ADB} shell dumpsys package {pkg} | grep -m2 "
                                   "'firstInstallTime\\|lastUpdateTime'"),
        ):
            res = provider.execute(env_id, cmd, timeout=180)
            facts[label] = res.stdout.strip()[:4000]
        result["package_facts"] = facts
        installed_pkg = provider.execute(env_id, f"{ADB} shell pm list packages | grep -i camsc",
                                         timeout=120).stdout.strip()
        result["installed_packages"] = installed_pkg
        phase("package-facts", pkg in installed_pkg,
              {"resolved_main_activity": facts.get("main_activity", "")})

        # -- 6 first run ------------------------------------------------------------
        baseline_storage = ls_snapshot(provider, env_id, pkg)
        (workdir / "storage_before_first_run.txt").write_text(baseline_storage)
        launch = provider.execute(
            env_id, f"{ADB} shell monkey -p {pkg} -c android.intent.category.LAUNCHER 1 "
                    f"2>&1 | tail -3", timeout=300)
        result["first_launch_monkey"] = launch.stdout.strip()[-300:]
        proc_line = ""
        for _ in range(12):  # up to ~120 s for first process start under TCG
            time.sleep(10)
            ps = provider.execute(env_id, f"{ADB} shell ps -A | grep {pkg} | head -1",
                                  timeout=120)
            if pkg in ps.stdout:
                proc_line = ps.stdout.strip().splitlines()[0]
                break
        result["first_run_process"] = proc_line
        if not proc_line:
            phase("first-run", False, {"monkey": result["first_launch_monkey"]})
            result["verdict"] = "FAIL"
            raise BlockedExit()
        focus = provider.execute(env_id, f"{ADB} shell dumpsys window | grep -m1 mCurrentFocus",
                                 timeout=120)
        result["first_run_window_focus"] = focus.stdout.strip()[:300]

        # -- 6b SystemUI ANR dismissal (probe-17 recipe) --------------------------
        # Under TCG the first launch of a heavy app trips a SystemUI ANR dialog
        # over the splash. Dismiss via the proven dump-tap ladder — dump+cat
        # through ONE adb shell (two-filesystems trap: the dump lives on the
        # device; host-side grep sees nothing), parse Wait-button bounds in
        # python, tap its center; fall back to the proven (540, 1244).
        anr = {"attempts": 0, "dismissed": False, "taps": []}
        for _ in range(3):
            provider.execute(env_id, f"{ADB} shell uiautomator dump /sdcard/anr.xml "
                                     f"2>&1 | tail -1", timeout=180)
            xml = provider.execute(env_id, f"{ADB} shell cat /sdcard/anr.xml",
                                   timeout=180).stdout
            m = re.search(r'text="Wait"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
                          xml or "")
            if not m:
                anr["dismissed"] = True  # no Wait button — no dialog present
                break
            x = (int(m.group(1)) + int(m.group(3))) // 2
            y = (int(m.group(2)) + int(m.group(4))) // 2
            provider.execute(env_id, f"{ADB} shell input tap {x} {y}", timeout=120)
            anr["attempts"] += 1
            anr["taps"].append([x, y])
            time.sleep(8)
        if not anr["dismissed"] and anr["attempts"] >= 3:
            # last resort: the camera-campaign-proven Wait coordinates
            provider.execute(env_id, f"{ADB} shell input tap 540 1244", timeout=120)
            anr["taps"].append([540, 1244])
            anr["dismissed"] = True
        result["anr_dismissal"] = anr
        first_run_caps = []
        for wait_s in (0, 15, 30):
            if wait_s:
                time.sleep(wait_s)
            shot = provider.capture(env_id, CaptureKind.screenshot)
            ui = provider.capture(env_id, CaptureKind.ui_hierarchy)
            first_run_caps.append({"after_s": wait_s,
                                   "screenshot": shot.sandbox_path,
                                   "screenshot_sha256": shot.sha256,
                                   "ui_hierarchy": ui.sandbox_path,
                                   "ui_hierarchy_sha256": ui.sha256,
                                   "window_focus": provider.execute(
                                       env_id, f"{ADB} shell dumpsys window | "
                                               f"grep -m1 mCurrentFocus", timeout=120
                                   ).stdout.strip()[:300]})
        result["first_run_captures"] = first_run_caps
        cat = provider.capture(env_id, CaptureKind.logcat)
        result["first_run_logcat"] = {"path": cat.sandbox_path, "sha256": cat.sha256,
                                      "bytes": cat.bytes}
        result["first_run_network_domains"] = network_domains_from_logcat(
            provider.execute(env_id, f"{ADB} logcat -d", timeout=300).stdout)
        phase("first-run", True, {"process": proc_line[:80],
                                  "focus": result["first_run_window_focus"],
                                  "domains": result["first_run_network_domains"]})

        # -- 7 state scan -------------------------------------------------------------
        after_storage = ls_snapshot(provider, env_id, pkg)
        (workdir / "storage_after_first_run.txt").write_text(after_storage)
        result["storage_changed"] = baseline_storage != after_storage
        granted = provider.execute(
            env_id, f"{ADB} shell dumpsys package {pkg} | "
                    "sed -n '/runtime permissions:/,/Queries:/p' | grep granted=true | head -20",
            timeout=180).stdout.strip()
        result["runtime_permissions_granted_after_first_run"] = granted[:2000]
        phase("state-scan", True, {"storage_changed": result["storage_changed"],
                                   "granted_runtime_permissions": granted[:600]})

        # -- 8 offline probe -------------------------------------------------------------
        provider.execute(env_id, f"{ADB} shell svc wifi disable", timeout=120)
        provider.execute(env_id, f"{ADB} shell svc data disable", timeout=120)
        provider.execute(env_id, f"{ADB} shell am force-stop {pkg}", timeout=120)
        time.sleep(3)
        launch2 = provider.execute(
            env_id, f"{ADB} shell monkey -p {pkg} -c android.intent.category.LAUNCHER 1 "
                    f"2>&1 | tail -2", timeout=300)
        time.sleep(20)
        off_shot = provider.capture(env_id, CaptureKind.screenshot)
        off_ui = provider.capture(env_id, CaptureKind.ui_hierarchy)
        off_focus = provider.execute(env_id, f"{ADB} shell dumpsys window | "
                                             f"grep -m1 mCurrentFocus", timeout=120)
        result["offline_probe"] = {
            "monkey": launch2.stdout.strip()[-200:],
            "screenshot": off_shot.sandbox_path, "screenshot_sha256": off_shot.sha256,
            "ui_hierarchy": off_ui.sandbox_path, "ui_hierarchy_sha256": off_ui.sha256,
            "window_focus": off_focus.stdout.strip()[:300]}
        provider.execute(env_id, f"{ADB} shell svc wifi enable", timeout=120)
        provider.execute(env_id, f"{ADB} shell svc data enable", timeout=120)
        phase("offline-probe", True, result["offline_probe"]["window_focus"])

        # -- 9 second run (state persistence) ----------------------------------------------
        provider.interact(env_id, Interaction(kind="press", button="home", label="home"))
        time.sleep(3)
        launch3 = provider.execute(
            env_id, f"{ADB} shell monkey -p {pkg} -c android.intent.category.LAUNCHER 1 "
                    f"2>&1 | tail -2", timeout=300)
        time.sleep(15)
        second_shot = provider.capture(env_id, CaptureKind.screenshot)
        second_ui = provider.capture(env_id, CaptureKind.ui_hierarchy)
        result["second_run"] = {
            "monkey": launch3.stdout.strip()[-200:],
            "screenshot": second_shot.sandbox_path,
            "screenshot_sha256": second_shot.sha256,
            "ui_hierarchy": second_ui.sandbox_path,
            "ui_hierarchy_sha256": second_ui.sha256,
            "screenshot_identical_to_first": (
                second_shot.sha256 == first_run_caps[-1]["screenshot_sha256"])}
        phase("second-run", True, {"identical_screenshot":
                                   result["second_run"]["screenshot_identical_to_first"]})

        # -- 10 evidence bundle ------------------------------------------------------------
        bundle = provider.collect_evidence(env_id, run_id)
        result["evidence"] = {"local_path": bundle.local_path,
                              "manifest": bundle.manifest_path,
                              "files": len(bundle.files),
                              "trace_events": bundle.trace_events}
        (workdir / "observation-notes.json").write_text(json.dumps(result, indent=2))
        phase("evidence", len(bundle.files) >= 4,
              {"files": len(bundle.files), "trace_events": bundle.trace_events})

        result["verdict"] = "PASS"
    except BlockedExit:
        pass  # verdict + phases already recorded; result JSON still written below
    except Exception as e:  # noqa: BLE001
        result["verdict"] = "FAIL"
        result["error"] = f"{type(e).__name__}: {e}"
        result["traceback"] = traceback.format_exc()[-4000:]
        log(f"[!] DRIVER FAILURE: {e}")
    finally:
        if provider is not None:
            for e in list(provider.list_envs()):
                if args.keep_env:
                    log(f"keep-env: leaving {e['env_id']} alive ({e['status']})")
                    continue
                try:
                    provider.destroy(e["env_id"])
                    log(f"cleanup: destroyed {e['env_id']}")
                except Exception:  # noqa: BLE001
                    pass

    result["finished_utc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    out = workdir / "observation-result.json"
    out.write_text(json.dumps(result, indent=2))
    log(f"verdict: {result['verdict']}  -> {out}")
    return 0 if result["verdict"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
