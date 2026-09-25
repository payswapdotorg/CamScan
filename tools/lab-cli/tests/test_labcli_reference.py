"""The reference live driver's hermetic contract tests (CAMSCAN-009,
extended by CAMSCAN-010A — the launch-step port).

No network, no e2b SDK, no credentials, no real sleeps: the driver is
driven through an injected scripted transport (ScriptedProvider) that
records every operation in call order and answers ``execute`` from
substring-scripted responses — the same double philosophy as
tools/adb-bridge/tests/fake_provider.py, tailored to the install
ladder. The fake clock makes every retry/wait instant (sleeps advance
recorded time only); the injected fixed wall clock keeps the probe-29
ladder-diag timestamps deterministic (00:00:00).

Pinned properties (the work order's list):

- provision→install-recipe call ORDER: package-service settle →
  dexopt setprop BEFORE install-multiple → registry check after
  Success → dynamic launcher resolution;
- the GMS-churn install-ladder state machine: fail → re-settle probe
  (service down) → wait → retry → success; sandbox death → clean
  abort (sandbox destroyed, NO further install attempts); exhausted
  attempts → clean abort; outcome-window timeout → zombie kill;
- XAPK acquisition: in-sandbox presigned-URL curl with sha256
  verification (mismatch → clean abort), local --apk extraction +
  push fallback;
- execute: CAMSCAN-010A launch machinery — component launch
  (``am start -W -n <resolved component>``) PREFERRED over monkey
  (probe-26), the dex2oat quiescence gate before the ladder with
  bounded-then-proceed tolerance (probe-29), the ladder state machine
  (background am start + EXIT_n marker → bounded outcome window →
  activity-service gate → gap-cadence settle → next attempt;
  probe-27), the wrapper-transient fall-through (probe-28: LAUNCHED
  echo missing never aborts), 'brought to the front' counts as up,
  am-confirmed precedence over transport-blind ps reads (probe-30)
  with the ~2-min poll cap (probe-31), ladder-exhaustion forensics
  (timestamped diag lines, am output tails, exit codes, death
  forensics — probe-25) recorded in problems/reason AND the
  run-metadata action trace, the no-component monkey fallback through
  the bridge verb — then the patient process wait → SystemUI-ANR
  dismissal ladder (dump → Wait-button center tap) AFTER a SUCCESSFUL
  launch → per-step captures → run-metadata.json (deterministic
  timestamps when the request clock is empty); device-side step
  failures recorded, not raised;
- teardown: never raises, even when stop and destroy both raise;
- the registry resolution flip: env=reference resolves
  ReferenceDriver after the CAMSCAN-009 wiring; the pre-009 registry
  state (factories absent) is the honest NO-OP reason;
- scheduler resolution for S001–S004: both on-record reports enter
  the pool slug-ascending (base e2b first), S001–S003 schedulable,
  S004 honestly NO-OP with the camera_fixture reason from BOTH
  records.
"""
from __future__ import annotations

import hashlib
import json
import re
import zipfile
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from labcli_helpers import REPO_ROOT, run_cli
from tools.evidence_cli.jsonio import load as jsonio_load
from tools.lab_cli import drivers as driver_registry
from tools.lab_cli.drivers import (
    DriverHandle,
    ExecutionRequest,
    ProvisionRequest,
)
from tools.lab_cli.reference_live import (
    BOOT_SETTLE_S,
    DEX2OAT_GATE_MAX_POLLS,
    DEX2OAT_GATE_POLL_S,
    INSTALL_MAX_ATTEMPTS,
    LAUNCH_MAX_ATTEMPTS,
    LAUNCH_PROC_POLL_CAP_CONFIRMED,
    LAUNCH_SETTLE_S,
    OUTCOME_POLL_S,
    PROCESS_WAIT_ROUNDS,
    RECORDED_XAPK_SHA256,
    REFERENCE_MEMORY_MB,
    REFERENCE_SYSTEM_IMAGE,
    RETRY_BACKOFF_S,
    SERVICE_SETTLE_POLL_S,
    ReferenceDriver,
    _file_sha256,
    _Native,
)
from tools.lab_cli.run import load_provider_reports
from tools.lab_cli.scenarios import LabCliError, resolve_scenario
from tools.lab_cli.steps import plan_steps

from lab.providers.types import (
    Artifact,
    CaptureKind,
    CommandResult,
    Interaction,
)

RUN_ID = "20260924T000000Z-S001-live"


# ------------------------------------------------------------------ doubles

class FakeClock:
    """Deterministic time source: sleeps advance recorded time only."""

    def __init__(self) -> None:
        self.now = 0.0
        self.slept: list[float] = []

    def monotonic(self) -> float:
        return self.now

    def sleep(self, seconds: float) -> None:
        self.slept.append(seconds)
        self.now += seconds


