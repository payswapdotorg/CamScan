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
from lab.providers.types import CommandResult  # noqa: E402
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
        _window_t0 = time.time()  # probe-32: the 60-min E2B hard cap clock
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

        # -- 3c GMS quiesce (probe-22, 2026-09-24) ------------------------------
        # Post-boot GMS churn under degraded TCG bg-ANRs
        # com.android.networkstack; ConnectivityModuleConnector then
        # crashes system_server ("Lost network stack") and the package
        # service stays dead through the minutes-long restart — the
        # install ladder burns inside that loop. Disable the churn
        # sources for the install window; re-enabled before first run
        # (install-time quiesce only, observation fidelity unchanged).
        quiesced = []
        for gms_pkg in ("com.google.android.gms",
                        "com.google.android.apps.wellbeing",
                        "com.android.vending"):
            q = provider.execute(
                env_id,
                f"{ADB} shell pm disable-user --user 0 {gms_pkg} 2>&1 | tail -1",
                timeout=90)
            quiesced.append((gms_pkg, (q.stdout or "").strip()[:120]))
        result["gms_quiesce"] = quiesced

        # -- 4 install -----------------------------------------------------------
        # Split bundles (.xapk/.apks from reputable mirrors) install via
        # install-multiple; plain APKs via install -r.
        # 2026-09-23: CAMSCAN_APK_URL env — in-sandbox download path. Pushing
        # the 162MB base split through the e2b files API stalls in an httpx
        # retry loop (all bytes acked, POST response never settles — observed
        # 20+ min). The sandbox pulling from object storage (R2 presigned URL)
        # is faster and avoids the SDK write path entirely. sha256-verified
        # in-sandbox against the recorded artifact hash before install.
        remote_files: list[str] = []
        apk_url = os.environ.get("CAMSCAN_APK_URL", "")
        if apk_url and apk_path.suffix.lower() in (".xapk", ".apks"):
            dl = provider.execute(env_id, f"""
cd /root
curl -fsSL -o bundle.dl '{apk_url}'
sha256sum bundle.dl
mkdir -p xapk
cd xapk
unzip -o ../bundle.dl '*.apk' 2>&1 | tail -3
ls -la /root/xapk/*.apk
""", timeout=900)
            want_sha = (result.get("apk") or {}).get("sha256", "")
            got_sha = ""
            # probe-29b: accept ONLY a real sha256sum line (<64-hex> bundle.dl).
            # The pre-29b parse took the FIRST line containing "bundle.dl" —
            # on a failed download (expired presigned URL -> curl -f 403
            # silent-fail) the first such line in stdout was UNZIP's error
            # (its 2>&1 is piped into stdout; sha256sum's error goes to
            # stderr), so the mismatch read got='unzip:' — technically
            # caught, diagnostically misleading.
            for ln in dl.stdout.splitlines():
                parts = ln.split()
                if (len(parts) >= 2 and parts[1].endswith("bundle.dl")
                        and len(parts[0]) == 64
                        and all(c in "0123456789abcdef" for c in parts[0])):
                    got_sha = parts[0]
                    break
            if want_sha and got_sha != want_sha:
                phase("install", False, {
                    "reason": "in-sandbox download sha mismatch",
                    "want": want_sha, "got": got_sha,
                    "download_tail": (dl.stdout or "")[-200:]})
                raise BlockedExit()
            splits_names = [p.strip().split("/")[-1]
                            for p in dl.stdout.splitlines()
                            if p.strip().endswith(".apk") and p.startswith("-")]
            remote_files = [f"/root/xapk/{n}" for n in splits_names]
            result["install_splits"] = [
                {"file": n, "bytes": 0, "sha256": ""} for n in splits_names]
            result["apk_delivery"] = "in-sandbox-url-download"
            install_cmd = f"{ADB} install-multiple -r " + " ".join(remote_files)
        elif apk_path.suffix.lower() in (".xapk", ".apks"):
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
        sandbox_death = None
        # retry-until-lucky (probe-24 final substrate truth): the google_apis
        # image NEVER settles — GMS bg-ANR churn bursts every 5-10 min forever
        # and kills the package service mid-stream at random. Each attempt is
        # cheap; the inter-burst gaps are 5-10 min apart, so 8 attempts with
        # the gated backoff below spans ~30 min of windows (probe 17 and 21b
        # both succeeded/reached-commit on attempt 2 — luck is real and
        # bounded retries harvest it).
        #
        # run-005 lessons (2026-09-23T024052Z, sandbox igj6mqwz):
        #   (a) a hung install stream eats the whole 20-min command timeout
        #       with no renewal opportunity — the sandbox expired mid-command
        #       and the loop then burned ~50 min hammering the corpse. Fix:
        #       run each install in the BACKGROUND with an EXIT_n marker file
        #       (probe-21b pattern) and poll lightly — polls renew the
        #       sandbox lifetime and a hang costs one 6-min outcome window,
        #       not 20 min.
        #   (b) sandbox death ("sandbox was not found" / "sandbox timeout" /
        #       "ended before the stream completed") is NOT a retryable
        #       install failure. Abort at once with verdict
        #       BLOCKED/sandbox_death — a fresh run gets a fresh 60-min
        #       window (E2B hard cap; set_timeout renewals cannot extend
        #       past it, as run-005 + probe-24 both died at exactly ~60 min).
        def _sandbox_dead(res) -> bool:
            blob = f"{res.stdout or ''}\n{res.stderr or ''}"
            return ("sandbox was not found" in blob
                    or "sandbox timeout" in blob
                    or "ended before the stream completed" in blob)

        for attempt in range(8):
            if sandbox_death:
                break
            # diagnostics: files present? package service up? (verdicts that
            # die on stderr are invisible otherwise — run-001 lesson)
            diag = provider.execute(env_id, f"""
echo "=== install attempt {attempt + 1} diagnostics ==="
ls -la /root/*.apk 2>&1 | head -5
{ADB} shell pm list packages 2>&1 | head -1
""", timeout=120)
            if _sandbox_dead(diag):
                sandbox_death = (diag.stderr or diag.stdout or "").strip()[-300:]
                install_diag.append(f"[attempt {attempt + 1}] SANDBOX-DEAD: "
                                    f"{sandbox_death}")
                break
            install_diag.append(diag.stdout.strip()[-500:])
            # background install with EXIT marker (probe-21b pattern)
            launcher = provider.execute(env_id, f"""
rm -f /root/install.out
({install_cmd} > /root/install.out 2>&1; echo "EXIT_$?" >> /root/install.out) &
echo LAUNCHED
""", timeout=60)
            if _sandbox_dead(launcher):
                sandbox_death = (launcher.stderr or launcher.stdout or "").strip()[-300:]
                install_diag.append(f"[attempt {attempt + 1}] SANDBOX-DEAD: "
                                    f"{sandbox_death}")
                break
            install = launcher
            install_ok = False
            outcome_seen = False
            deadline = time.time() + 360  # 6-min outcome window per attempt
            while time.time() < deadline:
                time.sleep(20)
                poll = provider.execute(
                    env_id, "cat /root/install.out 2>&1 | tail -4", timeout=60)
                if _sandbox_dead(poll):
                    sandbox_death = (poll.stderr or poll.stdout or "").strip()[-300:]
                    break
                out = (poll.stdout or "")
                if "EXIT_" in out:
                    mres = re.search(r"EXIT_(-?\d+)", out)
                    ec = int(mres.group(1)) if mres else -1
                    # fetch the full outcome file for the record
                    full = provider.execute(
                        env_id, "cat /root/install.out 2>&1", timeout=60)
                    body = (full.stdout or out).strip()
                    install = CommandResult(
                        exit_code=ec, stdout=body,
                        stderr="" if ec == 0 else body[-400:],
                        command=install_cmd)
                    install_ok = ("Success" in body and ec == 0)
                    outcome_seen = True
                    break
            if sandbox_death:
                install_diag.append(f"[attempt {attempt + 1}] SANDBOX-DEAD: "
                                    f"{sandbox_death}")
                break
            if not outcome_seen:
                # probe-23 (2026-09-24): under degraded TCG the package-
                # manager COMMIT can land minutes before the adb client
                # stream returns (observed live: the registry showed the
                # package while adb was still streaming). Check the
                # registry at the window edge BEFORE declaring a timeout
                # — a committed package IS success; killing the lingering
                # adb stream afterwards is harmless (commit is durable).
                reg = provider.execute(
                    env_id,
                    f"{ADB} shell pm path {args.package} 2>&1 | head -1",
                    timeout=60)
                if not _sandbox_dead(reg) and \
                        (reg.stdout or "").strip().startswith("package:/"):
                    provider.execute(
                        env_id, "pkill -f 'adb install' 2>&1; echo KILLED",
                        timeout=60)
                    install = CommandResult(
                        exit_code=0, stdout="Success (probe-23 edge check)",
                        stderr="", command=install_cmd)
                    install_ok = True
                    outcome_seen = True
                    install_diag.append(
                        f"[attempt {attempt + 1}] probe-23 edge registry "
                        "check: commit landed, adb stream killed")
                else:
                    # outcome window elapsed with no EXIT marker — kill the
                    # zombie stream and record a timeout (cheap, no 20-min hang)
                    provider.execute(
                        env_id, "pkill -f 'adb install' 2>&1; echo KILLED",
                        timeout=60)
                    install = CommandResult(
                        exit_code=-1, stdout="", stderr="outcome window elapsed",
                        command=install_cmd)
                    install_diag.append(f"[attempt {attempt + 1}] "
                                        f"outcome-window-timeout")
            if install_ok:
                break
            if install is not None:
                # adb writes failure verdicts to stderr — capture both streams
                install_diag.append(
                    f"[attempt {attempt + 1}] exit={install.exit_code} "
                    f"stdout={(install.stdout or '').strip()[-200:]!r} "
                    f"stderr={(install.stderr or '').strip()[-400:]!r}")
            # run-003 lesson: the device package service can die MID-STREAM
            # (broken pipe / Can't find service) — random flap, minutes-scale.
            # Gated backoff: wait for the service to respond again + 30s
            # settle before the next attempt (probe 17 succeeded on attempt 2).
            for _ in range(8):  # up to ~120 s service re-settle
                probe = provider.execute(env_id,
                                         f"{ADB} shell pm list packages 2>&1 | head -1",
                                         timeout=60)
                if _sandbox_dead(probe):
                    sandbox_death = (probe.stderr or probe.stdout or "").strip()[-300:]
                    break
                if (probe.stdout or "").strip().startswith("package:"):
                    break
                time.sleep(15)
            if sandbox_death:
                break
            time.sleep(30)
        result["install_diagnostics"] = install_diag
        if sandbox_death:
            result["install"] = {
                "cmd": install_cmd.split(ADB)[-1][:200],
                "stderr": sandbox_death,
                "exit_code": -1,
                "attempts": attempt + 1,
                "sandbox_death": True}
            phase("install", False, result["install"])
            result["verdict"] = "BLOCKED"
            result["blocked_reason"] = "sandbox_death"
            raise BlockedExit()
        result["install"] = {"cmd": install_cmd.split(ADB)[-1][:200],
                             "stdout": (install.stdout or "").strip()[-800:],
                             "stderr": (install.stderr or "").strip()[-800:],
                             "exit_code": install.exit_code,
                             "attempts": attempt + 1}
        if not install_ok:
            phase("install", False, result["install"])
            result["verdict"] = "FAIL"
            raise BlockedExit()
        # probe-15 lesson: an adb "Success" verdict does NOT prove the
        # package landed in the registry (the commit phase can die silently
        # after a broken pipe). Verify explicitly before declaring victory.
        registry = provider.execute(
            env_id, f"{ADB} shell pm path {args.package} 2>&1", timeout=60)
        registry_ok = (registry.stdout or "").strip().startswith("package:/")
        result["install"]["registry_check"] = {
            "stdout": (registry.stdout or "").strip()[-200:],
            "ok": registry_ok}
        if not registry_ok:
            phase("install", False, result["install"]["registry_check"])
            result["verdict"] = "FAIL"
            raise BlockedExit()
        phase("install", True, {"files": [Path(r).name for r in remote_files],
                                "registry": registry_ok})

        # -- 4b GMS restore (probe-22): re-enable before observation, then
        # settle so the re-enabled processes spin up BEFORE the first
        # launch (binder churn lands outside the fragile window).
        restored = []
        for gms_pkg, _ in quiesced:
            r = provider.execute(
                env_id, f"{ADB} shell pm enable {gms_pkg} 2>&1 | tail -1",
                timeout=60)
            restored.append((gms_pkg, (r.stdout or "").strip()[:120]))
        result["gms_restore"] = restored
        time.sleep(60)

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
        # probe-26 (2026-09-24): launch via the PROVEN probe-17 path —
        # `am start -W -n <resolved component>` — not component-less
        # monkey. Monkey only proves event INJECTION ("Events injected:
        # 1" in 3 straight runs whose process never spawned): under ANR
        # churn the intent can sit unprocessed while system_server
        # suicide-cycles. `am start -W` BLOCKS until AM reports the
        # launch verdict (Status/LaunchState/TotalTime land in the
        # captured tail) — the exact form that proved this app launches
        # on this substrate (probe 17, 2026-09-22: splash rendered, zero
        # crashes). The component comes from the package-facts
        # resolve-activity fact (last line, starts with pkg/). Fallback
        # to monkey only when no component resolved.
        #
        # probe-27 (2026-09-24, attempts 075022Z + 090119Z forensics):
        # the launch binder call dies EXACTLY like the install one —
        # 'cmd: Failure calling service activity: Broken pipe (32)' —
        # while the adb canary stays alive (probe-25: __canary_ok__,
        # ps_exit=0). The install ladder PROVED this failure class is
        # transient (retry-until-lucky + gated backoff harvests the
        # 5-10-min inter-burst gaps); the launch was single-shot — that
        # asymmetry killed both runs. Mirror the install ladder: am
        # start in the BACKGROUND with an EXIT_n marker (a blocked am
        # start costs one bounded window, not an 8-min hang), outcome
        # window per attempt, activity-service gate (am get-current-user)
        # + 30 s settle between attempts. 'brought to the front' counts
        # as up (the task already exists). Ladder exhaustion falls
        # through to the patient poll + probe-25 forensics.
        comp = ""
        for _ln in (facts.get("main_activity") or "").strip().splitlines():
            _ln = _ln.strip()
            if _ln.startswith(pkg + "/"):
                comp = _ln
                break
        launch = None
        launch_diag = []
        am_confirmed = ""  # probe-30: set when am itself reports our activity up
        # probe-29 (2026-09-24, run 20260924T135903Z forensics): timestamp
        # every ladder line — the attempt-2 postmortem had to reconstruct
        # the timeline from file mtimes; hh:mm:ss prefixes make the next
        # postmortem a read, not an inference.
        def _ldiag(s: str) -> None:
            launch_diag.append(time.strftime("%H:%M:%S ") + s)
        # probe-29: the install's background dexopt (verify filter on a
        # 162 MB base + 59 MB arm64 split under ~130 MB free) steals the
        # exact CPU the cold start needs — attempt 2's am start was
        # ACCEPTED (no resolution error) yet the app process never spawned
        # through a 90 s WaitTime + a 6.7-min patient poll while logcat
        # showed system_server slow-dispatch storms. Gate the ladder on
        # dex2oat quiescence (bounded — fresh windows land installs fast
        # and the verifier is usually already done).
        for _ in range(24):  # ≤ 4 min at 10 s cadence
            try:
                dx = provider.execute(
                    env_id, f"{ADB} shell ps -A | grep -c dex2oat", timeout=60)
                if (dx.stdout or "").strip() in ("0", ""):
                    break
            except Exception:  # noqa: BLE001 — best-effort gate
                break
            time.sleep(10)
        else:
            _ldiag("dex2oat still running after 4 min — proceeding anyway")
        if comp:
            result["first_launch_cmd"] = f"am start -W -n {comp} (ladder)"
            # probe-30 (2026-09-24, run 20260924T152953Z): am start -W's own
            # output is AUTHORITATIVE launch evidence — 'Status: ok' with
            # 'Activity: <pkg>/...' reports the AMS's own state (the app's
            # activity cannot be top-most without its process). Track it:
            # the patient ps poll below can be transport-blind (in-band
            # timeout text on every read) and must not veto the AMS.
            am_confirmed = ""
            for attempt in range(4):
                # probe-28 (2026-09-24, run 20260924T124252Z forensics): the
                # wrapper execute can fail TRANSIENTLY in-band — the E2B SDK's
                # request_timeout error TEXT arrives in stdout/stderr (the
                # provider layer returns it, it does not raise), so "LAUNCHED"
                # is missing while the sandbox and binder are both fine
                # (canary __canary_ok__, ps_exit=0 in the same run's death
                # forensics). The pre-28 ladder hard-broke here — aborting all
                # 4 attempts on ONE wrapper transient, the exact
                # single-shot-vs-retry-ladder asymmetry probe-27 fixed for the
                # am start call itself. Fix: fall through. The poll section
                # below either finds launch.out (the wrapper DID run
                # server-side despite the client-side timeout) or burns its
                # bounded 3-min window; the activity-service gate + settle
                # then backs off exactly like any other failed attempt, and
                # only a genuinely dead activity service aborts the ladder.
                bg = None
                try:
                    bg = provider.execute(env_id, f"""
rm -f /root/launch.out
({ADB} shell am start -W -n {comp} > /root/launch.out 2>&1; echo "EXIT_$?" >> /root/launch.out) &
echo LAUNCHED
""", timeout=60)
                except Exception as _le:  # noqa: BLE001 — transport death
                    _ldiag(f"[attempt {attempt + 1}] TRANSPORT-DEAD: "
                           f"{type(_le).__name__}: {_le}"[:200])
                if bg is not None and "LAUNCHED" not in (bg.stdout or ""):
                    _ldiag(f"[attempt {attempt + 1}] "
                           f"launcher-echo missing: "
                           f"{(bg.stdout or bg.stderr or '').strip()[-150:]!r}")
                outcome_seen = False
                deadline = time.time() + 180  # 3-min outcome window
                while time.time() < deadline:
                    time.sleep(20)
                    try:
                        poll = provider.execute(
                            env_id, "cat /root/launch.out 2>&1 | tail -6", timeout=60)
                    except Exception as _le:  # noqa: BLE001
                        _ldiag(f"[attempt {attempt + 1}] POLL-DEAD: "
                               f"{type(_le).__name__}"[:120])
                        poll = None
                    if poll is None:
                        break
                    if "EXIT_" in (poll.stdout or ""):
                        mres = re.search(r"EXIT_(-?\d+)",
                                         poll.stdout or "")
                        ec = int(mres.group(1)) if mres else -1
                        try:
                            full = provider.execute(
                                env_id, "cat /root/launch.out 2>&1", timeout=60)
                            body = (full.stdout or poll.stdout or "").strip()
                        except Exception:  # noqa: BLE001
                            body = (poll.stdout or "").strip()
                        launch = CommandResult(
                            exit_code=ec, stdout=body,
                            stderr="" if ec == 0 else body[-400:],
                            command=f"am start -W -n {comp}")
                        outcome_seen = True
                        break
                if poll is not None and outcome_seen:
                    body = (launch.stdout or "")
                    _ldiag(f"[attempt {attempt + 1}] exit={launch.exit_code} "
                           f"stdout={body[-250:]!r}")
                    # probe-29: 'Error type 3 / does not exist' means the am
                    # tool's OWN PackageManager query came back blind — while
                    # resolve-activity had answered minutes earlier and the
                    # activity-service gate never dropped (attempt 2: quiesce
                    # 'Can't find service: package' → facts resolve OK →
                    # attempts 2-3 blind → attempt 4 resolved). The PM binder
                    # endpoint FLAPS on the same minutes cadence as the
                    # install broken-pipe bursts. Capture the blind-state
                    # evidence so the next postmortem can see it directly.
                    if "does not exist" in body or "Error type 3" in body:
                        for _label, _cmd in (
                                ("pm-path", f"{ADB} shell pm path {pkg} | head -2"),
                                ("resolve-again",
                                 (f"{ADB} shell cmd package resolve-activity --brief "
                                  f"-a android.intent.action.MAIN "
                                  f"-c android.intent.category.LAUNCHER {pkg} | tail -1")),
                                ("activity-table",
                                 (f"{ADB} shell dumpsys package {pkg} "
                                  f"| grep -m2 mainactivity"))):
                            try:
                                _r = provider.execute(env_id, _cmd, timeout=60)
                                _ldiag(f"[attempt {attempt + 1}] blind-evidence "
                                       f"{_label}={( _r.stdout or '').strip()[:120]!r}")
                            except Exception:  # noqa: BLE001
                                _ldiag(f"[attempt {attempt + 1}] blind-evidence "
                                       f"{_label}=EXCEPTION")
                    if "Status: ok" in body:
                        if f"Activity: {pkg}" in body:
                            am_confirmed = body[-400:]
                        launch = CommandResult(
                            exit_code=0, stdout=body, stderr="",
                            command=f"am start -W -n {comp}")
                        break
                    if "brought to the front" in body:
                        _ldiag(
                            f"[attempt {attempt + 1}] task already fronted — "
                            "treating as up")
                        break
                elif poll is not None:
                    # bounded window elapsed with no EXIT marker — kill the
                    # blocked am start (a hang costs one 3-min window, not 8)
                    try:
                        provider.execute(
                            env_id, "pkill -f 'am start' 2>&1; echo KILLED",
                            timeout=60)
                    except Exception as _ke:  # noqa: BLE001 — best-effort kill
                        _ldiag(f"[attempt {attempt + 1}] pkill-dead: "
                               f"{type(_ke).__name__}")
                    _ldiag(f"[attempt {attempt + 1}] "
                           "outcome-window-timeout (blocked am start)")
                # gated backoff: wait for the activity service to respond
                # again + settle. probe-29: 30 s clustered all 4 attempts
                # inside one TCG burst; the regime's inter-burst gaps run
                # 5-10 min, so a 120 s settle lets the ladder straddle a
                # full burst cycle (4 attempts now span ~15-20 min).
                svc_up = False
                for _ in range(8):
                    try:
                        gate = provider.execute(
                            env_id,
                            f"{ADB} shell am get-current-user 2>&1 | head -1",
                            timeout=60)
                        if ((gate.stdout or "").strip()).isdigit():
                            svc_up = True
                            break
                    except Exception as _ge:  # noqa: BLE001 — best-effort gate
                        _ldiag(f"[attempt {attempt + 1}] gate-dead: "
                               f"{type(_ge).__name__}")
                    time.sleep(15)
                _ldiag(f"[attempt {attempt + 1}] "
                       f"activity-service gate: {'up' if svc_up else 'down'}")
                if not svc_up:
                    break
                time.sleep(120)
            result["first_launch_ladder"] = launch_diag
        else:
            result["first_launch_cmd"] = "monkey (no component resolved)"
            try:
                launch = provider.execute(
                    env_id, f"{ADB} shell monkey -p {pkg} -c android.intent.category.LAUNCHER 1 "
                            f"2>&1 | tail -3", timeout=300)
            except Exception:  # noqa: BLE001 — transport death ≠ verdict
                launch = None
        result["first_launch_monkey"] = (launch.stdout if launch else "").strip()[-300:]
        proc_line = ""
        last_ps = ""
        last_ps_err = ""
        last_ps_exit = None
        # probe-24 (2026-09-24): 120 s was NOT enough for the cold start
        # under the night TCG regime (observed: install landed via the
        # probe-23 edge check, monkey injected the launch event, and the
        # app process took >120 s to appear — the run died at first-run
        # while the process was still coming up). 40 polls x 10 s ≈
        # 6.7 min, affordable now that probe-23 lands installs ~20 min
        # into the window.
        # probe-25 (2026-09-24): an EMPTY ps read is ambiguous — "the app
        # process never spawned" vs "the adb read itself died" (broken
        # pipe, exactly the install-ladder failure mode under the same
        # regime; 2026-09-24T032659Z burned 13 min of patient polls on
        # an undifferentiated empty). Capture exit/stderr per poll and,
        # on failure, a death-forensics bundle: canary echo (is the adb
        # stream alive at all?), system ps head (is the process list
        # itself listing?), and a logcat tail (system_server ANR/suicide
        # screams here). Infra death vs app death must not look alike.
        # probe-30 (2026-09-24, run 20260924T152953Z postmortem): that run
        # had the app UP — attempt 4's am start returned 'Status: ok',
        # 'Activity: com.intsig.camscanner/.mainmenu.mainactivity.MainActivity',
        # 'delivered to currently running top-most instance', TotalTime 0 —
        # and then ALL 40 patient ps reads came back transport-blind
        # (exit -1, in-band sandbox-timeout text; canary/system-ps/logcat
        # forensics ALL empty), so the pre-30 poll scored 'process absent'
        # and FAILED a launched app. Blindness detection: a read whose
        # exit is -1 or whose text carries the SDK timeout signature is
        # NOT an absence observation — skip it, count it. If am already
        # confirmed the launch, its evidence outranks the (blind) ps poll.
        n_blind = 0
        n_clean_absent = 0
        # probe-31 (2026-09-24, run 20260924T174918Z postmortem): the launch
        # landed at minute ~54 of the 60-min sandbox window (install churn
        # 37 min + ladder retries 12 min), and the FULL 40x10 s ps poll then
        # burned the remaining budget BEFORE the evidence captures could run
        # — the run died with the app up and zero captures. When am already
        # confirmed the launch, the ps poll is a NICE-TO-HAVE (a process-line
        # detail), not the gate: cap it at 12 polls (2 min) and go straight
        # to the evidence phases while the transport still has minutes left.
        poll_cap = 12 if am_confirmed else 40
        for _ in range(poll_cap):
            time.sleep(10)
            ps = provider.execute(env_id, f"{ADB} shell ps -A | grep {pkg} | head -1",
                                  timeout=120)
            last_ps = (ps.stdout or "").strip()[:200]
            last_ps_err = (getattr(ps, "stderr", "") or "").strip()[-200:]
            last_ps_exit = getattr(ps, "exit_code", None)
            if (last_ps_exit == -1
                    or "timeout" in last_ps_err.lower()
                    or "You can modify" in last_ps
                    or "request_timeout" in last_ps):
                n_blind += 1
                continue
            if pkg in (ps.stdout or ""):
                proc_line = last_ps.splitlines()[0]
                break
            n_clean_absent += 1
        result["first_run_process"] = proc_line
        result["first_run_last_ps"] = last_ps
        result["first_run_ps_reads"] = {"blind": n_blind,
                                         "clean_absent": n_clean_absent,
                                         "poll_cap": poll_cap}
        if not proc_line and am_confirmed:
            # the AMS itself reported the app top-most with Status: ok —
            # a blind ps window cannot overrule it. Record the am evidence
            # and continue to the evidence phases (best-effort: if the
            # transport stays blind the captures fail and the run FAILs
            # honestly; if it recovers, the run earns its PASS).
            proc_line = (f"am-confirmed: Status: ok, top-most {pkg} instance "
                         f"(ps reads: {n_blind} blind / {n_clean_absent} "
                         f"clean-absent)")
            result["first_run_process"] = proc_line
            result["first_run_am_confirmation"] = am_confirmed
        if not proc_line:
            forensics = {"ps_exit": last_ps_exit, "ps_err": last_ps_err}
            try:
                canary = provider.execute(env_id, f"{ADB} shell echo __canary_ok__",
                                          timeout=60)
                forensics["canary"] = (canary.stdout or "").strip()[:80]
                forensics["canary_exit"] = getattr(canary, "exit_code", None)
            except Exception:  # noqa: BLE001 — best-effort forensics
                forensics["canary"] = "EXCEPTION"
            try:
                system_ps = provider.execute(env_id, f"{ADB} shell ps -A | head -3",
                                             timeout=60)
                forensics["system_ps_head"] = (system_ps.stdout or "").strip()[:300]
            except Exception:  # noqa: BLE001
                forensics["system_ps_head"] = "EXCEPTION"
            try:
                cat = provider.execute(
                    env_id, f"{ADB} shell logcat -d -t 200 2>&1 | tail -15",
                    timeout=120)
                forensics["logcat_tail"] = (cat.stdout or "").strip()[-1200:]
            except Exception:  # noqa: BLE001
                forensics["logcat_tail"] = "EXCEPTION"
            result["first_run_death_forensics"] = forensics
            phase("first-run", False, {"monkey": result["first_launch_monkey"],
                                       "last_ps": last_ps,
                                       "ps_exit": last_ps_exit,
                                       "ps_err": last_ps_err,
                                       "canary": forensics.get("canary", ""),
                                       "logcat_tail": forensics.get(
                                           "logcat_tail", "")[-400:]})
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
        # probe-32 (2026-09-24, run 20260924T191339Z postmortem): that run banked
        # 3 screenshot+ui capture pairs and then DIED at the logcat capture —
        # the sandbox hit its 60-min hard cap mid-dump (RuntimeError raised by
        # the provider, killing a run one evidence step from completion).
        # Hardening: each remaining evidence stage is death-tolerant (records
        # its partial + the error, flow continues), the domains read is
        # bounded (logcat -d -t 3000 — the full dump of a running system can
        # take minutes under strain), and a clock guard skips the optional
        # tail probes (< 8 min left) in favor of the evidence bundle.
        try:
            cat = provider.capture(env_id, CaptureKind.logcat)
            result["first_run_logcat"] = {"path": cat.sandbox_path, "sha256": cat.sha256,
                                          "bytes": cat.bytes}
        except Exception as _ce:  # noqa: BLE001 — evidence-stage death tolerance
            result["first_run_logcat"] = {"error": f"{type(_ce).__name__}: {_ce}"[:200]}
        try:
            result["first_run_network_domains"] = network_domains_from_logcat(
                provider.execute(env_id, f"{ADB} logcat -d -t 3000",
                                 timeout=180).stdout)
        except Exception as _de:  # noqa: BLE001
            result["first_run_network_domains"] = \
                f"error: {type(_de).__name__}"[:200]
        phase("first-run", True, {"process": proc_line[:80],
                                  "focus": result["first_run_window_focus"],
                                  "domains": result["first_run_network_domains"]})

        # -- 7 state scan -------------------------------------------------------------
        try:
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
        except Exception as _se:  # noqa: BLE001
            result["state_scan_error"] = f"{type(_se).__name__}: {_se}"[:200]
            phase("state-scan", False, {"error": result["state_scan_error"]})

        # probe-32 clock guard: the optional tail probes (offline + second
        # run) cost ~5-8 min; with less than that left before the 60-min
        # hard cap, skip them (recorded) — the evidence bundle still runs.
        # A skipped tail is NOT a PASS (the full observation includes it);
        # the verdict is set from phase outcomes below.
        _mins_left = 60 - (time.time() - _window_t0) / 60 - 1.0
        if _mins_left < 8:
            result["tail_probes_skipped"] = (f"window budget: {_mins_left:.1f} min "
                                             "left — offline/second-run skipped, "
                                             "evidence bundle prioritized")
        else:
            # -- 8 offline probe --------------------------------------------------
            try:
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
            except Exception as _oe:  # noqa: BLE001 — evidence-stage death tolerance
                result["offline_probe_error"] = f"{type(_oe).__name__}: {_oe}"[:200]
                phase("offline-probe", False, {"error": result["offline_probe_error"]})

            # -- 9 second run (state persistence) ----------------------------------
            try:
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
            except Exception as _te:  # noqa: BLE001 — evidence-stage death tolerance
                result["second_run_error"] = f"{type(_te).__name__}: {_te}"[:200]
                phase("second-run", False, {"error": result["second_run_error"]})

        # -- 10 evidence bundle --------------------------------------------------------
        # probe-32: the bundle runs whenever the transport lets it (evidence
        # is banked even on runs that will not PASS); PASS requires the FULL
        # observation — every phase ok and no budget-skipped tail.
        try:
            bundle = provider.collect_evidence(env_id, run_id)
            result["evidence"] = {"local_path": bundle.local_path,
                                  "manifest": bundle.manifest_path,
                                  "files": len(bundle.files),
                                  "trace_events": bundle.trace_events}
            (workdir / "observation-notes.json").write_text(json.dumps(result, indent=2))
            phase("evidence", len(bundle.files) >= 4,
                  {"files": len(bundle.files), "trace_events": bundle.trace_events})
        except Exception as _be:  # noqa: BLE001 — bank what we have
            result["evidence_error"] = f"{type(_be).__name__}: {_be}"[:200]
            phase("evidence", False, {"error": result["evidence_error"]})

        if (all(p.get("ok") for p in result.get("phases", []))
                and not result.get("tail_probes_skipped")):
            result["verdict"] = "PASS"
        else:
            result["verdict"] = "FAIL"
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
