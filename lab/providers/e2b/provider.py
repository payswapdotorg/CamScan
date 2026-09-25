"""E2B LabProvider — the real implementation (CAMSCAN-008).

Substrate: E2B public `desktop` template (8 vCPU / ~8 GB RAM / 25 GB disk)
running the Android emulator under QEMU TCG software emulation (`-accel off`).
The bootstrap recipe is baked, not rediscovered — see `bootstrap.py`.

Long-operation handling (explicit, never a single short timeout):
  - sandbox lifetime is renewed opportunistically before every operation and
    on a fixed cadence during boot polling (Hobby tier caps TOTAL lifetime
    at 1 h — renewal past the create-time deadline is a harmless no-op;
    CAMSCAN-010D, see renewal_s below);
  - TCG cold boot has its own budget (default 2400 s; measured 410 s);
  - every execute/interact/capture takes an explicit per-call timeout. The
    scenario runner passes `meta.step_timeout_seconds` here; scenario-level
    `meta.timeout_seconds` is enforced by the runner, NOT by the provider —
    provider bootstrap/boot budgets are never counted against a scenario.

Credentials: E2B_API_KEY from the environment only. Never in git, manifests,
reports, evidence, or logs.

Run the acceptance gate (CAMSCAN-008 §22) with:
    python3 lab/providers/e2b/acceptance.py
"""
from __future__ import annotations

import base64
import json
import os
import secrets
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Optional

from ..types import (
    Artifact,
    CaptureKind,
    CommandResult,
    EnvironmentHandle,
    EnvironmentSpec,
    EvidenceBundle,
    Interaction,
    ResetSpec,
    TraceEvent,
)
from . import bootstrap as bs
from .bootstrap import ADB, SDK

REPO_ROOT = Path(__file__).resolve().parents[3]
CAPABILITY_REPORT_PATH = REPO_ROOT / "lab" / "providers" / "e2b" / "capability-report.json"

#: Static substrate truth. `camera_fixture`, `recording` and `snapshot` start
#: unverified (false) and are flipped ONLY by the empirically probed values
#: recorded in the committed capability-report.json (written by the
#: acceptance gate). `emulator_acceleration` is "none" — TCG — never "kvm".
#: v0.2 (CLI-first 2026-09-23): android_sdk / android_cli / gradle are
#: first-class true (bootstrap installs the SDK via sdkmanager/avdmanager;
#: gradlew-wrapper builds proven by the CAMSCAN-001 worker delivery).
#: android_studio stays FALSE — and is optional metadata, never matchable.
STATIC_CAPABILITIES: dict[str, Any] = {
    "slug": "e2b",
    "version": "0.2.0",
    "gui": True,                # screen content observable (screenshot + UI dump)
    "persistent": False,        # sandbox destroyed after run
    "android_sdk": True,        # cmdline-tools + platform-tools + platforms + build-tools
    "android_cli": True,        # sdkmanager/avdmanager path (known-good bootstrap)
    "android_emulator": True,
    "emulator_acceleration": "none",
    "adb": True,
    "gradle": True,             # gradlew wrapper path — proven by worker builds
    "camera_fixture": False,    # probed by acceptance (virtualscene poster path)
    "screenshots": True,
    "recording": False,         # probed by acceptance (screenrecord under TCG)
    "snapshot": False,          # probed by acceptance (e2b pause/resume)
    "android_studio": False,    # OPTIONAL metadata: no IDE anywhere in the lab
    "notes": {
        "gui": "swiftshader_indirect rendering; screen content capturable without a display",
        "emulator_acceleration": "QEMU TCG (-accel off); no /dev/kvm in E2B Firecracker microVMs",
        "camera_fixture": "virtualscene back camera; deterministic poster injection probed at acceptance",
        "android_sdk": "bootstrap.py installs cmdline-tools latest + platform-tools + emulator + system-images;android-30;default;x86_64 + platforms;android-30 + build-tools;33.0.2 — all via sdkmanager (terminal-only)",
        "android_cli": "known-good path: sdkmanager/avdmanager from cmdline-tools (proven at the 2026-09-21 acceptance gate). Newer unified `android` CLI: experiment pending (lab/substrate/) — the capability means terminal-usable Android tooling, whichever CLI generation provides it",
        "gradle": "gradlew wrapper (Gradle 8.9 / AGP 8.7.3 / JDK 17) — proven by the CAMSCAN-001 worker delivery build in an E2B desktop sandbox (APK archived to R2); no IDE involved",
    },
}