class ScriptedProvider:
    """Hermetic transport double for the reference driver (no network,
    no SDK): records every operation in call order (``ops``) and answers
    ``execute`` from substring rules — each rule pops its scripted
    responses in order, repeating the last; unmatched commands get
    sensible adb defaults."""

    slug = "fake-reference"

    def __init__(self) -> None:
        self.ops: list[tuple[str, str]] = []
        self.exec_log: list[str] = []
        self.rules: list[list] = []          # [needle, responses, idx]
        self.provisioned_specs: list[Any] = []
        self.lifecycle: list[str] = []
        self.stop_raises = False
        self.destroy_raises = False
        self.interactions: list[Interaction] = []
        self.captures: list[CaptureKind] = []
        self.remote_files: dict[str, bytes] = {}
        self.ui_xmls: list[str] = ["<hierarchy/>"]
        self._ui_i = 0
        self.screenshot_bytes = b"\x89PNG-fake-reference"
        self.logcat_text = "fake-reference-logcat\n"

    # ------------------------------------------------ scripting surface
    def on(self, needle: str, *responses: Any) -> None:
        outs = [r if isinstance(r, CommandResult)
                else CommandResult(0, r, "", 5, needle)
                for r in responses]
        self.rules.append([needle, outs, 0])

    # ------------------------------------------------ LabProvider-ish
    def provision(self, spec: Any) -> Any:
        self.ops.append(("provision", str(spec.purpose)))
        self.provisioned_specs.append(spec)
        return SimpleNamespace(
            env_id="e2b-fake01", sandbox_id="sbx-fake01",
            avd_name=f"camscan-{spec.purpose}", spec=spec, timings={})

    def start(self, env_id: str) -> dict[str, Any]:
        self.ops.append(("start", env_id))
        return {"boot_s": 410.0, "adb_serial": "emulator-5554",
                "android_version": "11"}

    def report(self, env_id: str) -> dict[str, Any]:
        self.ops.append(("report", env_id))
        return {"ok": True, "android_version": "11",
                "device_model": "Pixel 4", "resolution": "1080x2280",
                "density": "440", "locale": "en-US", "timezone": "UTC",
                "avd_name": "camscan-reference"}

    def execute(self, env_id: str, cmd: str,
                timeout: float | None = None) -> CommandResult:
        self.ops.append(("execute", cmd))
        self.exec_log.append(cmd)
        for rule in self.rules:
            if rule[0] in cmd:
                idx = rule[2]
                rule[2] = min(idx + 1, len(rule[1]) - 1)
                return rule[1][idx]
        return self._default_execute(cmd)

    def interact(self, env_id: str, action: Interaction,
                 timeout: float | None = None) -> None:
        self.ops.append(("interact", f"{action.kind}:{action.x},{action.y}"))
        self.interactions.append(action)

    def capture(self, env_id: str, kind: CaptureKind,
                timeout: float | None = None) -> Artifact:
        self.ops.append(("capture", kind.value))
        self.captures.append(kind)
        seq = len(self.captures)
        if kind is CaptureKind.ui_hierarchy:
            idx = min(self._ui_i, len(self.ui_xmls) - 1)
            self._ui_i += 1
            data = self.ui_xmls[idx].encode()
            path = f"/remote/fake/{seq:04d}-ui.xml"
        elif kind is CaptureKind.screenshot:
            data = self.screenshot_bytes
            path = f"/remote/fake/{seq:04d}-screenshot.png"
        elif kind is CaptureKind.logcat:
            data = self.logcat_text.encode()
            path = f"/remote/fake/{seq:04d}-logcat.txt"
        else:
            raise ValueError(f"unsupported capture kind {kind!r}")
        self.remote_files[path] = data
        return Artifact(kind=kind.value, sandbox_path=path,
                        sha256=hashlib.sha256(data).hexdigest(),
                        bytes=len(data), duration_ms=5, taken_utc="")

    def transfer(self, env_id: str, direction: str, local: Any,
                 remote: str) -> dict[str, Any]:
        self.ops.append(("transfer", f"{direction}:{remote}"))
        local_path = Path(local)
        if direction == "push":
            data = local_path.read_bytes()
            self.remote_files[str(remote)] = data
            return {"direction": "push", "bytes": len(data)}
        if direction == "pull":
            local_path.parent.mkdir(parents=True, exist_ok=True)
            data = self.remote_files[str(remote)]
            local_path.write_bytes(data)
            return {"direction": "pull", "bytes": len(data)}
        raise ValueError(f"direction must be push|pull, got {direction!r}")

    def stop(self, env_id: str) -> None:
        self.ops.append(("stop", env_id))
        self.lifecycle.append("stop")
        if self.stop_raises:
            raise RuntimeError("stop failed (simulated)")

    def destroy(self, env_id: str) -> None:
        self.ops.append(("destroy", env_id))
        self.lifecycle.append("destroy")
        if self.destroy_raises:
            raise RuntimeError("destroy failed (simulated)")

    # -------------------------------------------------------- defaults
    def _default_execute(self, cmd: str) -> CommandResult:
        def ok(stdout: str = "") -> CommandResult:
            return CommandResult(0, stdout, "", 5, cmd)
        # CAMSCAN-010A launch-machinery defaults (the happy path):
        # dex2oat quiescent, the background launcher's LAUNCHED echo,
        # an am start -W verdict (Status: ok + our activity — the
        # probe-30 am_confirmed evidence), and an answering activity
        # service. Checked BEFORE the generic "am start" / "ps -A"
        # needles because the launcher script contains "am start" and
        # the dex2oat gate contains "ps -A".
        if "monkey" in cmd:
            return ok("Events injected: 1\n")
        if "grep -c dex2oat" in cmd:
            return ok("0\n")
        if "rm -f /root/launch.out" in cmd:
            return ok("LAUNCHED\n")
        if "cat /root/launch.out" in cmd:
            return ok("Status: ok\n"
                      "Activity: com.intsig.camscanner/"
                      ".mainmenu.mainactivity.MainActivity\n"
                      "LaunchState: COLD\nTotalTime: 1500\nEXIT_0\n")
        if "am get-current-user" in cmd:
            return ok("0\n")
        if "am start" in cmd:
            return ok("Status: ok\nLaunchState: COLD\nTotalTime: 2000\n")
        if "ps -A" in cmd:
            return ok("u0a123 4321 com.intsig.camscanner\n")
        if "versionName" in cmd:
            return ok("    versionName=7.25.5.2609020000\n")
        if "versionCode" in cmd:
            return ok("    versionCode=2609020000 minSdk=23\n")
        if "resolve-activity" in cmd:
            return ok("com.intsig.camscanner/"
                      "com.intsig.camscanner.launcher.MainActivity\n")
        if "pm path" in cmd:
            return ok("package:/data/app/~~xyz/com.intsig.camscanner-1/"
                      "base.apk\n")
        if "setprop pm.dexopt.install" in cmd:
            return ok("SETPROP_OK\n")
        if "pm list packages" in cmd:
            return ok("package:com.android.cpb\n")
        return ok("")


# ------------------------------------------------------------------ helpers

def _make_xapk(path: Path) -> Path:
    """A tiny but structurally real split bundle (base + 2 config splits
    + XAPK manifest.json) — the CAMSCAN-004 packaging shape."""
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("manifest.json", json.dumps({
            "split_apks": [
                {"id": "base", "file": "base.apk"},
                {"id": "arm64_v8a", "file": "config.arm64_v8a.apk"},
                {"id": "en", "file": "config.en.apk"},
            ]}))
        z.writestr("base.apk", b"FAKE-BASE-APK-BYTES")
        z.writestr("config.arm64_v8a.apk", b"FAKE-ARM64-SPLIT")
        z.writestr("config.en.apk", b"FAKE-EN-SPLIT")
    return path


def _make_driver(provider: ScriptedProvider,
                 apk: str | Path | None = None) \
        -> tuple[ReferenceDriver, FakeClock]:
    clock = FakeClock()
    driver = ReferenceDriver(apk=apk, provider=provider,
                             sleep=clock.sleep, monotonic=clock.monotonic,
                             wall_time=lambda: 0.0)  # probe-29 diag stamps
    return driver, clock                              # pinned 00:00:00


def _provision_request(lines: list[str], *, apk: str | Path | None = None,
                       subject: str = "reference",
                       scenario_id: str = "S001") -> ProvisionRequest:
    scenario = resolve_scenario(scenario_id, REPO_ROOT / "lab" / "scenarios")
    return ProvisionRequest(
        run_id=RUN_ID, subject=subject, scenario=scenario,
        provider_report={"slug": "e2b"},
        step_timeout_s=scenario.step_timeout_seconds,
        timeout_s=scenario.timeout_seconds,
        apk=Path(apk) if apk else None, emit=lines.append)


