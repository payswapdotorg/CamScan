"""The reference live driver's hermetic contract tests (CAMSCAN-009,
extended by CAMSCAN-010A — the launch-step port, and CAMSCAN-010B —
the run-metadata gaps: device.screen fallback + package-facts
blindness tolerance, and CAMSCAN-010C — the early package-facts
stash: install-time ground truth, and CAMSCAN-010D — the E2B Hobby
total-lifetime budget governor: 3600 s hard cap, and CAMSCAN-010E —
the package-facts dumpsys adb-shell command prefix).

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
- CAMSCAN-010B run-metadata gaps: (a) an empty-report identity block
  falls back to the capability-record-documented pixel_4 geometry and
  the staged run-metadata passes the REAL evidence-cli bundle
  pipeline (the exact stage that rejected the finished
  20260925T091135Z live run on device.screen ''); (b) a blind-then-
  answering package-facts read lands version_name through the
  probe-33 bounded retry (timestamped blind-read diagnostics); (c)
  all-blind package facts fail the run honestly with a readable
  reason naming every blind read (never an empty placeholder into
  the bundle validator); (d) a non-empty report resolution is used
  verbatim — the pinned geometry never overrides a report that
  answered;
- CAMSCAN-010C early package-facts stash (install-time ground truth):
  (a) the early read runs at provision, right after the registry
  verify and BEFORE the GMS restore, stashes the landed facts on the
  handle (native.install_time_facts) and execute() consumes them —
  no late dumpsys read, the manifest carries the stashed facts
  through the REAL bundle pipeline, the provenance line names the
  source; (b) an all-blind early read NEVER fails provision (the
  stash rides the handle empty with its 8 timestamped diag lines) and
  the late read lands the facts (the 010B fallback, unchanged); (c)
  both stages blind → the honest failure names BOTH diag sets
  (phase-labeled, timestamped, combined counts); (d) the 010B
  blind-then-answering retry contract keeps passing unchanged — the
  bounded retry now runs at install time and the stash serves
  execute() (the late-read-only path is the fallback);
- CAMSCAN-010D total-lifetime budget governor (E2B Hobby: 3600 s hard
  cap, E2B_TOTAL_LIFETIME_CAP_S): _Native.sandbox_t0 is captured in
  provision() immediately BEFORE provider.provision (ordering pinned);
  the post-launch process wait and the launch-step process poll (BOTH
  the am-confirmed and the unconfirmed path) cut early when the
  remaining budget drops below LAUNCH_PROC_BUDGET_RESERVE_S —
  timestamped cut lines, the ANR ladder still running after the cut,
  the unconfirmed cut feeding the probe-25 death-forensics /
  honest-fail path; a package-facts read carrying the sandbox-death
  signature ("sandbox timeout" — the exact 20260925T110418Z
  '.set_timeout'-advice rejection) aborts the retries at once (never
  hammer a corpse) and the honest-fail reason names the expiry with
  age context; a SIGINT campaign stop during provision destroys the
  paid sandbox and re-raises; young sandboxes keep their FULL waits
  (no cut when the budget is plentiful);
- CAMSCAN-010E dumpsys adb-shell prefix (the 2026-09-25 16:34 UTC
  live-run root cause, sandbox e2b-5822073f): every package-facts
  read command on exec_log carries the ``{adb} shell `` prefix — the
  EXACT command shape is pinned (adb path + ' shell dumpsys
  package ' + the grep -m1 marker), at BOTH read stages
  (install-time through provision's ADB local, late through
  native.adb), and NO bare 'dumpsys package' form ever executes —
  provider.execute runs the sandbox's LINUX bash and a bare
  Linux-side dumpsys is deterministic '/bin/bash: line 1: dumpsys:
  command not found' (16 identical failures in the campaign run),
  invisible to the substring-matching ScriptedProvider needles —
  which is exactly why the shape is pinned, not the substring;
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
from tools.evidence_cli.schema import SCREEN_RE, validate_manifest
from tools.lab_cli import drivers as driver_registry
from tools.lab_cli.drivers import (
    DriverHandle,
    ExecutionRequest,
    ProvisionRequest,
)
from tools.lab_cli.evidence import bundle_stage, scenario_copy_for_stage
from tools.lab_cli.reference_live import (
    BOOT_SETTLE_S,
    DEX2OAT_GATE_MAX_POLLS,
    DEX2OAT_GATE_POLL_S,
    E2B_TOTAL_LIFETIME_CAP_S,
    INSTALL_MAX_ATTEMPTS,
    LAUNCH_MAX_ATTEMPTS,
    LAUNCH_PROC_BUDGET_RESERVE_S,
    LAUNCH_PROC_POLL_CAP_CONFIRMED,
    LAUNCH_SETTLE_S,
    OUTCOME_POLL_S,
    PKG_FACTS_MAX_ATTEMPTS,
    PKG_FACTS_SETTLE_S,
    PROCESS_WAIT_ROUNDS,
    PROCESS_WAIT_S,
    RECORDED_XAPK_SHA256,
    REFERENCE_FALLBACK_ANDROID_VERSION,
    REFERENCE_FALLBACK_DENSITY_DPI,
    REFERENCE_FALLBACK_SCREEN,
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

# CAMSCAN-010E: the adb path the driver's facts machinery must prefix
# (the baked TCG bootstrap recipe's ADB — stdlib-only, SDK-free import:
# the exact local provision() threads into _package_facts and _Native
# carries for the late read; the same value every other ``{adb} shell``
# command in the driver uses).
from lab.providers.e2b.bootstrap import ADB
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
                       scenario_id: str = "S001",
                       provider_report: dict[str, Any] | None = None,
                       ) -> ProvisionRequest:
    scenario = resolve_scenario(scenario_id, REPO_ROOT / "lab" / "scenarios")
    return ProvisionRequest(
        run_id=RUN_ID, subject=subject, scenario=scenario,
        provider_report=provider_report or {"slug": "e2b"},
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


# ------------------------------------------ run-metadata gaps (CAMSCAN-010B)

class _EmptyIdentityReportProvider(ScriptedProvider):
    """The 20260925T091135Z-S001-live report shape (CAMSCAN-010B root
    cause 1): every identity probe — model, android version, resolution,
    density — came back empty, so the pre-010B code shipped device.screen
    '' (and would ship model/android_version '' the same way) into the
    evidence-cli bundle."""

    def report(self, env_id: str) -> dict[str, Any]:
        self.ops.append(("report", env_id))
        return {"ok": True, "android_version": "", "device_model": "",
                "resolution": "", "density": "", "locale": "",
                "timezone": "", "avd_name": "camscan-reference"}


class _AltGeometryReportProvider(ScriptedProvider):
    """A report that ANSWERED — with an identity deliberately different
    from the pinned pixel_4 profile (the 1080x2400@420dpi geometry the
    parity-cli fixture tests use): the 010B fallback must never override
    a report that answered (work-order constraint d)."""

    def report(self, env_id: str) -> dict[str, Any]:
        self.ops.append(("report", env_id))
        return {"ok": True, "android_version": "12",
                "device_model": "Pixel 6", "resolution": "1080x2400",
                "density": "420", "locale": "en-GB", "timezone": "UTC",
                "avd_name": "camscan-reference"}


class _AltGeometryNoDensityProvider(_AltGeometryReportProvider):
    """Same answered geometry, density probe empty — the pinned pixel_4
    dpi fills ONLY the hole; the report's WxH still wins."""

    def report(self, env_id: str) -> dict[str, Any]:
        doc = super().report(env_id)
        doc["density"] = ""
        return doc