@dataclass
class E2BProviderConfig:
    template: str = "desktop"
    # CAMSCAN-010D truth-fix (comments only — no behavior change): the
    # E2B account is Hobby-tier and the cap is on TOTAL sandbox age,
    # not per call. The SDK docstring (e2b/sandbox_sync/main.py,
    # set_timeout) says "The maximum time a sandbox can be kept alive
    # is 24 hours (86_400 seconds) for Pro users and 1 hour (3_600
    # seconds) for Hobby users." The 20260925T110418Z-S001-live
    # postmortem matched it exactly (sandbox created ~11:04:24, envd
    # UNAVAILABLE at 12:04:24 = age exactly 3600 s). The create-time
    # timeout below IS the whole budget under Hobby.
    sandbox_lifetime_s: int = 3600       # total lifetime — the Hobby cap (3600 s)
    # renewal_s: under Hobby, set_timeout(3600) at age N still caps
    # TOTAL age at 3600 s — the old "per renewal call (API cap 1 h)"
    # reading was WRONG; renewal past the create-time deadline is a
    # harmless no-op (run-005 lesson: a fresh run gets a fresh 60-min
    # window). Kept: the calls are harmless and would extend lifetime
    # on a Pro-tier account.
    renewal_s: int = 3600                # renewal ask (no-op beyond the Hobby TOTAL-age cap)
    renewal_interval_s: float = 240.0    # opportunistic renewal cadence
    boot_budget_s: int = 2400            # TCG cold-boot budget (measured 410 s)
    boot_poll_s: float = 20.0
    cmd_timeout_s: int = 300             # default execute timeout
    install_timeout_s: int = 600         # adb install
    capture_timeout_s: int = 300         # screencap / ui dump under TCG (~30-60 s)
    recording_max_s: int = 180
    default_step_timeout_s: int = 120    # scenario meta.step_timeout_seconds default
    evidence_root: str = "/root/evidence"
    cap_dir: str = "/root/cap"
    trace_dir: str = "/root/trace"
    local_workdir: str = "."             # local dir for pulled evidence bundles
    api_key_env: str = "E2B_API_KEY"


def _utc() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