# ---------------------------------------------------------------- preflights

def test_provision_refuses_without_api_key(monkeypatch):
    monkeypatch.delenv("E2B_API_KEY", raising=False)
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    provider = ScriptedProvider()
    driver, _clock = _make_driver(provider, apk=None)
    with pytest.raises(LabCliError) as excinfo:
        driver.provision(_provision_request([]))
    assert "E2B_API_KEY is not set" in str(excinfo.value)
    assert "refuses to provision" in str(excinfo.value)
    # the refusal fires BEFORE anything is provisioned
    assert provider.ops == []


def test_provision_env_guard_refuses_implementation(monkeypatch):
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    provider = ScriptedProvider()
    driver, _clock = _make_driver(provider)
    with pytest.raises(LabCliError) as excinfo:
        driver.provision(_provision_request([], subject="implementation"))
    assert "env=reference" in str(excinfo.value)
    assert provider.ops == []


def test_provision_needs_xapk_source(monkeypatch):
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    provider = ScriptedProvider()
    driver, _clock = _make_driver(provider, apk=None)
    with pytest.raises(LabCliError) as excinfo:
        driver.provision(_provision_request([]))
    assert "--apk" in str(excinfo.value)
    assert "CAMSCAN_APK_URL" in str(excinfo.value)
    assert provider.ops == []


# --------------------------------------------------------------- recipe order

def test_provision_recipe_order_local_xapk(monkeypatch, tmp_path):
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    provider.on("cat /root/install.out", "Success\nEXIT_0\n")
    lines: list[str] = []
    driver, clock = _make_driver(provider, apk=xapk)
    handle = driver.provision(_provision_request(lines, apk=xapk))

    # the reference env's OWN profile (CAMSCAN-004: separate AVD,
    # google_apis image for ndk_translation, proven memory size)
    spec = provider.provisioned_specs[0]
    assert spec.purpose == "reference"
    assert spec.system_image == REFERENCE_SYSTEM_IMAGE
    assert spec.memory_mb == REFERENCE_MEMORY_MB

    # probe-17 order: package-service settle → dexopt setprop BEFORE
    # install-multiple → registry check after Success → dynamic
    # launcher resolution
    exec_log = provider.exec_log
    settle = next(i for i, c in enumerate(exec_log)
                  if "pm list packages" in c)
    setprop = next(i for i, c in enumerate(exec_log)
                   if "setprop pm.dexopt.install verify" in c)
    install = next(i for i, c in enumerate(exec_log)
                   if "install-multiple" in c)
    registry = next(i for i, c in enumerate(exec_log)
                    if "pm path com.intsig.camscanner" in c)
    launcher = next(i for i, c in enumerate(exec_log)
                    if "resolve-activity" in c)
    assert settle < setprop < install < registry < launcher

    # the splits were extracted + pushed (local fallback); install
    # targets SANDBOX-side paths (host-side paths, never device paths)
    pushes = [op for op in provider.ops if op[0] == "transfer"]
    assert [p[1] for p in pushes] == [
        "push:/root/base.apk",
        "push:/root/config.arm64_v8a.apk",
        "push:/root/config.en.apk",
    ]
    assert "/root/base.apk" in exec_log[install]
    assert "/root/config.en.apk" in exec_log[install]

    # handle facts
    assert handle.subject == "reference"
    assert handle.provider_slug == "e2b"
    assert handle.environment_id == "e2b-fake01"
    assert handle.application["package"] == "com.intsig.camscanner"
    assert handle.application["installer_sha256"] == _file_sha256(xapk)
    assert handle.device["model"] == "Pixel 4"
    assert handle.device["android_version"] == "11"
    assert any("dexopt filter set" in line for line in lines)
    assert any("package registered" in line for line in lines)
    assert any("launcher resolved dynamically" in line for line in lines)
    # probe-17 step 1 (+60 s boot settle) + probe-22 GMS restore settle
    # (+60 s after re-enabling gms/wellbeing/vending, before launch)
    assert clock.slept == [BOOT_SETTLE_S, BOOT_SETTLE_S]


# ---------------------------------------------------------------- acquisition

def test_provision_url_delivery_sha_verified(monkeypatch):
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.setenv("CAMSCAN_APK_URL",
                       "https://r2.example.com/presigned.xapk")
    provider = ScriptedProvider()
    provider.on("curl -fsSL",
                f"{RECORDED_XAPK_SHA256}  bundle.dl\n"
                "  inflating: base.apk\n"
                "  inflating: config.arm64_v8a.apk\n"
                "  inflating: config.en.apk\n"
                "-rw-r--r-- 1 root root 162000000 Sep 23 10:00 "
                "/root/xapk/base.apk\n"
                "-rw-r--r-- 1 root root 2000000 Sep 23 10:00 "
                "/root/xapk/config.arm64_v8a.apk\n"
                "-rw-r--r-- 1 root root 1000000 Sep 23 10:00 "
                "/root/xapk/config.en.apk\n")
    provider.on("cat /root/install.out", "Success\nEXIT_0\n")
    lines: list[str] = []
    driver, _clock = _make_driver(provider, apk=None)  # URL-only mode
    handle = driver.provision(_provision_request(lines))

    install = next(c for c in provider.exec_log
                   if "install-multiple" in c)
    assert "/root/xapk/base.apk" in install
    assert "/root/xapk/config.arm64_v8a.apk" in install
    assert "/root/xapk/config.en.apk" in install
    # URL-only: the expected hash is the recorded archive hash
    assert handle.application["installer_sha256"] == RECORDED_XAPK_SHA256
    assert any("delivered in-sandbox" in line for line in lines)
    # URL mode never pushes the bundle through the files API (the
    # known-stall delivery path is never taken)
    assert not any(op[0] == "transfer" for op in provider.ops)


def test_provision_url_sha_mismatch_clean_abort(monkeypatch):
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.setenv("CAMSCAN_APK_URL",
                       "https://r2.example.com/presigned.xapk")
    provider = ScriptedProvider()
    provider.on("curl -fsSL", f"{'0' * 64}  bundle.dl\n")
    driver, _clock = _make_driver(provider, apk=None)
    with pytest.raises(LabCliError) as excinfo:
        driver.provision(_provision_request([]))
    assert "sha256 mismatch" in str(excinfo.value)
    # clean abort: NO install attempt, the paid sandbox destroyed
    assert not any("install-multiple" in c for c in provider.exec_log)
    assert ("destroy", "e2b-fake01") in provider.ops


# -------------------------------------------------------------------- ladder