def test_provision_empty_report_identity_falls_back_and_bundles(
        monkeypatch, tmp_path):
    """CAMSCAN-010B (a) — the deterministic hole: an empty-report
    resolution falls back to the capability-record-documented pixel_4
    geometry (REFERENCE_FALLBACK_SCREEN — lab/providers/e2b-reference/
    capability-report.json notes.device_profile) and the staged
    run-metadata passes the REAL evidence-cli bundle pipeline, the exact
    stage that rejected the finished 20260925T091135Z live run on
    ``device.screen: must not be empty`` + ``must match WxH@dpi``."""
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = _EmptyIdentityReportProvider()
    provider.on("cat /root/install.out", "Success\nEXIT_0\n")
    lines: list[str] = []
    driver, _clock = _make_driver(provider, apk=xapk)
    # the manifest's provider block mirrors the REAL on-record e2b
    # capability report (as run.py passes it — the bundle validator
    # requires a non-empty capabilities mapping)
    e2b_report = next(r for r in load_provider_reports(REPO_ROOT)
                      if r["slug"] == "e2b")
    # the stage dir is NAMED like the run (bundle_run requires
    # run_dir.name == run_id — the same shape run.py stages)
    stage = tmp_path / RUN_ID
    handle = driver.provision(_provision_request(lines, apk=xapk,
                                                 provider_report=e2b_report))

    # the fallback chain: report (empty) → provisioned spec → the
    # capability-record-documented pixel_4 profile — never an empty
    # string into the manifest
    assert handle.device["screen"] == REFERENCE_FALLBACK_SCREEN
    assert SCREEN_RE.match(handle.device["screen"])   # WxH@dpi shape
    assert handle.device["android_version"] \
        == REFERENCE_FALLBACK_ANDROID_VERSION
    assert handle.device["model"] == "pixel_4"   # the spec rung
    assert handle.device["locale"] == "en-US"    # the spec rung
    assert handle.device["timezone"] == "UTC"    # the spec rung

    scenario = resolve_scenario("S001", REPO_ROOT / "lab" / "scenarios")
    plans = plan_steps(scenario.steps, None)
    request = ExecutionRequest(
        handle=handle, run_id=RUN_ID, subject="reference",
        scenario=scenario, step_plans=plans, stage_dir=stage,
        fixtures=[], started_at="2026-09-24T00:00:00Z",
        finished_at="2026-09-24T00:05:00Z", emit=lines.append)
    result = driver.execute(handle, request)
    assert result.ok is True, result.reason

    doc = jsonio_load(stage / "run-metadata.json")
    assert doc["device"]["screen"] == REFERENCE_FALLBACK_SCREEN

    # the REAL bundle pipeline (scenario copy → bundle_run → schema
    # validation): the exact stage that died live must pass with the
    # fallback in place
    scenario_copy_for_stage(stage, scenario.file)
    n_artifacts = bundle_stage(stage, REPO_ROOT)
    assert n_artifacts == 3          # screenshot + ui dump + logcat
    manifest = jsonio_load(stage / "manifest.json")
    assert manifest["device"]["screen"] == REFERENCE_FALLBACK_SCREEN
    assert manifest["application"]["version_name"] == "7.25.5.2609020000"
    assert validate_manifest(manifest) == []


def test_provision_report_resolution_used_verbatim_no_override(
        monkeypatch, tmp_path):
    """CAMSCAN-010B (d) — a non-empty report resolution is used
    verbatim (no override): the report's own geometry composes through
    unchanged (WxH@dpi from its own resolution + density probes), and
    when only the density probe came back empty the pinned pixel_4 dpi
    fills just the hole — the report's WxH still wins."""
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")

    # both probes answered: the report's identity passes through whole
    provider = _AltGeometryReportProvider()
    provider.on("cat /root/install.out", "Success\nEXIT_0\n")
    driver, _clock = _make_driver(provider, apk=xapk)
    handle = driver.provision(_provision_request([], apk=xapk))
    assert handle.device["screen"] == "1080x2400@420dpi"
    assert handle.device["model"] == "Pixel 6"
    assert handle.device["android_version"] == "12"
    assert handle.device["locale"] == "en-GB"

    # density probe empty: pinned dpi, report WxH verbatim
    provider2 = _AltGeometryNoDensityProvider()
    provider2.on("cat /root/install.out", "Success\nEXIT_0\n")
    driver2, _clock2 = _make_driver(provider2, apk=xapk)
    handle2 = driver2.provision(_provision_request([], apk=xapk))
    assert handle2.device["screen"] \
        == f"1080x2400@{REFERENCE_FALLBACK_DENSITY_DPI}dpi"


def test_execute_package_facts_blind_then_answering_lands(monkeypatch,
                                                           tmp_path):
    """CAMSCAN-010B (b) — the probe-33 lottery fix: the first
    versionName read comes back transport-blind (the exact post-restore
    flap class: exit -1, in-band timeout text — never an absence
    observation), the bounded retry settles and re-reads, and
    version_name LANDS; the run succeeds with the discovered facts.
    (CAMSCAN-010C: the same bounded retry now runs at INSTALL time —
    the stash lands after the blind first read and execute() consumes
    it with no late read; the pinned retry cadence — re-read ONLY the
    blind fact, one settle — is the unchanged 010B contract either
    way.)"""
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    provider.on("cat /root/install.out", "Success\nEXIT_0\n")
    # attempt 1: the blind transport read (probe-30/33 shape);
    # attempt 2+: the answering dumpsys line (rule repeats the last)
    provider.on("versionName",
                CommandResult(-1, "", "Error: request_timeout after "
                              "60000 ms", 5, "dumpsys"),
                "    versionName=7.25.5.2609020000\n")
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

    assert result.ok is True, result.reason
    exec_log = provider.exec_log
    # the bounded retry re-read ONLY the blind fact; the landed fact
    # was never re-read
    assert len([c for c in exec_log if "versionName" in c]) == 2
    assert len([c for c in exec_log if "versionCode" in c]) == 1
    # exactly one settle between the attempts (probe-33 cadence)
    assert clock.slept.count(PKG_FACTS_SETTLE_S) == 1
    # the blind read surfaced on the console (timestamped diag lines
    # ride the failure path; the console always tells the story)
    assert any(f"package facts blind on attempt 1/{PKG_FACTS_MAX_ATTEMPTS}"
               in line and "versionName unreadable" in line
               for line in lines)
    # and the landed facts reached the metadata — never an empty field
    doc = jsonio_load(stage / "run-metadata.json")
    assert doc["application"]["version_name"] == "7.25.5.2609020000"
    assert doc["application"]["version_code"] == 2609020000