class E2BProvider:
    """LabProvider implementation over E2B sandboxes + TCG Android emulator."""

    slug = "e2b"

    def __init__(self, config: Optional[E2BProviderConfig] = None):
        self.cfg = config or E2BProviderConfig()
        self._envs: dict[str, EnvironmentHandle] = {}
        self._sandboxes: dict[str, Any] = {}
        self._last_renewal: dict[str, float] = {}
        self._cap_seq: dict[str, int] = {}
        self._created_count: dict[str, int] = {}

    # ------------------------------------------------------------ api key

    def _api_key(self) -> str:
        key = os.environ.get(self.cfg.api_key_env, "")
        if not key:
            raise RuntimeError(
                f"{self.cfg.api_key_env} not set — the e2b provider requires "
                "the E2B API key in the environment (never in git/evidence)")
        return key

    def _new_sandbox(self, lifetime_s: int):
        from e2b import Sandbox  # lazy: module importable without the SDK
        return Sandbox.create(template=self.cfg.template, timeout=lifetime_s)

    def _attach_sandbox(self, sandbox_id: str):
        """Attach to an existing (running or paused) sandbox by id.
        Sandbox.connect() resumes paused sandboxes (on_resume='restore')."""
        from e2b import Sandbox
        return Sandbox.connect(sandbox_id)

    # -------------------------------------------------------- capabilities

    def capabilities(self) -> dict[str, Any]:
        """Typed capability report. The committed capability-report.json
        (probed at acceptance, CI-validated) overrides unverified fields."""
        report = dict(STATIC_CAPABILITIES)
        report["notes"] = dict(STATIC_CAPABILITIES["notes"])
        try:
            committed = json.loads(CAPABILITY_REPORT_PATH.read_text())
            for key, value in committed.items():
                if key in ("slug", "version", "generated_utc", "verified_utc", "notes"):
                    report[key] = value
                elif key in report:
                    report[key] = value
            report["notes"].update(committed.get("notes", {}))
        except (OSError, ValueError):
            report["generated_utc"] = _utc()
            report["verified_utc"] = None
        return report

    # ----------------------------------------------------------- internals

    def _sandbox(self, env_id: str):
        env = self._require_env(env_id)
        if env.status == "destroyed":
            raise RuntimeError(f"env {env_id} destroyed")
        sb = self._sandboxes.get(env_id)
        if sb is None:
            sb = self._attach_sandbox(env.sandbox_id)
            self._sandboxes[env_id] = sb
        return sb, env

    def _require_env(self, env_id: str) -> EnvironmentHandle:
        env = self._envs.get(env_id)
        if env is None:
            raise KeyError(f"unknown environment {env_id!r}")
        return env

    def _maybe_renew(self, env_id: str) -> None:
        now = time.time()
        last = self._last_renewal.get(env_id, 0.0)
        if now - last < self.cfg.renewal_interval_s:
            return
        sb = self._sandboxes.get(env_id)
        if sb is None:
            return
        try:
            sb.set_timeout(self.cfg.renewal_s)
            self._last_renewal[env_id] = now
        except Exception:  # noqa: BLE001 — best-effort; next op retries
            pass

    def _sh(self, env_id: str, cmd: str, timeout: Optional[float] = None,
            user: str = "root", record: bool = True) -> CommandResult:
        """Execute a command in the env's sandbox with explicit timeout,
        opportunistic lifetime renewal, timing + trace recording."""
        sb, _ = self._sandbox(env_id)
        self._maybe_renew(env_id)
        t0 = time.time()
        timeout = timeout if timeout is not None else self.cfg.cmd_timeout_s
        try:
            r = sb.commands.run(cmd, timeout=timeout, user=user)
            res = CommandResult(exit_code=r.exit_code, stdout=r.stdout or "",
                                stderr=r.stderr or "", command=cmd,
                                duration_ms=round((time.time() - t0) * 1000))
        except Exception as e:  # noqa: BLE001 — CommandExitException carries fields
            res = CommandResult(
                exit_code=int(getattr(e, "exit_code", -1)),
                stdout=str(getattr(e, "stdout", "") or ""),
                stderr=str(getattr(e, "stderr", "") or "") or f"RUN_ERROR: {e}",
                command=cmd, duration_ms=round((time.time() - t0) * 1000))
        if record:
            self._trace(env_id, "execute", label=cmd[:120], detail={
                "exit_code": res.exit_code, "duration_ms": res.duration_ms,
                "timeout_s": timeout})
        return res

    def _adb(self, env_id: str, adb_cmd: str, timeout: Optional[float] = None,
             record: bool = True) -> CommandResult:
        return self._sh(env_id, f"{ADB} {adb_cmd}", timeout=timeout, record=record)

    def _trace(self, env_id: str, kind: str, label: str = "",
               detail: Optional[dict[str, Any]] = None) -> None:
        """Append a trace event to the sandbox-side JSONL action trace.
        Base64 transport keeps arbitrary JSON safe through the shell; the
        payload carries its own trailing newline so the file is valid JSONL."""
        env = self._require_env(env_id)
        event = TraceEvent(ts_utc=_utc(), env_id=env_id, kind=kind,
                           label=label, detail=detail or {})
        line = json.dumps(asdict(event), separators=(",", ":"))
        b64 = base64.b64encode((line + "\n").encode()).decode()
        sb = self._sandboxes.get(env_id)
        if sb is None:
            return
        try:
            sb.commands.run(
                f"mkdir -p {self.cfg.trace_dir} && echo {b64} | base64 -d "
                f">> {self.cfg.trace_dir}/{env_id}.jsonl",
                timeout=30, user="root")
        except Exception:  # noqa: BLE001 — trace must never break an operation
            pass

    def _trace_count(self, env_id: str) -> int:
        res = self._sh(env_id, f"wc -l < {self.cfg.trace_dir}/{env_id}.jsonl 2>/dev/null || echo 0",
                       record=False)
        try:
            return int(res.stdout.strip().splitlines()[-1])
        except (ValueError, IndexError):
            return 0

    # ------------------------------------------------------------ lifecycle

    def provision(self, spec: Optional[EnvironmentSpec] = None) -> EnvironmentHandle:
        """Fresh E2B sandbox + full baked bootstrap (no emulator boot yet).

        Transient substrate failures (network blips during SDK fetch etc.)
        are handled by REPLACING the sandbox once — replace, never repair;
        no manual steps. A second consecutive failure raises with the
        failing step recorded (handle left in `error` state).
        """
        spec = spec or EnvironmentSpec()
        last_detail = ""
        for attempt in (1, 2):
            env_id = f"e2b-{secrets.token_hex(4)}"
            avd_name = f"camscan-{spec.purpose}"
            t0 = time.time()
            try:
                sb = self._new_sandbox(self.cfg.sandbox_lifetime_s)
                env = EnvironmentHandle(
                    env_id=env_id, sandbox_id=sb.sandbox_id, avd_name=avd_name,
                    adb_serial="", provider_slug=self.slug, spec=spec,
                    capabilities=self.capabilities(), created_utc=_utc(),
                    status="provisioning",
                    trace_path=f"{self.cfg.trace_dir}/{env_id}.jsonl")
                self._envs[env_id] = env
                self._sandboxes[env_id] = sb
                self._last_renewal[env_id] = time.time()
                self._sh(env_id, f"mkdir -p {self.cfg.cap_dir} {self.cfg.trace_dir} "
                                 f"{self.cfg.evidence_root}", record=False)
                packages = tuple(dict.fromkeys(
                    bs.BASE_SDK_PACKAGES + tuple(spec.extra_sdk_packages)))
                ok, detail = bs.bootstrap(
                    sb, env_id, env.timings, packages=packages, avd_name=avd_name,
                    system_image=spec.system_image, device_profile=spec.device_profile,
                    on_progress=lambda m: self._trace(env_id, "bootstrap", label=m))
                env.timings["provision_total_s"] = round(time.time() - t0, 1)
                env.timings["provision_attempt"] = attempt
                if not ok:
                    env.status = "error"
                    env.last_error = detail
                    last_detail = detail
                    # transient-substrate policy: replace the sandbox once
                    self._trace(env_id, "lifecycle", label="bootstrap-failed",
                                detail={"attempt": attempt, "error": detail[:400]})
                    self.destroy(env_id)
                    continue
                env.status = "provisioned"
                self._trace(env_id, "lifecycle", label="provisioned",
                            detail={"sandbox_id": env.sandbox_id, "avd": avd_name,
                                    "sdk": SDK, "packages": list(packages),
                                    "attempt": attempt})
                return env
            except Exception as e:  # noqa: BLE001 — sandbox create/infra failure
                last_detail = f"{type(e).__name__}: {e}"
                self._envs.pop(env_id, None)
                self._sandboxes.pop(env_id, None)
                self._last_renewal.pop(env_id, None)
                continue
        raise RuntimeError(f"bootstrap failed on both attempts: {last_detail}")

    def start(self, env_id: str) -> dict[str, Any]:
        """Boot the emulator (TCG) and wait for adb + boot_completed.

        Idempotent: no-op when already ready. Returns boot info with the
        measured boot time; the boot budget is provider-owned (never a
        scenario timeout).
        """
        sb, env = self._sandbox(env_id)
        if env.status == "ready":
            return {"already_ready": True, "boot_s": env.timings.get("boot_s")}
        if env.status not in ("provisioned", "stopped", "error"):
            raise RuntimeError(f"env {env_id} in state {env.status!r} cannot start")

        env.status = "booting"
        env.last_error = None
        t0 = time.time()
        launch = bs.emulator_launch_cmd(
            env.avd_name, env.spec.memory_mb, env.spec.cores, env.spec.locale,
            env.spec.timezone, env.spec.camera_back, wipe_data=False,
            camera_poster=env.spec.camera_poster)
        res = self._sh(env_id, launch, timeout=120, record=False)
        first = res.stdout.strip().splitlines()
        procs = first[0].strip() if first else "0"
        if procs == "0":
            tail = self._sh(env_id, "tail -c 800 /tmp/emulator.log", record=False)
            env.status = "error"
            env.last_error = f"emulator process failed to start: {tail.stdout[:400]}"
            raise RuntimeError(env.last_error)
        self._trace(env_id, "lifecycle", label="emulator-launched")

        booted, detail = self._wait_boot(env)
        env.timings["boot_s"] = round(time.time() - t0, 1)
        if not booted:
            env.status = "error"
            env.last_error = detail
            raise RuntimeError(f"boot failed: {detail}")
        env.status = "ready"
        self._trace(env_id, "boot", label="boot_completed",
                    detail={"boot_s": env.timings["boot_s"], "adb_serial": env.adb_serial})
        return {"boot_s": env.timings["boot_s"], "adb_serial": env.adb_serial,
                "android_version": detail.get("android_version", "")}

    def _wait_boot(self, env: EnvironmentHandle) -> tuple[bool, dict[str, Any]]:
        """Poll adb until sys.boot_completed=1 within the provider boot budget.

        Fail-fast on emulator death and on fatal emulator.log strings; renew
        the sandbox lifetime on a fixed cadence during the poll.
        """
        env_id = env.env_id
        t0 = time.time()
        budget = self.cfg.boot_budget_s
        poll = 0
        last_tail = ""
        info: dict[str, Any] = {}
        while time.time() - t0 < budget:
            poll += 1
            time.sleep(self.cfg.boot_poll_s)
            self._maybe_renew(env_id)
            elapsed = int(time.time() - t0)
            if poll == 2:  # fail fast if the emulator died right after launch
                alive = self._sh(env_id, bs.emulator_alive_cmd(env.avd_name),
                                 timeout=30, record=False)
                if alive.stdout.strip().splitlines()[0].strip() == "0":
                    return False, {"error": "emulator process died during early boot",
                                   "emulator_log_tail": last_tail[-1200:]}
            dev = self._adb(env_id, "devices", timeout=45, record=False)
            serial = ""
            for line in dev.stdout.splitlines():
                line = line.strip()
                if line.startswith("emulator-") and "device" in line:
                    serial = line.split()[0]
                    break
            if serial and not env.adb_serial:
                env.adb_serial = serial
            boot_flag = self._adb(env_id, "shell getprop sys.boot_completed",
                                  timeout=45, record=False) if serial else CommandResult(-1)
            if boot_flag.stdout.strip().endswith("1"):
                # AOSP `default` images boot to the first-boot SETUP WIZARD:
                # sys.boot_completed=1 arrives while com.android.sdksetup still
                # owns the foreground and app launches stall with
                # 'Status: timeout'. Environment-ready therefore means:
                # provisioned + wizard dismissed + launcher focused.
                for setting in (
                        "settings put global device_provisioned 1",
                        "settings put secure user_setup_complete 1",
                        "settings put global setup_wizard_has_run 1"):
                    self._adb(env_id, f"shell {setting}", timeout=60, record=False)
                self._adb(env_id, "shell am force-stop com.android.sdksetup",
                          timeout=60, record=False)
                self._adb(env_id, "shell input keyevent KEYCODE_HOME",
                          timeout=60, record=False)
                focus = self._adb(env_id, "shell dumpsys window | grep -m1 mCurrentFocus",
                                  timeout=120, record=False)
                info["window_focus"] = focus.stdout.strip()[:200]
                info["wizard_dismissed"] = "sdksetup" not in focus.stdout
                props = self._adb(env_id, "shell getprop ro.build.version.release",
                                  timeout=120, record=False)
                info["android_version"] = props.stdout.strip().splitlines()[0] if props.stdout else ""
                return True, info
            tail = self._sh(env_id, "tail -c 1500 /tmp/emulator.log", record=False)
            last_tail = tail.stdout
            fatal = next((s for s in bs.BOOT_FATAL_STRINGS
                          if s.lower() in last_tail.lower()), None)
            if fatal:
                return False, {"error": f"fatal in emulator.log: {fatal}",
                               "emulator_log_tail": last_tail[-1200:]}
            if poll % 6 == 0:
                self._trace(env_id, "boot", label="waiting",
                            detail={"elapsed_s": elapsed, "adb": dev.stdout.strip()[:60]})
        return False, {"error": f"boot not completed within {budget}s",
                       "emulator_log_tail": last_tail[-1200:]}

    def stop(self, env_id: str) -> None:
        """Kill the emulator process; the sandbox stays alive (fast restart)."""
        sb, env = self._sandbox(env_id)
        res = self._sh(env_id, bs.emulator_kill_cmd(env.avd_name), timeout=60, record=False)
        env.status = "stopped"
        env.adb_serial = ""
        self._trace(env_id, "lifecycle", label="stopped")

    def reset(self, env_id: str, reset: ResetSpec) -> None:
        """Deterministically re-establish preconditions (never by hand):
        wipe-data relaunch -> optional APK reinstall -> permission baseline ->
        settings puts. Requires env ready on return."""
        sb, env = self._sandbox(env_id)
        if env.status == "ready":
            self.stop(env_id)
        t0 = time.time()
        launch = bs.emulator_launch_cmd(
            env.avd_name, env.spec.memory_mb, env.spec.cores, env.spec.locale,
            env.spec.timezone, env.spec.camera_back,
            wipe_data=bool(reset.wipe_data),
            camera_poster=env.spec.camera_poster)
        env.status = "booting"
        res = self._sh(env_id, launch, timeout=120, record=False)
        booted, detail = self._wait_boot(env)
        env.timings["reset_boot_s"] = round(time.time() - t0, 1)
        if not booted:
            env.status = "error"
            env.last_error = str(detail)
            raise RuntimeError(f"reset boot failed: {detail}")
        env.status = "ready"
        if reset.reinstall_apk:
            install = self._adb(env_id, f"install -r -t {reset.reinstall_apk}",
                                timeout=self.cfg.install_timeout_s)
            if install.exit_code != 0:
                raise RuntimeError(f"apk reinstall failed: {install.stdout} {install.stderr}")
        for permission, grant in reset.permissions.items():
            verb = "grant" if grant else "revoke"
            self._adb(env_id, f"shell pm {verb} {permission}", timeout=120)
        for key, value in reset.settings.items():
            self._adb(env_id, f"shell settings put {key} {value}", timeout=120)
        self._trace(env_id, "lifecycle", label="reset", detail={
            "wipe_data": reset.wipe_data, "apk": reset.reinstall_apk,
            "permissions": reset.permissions, "settings": reset.settings})

    def snapshot(self, env_id: str, name: str) -> str:
        """Freeze the whole sandbox (emulator included) via E2B pause.

        Returns a SnapshotId "<sandbox_id>|paused|<name>". Restoring via
        restore() thaws the exact state — no re-bootstrap, no re-boot.
        """
        sb, env = self._sandbox(env_id)
        t0 = time.time()
        ok = sb.pause()
        env.timings[f"snapshot_{name}_s"] = round(time.time() - t0, 1)
        if not ok:
            raise RuntimeError("e2b pause() failed — snapshot unavailable")
        snap_id = f"{env.sandbox_id}|paused|{name}"
        env.status = "paused"
        self._trace(env_id, "lifecycle", label=f"snapshot:{name}")
        return snap_id

    def restore(self, env_id: str, snap_id: str) -> None:
        """Thaw a paused sandbox (on_resume='restore' = disk+memory state)."""
        env = self._require_env(env_id)
        sandbox_id, mode, _name = (snap_id.split("|", 2) + ["", ""])[:3]
        if sandbox_id != env.sandbox_id or mode != "paused":
            raise RuntimeError(f"snapshot id {snap_id!r} does not match env {env_id}")
        if env_id in self._sandboxes:
            del self._sandboxes[env_id]
        # attach + thaw (connect resumes paused sandboxes, restoring the
        # disk+memory state — the emulator keeps running where it froze)
        sb = self._attach_sandbox(env.sandbox_id)
        self._sandboxes[env_id] = sb
        self._last_renewal[env_id] = time.time()
        # verify the emulator survived the freeze/thaw
        alive = self._sh(env_id, bs.emulator_alive_cmd(env.avd_name), timeout=60, record=False)
        procs = alive.stdout.strip().splitlines()[0].strip() if alive.stdout.strip() else "0"
        if procs == "0":
            env.status = "stopped"
            raise RuntimeError("sandbox restored but emulator died — restart needed")
        boot_flag = self._adb(env_id, "shell getprop sys.boot_completed", timeout=120, record=False)
        if boot_flag.stdout.strip().endswith("1"):
            env.status = "ready"
        else:
            env.status = "booting"
        self._trace(env_id, "lifecycle", label="restored")

    def destroy(self, env_id: str) -> None:
        """Kill the sandbox entirely."""
        env = self._require_env(env_id)
        sb = self._sandboxes.pop(env_id, None)
        if sb is None:
            try:
                sb = self._attach_sandbox(env.sandbox_id)
            except Exception:  # noqa: BLE001 — already gone
                sb = None
        if sb is not None:
            try:
                sb.kill()
            except Exception:  # noqa: BLE001 — idempotent destroy
                pass
        env.status = "destroyed"
        self._envs.pop(env_id, None)
        self._last_renewal.pop(env_id, None)
        self._cap_seq.pop(env_id, None)

    # ------------------------------------------------------------ operation

    def execute(self, env_id: str, cmd: str,
                timeout: Optional[float] = None) -> CommandResult:
        return self._sh(env_id, cmd, timeout=timeout)

    def interact(self, env_id: str, action: Interaction,
                 timeout: Optional[float] = None) -> TraceEvent:
        """One concrete device interaction via adb input, traced."""
        if timeout is None:
            timeout = self.cfg.default_step_timeout_s
        cmd = action.to_cmd(ADB)
        t0 = time.time()
        res = self._sh(env_id, cmd, timeout=timeout, record=False)
        event = TraceEvent(
            ts_utc=_utc(), env_id=env_id, kind="interact",
            label=action.label or action.kind,
            detail={"kind": action.kind, "x": action.x, "y": action.y,
                    "x2": action.x2, "y2": action.y2, "text": action.text,
                    "button": action.button, "keycode": action.keycode,
                    "cmd": cmd, "exit_code": res.exit_code,
                    "duration_ms": res.duration_ms})
        self._trace(env_id, event.kind, label=event.label, detail=event.detail)
        if res.exit_code != 0:
            raise RuntimeError(
                f"interaction {action.kind} failed (exit {res.exit_code}): "
                f"{res.stdout[:200]} {res.stderr[:200]}")
        return event

    def capture(self, env_id: str, kind: CaptureKind,
                timeout: Optional[float] = None,
                seconds: int = 10) -> Artifact:
        """Capture device evidence: screenshot | ui_hierarchy | logcat | recording."""
        if timeout is None:
            timeout = self.cfg.capture_timeout_s
        env = self._require_env(env_id)
        seq = self._cap_seq.get(env_id, 0) + 1
        self._cap_seq[env_id] = seq
        t0 = time.time()
        if kind == CaptureKind.screenshot:
            path = f"{self.cfg.cap_dir}/{seq:04d}-screenshot.png"
            res = self._sh(env_id, f"{ADB} exec-out screencap -p > {path} 2>/dev/null",
                           timeout=timeout, record=False)
            check = self._sh(env_id, f"test -s {path} && echo CAP_OK || echo CAP_EMPTY",
                             timeout=30, record=False)
            if "CAP_OK" not in check.stdout:
                raise RuntimeError(f"screenshot capture failed: {res.stdout[:200]}")
        elif kind == CaptureKind.ui_hierarchy:
            path = f"{self.cfg.cap_dir}/{seq:04d}-ui.xml"
            res = self._sh(env_id, f"{ADB} shell uiautomator dump /sdcard/window_dump.xml "
                                   f">/dev/null 2>&1; "
                                   f"{ADB} exec-out cat /sdcard/window_dump.xml > {path} 2>/dev/null",
                           timeout=timeout, record=False)
            check = self._sh(env_id, f"test -s {path} && echo CAP_OK || echo CAP_EMPTY",
                             timeout=30, record=False)
            if "CAP_OK" not in check.stdout:
                raise RuntimeError(f"ui hierarchy dump failed: {res.stdout[:200]}")
        elif kind == CaptureKind.logcat:
            path = f"{self.cfg.cap_dir}/{seq:04d}-logcat.txt"
            res = self._sh(env_id, f"{ADB} logcat -d > {path} 2>/dev/null",
                           timeout=timeout, record=False)
            check = self._sh(env_id, f"test -s {path} && echo CAP_OK || echo CAP_EMPTY",
                             timeout=30, record=False)
            if "CAP_OK" not in check.stdout:
                raise RuntimeError(f"logcat dump failed: {res.stdout[:200]}")
        elif kind == CaptureKind.recording:
            seconds = max(1, min(seconds, self.cfg.recording_max_s))
            path = f"{self.cfg.cap_dir}/{seq:04d}-recording.mp4"
            res = self._sh(env_id, f"{ADB} shell screenrecord --time-limit {seconds} "
                                   f"/sdcard/rec.mp4 >/dev/null 2>&1; "
                                   f"{ADB} pull /sdcard/rec.mp4 {path} >/dev/null 2>&1",
                           timeout=timeout + seconds + 60, record=False)
            check = self._sh(env_id, f"test -s {path} && echo CAP_OK || echo CAP_EMPTY",
                             timeout=30, record=False)
            if "CAP_OK" not in check.stdout:
                raise RuntimeError(f"screenrecord failed: {res.stdout[:200]}")
        else:
            raise ValueError(f"unsupported capture kind {kind!r}")
        stat = self._sh(env_id, f"stat -c '%s' {path}", timeout=30, record=False)
        digest = self._sh(env_id, f"sha256sum {path} | awk '{{print $1}}'",
                          timeout=60, record=False)
        artifact = Artifact(
            kind=kind.value, sandbox_path=path,
            bytes=int(stat.stdout.strip() or 0),
            sha256=digest.stdout.strip().splitlines()[0] if digest.stdout.strip() else "",
            duration_ms=round((time.time() - t0) * 1000), taken_utc=_utc())
        self._trace(env_id, "capture", label=kind.value,
                    detail={"path": path, "bytes": artifact.bytes,
                            "sha256": artifact.sha256,
                            "duration_ms": artifact.duration_ms})
        return artifact

    # ------------------------------------------------------ evidence/transfer

    def _environment_metadata(self, env_id: str) -> dict[str, Any]:
        env = self._require_env(env_id)
        props = [
            ("android_version", "ro.build.version.release"),
            ("api_level", "ro.build.version.sdk"),
            ("build_fingerprint", "ro.build.fingerprint"),
            ("device_model", "ro.product.model"),
            ("resolution", "dumpsys window | grep -m1 'init='"),
            ("density", "ro.sf.lcd_density"),
            ("locale", "persist.sys.locale"),
            ("timezone", "persist.sys.timezone"),
        ]
        meta: dict[str, Any] = {
            "env_id": env.env_id, "sandbox_id": env.sandbox_id,
            "avd_name": env.avd_name, "adb_serial": env.adb_serial,
            "provider_slug": self.slug, "system_image": env.spec.system_image,
            "device_profile": env.spec.device_profile,
            "emulator_acceleration": "none",
            "created_utc": env.created_utc,
            "capabilities": self.capabilities(),
        }
        for key, prop in props:
            res = self._adb(env_id, f"shell getprop {prop}", timeout=120, record=False)
            value = res.stdout.strip().splitlines()[0] if res.stdout.strip() else ""
            meta[key] = value
        if "init=" in meta.get("resolution", ""):
            meta["resolution"] = meta["resolution"].split("init=")[1].split(" ")[0].rstrip(")")
        return meta

    def collect_evidence(self, env_id: str, run_id: str) -> EvidenceBundle:
        """Assemble the run's evidence bundle in-sandbox, then pull it local.

        The manifest (sha256 per file + environment metadata + capability
        report) makes the run reproducible; large-artifact R2 upload is the
        evidence CLI's job (worker deliverable), never the provider's.
        """
        sb, env = self._sandbox(env_id)
        bundle = f"{self.cfg.evidence_root}/{run_id}"
        self._sh(env_id, f"rm -rf {bundle} && mkdir -p {bundle}", record=False)
        self._sh(env_id, f"cp -r {self.cfg.cap_dir}/. {bundle}/cap/ 2>/dev/null || true",
                 record=False)
        trace_dst = f"{bundle}/trace.jsonl"
        self._sh(env_id, f"cp {env.trace_path} {trace_dst} 2>/dev/null || true", record=False)
        meta = self._environment_metadata(env_id)
        meta["run_id"] = run_id
        b64 = base64.b64encode(json.dumps(meta, indent=2).encode()).decode()
        self._sh(env_id, f"echo {b64} | base64 -d > {bundle}/environment.json", record=False)
        # manifest: every file under the bundle with sha256 + size
        manifest_cmd = (f"cd {bundle} && find . -type f ! -name manifest.json | sort | "
                        f"while read f; do "
                        f"h=$(sha256sum \"$f\" | awk '{{print $1}}'); "
                        f"s=$(stat -c %s \"$f\"); "
                        f"echo \"$f|$h|$s\"; done")
        res = self._sh(env_id, manifest_cmd, timeout=300, record=False)
        files: list[dict[str, Any]] = []
        for line in res.stdout.splitlines():
            parts = [p for p in line.strip().split("|") if p]
            if len(parts) == 3:
                rel, digest, size = parts
                kind = ("trace" if "trace.jsonl" in rel
                        else "screenshot" if rel.endswith(".png")
                        else "ui_hierarchy" if rel.endswith(".xml")
                        else "logcat" if "logcat" in rel
                        else "recording" if rel.endswith(".mp4")
                        else "file")
                files.append({"path": rel.lstrip("./"), "kind": kind,
                              "sha256": digest, "bytes": int(size)})
        manifest = {
            "run_id": run_id, "env_id": env.env_id, "sandbox_id": env.sandbox_id,
            "provider_slug": self.slug, "generated_utc": _utc(),
            "environment": meta, "files": files,
            "trace_events": self._trace_count(env_id),
            "timings": env.timings,
        }
        b64 = base64.b64encode(json.dumps(manifest, indent=2).encode()).decode()
        self._sh(env_id, f"echo {b64} | base64 -d > {bundle}/manifest.json", record=False)
        # pull the whole bundle to local disk
        local_root = Path(self.cfg.local_workdir).resolve() / "evidence" / run_id
        local_root.mkdir(parents=True, exist_ok=True)
        listing = sb.files.list(bundle, depth=5)  # cap/ subdir + files
        for entry in listing:
            etype = getattr(entry, "type", None)
            evalue = getattr(etype, "value", etype)  # FileType enum or raw string
            if str(evalue) == "dir":
                continue
            rel = str(entry.path).replace(bundle, "").lstrip("/")
            if not rel:
                continue
            target = local_root / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            data = sb.files.read(str(entry.path), format="bytes")
            target.write_bytes(bytes(data))
        bundle_obj = EvidenceBundle(
            run_id=run_id, env_id=env.env_id, sandbox_id=env.sandbox_id,
            provider_slug=self.slug, local_path=str(local_root),
            manifest_path=str(local_root / "manifest.json"),
            files=files, trace_events=manifest["trace_events"])
        self._trace(env_id, "lifecycle", label="evidence-collected",
                    detail={"run_id": run_id, "files": len(files)})
        return bundle_obj

    def transfer(self, env_id: str, direction: str, local: Path,
                 remote: str) -> dict[str, Any]:
        """Push/pull a file between lab host and sandbox (size-verified)."""
        sb, _ = self._sandbox(env_id)
        self._maybe_renew(env_id)
        if direction == "push":
            data = Path(local).read_bytes()
            sb.files.write(remote, data)
            res = self._sh(env_id, f"stat -c %s {remote}", timeout=30, record=False)
            remote_size = int(res.stdout.strip() or 0)
            if remote_size != len(data):
                raise RuntimeError(f"push size mismatch: local={len(data)} remote={remote_size}")
            out = {"direction": "push", "local": str(local), "remote": remote,
                   "bytes": len(data)}
        elif direction == "pull":
            data = bytes(sb.files.read(remote, format="bytes"))
            Path(local).parent.mkdir(parents=True, exist_ok=True)
            Path(local).write_bytes(data)
            if Path(local).stat().st_size != len(data):
                raise RuntimeError("pull size mismatch")
            out = {"direction": "pull", "local": str(local), "remote": remote,
                   "bytes": len(data)}
        else:
            raise ValueError(f"direction must be push|pull, got {direction!r}")
        self._trace(env_id, "lifecycle", label=f"transfer:{direction}", detail=out)
        return out

    # ---------------------------------------------------------------- report

    def report(self, env_id: str) -> dict[str, Any]:
        """Structured health/capability report (machine-verifiable)."""
        from ..types import HealthReport
        env = self._require_env(env_id)
        sb = self._sandboxes.get(env_id)
        alive = False
        if sb is not None:
            try:
                alive = bool(sb.is_running())
            except Exception:  # noqa: BLE001
                alive = False
        procs = 0
        adb_devices = ""
        boot_completed = False
        if alive:
            r = self._sh(env_id, bs.emulator_alive_cmd(env.avd_name), timeout=60, record=False)
            try:
                procs = int(r.stdout.strip().splitlines()[0] or 0)
            except (ValueError, IndexError):
                procs = 0
            d = self._adb(env_id, "devices", timeout=60, record=False)
            adb_devices = d.stdout.strip()
            if procs:
                b = self._adb(env_id, "shell getprop sys.boot_completed",
                              timeout=120, record=False)
                boot_completed = b.stdout.strip().endswith("1")
        disk = self._sh(env_id, "df -m / | tail -1 | awk '{print $4}'", timeout=60, record=False)
        mem = self._sh(env_id, "free -m | awk '/Mem:/{print $4}'", timeout=60, record=False)
        meta = self._environment_metadata(env_id) if procs else {}
        report = HealthReport(
            env_id=env.env_id, sandbox_id=env.sandbox_id, generated_utc=_utc(),
            ok=bool(alive and procs and boot_completed),
            sandbox_alive=alive, emulator_processes=procs,
            adb_devices=adb_devices, boot_completed=boot_completed,
            android_version=meta.get("android_version", ""),
            device_model=meta.get("device_model", ""),
            resolution=meta.get("resolution", ""), density=meta.get("density", ""),
            locale=meta.get("locale", ""), timezone=meta.get("timezone", ""),
            avd_name=env.avd_name,
            uptime_s=round(time.time() - time.mktime(
                time.strptime(env.created_utc, "%Y-%m-%dT%H:%M:%SZ")), 1),
            disk_free_mb=int(disk.stdout.strip() or 0),
            mem_free_mb=int(mem.stdout.strip() or 0),
            capabilities=self.capabilities(), timings=env.timings,
            ops_recorded=self._trace_count(env_id), last_error=env.last_error)
        return asdict(report)

    # -------------------------------------------------------------- listing

    def list_envs(self) -> list[dict[str, Any]]:
        return [{"env_id": e.env_id, "sandbox_id": e.sandbox_id, "status": e.status,
                 "avd_name": e.avd_name, "purpose": e.spec.purpose}
                for e in self._envs.values()]