def test_install_ladder_fail_resettle_retry_success(monkeypatch, tmp_path):
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    # the GMS-churn lottery (probe-17 shape): attempt 1 dies on the
    # package-service flap; the service is DOWN for the first re-settle
    # probe, recovers on the second; attempt 2 succeeds
    provider.on("pm list packages",
                "package:com.android.cpb\n",      # boot settle
                "package:com.android.cpb\n",      # attempt-1 diagnostics
                "Can't find service: package\n",  # re-settle probe 1: down
                "package:com.android.cpb\n")      # re-settle probe 2: up
    provider.on("cat /root/install.out",
                "Can't find service: package\nEXIT_1\n",   # poll 1
                "Can't find service: package\nEXIT_1\n",   # full fetch 1
                "Success\nEXIT_0\n",                        # poll 2
                "Success\nEXIT_0\n")                        # full fetch 2
    lines: list[str] = []
    driver, clock = _make_driver(provider, apk=xapk)
    driver.provision(_provision_request(lines, apk=xapk))

    exec_log = provider.exec_log
    installs = [i for i, c in enumerate(exec_log)
                if "install-multiple" in c]
    assert len(installs) == 2
    # re-settle probes BETWEEN the failed attempt and the retry
    # (run-003 lesson: `pm list packages` must answer again first)
    between = exec_log[installs[0]:installs[1]]
    assert len([c for c in between if "pm list packages" in c]) >= 2
    # the documented wait pattern: boot settle → probe poll → backoff
    # → probe-22 GMS restore settle (after the retry succeeds)
    assert clock.slept == [BOOT_SETTLE_S, SERVICE_SETTLE_POLL_S,
                           RETRY_BACKOFF_S, BOOT_SETTLE_S]
    # poll + full fetch per attempt (the outcome window's two reads)
    assert len([c for c in exec_log
                if "cat /root/install.out" in c]) == 4
    assert any("package registered" in line for line in lines)


def test_install_ladder_sandbox_death_clean_abort(monkeypatch, tmp_path):
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    # attempt 1 fails; the re-settle probe reports the sandbox DEAD
    provider.on("pm list packages",
                "package:com.android.cpb\n",
                "package:com.android.cpb\n",
                CommandResult(1, "", "Exception: sandbox was not found",
                              5, "probe"))
    provider.on("cat /root/install.out",
                "Can't find service: package\nEXIT_1\n")
    driver, _clock = _make_driver(provider, apk=xapk)
    with pytest.raises(LabCliError) as excinfo:
        driver.provision(_provision_request([], apk=xapk))
    assert "sandbox death" in str(excinfo.value)
    # clean abort: exactly ONE install attempt (never hammer a corpse —
    # run-005 lesson b) and the paid sandbox is destroyed
    assert len([c for c in provider.exec_log
                if "install-multiple" in c]) == 1
    assert ("destroy", "e2b-fake01") in provider.ops


def test_install_ladder_exhausted_attempts_clean_abort(monkeypatch,
                                                        tmp_path):
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    provider.on("cat /root/install.out",
                "Can't find service: package\nEXIT_1\n")
    driver, _clock = _make_driver(provider, apk=xapk)
    with pytest.raises(LabCliError) as excinfo:
        driver.provision(_provision_request([], apk=xapk))
    assert f"after {INSTALL_MAX_ATTEMPTS} bounded attempts" \
        in str(excinfo.value)
    assert len([c for c in provider.exec_log
                if "install-multiple" in c]) == INSTALL_MAX_ATTEMPTS
    assert ("destroy", "e2b-fake01") in provider.ops


def test_install_outcome_window_timeout_kills_zombie(monkeypatch,
                                                      tmp_path):
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    # attempt 1's outcome file NEVER shows a verdict: the 6-min window
    # elapses, the window-edge registry check (probe-23) sees an EMPTY
    # registry (first scripted pm path), the zombie stream is killed,
    # attempt 2 succeeds (the post-Success registry verify then reads
    # the second scripted pm path)
    provider.on("cat /root/install.out",
                *([""] * 25), "Success\nEXIT_0\n", "Success\nEXIT_0\n")
    provider.on("pm path", "",
                "package:/data/app/~~xyz/com.intsig.camscanner-1/base.apk\n")
    lines: list[str] = []
    driver, _clock = _make_driver(provider, apk=xapk)
    driver.provision(_provision_request(lines, apk=xapk))
    exec_log = provider.exec_log
    assert any("pkill" in c for c in exec_log)
    assert len([c for c in exec_log if "install-multiple" in c]) == 2
    assert any("outcome-window timeout" in line for line in lines)


def test_install_probe23_edge_registry_saves_window(monkeypatch, tmp_path):
    """probe-23: under degraded TCG the commit lands while the adb
    stream is still running — the window-edge registry check must
    declare Success without a second attempt (observed live 2026-09-24:
    registry showed the package ~2 min before the window edge)."""
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    # attempt 1's outcome file NEVER shows an adb verdict — but the
    # commit DID land: the first pm path (the edge check) answers good
    provider.on("cat /root/install.out", *([""] * 25))
    provider.on("pm path",
                "package:/data/app/~~xyz/com.intsig.camscanner-1/base.apk\n")
    lines: list[str] = []
    driver, _clock = _make_driver(provider, apk=xapk)
    driver.provision(_provision_request(lines, apk=xapk))
    exec_log = provider.exec_log
    # saved at the edge: ONE install launch, zombie killed, no retry
    assert len([c for c in exec_log if "install-multiple" in c]) == 1
    assert any("pkill" in c for c in exec_log)
    assert any("Success (probe-23" in line for line in lines)
    assert not any("outcome-window timeout" in line for line in lines)
    # the caller's explicit registry verification still ran afterwards
    assert sum(1 for c in exec_log if "pm path com.intsig.camscanner" in c) >= 2


# ------------------------------------------------------------------- execute