# ------------------------------- install-time facts stash (CAMSCAN-010C)

def test_provision_stashes_install_time_facts_late_read_skipped(
        monkeypatch, tmp_path):
    """CAMSCAN-010C (a) — the early stash lands: provision runs ONE full
    010B ``_package_facts`` invocation (4x15s bounded blindness retry,
    timestamped diag) right after ``_registry_verify`` — install
    confirmed, system freshest, BEFORE the GMS restore (whose
    post-restore flap is the 010B blindness class) — and stashes the
    landed facts on the handle (``native.install_time_facts``).
    execute() consumes the stash: NO late dumpsys read runs (every
    facts read happened at provision time), no blindness settle ever
    sleeps, and the run-metadata/manifest carry the stashed facts
    through the REAL bundle pipeline (install-time ground truth; the
    provenance line names WHEN the facts were discovered)."""
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    provider.on("cat /root/install.out", "Success\nEXIT_0\n")
    lines: list[str] = []
    driver, clock = _make_driver(provider, apk=xapk)
    # the manifest's provider block mirrors the REAL on-record e2b
    # capability report (bundle_run requires non-empty capabilities)
    e2b_report = next(r for r in load_provider_reports(REPO_ROOT)
                      if r["slug"] == "e2b")
    stage = tmp_path / RUN_ID
    handle = driver.provision(_provision_request(lines, apk=xapk,
                                                 provider_report=e2b_report))

    # the stash landed on the handle — install-time ground truth, no
    # blind reads on the way (the diag rides empty)
    assert handle.native.install_time_facts == {
        "version_name": "7.25.5.2609020000", "version_code": 2609020000}
    assert handle.native.install_time_facts_diag == []
    assert any("install-time package facts stashed" in line
               and "version_name='7.25.5.2609020000'" in line
               for line in lines)

    # the early read ran ONCE per fact, at provision time — right
    # after the registry verify and BEFORE the GMS restore commands
    # (the work order's "install confirmed, system freshest")
    exec_log = provider.exec_log
    assert len([c for c in exec_log if "versionName" in c]) == 1
    assert len([c for c in exec_log if "versionCode" in c]) == 1
    registry = next(i for i, c in enumerate(exec_log)
                    if "pm path com.intsig.camscanner" in c)
    facts_at = next(i for i, c in enumerate(exec_log)
                    if "versionName" in c)
    gms_restore = next(i for i, c in enumerate(exec_log)
                       if "pm enable" in c)
    assert registry < facts_at < gms_restore

    scenario = resolve_scenario("S001", REPO_ROOT / "lab" / "scenarios")
    plans = plan_steps(scenario.steps, None)
    request = ExecutionRequest(
        handle=handle, run_id=RUN_ID, subject="reference",
        scenario=scenario, step_plans=plans, stage_dir=stage,
        fixtures=[], started_at="2026-09-24T00:00:00Z",
        finished_at="2026-09-24T00:05:00Z", emit=lines.append)
    result = driver.execute(handle, request)
    assert result.ok is True, result.reason
    assert result.problems == []

    # the late read was skipped/not required: NO new dumpsys facts
    # reads during execute and no blindness settle ever slept
    assert len([c for c in provider.exec_log if "versionName" in c]) == 1
    assert len([c for c in provider.exec_log if "versionCode" in c]) == 1
    assert clock.slept.count(PKG_FACTS_SETTLE_S) == 0
    # the provenance line names the source honestly (the evidence is
    # honest about WHEN the facts were discovered)
    assert any("package facts from the install-time stash" in line
               and "install-time ground truth" in line
               for line in lines)

    # the stashed facts reach the metadata AND the REAL bundle
    # pipeline (the same stage the 010B test drives)
    doc = jsonio_load(stage / "run-metadata.json")
    assert doc["application"]["version_name"] == "7.25.5.2609020000"
    assert doc["application"]["version_code"] == 2609020000
    scenario_copy_for_stage(stage, scenario.file)
    n_artifacts = bundle_stage(stage, REPO_ROOT)
    assert n_artifacts == 3          # screenshot + ui dump + logcat
    manifest = jsonio_load(stage / "manifest.json")
    assert manifest["application"]["version_name"] == "7.25.5.2609020000"
    assert manifest["application"]["version_code"] == 2609020000
    assert validate_manifest(manifest) == []


