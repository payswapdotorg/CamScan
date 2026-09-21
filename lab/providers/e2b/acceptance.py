#!/usr/bin/env python3
"""CAMSCAN-008 acceptance gate — the full provider lifecycle, no manual repair.

Proves, using ONLY the LabProvider API:
    provision -> start (TCG boot) -> verify adb -> build+install probe APK ->
    launch APK -> interact (wake/tap/back) -> screenshot -> UI dump ->
    logcat -> collect_evidence -> transfer pull -> stop -> destroy

Plus empirical capability probes recorded into the structured health report:
    - screenrecord (recording capability)
    - e2b pause/resume with a live emulator (snapshot capability)
    - virtualscene camera console/AVD probing (camera_fixture capability)

Emits (local, under <workdir>/acceptance/):
    acceptance_result.json   — gate verdict + timings + probed capabilities
    health_report.json       — provider.report() output
    evidence/                — the pulled evidence bundle
Exit code 0 only when the lifecycle completes AND every hard assertion holds.
The capability flips (camera_fixture/recording/snapshot) are PROBES, not
hard gates — they record truth for the committed capability report.

Run:  E2B_API_KEY=... python3 lab/providers/e2b/acceptance.py
"""
from __future__ import annotations

import json
import os
import sys
import time
import traceback
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO_ROOT))

from lab.providers.types import CaptureKind, EnvironmentSpec, Interaction  # noqa: E402
from lab.providers.e2b import E2BProvider, E2BProviderConfig  # noqa: E402
from lab.providers.e2b.bootstrap import ADB  # noqa: E402

WORKDIR = Path(os.environ.get("CAMSCAN_ACCEPTANCE_DIR",
                              REPO_ROOT / "lab" / "providers" / "e2b" / ".acceptance"))