def test_execute_launch_component_ladder_anr_dismissal_and_evidence(
        monkeypatch, tmp_path):
    """CAMSCAN-010A happy path: the launch step uses the resolved
    component (am start -W -n — the probe-26/27 ladder), NOT monkey;
    the ANR dismissal + evidence flow after a successful launch is
    unchanged."""
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    provider.on("cat /root/install.out", "Success\nEXIT_0\n")
    # the ANR ladder's ui dumps: round 1 shows the Wait button over
    # the splash, round 2 the dialog is gone (observe.py 6b shape)
    provider.ui_xmls = [
        ('<hierarchy rotation="0"><node text="Wait" '
         'bounds="[420,1180][660,1310]"/></hierarchy>'),
        '<hierarchy rotation="0"><node text="CamScanner"/></hierarchy>',
    ]
    driver, _clock = _make_driver(provider, apk=xapk)
    lines: list[str] = []
    handle = driver.provision(_provision_request(lines, apk=xapk))

    scenario = resolve_scenario("S001", REPO_ROOT / "lab" / "scenarios")
    plans = plan_steps(scenario.steps, None)
    stage = tmp_path / "stage"
    request = ExecutionRequest(
        handle=handle, run_id=RUN_ID, subject="reference",
        scenario=scenario, step_plans=plans, stage_dir=stage,
        fixtures=[], started_at="2026-09-24T00:00:00Z",
        finished_at="2026-09-24T00:05:00Z", emit=lines.append)
    result = driver.execute(handle, request)

    assert result.ok is True
    assert result.steps_executed == 1
    assert result.problems == []
    exec_log = provider.exec_log

    # probe-26: component launch PREFERRED over monkey — the ladder's
    # am start carries the provision-time resolved component, and no
    # monkey command ever runs
    am_start = next(c for c in exec_log if "am start -W -n" in c)
    assert ("am start -W -n com.intsig.camscanner/"
            "com.intsig.camscanner.launcher.MainActivity") in am_start
    assert not any("monkey" in c for c in exec_log)

    # probe-29: the dex2oat gate runs BEFORE the ladder's am start
    dex2oat = next(i for i, c in enumerate(exec_log)
                   if "grep -c dex2oat" in c)
    assert dex2oat < exec_log.index(am_start)

    # attempt 1 succeeds: one background launch + the outcome poll's
    # tail read + full fetch (no gate, no settle — the ladder breaks
    # on the am verdict)
    assert len([c for c in exec_log
                if "rm -f /root/launch.out" in c]) == 1
    assert len([c for c in exec_log
                if "cat /root/launch.out" in c]) == 2
    assert not any("am get-current-user" in c for c in exec_log)

    # the launch-step process poll ran between the am start and the
    # ANR dismissal (probe-17 step 6 order preserved)
    ops = provider.ops
    anr_dump = next(i for i, op in enumerate(ops)
                    if op[0] == "capture" and op[1] == "ui_hierarchy")
    tap_idx = next(i for i, op in enumerate(ops)
                   if op[0] == "interact" and op[1] == "tap:540,1245")
    assert ops.index(("execute", am_start)) < anr_dump < tap_idx
    assert any("launch via am start -W -n" in line for line in lines)
    assert any("launch attempt 1: am start ok (Status: ok)"
               in line for line in lines)
    assert any("ANR" in line for line in lines)
    assert any("app process up" in line for line in lines)

    # per-step evidence per EVIDENCE.md + the final logcat
    subject = stage / "reference"
    assert (subject / "screenshots" / "01-launch.png").read_bytes() == \
        provider.screenshot_bytes
    assert "<hierarchy" in (subject / "ui" / "01-launch.xml").read_text(
        encoding="utf-8")
    assert (subject / "logs" / "logcat.txt").read_text(
        encoding="utf-8") == "fake-reference-logcat"

    # single-subject metadata: deterministic window (empty clock),
    # dumpsys-discovered package facts, provider block from the handle
    doc = jsonio_load(stage / "run-metadata.json")
    assert doc["run_id"] == RUN_ID
    assert doc["scenario"] == "application-launch"
    assert doc["subject"] == "reference"
    assert doc["provider"]["slug"] == "e2b"
    assert doc["application"]["package"] == "com.intsig.camscanner"
    assert doc["application"]["version_name"] == "7.25.5.2609020000"
    assert doc["application"]["version_code"] == 2609020000
    assert doc["started_at"] == "2026-09-24T00:00:00Z"
    assert doc["finished_at"] == "2026-09-24T00:05:00Z"
    # a SUCCESSFUL launch keeps the deterministic trace shape (the
    # probe-29 diag timestamps ride only the failure forensics — the
    # work-order item 8 demand)
    assert doc["action_trace"] == [{"t_ms": 0, "action": "launch",
                                    "target": "", "result": "ok"}]
    assert doc["fixtures"] == []


def test_execute_launch_ladder_retry_gate_settle(monkeypatch, tmp_path):
    """probe-27 state machine: attempt 1 dies on the launch-binder
    broken pipe (the exact failure class observe.py diagnosed); the
    activity-service gate answers, the gap-cadence settle runs, and
    attempt 2's am verdict wins — monkey never involved."""
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    provider.on("cat /root/install.out", "Success\nEXIT_0\n")
    broken = ("cmd: Failure calling service activity: Broken pipe (32)\n"
              "EXIT_1\n")
    landed = ("Starting: Intent...\n"
              "Status: ok\n"
              "Activity: com.intsig.camscanner/"
              ".mainmenu.mainactivity.MainActivity\n"
              "TotalTime: 1200\n"
              "EXIT_0\n")
    # tail read + full fetch per attempt, in call order
    provider.on("cat /root/launch.out", broken, broken, landed, landed)
    driver, clock = _make_driver(provider, apk=xapk)
    lines: list[str] = []
    handle = driver.provision(_provision_request(lines, apk=xapk))

    scenario = resolve_scenario("S001", REPO_ROOT / "lab" / "scenarios")
    plans = plan_steps(scenario.steps, None)
    stage = tmp_path / "stage"
    request = ExecutionRequest(
        handle=handle, run_id=RUN_ID, subject="reference",
        scenario=scenario, step_plans=plans, stage_dir=stage,
        fixtures=[], started_at="2026-09-24T00:00:00Z",
        finished_at="2026-09-24T00:05:00Z", emit=lines.append)
    result = driver.execute(handle, request)

    assert result.ok is True
    exec_log = provider.exec_log
    # exactly two bounded attempts
    launches = [i for i, c in enumerate(exec_log)
                if "rm -f /root/launch.out" in c]
    assert len(launches) == 2
    # attempt/gate/settle ordering: poll+full fetch, then the
    # activity-service gate probe, then the settle sleep, then the
    # next attempt's launcher
    gate = next(i for i, c in enumerate(exec_log)
                if "am get-current-user" in c)
    assert launches[0] < gate < launches[1]
    poll1 = next(i for i, c in enumerate(exec_log)
                 if "cat /root/launch.out" in c)
    assert launches[0] < poll1 < gate
    # one outcome-poll sleep per attempt (the EXIT marker was seen on
    # the first read) + the probe-29 gap-cadence settle between them
    assert clock.slept.count(OUTCOME_POLL_S) == 2
    assert clock.slept.count(LAUNCH_SETTLE_S) == 1
    # per-attempt verdicts surfaced on the console
    assert any("launch attempt 1: failed (exit 1)" in line
               and "Broken pipe" in line for line in lines)
    assert any("launch attempt 2: am start ok (Status: ok)"
               in line for line in lines)
    assert not any("monkey" in c for c in exec_log)