def test_execute_early_facts_blind_late_read_lands(monkeypatch,
                                                    tmp_path):
    """CAMSCAN-010C (b) — the early read goes all-blind (the exact
    2026-09-25 campaign death-zone rejection class, met at provision
    time where it is BEST-EFFORT): the bounded 4x15s retry exhausts,
    provision STILL SUCCEEDS (a blind early read never fails
    provision), the stash rides the handle empty with its 8
    phase-labeled timestamped diag lines — and execute()'s late read
    lands the facts (the unchanged 010B fallback path)."""
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    provider.on("cat /root/install.out", "Success\nEXIT_0\n")
    # the early read is blind on ALL 4 bounded attempts (each rule pops
    # one scripted response per read); the late read then lands on its
    # first attempt (each rule repeats its last response)
    blind = CommandResult(-1, "", "Error: request_timeout after 60000 ms",
                          5, "dumpsys")
    provider.on("versionName", blind, blind, blind, blind,
                "    versionName=7.25.5.2609020000\n")
    provider.on("versionCode", blind, blind, blind, blind,
                "    versionCode=2609020000 minSdk=23\n")
    driver, clock = _make_driver(provider, apk=xapk)
    lines: list[str] = []
    handle = driver.provision(_provision_request(lines, apk=xapk))

    # provision SUCCEEDED despite the all-blind early read; the stash
    # rides the handle EMPTY with the recorded diag (best-effort —
    # the late read stays the fallback)
    assert handle.native.install_time_facts == {}
    early_diag = handle.native.install_time_facts_diag
    assert len(early_diag) == 2 * PKG_FACTS_MAX_ATTEMPTS
    assert all(re.match(r"^\d{2}:\d{2}:\d{2} \[install-time attempt "
                        r"[1-4]\] version(Name|Code) read blind ", line)
               for line in early_diag)
    assert all(line.startswith("00:00:00 ") for line in early_diag)
    assert any(f"install-time package facts blind after "
               f"{PKG_FACTS_MAX_ATTEMPTS} bounded attempts" in line
               for line in lines)
    # the early read burned its full bounded budget: 4 attempts x 2
    # facts, 3 settles
    assert len([c for c in provider.exec_log if "versionName" in c]) \
        == PKG_FACTS_MAX_ATTEMPTS
    assert clock.slept.count(PKG_FACTS_SETTLE_S) \
        == PKG_FACTS_MAX_ATTEMPTS - 1

    scenario = resolve_scenario("S001", REPO_ROOT / "lab" / "scenarios")
    plans = plan_steps(scenario.steps, None)
    stage = tmp_path / "stage"
    request = ExecutionRequest(
        handle=handle, run_id=RUN_ID, subject="reference",
        scenario=scenario, step_plans=plans, stage_dir=stage,
        fixtures=[], started_at="2026-09-24T00:00:00Z",
        finished_at="2026-09-24T00:05:00Z", emit=lines.append)
    result = driver.execute(handle, request)

    # the fallback landed: the run succeeds with the discovered facts
    assert result.ok is True, result.reason
    assert any("install-time stash empty" in line
               and "010B fallback" in line for line in lines)
    assert any("late package facts landed" in line for line in lines)
    # one more read per fact at execute time, no further settles (the
    # late read landed on its first attempt)
    assert len([c for c in provider.exec_log if "versionName" in c]) \
        == PKG_FACTS_MAX_ATTEMPTS + 1
    assert len([c for c in provider.exec_log if "versionCode" in c]) \
        == PKG_FACTS_MAX_ATTEMPTS + 1
    assert clock.slept.count(PKG_FACTS_SETTLE_S) \
        == PKG_FACTS_MAX_ATTEMPTS - 1
    doc = jsonio_load(stage / "run-metadata.json")
    assert doc["application"]["version_name"] == "7.25.5.2609020000"
    assert doc["application"]["version_code"] == 2609020000


# ------------------------- dumpsys adb-shell prefix (CAMSCAN-010E)

def test_package_facts_command_carries_adb_shell_prefix(monkeypatch,
                                                         tmp_path):
    """CAMSCAN-010E — the command-SHAPE pin (the durable fix for the
    invisible-to-substring-tests bug class): the 2026-09-25 16:34 UTC
    campaign run (started 16:34:43, sandbox e2b-5822073f — the first
    live run with 010A–010D complete upstream) walked the ENTIRE chain
    successfully and failed ONLY at package facts: all 16 facts reads
    (4 install-time 16:53:34–16:54:22 + 4 late x 2 facts
    17:30:51–17:31:39) died with the IDENTICAL signature exit=1,
    stderr tail '/bin/bash: line 1: dumpsys: command not found' —
    provider.execute runs commands in the sandbox's LINUX bash and
    dumpsys is an ANDROID binary reachable only through the adb
    client, so the pre-010E bare ``dumpsys package …`` form can NEVER
    succeed on any sandbox (deterministic, not the probe-33
    blind-transient class). ScriptedProvider matched the old bug
    invisibly — its needles are SUBSTRINGS ("versionName", "dumpsys")
    that match the bare and prefixed forms alike — so this test pins
    the EXACT command shape on exec_log after a full run with an
    EMPTY stash (both call sites exercised: the install-time read
    through provision's ADB local, the late read through
    native.adb)."""
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    provider.on("cat /root/install.out", "Success\nEXIT_0\n")
    # the install-time read goes all-blind (the stash rides the handle
    # EMPTY) so the late read runs at execute time — BOTH call sites'
    # commands land in exec_log; the late read then lands the facts
    blind = CommandResult(-1, "", "Error: request_timeout after 60000 ms",
                          5, "dumpsys")
    provider.on("versionName", blind, blind, blind, blind,
                "    versionName=7.25.5.2609020000\n")
    provider.on("versionCode", blind, blind, blind, blind,
                "    versionCode=2609020000 minSdk=23\n")
    driver, _clock = _make_driver(provider, apk=xapk)
    lines: list[str] = []
    handle = driver.provision(_provision_request(lines, apk=xapk))
    assert handle.native.install_time_facts == {}   # the stash is EMPTY

    scenario = resolve_scenario("S001", REPO_ROOT / "lab" / "scenarios")
    plans = plan_steps(scenario.steps, None)
    stage = tmp_path / "stage"
    request = ExecutionRequest(
        handle=handle, run_id=RUN_ID, subject="reference",
        scenario=scenario, step_plans=plans, stage_dir=stage,
        fixtures=[], started_at="2026-09-24T00:00:00Z",
        finished_at="2026-09-24T00:05:00Z", emit=lines.append)
    result = driver.execute(handle, request)
    assert result.ok is True, result.reason    # the late read landed

    # the facts path ran at BOTH stages: 4 bounded install-time reads
    # x 2 facts, then the late read's first attempt x 2 facts
    exec_log = provider.exec_log
    facts_cmds = [c for c in exec_log if "dumpsys package" in c]
    assert len(facts_cmds) == 2 * PKG_FACTS_MAX_ATTEMPTS + 2

    # THE SHAPE PINS: a command exists matching the EXACT adb-prefixed
    # form (adb path + ' shell dumpsys package ' + the package + the
    # grep -m1 marker, nothing else on the line)…
    assert any(re.match(
        r'^\S*adb\S* shell dumpsys package com\.intsig\.camscanner '
        r'\| grep -m1 "versionName"$', c) for c in facts_cmds)
    assert any(re.match(
        r'^\S*adb\S* shell dumpsys package com\.intsig\.camscanner '
        r'\| grep -m1 "versionCode"$', c) for c in facts_cmds)
    # …stronger: the exact pinned string with the bootstrap recipe's
    # ADB path (the same local every other {adb} shell command in
    # provision uses; _Native carries it for the late read)
    expected_name = (f'{ADB} shell dumpsys package '
                     'com.intsig.camscanner | grep -m1 "versionName"')
    expected_code = (f'{ADB} shell dumpsys package '
                     'com.intsig.camscanner | grep -m1 "versionCode"')
    assert expected_name in facts_cmds
    assert expected_code in facts_cmds

    # and the NEGATIVE pin: NO exec_log entry is the bare Linux-side
    # 'dumpsys' form (the pre-010E bug — deterministic 'command not
    # found' on every sandbox, never the probe-33 class) — every
    # facts read carries the adb shell prefix
    assert not any(re.match(r"^dumpsys package", c) for c in exec_log)
    assert all(re.match(r"^\S*adb\S* shell dumpsys package ", c)
               for c in facts_cmds)