RUN_ID = "camscan008-acceptance-" + time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
PROBE_APK_LOCAL = Path(__file__).resolve().parent / "probe_apk.sh"
PROBE_APK_REMOTE = "/root/probe_apk.sh"
PROBE_APK = "/root/probeapk/probe.apk"
PROBE_PKG = "org.camscan.lab.probeapp"


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def main() -> int:
    WORKDIR.mkdir(parents=True, exist_ok=True)
    result: dict = {
        "gate": "CAMSCAN-008",
        "run_id": RUN_ID,
        "started_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "lifecycle": [],
        "probes": {},
        "capabilities_probed": {},
        "verdict": "FAIL",
    }
    provider = E2BProvider(E2BProviderConfig(
        local_workdir=str(WORKDIR), boot_budget_s=int(
            os.environ.get("CAMSCAN_BOOT_BUDGET_S", "2400"))))
    env_id = None
    try:
        # -- capabilities (pre-flight) -----------------------------------
        caps = provider.capabilities()
        result["capabilities_pre"] = {
            k: caps[k] for k in ("emulator_acceleration", "android_emulator", "adb")}
        assert caps["emulator_acceleration"] == "none", "must report TCG (none), never kvm"
        result["lifecycle"].append("capabilities(pre)")
        log(f"capabilities: accel={caps['emulator_acceleration']} "
            f"emulator={caps['android_emulator']} adb={caps['adb']}")

        # -- provision -----------------------------------------------------
        log("provision: fresh E2B sandbox + baked bootstrap ...")
        t0 = time.time()
        env = provider.provision(EnvironmentSpec(purpose="probe"))
        env_id = env.env_id
        result["sandbox_id"] = env.sandbox_id
        result["provision_s"] = round(time.time() - t0, 1)
        result["lifecycle"].append("provision")
        log(f"provisioned {env.env_id} sandbox={env.sandbox_id} "
            f"in {result['provision_s']}s (timings={env.timings})")

        # -- start / TCG boot ----------------------------------------------
        log("start: TCG emulator boot (budget "
            f"{provider.cfg.boot_budget_s}s, measured gate was 410s) ...")
        boot = provider.start(env_id)
        result["boot_s"] = boot.get("boot_s")
        result["android_version"] = boot.get("android_version")
        result["lifecycle"].append("start->boot_completed")
        log(f"boot_completed in {result['boot_s']}s "
            f"(android {result['android_version']})")

        # -- verify adb -----------------------------------------------------
        dev = provider.execute(env_id, f"{ADB} devices", timeout=60)
        assert "emulator-" in dev.stdout and "device" in dev.stdout, f"adb devices: {dev.stdout}"
        boot_flag = provider.execute(env_id, f"{ADB} shell getprop sys.boot_completed",
                                     timeout=120)
        assert boot_flag.stdout.strip().endswith("1"), "boot_completed != 1"
        result["lifecycle"].append("verify-adb")
        log(f"adb verified: {dev.stdout.strip().splitlines()[-1].strip()}")

        # -- build probe APK (pushed via transfer -> exercises push) --------
        push = provider.transfer(env_id, "push", PROBE_APK_LOCAL, PROBE_APK_REMOTE)
        result["probe_push_bytes"] = push["bytes"]
        result["lifecycle"].append("transfer(push)")
        build = provider.execute(env_id, f"sh {PROBE_APK_REMOTE}", timeout=600)
        assert "PROBE_APK_DONE" in build.stdout, f"probe apk build failed: {build.stdout[-500:]}"
        apk_hash = [l for l in build.stdout.splitlines() if "probe.apk" in l and len(l) == 71]
        result["probe_apk_sha256"] = apk_hash[0].split()[0] if apk_hash else ""
        result["lifecycle"].append("build-probe-apk")
        log(f"probe APK built (sha256={result['probe_apk_sha256'][:16]}…)")

        # -- install + launch ------------------------------------------------
        install = provider.execute(env_id, f"{ADB} install -r -t {PROBE_APK}",
                                   timeout=600)
        assert "Success" in install.stdout, f"install failed: {install.stdout[-300:]}"
        result["lifecycle"].append("install-apk")
        log("probe APK installed")
        launch = provider.execute(
            env_id, f"{ADB} shell am start -W -n {PROBE_PKG}/android.app.Activity",
            timeout=300)
        assert "Status: ok" in launch.stdout, f"launch failed: {launch.stdout[-400:]}"
        result["launch_status"] = "ok"
        result["lifecycle"].append("launch-apk")
        log(f"probe APK launched: {[l for l in launch.stdout.splitlines() if 'Status' in l]}")
        ps = provider.execute(env_id, f"{ADB} shell ps -A | grep {PROBE_PKG} | head -1",
                              timeout=120)
        result["probe_process"] = ps.stdout.strip().splitlines()[0] if ps.stdout.strip() else ""
        assert PROBE_PKG in ps.stdout, "probe process not running after launch"

        # -- interact (traced) -------------------------------------------------
        for action in (
            Interaction(kind="wake", label="wakeup"),
            Interaction(kind="tap", x=540, y=1140, label="center-tap"),
            Interaction(kind="press", button="home", label="home"),
        ):
            ev = provider.interact(env_id, action)
            log(f"interact {ev.label}: {ev.detail['duration_ms']}ms "
                f"exit={ev.detail['exit_code']}")
        result["lifecycle"].append("interact(x3)")
        result["interact_latency_ms"] = ev.detail["duration_ms"]

        # -- captures -----------------------------------------------------------
        shot = provider.capture(env_id, CaptureKind.screenshot)
        ui = provider.capture(env_id, CaptureKind.ui_hierarchy)
        cat = provider.capture(env_id, CaptureKind.logcat)
        result["screenshot_bytes"] = shot.bytes
        result["ui_hierarchy_bytes"] = ui.bytes
        result["logcat_bytes"] = cat.bytes
        assert shot.bytes > 10000, f"screenshot suspiciously small: {shot.bytes}"
        assert ui.bytes > 500, f"ui dump suspiciously small: {ui.bytes}"
        assert cat.bytes > 100, f"logcat suspiciously small: {cat.bytes}"
        result["lifecycle"].append("capture(screenshot+ui+logcat)")
        log(f"captures: shot={shot.bytes}B ui={ui.bytes}B logcat={cat.bytes}B")

        # -- probe: recording (screenrecord under TCG) --------------------------
        try:
            rec = provider.capture(env_id, CaptureKind.recording, seconds=5)
            ok = rec.bytes > 1000
            result["probes"]["recording"] = {
                "ok": ok, "bytes": rec.bytes, "path": rec.sandbox_path}
            result["capabilities_probed"]["recording"] = ok
        except Exception as e:  # noqa: BLE001
            result["probes"]["recording"] = {"ok": False, "error": str(e)[:300]}
            result["capabilities_probed"]["recording"] = False
        log(f"probe recording: {result['capabilities_probed'].get('recording')}")

        # -- probe: camera virtualscene console ---------------------------------
        cam = probe_camera(provider, env_id)
        result["probes"]["camera"] = cam
        result["capabilities_probed"]["camera_fixture"] = bool(cam.get("injectable"))
        log(f"probe camera_fixture: {cam.get('injectable')} "
            f"(mechanism={cam.get('mechanism')})")

        # -- collect evidence ------------------------------------------------
        bundle = provider.collect_evidence(env_id, RUN_ID)
        result["evidence"] = {
            "run_id": RUN_ID, "files": len(bundle.files),
            "trace_events": bundle.trace_events,
            "local_path": bundle.local_path}
        assert len(bundle.files) >= 4, "evidence bundle missing artifacts"
        result["lifecycle"].append("collect_evidence")
        log(f"evidence collected: {len(bundle.files)} files, "
            f"{bundle.trace_events} trace events")

        # -- transfer pull (explicit API use) ---------------------------------
        pulled = provider.transfer(env_id, "pull", WORKDIR / "pulled-probe.apk", PROBE_APK)
        assert pulled["bytes"] == push["bytes"] or pulled["bytes"] > 10000
        result["pulled_apk_bytes"] = pulled["bytes"]
        result["lifecycle"].append("transfer(pull)")
        log(f"pulled probe APK locally ({pulled['bytes']}B)")

        # -- probe: snapshot (e2b pause/resume with live emulator) --------------
        snap_ok, snap_detail = probe_snapshot(provider, env_id)
        result["probes"]["snapshot"] = snap_detail
        result["capabilities_probed"]["snapshot"] = snap_ok
        log(f"probe snapshot (pause/resume with live emulator): {snap_ok}")

        # -- report --------------------------------------------------------------
        health = provider.report(env_id)
        (WORKDIR / "health_report.json").write_text(json.dumps(health, indent=2))
        result["health_ok"] = health["ok"]
        assert health["ok"], f"health report not ok: {health.get('last_error')}"
        result["lifecycle"].append("report")

        # -- stop / destroy --------------------------------------------------------
        provider.stop(env_id)
        assert provider.report(env_id)["emulator_processes"] == 0
        result["lifecycle"].append("stop")
        provider.destroy(env_id)
        env_id = None
        result["lifecycle"].append("destroy")
        log("stopped + destroyed cleanly")

        # -- gate verdict ------------------------------------------------------------
        required = ["provision", "start->boot_completed", "verify-adb", "transfer(push)",
                    "build-probe-apk", "install-apk", "launch-apk", "interact(x3)",
                    "capture(screenshot+ui+logcat)", "collect_evidence", "transfer(pull)",
                    "report", "stop", "destroy"]
        missing = [s for s in required if s not in result["lifecycle"]]
        result["verdict"] = "PASS" if not missing else f"FAIL(missing:{missing})"
    except Exception as e:  # noqa: BLE001
        result["verdict"] = "FAIL"
        result["error"] = f"{type(e).__name__}: {e}"
        result["traceback"] = traceback.format_exc()[-4000:]
        log(f"[!] GATE FAILURE: {e}")
    finally:
        if env_id and env_id in provider._envs:
            try:
                provider.destroy(env_id)
            except Exception:  # noqa: BLE001
                pass

    result["finished_utc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    out = WORKDIR / "acceptance_result.json"
    out.write_text(json.dumps(result, indent=2))
    log(f"verdict: {result['verdict']}  -> {out}")
    return 0 if result["verdict"] == "PASS" else 1


# --------------------------------------------------------------------- probes

def probe_camera(provider: E2BProvider, env_id: str) -> dict:
    """Empirically probe the virtualscene camera injection mechanism."""
    out: dict = {"injectable": False, "mechanism": None}
    # 1. does the emulator console expose virtualscene commands?
    help_out = provider.execute(env_id, f"{ADB} emu avd camera back 2>&1 | head -5",
                                timeout=120)
    out["console_camera_back"] = help_out.stdout.strip()[:200]
    vs = provider.execute(env_id, f"{ADB} emu virtualscene 2>&1 | head -5", timeout=120)
    out["console_virtualscene"] = vs.stdout.strip()[:200]
    # 2. does the AVD dir have poster/scene customization hooks?
    avd_dir = provider.execute(
        env_id, "ls -la /root/.android/avd/ 2>/dev/null; "
                "find /root/.android/avd -maxdepth 3 -iname '*poster*' -o -iname '*scene*' 2>/dev/null | head -10",
        timeout=120)
    out["avd_dir"] = avd_dir.stdout.strip()[:500]
    # 3. is the back camera actually virtualscene (getprop)?
    hal = provider.execute(
        env_id, f"{ADB} shell dumpsys media.camera | grep -m5 -i 'virtual\\|emulated\\|Camera ID' | head -5",
        timeout=180)
    out["camera_service"] = hal.stdout.strip()[:400]
    # 4. try the poster set command shape (harmless if unsupported)
    poster = provider.execute(
        env_id, f"{ADB} emu virtualscene poster poster1 /root/probeapk/probe.apk 2>&1 | head -3",
        timeout=120)
    out["console_poster_try"] = poster.stdout.strip()[:200]
    if "KO" not in poster.stdout and "unknown" not in poster.stdout.lower():
        out["injectable"] = True
        out["mechanism"] = "console:virtualscene-poster"
    return out


def probe_snapshot(provider: E2BProvider, env_id: str) -> tuple[bool, dict]:
    """Pause the sandbox with a live emulator, resume, verify it survived."""
    detail: dict = {}
    try:
        snap_id = provider.snapshot(env_id, "acceptance")
        detail["snapshot_id"] = snap_id
        provider.restore(env_id, snap_id)
        alive = provider.execute(
            env_id, "pgrep -c -f 'qemu-system.*-avd camscan-probe' || true",
            timeout=60)
        procs = alive.stdout.strip().splitlines()[0].strip() if alive.stdout.strip() else "0"
        boot = provider.execute(env_id, f"{ADB} shell getprop sys.boot_completed",
                                timeout=180)
        detail["emulator_procs_after_resume"] = procs
        detail["boot_completed_after_resume"] = boot.stdout.strip().endswith("1")
        ok = procs != "0" and detail["boot_completed_after_resume"]
        detail["ok"] = ok
        return ok, detail
    except Exception as e:  # noqa: BLE001
        detail["error"] = str(e)[:400]
        detail["ok"] = False
        # try to bring the env back to a sane state for the rest of the gate
        try:
            env = provider._envs.get(env_id)
            if env is not None and env.status not in ("ready", "destroyed"):
                provider.stop(env_id)
                provider.start(env_id)
        except Exception:  # noqa: BLE001
            pass
        return False, detail


if __name__ == "__main__":
    sys.exit(main())