def test_execute_launch_wrapper_transient_does_not_abort(
        monkeypatch, tmp_path):
    """probe-28: a single in-band wrapper transient (the SDK's
    request_timeout text arriving instead of the LAUNCHED echo) must
    NOT abort the ladder — the poll finds launch.out (the wrapper DID
    run server-side) and attempt 1 still wins."""
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    provider.on("cat /root/install.out", "Success\nEXIT_0\n")
    # the launcher's echo is replaced by in-band timeout text
    provider.on("rm -f /root/launch.out",
                "Error: request_timeout after 60000 ms\n")
    driver, _clock = _make_driver(provider, apk=xapk)
    lines: list[str] = []
    handle = driver.provision(_provision_request(lines, apk=xapk))

    scenario = resolve_scenario("S001", REPO_ROOT / "lab" / "scenarios")
    plans = plan_steps(scenario.steps, None)
    stage = tmp_path / "stage"
    request = ExecutionRequest(
        handle=handle, run_id=RUN_ID, subject="reference",
        scenario=scenario, step_plans=plans, stage_dir=stage,
        fixtures=[], started_at="2026-09-24T00:00:00Z",
        finished_at="2026-09-24T00:05:00Z", emit=lines.append)
    result = driver.execute(handle, request)

    # the transient did NOT burn an attempt: one background launch,
    # attempt 1's am verdict wins, the step is ok
    exec_log = provider.exec_log
    assert len([c for c in exec_log
                if "rm -f /root/launch.out" in c]) == 1
    assert result.ok is True
    assert any("launch attempt 1: am start ok (Status: ok)"
               in line for line in lines)


def test_execute_launch_am_confirmed_outranks_blind_ps(monkeypatch,
                                                       tmp_path):
    """probe-30 + probe-31: am start -W's own 'Status: ok' +
    'Activity: <pkg>' output is AUTHORITATIVE — every ps read coming
    back transport-blind (exit -1) must NOT veto it, and the poll is
    capped at ~2 min instead of burning the full 6.7-min budget."""
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    provider.on("cat /root/install.out", "Success\nEXIT_0\n")
    # every app-ps read is transport-blind (probe-30's exact failure
    # shape: exit -1, in-band timeout text — never an absence
    # observation)
    provider.on("ps -A | grep com.intsig.camscanner",
                CommandResult(-1, "", "Error: request_timeout", 5, "ps"))
    driver, _clock = _make_driver(provider, apk=xapk)
    lines: list[str] = []
    handle = driver.provision(_provision_request(lines, apk=xapk))

    scenario = resolve_scenario("S001", REPO_ROOT / "lab" / "scenarios")
    plans = plan_steps(scenario.steps, None)
    stage = tmp_path / "stage"
    request = ExecutionRequest(
        handle=handle, run_id=RUN_ID, subject="reference",
        scenario=scenario, step_plans=plans, stage_dir=stage,
        fixtures=[], started_at="2026-09-24T00:00:00Z",
        finished_at="2026-09-24T00:05:00Z", emit=lines.append)
    result = driver.execute(handle, request)

    assert result.ok is True
    assert result.problems == []
    # the AMS overruled the blind reads (probe-30)
    assert any("am-confirmed" in line and "blind ps overruled"
               in line for line in lines)
    # probe-31: the launch-step poll stopped at the ~2-min cap
    # (12 reads); the unchanged _post_launch settle adds its own
    # full 40-read patient wait — 52 total, not 80
    ps_reads = len([c for c in provider.exec_log
                    if "grep com.intsig.camscanner" in c])
    assert ps_reads == LAUNCH_PROC_POLL_CAP_CONFIRMED + PROCESS_WAIT_ROUNDS


def test_execute_launch_brought_to_front_counts_as_up(monkeypatch,
                                                       tmp_path):
    """probe-27: 'Warning: Activity not started, its current task has
    been brought to the front' means the task EXISTS — one attempt, no
    retry, the patient poll confirms the process."""
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    provider.on("cat /root/install.out", "Success\nEXIT_0\n")
    provider.on("cat /root/launch.out",
                "Warning: Activity not started, its current task has "
                "been brought to the front\nEXIT_0\n")
    driver, _clock = _make_driver(provider, apk=xapk)
    lines: list[str] = []
    handle = driver.provision(_provision_request(lines, apk=xapk))

    scenario = resolve_scenario("S001", REPO_ROOT / "lab" / "scenarios")
    plans = plan_steps(scenario.steps, None)
    stage = tmp_path / "stage"
    request = ExecutionRequest(
        handle=handle, run_id=RUN_ID, subject="reference",
        scenario=scenario, step_plans=plans, stage_dir=stage,
        fixtures=[], started_at="2026-09-24T00:00:00Z",
        finished_at="2026-09-24T00:05:00Z", emit=lines.append)
    result = driver.execute(handle, request)

    exec_log = provider.exec_log
    assert len([c for c in exec_log
                if "rm -f /root/launch.out" in c]) == 1
    assert result.ok is True
    assert any("task already fronted (up)" in line for line in lines)


def test_execute_launch_window_timeout_kills_zombie(monkeypatch,
                                                     tmp_path):
    """probe-27: the outcome window elapses with no EXIT marker — the
    blocked am start zombie is killed (a hang costs one 3-min window,
    not 8 min), the gate + settle back off, attempt 2 lands."""
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    provider.on("cat /root/install.out", "Success\nEXIT_0\n")
    landed = ("Status: ok\n"
              "Activity: com.intsig.camscanner/"
              ".mainmenu.mainactivity.MainActivity\n"
              "TotalTime: 1200\n"
              "EXIT_0\n")
    # attempt 1's outcome file NEVER shows a marker (the 3-min window
    # elapses at 9 polls); attempt 2's reads see the verdict
    provider.on("cat /root/launch.out", *([""] * 9), landed, landed)
    driver, clock = _make_driver(provider, apk=xapk)
    lines: list[str] = []
    handle = driver.provision(_provision_request(lines, apk=xapk))

    scenario = resolve_scenario("S001", REPO_ROOT / "lab" / "scenarios")
    plans = plan_steps(scenario.steps, None)
    stage = tmp_path / "stage"
    request = ExecutionRequest(
        handle=handle, run_id=RUN_ID, subject="reference",
        scenario=scenario, step_plans=plans, stage_dir=stage,
        fixtures=[], started_at="2026-09-24T00:00:00Z",
        finished_at="2026-09-24T00:05:00Z", emit=lines.append)
    result = driver.execute(handle, request)

    exec_log = provider.exec_log
    assert any("pkill -f 'am start'" in c for c in exec_log)
    assert len([c for c in exec_log
                if "rm -f /root/launch.out" in c]) == 2
    assert result.ok is True
    assert any("outcome-window timeout" in line for line in lines)
    # 9 window polls burned on attempt 1 + 1 verdict poll on attempt 2
    assert clock.slept.count(OUTCOME_POLL_S) == 10