def test_execute_package_facts_all_blind_fails_honestly(monkeypatch,
                                                         tmp_path):
    """CAMSCAN-010B (c) / CAMSCAN-010C (c) — all-blind package facts at
    BOTH read stages: the install-time read exhausts its bounded
    retries (best-effort — provision continues), the late read
    exhausts its own, and the run FAILS with a readable reason naming
    every blind read of BOTH stages (timestamped, phase-labeled, the
    _ldiag style — the combined diagnostics) — never an empty
    version_name silently passing into the bundle validator (the
    20260925T091135Z evidence-stage death: the entire live chain had
    succeeded; the 010C campaign postmortem: the same rejection class
    killed two more full-chain runs at exactly 3600 s sandbox age — the
    E2B Hobby total-lifetime cap, CAMSCAN-010D — exactly
    why the early stash exists)."""
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    provider.on("cat /root/install.out", "Success\nEXIT_0\n")
    # every package-facts read is transport-blind, at BOTH stages, on
    # every attempt (the rules repeat their single scripted response)
    blind = CommandResult(-1, "", "Error: request_timeout after 60000 ms",
                          5, "dumpsys")
    provider.on("versionName", blind)
    provider.on("versionCode", blind)
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

    # the steps themselves succeeded — the failure is the metadata
    # stage, and it is honest: readable, naming the blind reads of
    # BOTH stages (install-time + late)
    assert result.steps_executed == 1
    assert result.ok is False
    assert len(result.problems) == 2
    headline = result.problems[0]
    assert (f"package facts unreadable after {PKG_FACTS_MAX_ATTEMPTS} "
            "bounded attempts at install time AND "
            f"{PKG_FACTS_MAX_ATTEMPTS} at execute time") in headline
    assert "application.version_name" in headline
    assert "application.version_code" in headline
    assert "CAMSCAN-010C combined install-time + late" in headline
    assert "package facts unreadable" in result.reason

    # every blind read of BOTH stages named, timestamped (the injected
    # wall clock pins 00:00:00) and phase-labeled: 2 facts x 4 attempts
    # per stage, 3 settles per stage
    diagnostics = result.problems[1]
    assert diagnostics.startswith(
        "package-facts diagnostics ("
        f"{2 * 2 * PKG_FACTS_MAX_ATTEMPTS} lines: "
        f"{2 * PKG_FACTS_MAX_ATTEMPTS} install-time + "
        f"{2 * PKG_FACTS_MAX_ATTEMPTS} late): ")
    diag_body = diagnostics.split("): ", 1)[1]      # drop the header
    diag_lines = [part.strip() for part in diag_body.split(" | ")]
    assert all(re.match(r"^\d{2}:\d{2}:\d{2} \[install-time attempt "
                        r"[1-4]\] version(Name|Code) read blind ", line)
               for line in diag_lines[:2 * PKG_FACTS_MAX_ATTEMPTS])
    assert all(re.match(r"^\d{2}:\d{2}:\d{2} \[late attempt "
                        r"[1-4]\] version(Name|Code) read blind ", line)
               for line in diag_lines[2 * PKG_FACTS_MAX_ATTEMPTS:])
    assert all(line.startswith("00:00:00 ") for line in diag_lines)
    assert "request_timeout" in diagnostics      # the stderr tail named
    assert len([c for c in provider.exec_log if "versionName" in c]) \
        == 2 * PKG_FACTS_MAX_ATTEMPTS
    assert len([c for c in provider.exec_log if "versionCode" in c]) \
        == 2 * PKG_FACTS_MAX_ATTEMPTS
    assert clock.slept.count(PKG_FACTS_SETTLE_S) \
        == 2 * (PKG_FACTS_MAX_ATTEMPTS - 1)

    # provision SUCCEEDED despite the all-blind early read (the stash
    # is best-effort) — the stash rides the handle empty, its diag
    # recorded and COMBINED with the late read's above
    assert handle.native.install_time_facts == {}
    assert len(handle.native.install_time_facts_diag) \
        == 2 * PKG_FACTS_MAX_ATTEMPTS

    # the placeholder stays in the written metadata (the run failed —
    # the runner never bundles a failed subject, so the validator
    # never sees the empty field; the readable reason is the surface)
    doc = jsonio_load(stage / "run-metadata.json")
    assert doc["application"]["version_name"] == ""
    assert doc["application"]["version_code"] == 0


# ------------------- lifetime budget governor (CAMSCAN-010D)

class _ProvisionClockProbeProvider(ScriptedProvider):
    """CAMSCAN-010D (e) — the sandbox_t0 ordering probe: advances the
    fake clock INSIDE provider.provision (the SDK-side create minutes
    advance real time without any driver sleep) and records the clock
    at entry/exit. The birth mark must be the ENTRY value — a mark
    taken after provider.provision would omit the create/bootstrap
    minutes from the age, OVERSTATE the remaining budget and cut the
    governors too late."""

    def __init__(self, clock: FakeClock) -> None:
        super().__init__()
        self._clock = clock
        self.t_entry: float | None = None
        self.t_exit: float | None = None

    def provision(self, spec: Any) -> Any:
        self.t_entry = self._clock.now
        self._clock.now += 300.0        # SDK-side sandbox create time
        self.t_exit = self._clock.now
        return super().provision(spec)


def test_provision_records_sandbox_t0_before_provider_provision(
        monkeypatch, tmp_path):
    """CAMSCAN-010D (e) — _Native.sandbox_t0 is the injected monotonic
    clock captured immediately BEFORE provider.provision(spec): the
    ENTRY value, never the exit value, so the create/bootstrap minutes
    count toward the E2B Hobby total-lifetime cap. The budget helpers
    read the mark: age == now - t0 (create time included), remaining
    == E2B_TOTAL_LIFETIME_CAP_S - age."""
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    clock = FakeClock()
    provider = _ProvisionClockProbeProvider(clock)
    provider.on("cat /root/install.out", "Success\nEXIT_0\n")
    driver = ReferenceDriver(apk=xapk, provider=provider,
                             sleep=clock.sleep, monotonic=clock.monotonic,
                             wall_time=lambda: 0.0)
    handle = driver.provision(_provision_request([], apk=xapk))

    # the birth mark is the ENTRY clock value (captured before
    # provider.provision ran) — never the exit value (after the 300 s
    # SDK-side create): the create minutes must count toward the cap
    assert provider.t_entry is not None
    assert provider.t_exit is not None
    assert handle.native.sandbox_t0 == provider.t_entry
    assert handle.native.sandbox_t0 != provider.t_exit
    # the age after provision INCLUDES the 300 s create time (the two
    # BOOT_SETTLE_S sleeps add 120 s on top: 300 + 120 = 420) and the
    # remaining budget is the cap minus that age
    assert driver._sandbox_age_s(handle.native) \
        == 300.0 + 2 * BOOT_SETTLE_S
    assert driver._sandbox_budget_remaining_s(handle.native) \
        == E2B_TOTAL_LIFETIME_CAP_S - (300.0 + 2 * BOOT_SETTLE_S)


def test_provision_keyboard_interrupt_destroys_and_reraises(
        monkeypatch, tmp_path):
    """CAMSCAN-010D (d) — the 2026-09-25 lead-verified incident: a
    SIGINT campaign stop during the ladder region sailed past
    provision()'s ``except Exception`` cleanup (KeyboardInterrupt is a
    BaseException) and LEAKED the paid sandbox (killed manually via
    the E2B API minutes later). The ladder region now catches
    BaseException → _destroy_quietly → re-raise the raw signal: the
    sandbox is destroyed AND the operator's stop still propagates."""
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")

    class _SigintProvider(ScriptedProvider):
        # the campaign stop lands mid-ladder (at start, right after
        # the sandbox exists)
        def start(self, env_id: str) -> dict[str, Any]:
            self.ops.append(("start", env_id))
            raise KeyboardInterrupt

    provider = _SigintProvider()
    driver, _clock = _make_driver(provider, apk=xapk)
    with pytest.raises(KeyboardInterrupt):
        driver.provision(_provision_request([], apk=xapk))
    # the paid sandbox was destroyed before the signal re-raised —
    # never leaked
    assert ("destroy", "e2b-fake01") in provider.ops


def test_execute_post_launch_wait_cut_reserves_evidence_budget(
        monkeypatch, tmp_path):
    """CAMSCAN-010D (a) — the 20260925T110418Z-S001-live killer:
    _post_launch's PROCESS_WAIT_ROUNDS x PROCESS_WAIT_S (400 s) wait
    ran at sandbox age ~3200→3600 s and starved the evidence phases of
    the sandbox's final minutes. The governor cuts the wait when the
    remaining budget drops below LAUNCH_PROC_BUDGET_RESERVE_S — a
    timestamped emit line names the age, the reserve and the cap —
    and the ANR ladder STILL RUNS after the cut (it is REQUIRED for
    subsequent steps: the SystemUI dialog blocks the app UI).

    CAMSCAN-010I interplay note: the launch-step process poll now
    engages EARLIER (3060 s true age — cap − reserve − destroy
    margin) than this wait's own 010D threshold (3120 s), so the
    original shape (the poll running untouched at 3115 s while
    _post_launch cut before its first round) is structurally
    unreachable — any sandbox aged past 3120 cuts the POLL first.
    The adapted pin: the poll starts HEALTHY under the 3060
    engagement (age 3050, budget 550 s), lands the process in its
    one round, and _post_launch then MARCHES its rounds into the
    death zone until its own 010D cut fires at age 3130 s."""
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    provider.on("cat /root/install.out", "Success\nEXIT_0\n")
    # the app-ps reads: the POLL's single round finds the process
    # (the launch step's own evidence); every later read is
    # clean-absent (exit 0, empty stdout — the wait must keep
    # marching, not break early) until its 010D cut fires
    provider.on("ps -A | grep com.intsig.camscanner",
                "u0a123 4321 com.intsig.camscanner\n", "", "", "", "",
                "", "", "", "")
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

    # the poll starts at clock 2*BOOT_SETTLE_S + OUTCOME_POLL_S (the
    # ladder's one attempt adds the outcome poll's sleep) pinned to
    # age 3050 s — budget 550 s, healthy under the 010I engagement
    # (3060 s): the poll runs its single round UNTOUCHED and lands
    # the process; _post_launch then starts at age 3060 s (budget
    # 540 s ≥ 480 s) and burns seven 10 s rounds until its round-top
    # check passes age 3120 s → the 010D cut fires at age 3130 s.
    clock_at_poll = (2 * BOOT_SETTLE_S + OUTCOME_POLL_S)
    handle.native.sandbox_t0 = clock_at_poll - 3050

    scenario = resolve_scenario("S001", REPO_ROOT / "lab" / "scenarios")
    plans = plan_steps(scenario.steps, None)
    stage = tmp_path / "stage"
    request = ExecutionRequest(
        handle=handle, run_id=RUN_ID, subject="reference",
        scenario=scenario, step_plans=plans, stage_dir=stage,
        fixtures=[], started_at="2026-09-24T00:00:00Z",
        finished_at="2026-09-24T00:05:00Z", emit=lines.append)
    result = driver.execute(handle, request)

    # the run itself still succeeds (am-confirmed launch; the ANR
    # ladder still dismissed the dialog)
    assert result.ok is True, result.reason
    # the poll ran its ONE round untouched (the 010I engagement did
    # not bite at age 3050 — no cut line ever emits; the round's ps
    # read is the first of the eight counted below, and the launch
    # step's success means the poll found the process there)
    assert not any("process-poll cut at age" in line for line in lines)
    # the _post_launch cut fired ONCE with the exact story: age,
    # reserve, cap — timestamped in the probe-29 style (pinned
    # 00:00:00 by the injected wall clock)
    cut = [line for line in lines if "process-wait cut at age" in line]
    assert len(cut) == 1
    assert "00:00:00 process-wait cut at age 3130s" in cut[0]
    assert (f"reserving {LAUNCH_PROC_BUDGET_RESERVE_S}s for the "
            "evidence phases") in cut[0]
    assert (f"E2B Hobby total-lifetime cap "
            f"{E2B_TOTAL_LIFETIME_CAP_S}s") in cut[0]
    # the wait was cut at its age-3130 round-top: the app-ps reads
    # are the poll's ONE round plus the wait's SEVEN death-zone
    # rounds (ages 3060-3120) — the cut stopped round 8 before its
    # sleep/read, and the full-wait "not observed" line never ran
    assert len([c for c in provider.exec_log
                if "grep com.intsig.camscanner" in c]) == 8
    assert not any("app process not observed within" in line
                   for line in lines)
    # the ANR ladder STILL RAN after the cut (required for subsequent
    # steps — the dialog blocks the app UI)
    assert any(op == ("interact", "tap:540,1245") for op in provider.ops)
    assert any("ANR Wait dismissed" in line for line in lines)