def test_execute_dex2oat_gate_bounded_then_proceeds(monkeypatch,
                                                    tmp_path):
    """probe-29: the dex2oat quiescence gate is bounded — a dexopt
    that never quiesces costs the ~4-min budget (24 polls x 10 s),
    then the ladder proceeds anyway (the gate is best-effort, never a
    verdict)."""
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    provider.on("cat /root/install.out", "Success\nEXIT_0\n")
    # dex2oat never quiesces (a nonzero count, repeating)
    provider.on("grep -c dex2oat", "3\n")
    driver, clock = _make_driver(provider, apk=xapk)
    lines: list[str] = []
    handle = driver.provision(_provision_request(lines, apk=xapk))

    scenario = resolve_scenario("S001", REPO_ROOT / "lab" / "scenarios")
    plans = plan_steps(scenario.steps, None)
    stage = tmp_path / "stage"
    request = ExecutionRequest(
        handle=handle, run_id=RUN_ID, subject="reference",
        scenario=scenario, step_plans=plans, stage_dir=stage,
        fixtures=[], started_at="2026-09-24T00:00:00Z",
        finished_at="2026-09-24T00:05:00Z", emit=lines.append)
    result = driver.execute(handle, request)

    exec_log = provider.exec_log
    assert len([c for c in exec_log
                if "grep -c dex2oat" in c]) == DEX2OAT_GATE_MAX_POLLS
    # the gate's 24 polls sit directly after the two provision settles
    # (the launch-step poll + the post-launch settle add their own 10 s
    # sleeps afterwards — same value, different machinery)
    settles = [BOOT_SETTLE_S, BOOT_SETTLE_S]
    gate_sleeps = clock.slept[len(settles):len(settles)
                              + DEX2OAT_GATE_MAX_POLLS]
    assert gate_sleeps == [DEX2OAT_GATE_POLL_S] * DEX2OAT_GATE_MAX_POLLS
    assert any("dex2oat still running after 4 min — proceeding anyway"
               in line for line in lines)
    # the ladder still ran and the launch still succeeded
    assert any("rm -f /root/launch.out" in c for c in exec_log)
    assert result.ok is True


def test_execute_launch_no_component_monkey_fallback(monkeypatch,
                                                      tmp_path):
    """probe-26: monkey remains ONLY the no-component fallback — with
    no resolvable launcher component the bridge's monkey form (its
    existing semantics) runs, the ladder does not, and the patient
    poll decides the verdict."""
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    provider.on("cat /root/install.out", "Success\nEXIT_0\n")
    # resolution stays empty at provision time AND at the launch-step
    # re-resolution
    provider.on("resolve-activity", "\n")
    driver, _clock = _make_driver(provider, apk=xapk)
    lines: list[str] = []
    handle = driver.provision(_provision_request(lines, apk=xapk))
    assert handle.native.launcher_component == ""

    scenario = resolve_scenario("S001", REPO_ROOT / "lab" / "scenarios")
    plans = plan_steps(scenario.steps, None)
    stage = tmp_path / "stage"
    request = ExecutionRequest(
        handle=handle, run_id=RUN_ID, subject="reference",
        scenario=scenario, step_plans=plans, stage_dir=stage,
        fixtures=[], started_at="2026-09-24T00:00:00Z",
        finished_at="2026-09-24T00:05:00Z", emit=lines.append)
    result = driver.execute(handle, request)

    exec_log = provider.exec_log
    assert any("monkey" in c for c in exec_log)
    # no ladder: no am start script, no marker file, no outcome polls
    assert not any("am start -W -n" in c for c in exec_log)
    assert not any("/root/launch.out" in c for c in exec_log)
    # the launch-step re-resolution ran before the monkey fallback
    resolves = [i for i, c in enumerate(exec_log)
                if "resolve-activity" in c]
    monkey_at = next(i for i, c in enumerate(exec_log) if "monkey" in c)
    assert resolves and resolves[-1] < monkey_at
    assert any("monkey fallback" in line for line in lines)
    assert result.ok is True
    doc = jsonio_load(stage / "run-metadata.json")
    assert doc["action_trace"] == [{"t_ms": 0, "action": "launch",
                                    "target": "", "result": "ok"}]


def test_execute_records_launch_failure_with_forensics(monkeypatch,
                                                         tmp_path):
    """CAMSCAN-010A item 8 — the attempt-1 disease was 'step 01 launch
    failed' with nothing else: ladder exhaustion (all 4 attempts die on
    the PM-blind 'does not exist' error, blind-evidence captured per
    probe-29) + transport-blind ps reads must surface the timestamped
    ladder diag, am output tails, exit codes and the probe-25 death
    forensics in problems/reason AND the run-metadata action trace —
    recorded, not raised."""
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    provider.on("cat /root/install.out", "Success\nEXIT_0\n")
    # every am start comes back with the am tool's OWN PackageManager
    # blind error (probe-29's exact observation: resolve-activity had
    # answered minutes earlier — the PM endpoint flaps)
    provider.on("cat /root/launch.out",
                "Error type 3: Activity class "
                "{com.intsig.camscanner/.mainmenu.mainactivity."
                "MainActivity} does not exist\nEXIT_1\n")
    # every app-ps read is transport-blind (never an absence
    # observation — and with no am confirmation, the verdict is FAIL)
    provider.on("ps -A | grep com.intsig.camscanner",
                CommandResult(-1, "", "Error: request_timeout", 5, "ps"))
    driver, clock = _make_driver(provider, apk=xapk)
    lines: list[str] = []
    handle = driver.provision(_provision_request(lines, apk=xapk))

    scenario = resolve_scenario("S001", REPO_ROOT / "lab" / "scenarios")
    plans = plan_steps(scenario.steps, None)
    stage = tmp_path / "stage"
    request = ExecutionRequest(
        handle=handle, run_id=RUN_ID, subject="reference",
        scenario=scenario, step_plans=plans, stage_dir=stage,
        fixtures=[], started_at="2026-09-24T00:00:00Z",
        finished_at="2026-09-24T00:05:00Z", emit=lines.append)
    result = driver.execute(handle, request)

    # device-side failures are recorded, not raised
    assert result.ok is False
    assert result.steps_executed == 1
    exec_log = provider.exec_log

    # the ladder burned all 4 bounded attempts, each separated by the
    # activity-service gate + the gap-cadence settle
    assert len([c for c in exec_log
                if "rm -f /root/launch.out" in c]) == LAUNCH_MAX_ATTEMPTS
    assert len([c for c in exec_log
                if "am get-current-user" in c]) == LAUNCH_MAX_ATTEMPTS
    assert clock.slept.count(LAUNCH_SETTLE_S) == LAUNCH_MAX_ATTEMPTS
    # probe-29: the PM-blind error triggered the blind-evidence
    # capture on every attempt
    assert len([c for c in exec_log
                if "pm path com.intsig.camscanner" in c
                and "head -2" in c]) == LAUNCH_MAX_ATTEMPTS
    assert len([c for c in exec_log if "mainactivity" in c]) \
        == LAUNCH_MAX_ATTEMPTS
    # probe-25: the death-forensics bundle ran (canary / system ps /
    # logcat tail)
    assert any("__canary_ok__" in c for c in exec_log)
    assert any("ps -A | head -3" in c for c in exec_log)
    assert any("logcat -d -t 200" in c for c in exec_log)

    # the forensics reach the evidence layer: problems + reason
    assert result.problems[0] == "step 01 launch failed"
    assert len(result.problems) == 2
    diagnostics = result.problems[1]
    assert diagnostics.startswith(
        "step 01 launch ladder diagnostics (")
    assert "[attempt 1]" in diagnostics
    assert "[attempt 4]" in diagnostics
    assert "exit=1" in diagnostics
    assert "does not exist" in diagnostics
    assert "death-forensics" in diagnostics
    assert "activity-service gate: up" in diagnostics
    assert "launch ladder diagnostics" in result.reason

    # AND the run-metadata action trace — the next postmortem is a
    # read, not an inference: every diag line carries the probe-29
    # HH:MM:SS timestamp prefix (pinned 00:00:00 by the injected wall
    # clock)
    doc = jsonio_load(stage / "run-metadata.json")
    entry = doc["action_trace"][0]
    assert entry["result"] == "failed"
    diag = entry["diag"]
    assert diag and all(re.match(r"^\d{2}:\d{2}:\d{2} ", line)
                        for line in diag)
    assert diag[0].startswith("00:00:00 ")
    assert any("[attempt 1]" in line and "exit=1" in line
               for line in diag)
    assert any("blind-evidence" in line for line in diag)
    assert any("death-forensics" in line for line in diag)
    assert any("launch failed — ladder + death forensics recorded"
               in line for line in lines)