def test_execute_launch_poll_cut_unconfirmed_feeds_death_forensics(
        monkeypatch, tmp_path):
    """CAMSCAN-010D (b) — the unconfirmed 400 s path burns paid time
    on a run that will honestly fail: when the remaining budget drops
    below the reserve the patient poll cuts early (timestamped diag +
    emit), ZERO ps rounds run, and the existing probe-25
    death-forensics / honest-fail path takes the verdict — problems +
    reason + the run-metadata action trace carry the cut line."""
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    provider.on("cat /root/install.out", "Success\nEXIT_0\n")
    # every am start comes back with the am tool's PM-blind error and
    # every app-ps read is transport-blind (the exact failure shape of
    # test_execute_records_launch_failure_with_forensics — am never
    # confirms, so the poll runs the UNCONFIRMED 40-round path)
    provider.on("cat /root/launch.out",
                "Error type 3: Activity class "
                "{com.intsig.camscanner/.mainmenu.mainactivity."
                "MainActivity} does not exist\nEXIT_1\n")
    provider.on("ps -A | grep com.intsig.camscanner",
                CommandResult(-1, "", "Error: request_timeout", 5, "ps"))
    driver, _clock = _make_driver(provider, apk=xapk)
    lines: list[str] = []
    handle = driver.provision(_provision_request(lines, apk=xapk))

    # an AGED sandbox at the patient poll's start: the ladder burned
    # its LAUNCH_MAX_ATTEMPTS attempts (each OUTCOME_POLL_S +
    # LAUNCH_SETTLE_S) on top of provision's 2 x BOOT_SETTLE_S — the
    # poll starts at clock 2*BOOT_SETTLE_S + LAUNCH_MAX_ATTEMPTS *
    # (OUTCOME_POLL_S + LAUNCH_SETTLE_S), pinned to age 3180 s:
    # budget 420 s < the 480 s reserve → the cut fires before round 1
    clock_at_poll = (2 * BOOT_SETTLE_S
                     + LAUNCH_MAX_ATTEMPTS
                     * (OUTCOME_POLL_S + LAUNCH_SETTLE_S))
    handle.native.sandbox_t0 = clock_at_poll - 3180

    scenario = resolve_scenario("S001", REPO_ROOT / "lab" / "scenarios")
    plans = plan_steps(scenario.steps, None)
    stage = tmp_path / "stage"
    request = ExecutionRequest(
        handle=handle, run_id=RUN_ID, subject="reference",
        scenario=scenario, step_plans=plans, stage_dir=stage,
        fixtures=[], started_at="2026-09-24T00:00:00Z",
        finished_at="2026-09-24T00:05:00Z", emit=lines.append)
    result = driver.execute(handle, request)

    # the honest fail: the step failed and the cut fed the existing
    # death-forensics path (probe-25)
    assert result.ok is False
    assert result.steps_executed == 1
    assert result.problems[0] == "step 01 launch failed"
    # ZERO patient-poll ps rounds ran (the unconfirmed 400 s path was
    # cut before its first round — the dying sandbox's final minutes
    # are the evidence phases' money, never the poll's)
    assert len([c for c in provider.exec_log
                if "grep com.intsig.camscanner" in c]) == 0
    # the timestamped cut diag reached problems/reason AND the
    # run-metadata action trace (the next postmortem is a read)
    diagnostics = result.problems[1]
    assert "00:00:00 process-poll cut at age 3180s" in diagnostics
    assert (f"reserving {LAUNCH_PROC_BUDGET_RESERVE_S}s for the "
            "evidence phases") in diagnostics
    assert (f"E2B Hobby total-lifetime cap "
            f"{E2B_TOTAL_LIFETIME_CAP_S}s") in diagnostics
    assert "process-poll cut" in result.reason
    doc = jsonio_load(stage / "run-metadata.json")
    assert any("process-poll cut at age 3180s" in line
               for line in doc["action_trace"][0]["diag"])
    # the console told the same story and the death forensics ran
    assert any("process-poll cut at age 3180s" in line for line in lines)
    assert any("__canary_ok__" in c for c in provider.exec_log)
    assert any("logcat -d -t 200" in c for c in provider.exec_log)


def test_execute_launch_poll_cut_am_confirmed_still_ok(monkeypatch,
                                                        tmp_path):
    """CAMSCAN-010D — the am-confirmed poll path is governed too (BOTH
    paths): with the budget under the reserve the poll cuts before
    round 1, ZERO ps reads run, and the probe-30 am evidence still
    carries the verdict (Status: ok + Activity: <pkg> outranks the
    never-run poll) — the evidence phases get the sandbox's last
    minutes instead of the nice-to-have poll."""
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    provider.on("cat /root/install.out", "Success\nEXIT_0\n")
    # the ANR ladder's dumps: Wait present round 1, gone round 2 —
    # the ladder must still run after the poll's cut
    provider.ui_xmls = [
        ('<hierarchy rotation="0"><node text="Wait" '
         'bounds="[420,1180][660,1310]"/></hierarchy>'),
        '<hierarchy rotation="0"><node text="CamScanner"/></hierarchy>',
    ]
    driver, _clock = _make_driver(provider, apk=xapk)
    lines: list[str] = []
    handle = driver.provision(_provision_request(lines, apk=xapk))

    # an AGED sandbox at the poll's start: the ladder's one attempt
    # adds OUTCOME_POLL_S on top of provision's 2 x BOOT_SETTLE_S —
    # the poll starts at clock 2*BOOT_SETTLE_S + OUTCOME_POLL_S,
    # pinned to age 3130 s (budget 470 s < the 480 s reserve → the
    # cut fires before round 1)
    clock_at_poll = 2 * BOOT_SETTLE_S + OUTCOME_POLL_S
    handle.native.sandbox_t0 = clock_at_poll - 3130

    scenario = resolve_scenario("S001", REPO_ROOT / "lab" / "scenarios")
    plans = plan_steps(scenario.steps, None)
    stage = tmp_path / "stage"
    request = ExecutionRequest(
        handle=handle, run_id=RUN_ID, subject="reference",
        scenario=scenario, step_plans=plans, stage_dir=stage,
        fixtures=[], started_at="2026-09-24T00:00:00Z",
        finished_at="2026-09-24T00:05:00Z", emit=lines.append)
    result = driver.execute(handle, request)

    assert result.ok is True, result.reason
    assert result.problems == []
    # ZERO ps reads: the am-confirmed poll cut before its first round
    # (probe-30's am evidence outranks the poll that never ran)
    assert len([c for c in provider.exec_log
                if "grep com.intsig.camscanner" in c]) == 0
    assert any("process-poll cut at age 3130s" in line for line in lines)
    # _post_launch's wait is cut too (the cut poll slept nothing —
    # the same age 3130 s) and the ANR ladder STILL ran
    assert any("process-wait cut at age 3130s" in line for line in lines)
    assert any(op == ("interact", "tap:540,1245") for op in provider.ops)