# ------------------------------------------------------------------ teardown

def test_teardown_never_raises():
    provider = ScriptedProvider()
    provider.stop_raises = True
    provider.destroy_raises = True
    driver, _clock = _make_driver(provider)
    handle = DriverHandle(
        subject="reference", provider_slug="e2b",
        environment_id="e2b-fake01",
        native=_Native(provider, "e2b-fake01", "adb", ""))
    lines: list[str] = []
    # best-effort on EVERY path — must not raise even though both
    # stop and destroy do
    driver.teardown(handle, error="simulated", emit=lines.append)
    assert ("stop", "e2b-fake01") in provider.ops
    assert ("destroy", "e2b-fake01") in provider.ops
    assert any("stop failed" in line for line in lines)
    assert any("destroy failed" in line for line in lines)
    assert any("destroyed e2b-fake01" in line for line in lines)


# -------------------------------------------------------------- registry flip

def test_registry_reference_flip(monkeypatch):
    from tools.lab_cli.e2b_live import E2bLiveDriver

    # AFTER wiring (CAMSCAN-009): env=reference resolves the
    # ReferenceDriver under both on-record provider slugs
    driver, reason = driver_registry.resolve_driver(
        "reference", "e2b", "live")
    assert isinstance(driver, ReferenceDriver)
    assert reason == ""
    assert driver_registry.live_driver_reason("reference", "e2b") is None
    driver2, reason2 = driver_registry.resolve_driver(
        "reference", "e2b-reference", "live")
    assert isinstance(driver2, ReferenceDriver)
    assert reason2 == ""

    # the implementation env's driver is untouched (extend, never break)
    impl, impl_reason = driver_registry.resolve_driver(
        "implementation", "e2b", "live")
    assert isinstance(impl, E2bLiveDriver)
    assert impl_reason == ""

    # BEFORE wiring (the pre-009 registry state): the honest NO-OP
    monkeypatch.setattr(driver_registry, "_LIVE_DRIVER_FACTORIES", {})
    before, before_reason = driver_registry.resolve_driver(
        "reference", "e2b", "live")
    assert before is None
    assert before_reason.startswith(
        "no live driver on record for env=reference on provider e2b")


# ------------------------------------------------------------------ scheduler

def test_scheduler_pool_slug_ascending_and_s004_honesty():
    from lab.providers.scheduler import pair_compatible, select_provider

    reports = load_provider_reports(REPO_ROOT)
    assert [r["slug"] for r in reports] == ["e2b", "e2b-reference"]
    s001 = resolve_scenario("S001", REPO_ROOT / "lab" / "scenarios")
    chosen, _reasons = select_provider(s001.requires, reports)
    assert chosen["slug"] == "e2b"  # base record first — pair stability

    s004 = resolve_scenario("S004", REPO_ROOT / "lab" / "scenarios")
    chosen4, reasons4 = select_provider(s004.requires, reports)
    assert chosen4 is None
    for slug in ("e2b", "e2b-reference"):
        assert ("camera_fixture: requirement True not satisfied by "
                "False") in "; ".join(reasons4[slug])

    # the env-class record is pair-compatible on every capability the
    # scenario names (equivalent device profile — CAMSCAN-004)
    ok, mismatches = pair_compatible(reports[0], reports[1],
                                     s001.requires)
    assert ok, mismatches


# ----------------------------------------------------------------- CLI plans

@pytest.mark.parametrize("stem, sid, steps", [
    ("S001", "application-launch", 1),
    ("S002", "onboarding", 2),
    ("S003", "camera-permission", 3),
])
def test_plan_cli_reference_resolves(stem, sid, steps):
    proc = run_cli(["run", stem, "--plan"])
    assert proc.returncode == 0, proc.stdout + proc.stderr
    assert (f"planned: {stem} {sid} env=reference provider=e2b "
            f"driver=reference-live app=com.intsig.camscanner "
            f"steps={steps}") in proc.stdout
    assert (f"planned: {stem} {sid} env=implementation provider=e2b "
            f"driver=e2b-live app=org.payswap.camscan "
            f"steps={steps}") in proc.stdout
    assert "NO-OP" not in proc.stdout


def test_plan_cli_s004_honest_noop():
    proc = run_cli(["run", "S004", "--plan"])
    assert proc.returncode == 0, proc.stdout + proc.stderr
    reason = ("no capable provider on record: e2b: camera_fixture: "
              "requirement True not satisfied by False; e2b-reference: "
              "camera_fixture: requirement True not satisfied by False")
    for env in ("reference", "implementation"):
        assert (f"planned: S004 single-document-capture env={env} "
                f"provider=none NO-OP ({reason})") in proc.stdout
    assert "driver=reference-live" not in proc.stdout
    assert "driver=e2b-live" not in proc.stdout