def test_execute_package_facts_dead_sandbox_fast_abort(
        monkeypatch, tmp_path):
    """CAMSCAN-010D (c) — the 20260925T110418Z-S001-live evidence-stage
    death, met honestly: the install-time stash went blind on a LIVING
    sandbox (the probe-33 class — the combined-diagnostics doctrine
    keeps covering it), and the late read's FIRST fact carries the
    sandbox-death signature (exit=-1, the '.set_timeout'-advice
    stderr — SANDBOX_DEATH_MARKERS' "sandbox timeout"). A dead sandbox
    is NOT a probe-33 blind transient: the retries abort AT ONCE
    (run-005 lesson b: never hammer a corpse — NO further
    versionName/versionCode read settles) and the honest-fail reason
    names the expiry with age context."""
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    provider.on("cat /root/install.out", "Success\nEXIT_0\n")
    # the install-time read: 4 bounded BLIND attempts on a LIVING
    # sandbox (the probe-33 request_timeout class — retries are the
    # correct doctrine there); the late read's first versionName
    # response then carries the death signature and would repeat it
    # forever (a corpse serves every read the same rejection)
    blind = CommandResult(-1, "", "Error: request_timeout after 60000 ms",
                          5, "dumpsys")
    dead = CommandResult(
        -1, "",
        "Exception: sandbox timeout — Try calling '.set_timeout' on "
        "the sandbox with the desired timeout.", 1, "dumpsys")
    provider.on("versionName", blind, blind, blind, blind, dead)
    provider.on("versionCode", blind, blind, blind, blind)
    driver, clock = _make_driver(provider, apk=xapk)
    lines: list[str] = []
    handle = driver.provision(_provision_request(lines, apk=xapk))
    # the early read stayed best-effort blind (provision succeeded,
    # the stash rides the handle empty with its 8 diag lines)
    assert handle.native.install_time_facts == {}

    # an AGED sandbox: the late read happens deep in the death zone
    # (age ~3200 s of the 3600 s cap — clock.now here already includes
    # the early read's 3 blindness settles)
    handle.native.sandbox_t0 = clock.now - 3200

    scenario = resolve_scenario("S001", REPO_ROOT / "lab" / "scenarios")
    plans = plan_steps(scenario.steps, None)
    stage = tmp_path / "stage"
    request = ExecutionRequest(
        handle=handle, run_id=RUN_ID, subject="reference",
        scenario=scenario, step_plans=plans, stage_dir=stage,
        fixtures=[], started_at="2026-09-24T00:00:00Z",
        finished_at="2026-09-24T00:05:00Z", emit=lines.append)
    result = driver.execute(handle, request)

    # the run failed HONESTLY at the evidence stage (the steps
    # themselves succeeded)
    assert result.steps_executed == 1
    assert result.ok is False
    # the fast-abort fired: versionName was read 4x blind at install
    # time + ONCE dead at execute time — and versionCode NEVER got a
    # late read (the abort stopped the attempt mid-fact-set; no
    # further retry settles on a corpse)
    assert len([c for c in provider.exec_log if "versionName" in c]) \
        == PKG_FACTS_MAX_ATTEMPTS + 1
    assert len([c for c in provider.exec_log if "versionCode" in c]) \
        == PKG_FACTS_MAX_ATTEMPTS
    # the install-time settles ran (3), the late read settled ZERO
    # times (aborted before any settle sleep)
    assert clock.slept.count(PKG_FACTS_SETTLE_S) \
        == PKG_FACTS_MAX_ATTEMPTS - 1
    # the honest-fail reason names the expiry with age context — the
    # exact 20260925T110418Z root cause, readable
    expected_age = clock.now - handle.native.sandbox_t0
    assert (f"sandbox expired (E2B total-lifetime cap "
            f"{E2B_TOTAL_LIFETIME_CAP_S}s) at age ~{expected_age:.0f}s — "
            "reads rejected instantly with the set_timeout-advice "
            "signature") in result.problems[0]
    assert "application.version_name" in result.problems[0]
    # the combined diagnostics still name BOTH stages: 8 install-time
    # blind lines + 1 late DEAD line (phase-labeled, timestamped, the
    # stderr tail carrying the '.set_timeout' advice)
    diagnostics = result.problems[1]
    assert (f"package-facts diagnostics "
            f"({2 * PKG_FACTS_MAX_ATTEMPTS + 1} lines: "
            f"{2 * PKG_FACTS_MAX_ATTEMPTS} install-time + 1 late): ") \
        in diagnostics
    assert "read DEAD — sandbox expired" in diagnostics
    assert "sandbox timeout" in diagnostics
    assert ".set_timeout" in diagnostics
    # the console told the fast-abort story
    assert any("sandbox-death signature on the read" in line
               and "aborting the reads at once" in line
               for line in lines)


def test_execute_young_sandbox_full_waits_no_cut(monkeypatch, tmp_path):
    """CAMSCAN-010D (f) — the young-sandbox happy path is UNCHANGED:
    with budget plentiful the launch-step poll runs its FULL
    probe-31-capped budget and _post_launch its FULL 40-round wait
    (no cut line ever emits) — the governor only bites in the death
    zone. (A young sandbox by construction: sandbox_t0 is the real
    captured mark from provision — age counts from the driver's own
    clock, not a test-set value.)"""
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    provider.on("cat /root/install.out", "Success\nEXIT_0\n")
    # the app process NEVER shows in a ps read (clean absence — exit
    # 0, empty stdout: not blind, not dead, just absent): both waits
    # burn their FULL budgets
    provider.on("ps -A | grep com.intsig.camscanner", "")
    driver, clock = _make_driver(provider, apk=xapk)
    lines: list[str] = []
    handle = driver.provision(_provision_request(lines, apk=xapk))
    # the REAL captured birth mark (young sandbox: age counts from
    # provision — the clock starts at 0)
    assert handle.native.sandbox_t0 == 0.0

    scenario = resolve_scenario("S001", REPO_ROOT / "lab" / "scenarios")
    plans = plan_steps(scenario.steps, None)
    stage = tmp_path / "stage"
    request = ExecutionRequest(
        handle=handle, run_id=RUN_ID, subject="reference",
        scenario=scenario, step_plans=plans, stage_dir=stage,
        fixtures=[], started_at="2026-09-24T00:00:00Z",
        finished_at="2026-09-24T00:05:00Z", emit=lines.append)
    result = driver.execute(handle, request)

    assert result.ok is True, result.reason
    # FULL waits, no cut: the am-confirmed poll burned its whole
    # 12-round cap, the post-launch wait its whole 40 rounds
    assert len([c for c in provider.exec_log
                if "grep com.intsig.camscanner" in c]) \
        == LAUNCH_PROC_POLL_CAP_CONFIRMED + PROCESS_WAIT_ROUNDS
    assert any(f"app process not observed within "
               f"{PROCESS_WAIT_ROUNDS * PROCESS_WAIT_S}s" in line
               for line in lines)
    assert not any("process-wait cut at age" in line for line in lines)
    assert not any("process-poll cut at age" in line for line in lines)
    # the age/budget helpers read the captured mark against the cap
    assert driver._sandbox_age_s(handle.native) == clock.now
    assert driver._sandbox_budget_remaining_s(handle.native) \
        == E2B_TOTAL_LIFETIME_CAP_S - clock.now


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
