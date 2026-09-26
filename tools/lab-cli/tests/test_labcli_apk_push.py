"""CAMSCAN-010L — the apk push-then-install + provision-failure
destroy pins (hermetic).

Two first-live-exercise gaps in the implementation-env path, both
proven by the lead's campaign S001 attempt 1 (launched 2026-09-26
12:06:43 UTC on post-010K code, console verbatim):

    run: pairing into existing run dir: /home/z/camscan/runs/
      20260925T180736Z-S001-live (new subjects: implementation)
    implementation: provider=e2b driver=e2b-live app=org.payswap.camscan
    implementation: provisioning e2b sandbox (TCG boot budget is
      provider-owned)…
    [~23 min: concurrent TCG boot completes, wipe + reboot + boot
      check pass]
    RuntimeError: apk reinstall failed: Performing Streamed Install
     adb: failed to stat /home/z/lead-staging/impl-apk/app-debug.apk:
     No such file or directory
    [2026-09-26 12:29:21 UTC] S001 attempt 1: operational failure —
      fresh retry (sandbox cleanly aborted)

(D1) the apk push was never implemented: the driver passes a HOST
    path through ResetSpec.reinstall_apk, and reset() fed it straight
    to an adb that runs INSIDE the sandbox (where the host path does
    not exist — "failed to stat");

(D2) the provision-failure leak: run.py's teardown invariant covers
    execute() ONLY, so the reset RuntimeError orphaned the paid
    sandbox (ib0ahyaj…) — still running 11 minutes after the
    campaign's unconditional "sandbox cleanly aborted" line, killed
    by hand via the E2B API.

Pinned here (no network, no e2b SDK, no credentials — placeholder env
key only; the provider-side slice runs through a FakeSandbox double
with _wait_boot stubbed, the driver-side slice through a stub provider
injected at the lazy ``from lab.providers.e2b import E2BProvider``
site):

- PUSH-THEN-INSTALL ORDER: reset with reinstall_apk pointing at a
  REAL local file → the transfer machinery's files.write carries the
  file's exact bytes to /tmp/<basename> BEFORE the adb install; the
  install command references the IN-SANDBOX path (command-shape pin,
  the 010E pattern); the local path never appears in any adb command;
- IN-SANDBOX PASS-THROUGH: a reinstall_apk that does NOT exist
  locally is passed to adb verbatim — no push, today's trace shape
  (backward compatibility; the reference driver's split installs
  never route through reinstall_apk at all);
- PUSH-FAILURE HONESTY: a raising push surfaces as the same
  RuntimeError shape with both paths + the cause, and NO adb install
  is attempted (no partial install);
- PROVISION-FAILURE DESTROY (the leak pin): a stubbed provider whose
  start/reset/report raises after provision() → the driver re-raises
  the ORIGINAL exception and provider.destroy ran with the env id —
  start, reset (the live shape), report and SIGINT (the 010D
  BaseException lesson) each pinned; a destroy that itself fails is
  emitted with the teardown emit shape and NEVER masks the original;
- SUCCESS PATH: a clean provision returns the handle and destroys
  nothing (the wrapper never over-triggers).
"""
from __future__ import annotations

import hashlib
import time
from dataclasses import dataclass
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from tools.lab_cli.drivers import ProvisionRequest
from tools.lab_cli.e2b_live import E2bLiveDriver
from tools.lab_cli.scenarios import resolve_scenario

from lab.providers.e2b.bootstrap import ADB
from lab.providers.e2b.provider import E2BProvider, E2BProviderConfig
from lab.providers.types import (
    EnvironmentHandle,
    EnvironmentSpec,
    ResetSpec,
)

REPO_ROOT = Path(__file__).resolve().parents[3]
ENV_ID = "e2b-fake-push"

#: A deterministic fake APK body (the driver passes a host file; the
#: push must carry exactly these bytes).
APK_BYTES = b"FAKE-IMPL-APK-010L\x00" + b"PK\x03\x04padding-bytes" * 64

#: The stable in-sandbox landing path for a pushed host APK
#: (/tmp/<basename> — part A's contract).
SANDBOX_APK = "/tmp/app-debug.apk"


# ------------------------------------------------------------------ doubles

@dataclass
class _ShResult:
    exit_code: int
    stdout: str = ""
    stderr: str = ""


class _FakeCommands:
    """records every commands.run into the sandbox's unified event
    stream and answers the transfer machinery's size check."""

    def __init__(self, sandbox: _PushSandbox) -> None:
        self._sandbox = sandbox

    def run(self, cmd: str, timeout: float | None = None,
            user: str = "root") -> _ShResult:
        self._sandbox.events.append(("sh", cmd))
        if cmd.startswith("stat -c %s "):
            remote = cmd[len("stat -c %s "):]
            size = len(self._sandbox.remote_files.get(remote, b""))
            return _ShResult(0, f"{size}\n")
        return _ShResult(0)


class _FakeFiles:
    """the e2b files-API surface reset()'s push rides (write only)."""

    def __init__(self, sandbox: _PushSandbox) -> None:
        self._sandbox = sandbox

    def write(self, remote: str, data: bytes) -> None:
        if self._sandbox.write_raises is not None:
            raise self._sandbox.write_raises
        self._sandbox.events.append(("push", remote, bytes(data)))
        self._sandbox.remote_files[remote] = bytes(data)


class _PushSandbox:
    """Hermetic E2B sandbox double for reset()'s push-then-install
    slice: ONE unified event stream (``events``) records every
    commands.run (``("sh", cmd)``) AND every files.write
    (``("push", remote, bytes)``) in call order, so push-vs-install
    ordering is pinnable across the two transports. ``write_raises``
    scripts a push-transport failure (test 3)."""

    def __init__(self, write_raises: Exception | None = None) -> None:
        self.events: list[tuple[Any, ...]] = []
        self.remote_files: dict[str, bytes] = {}
        self.write_raises = write_raises
        self.commands = _FakeCommands(self)
        self.files = _FakeFiles(self)

    def set_timeout(self, seconds: int) -> None:      # renewal no-op
        pass

    def kill(self) -> None:                           # destroy no-op
        pass


def _reset_ready(monkeypatch: pytest.MonkeyPatch,
                 sandbox: _PushSandbox) -> E2BProvider:
    """An E2BProvider wired to the fake sandbox (env registered
    provisioned — reset() skips its stop() fast path; _wait_boot
    stubbed booted; no SDK, no network: only the push-then-install
    slice of reset() runs for real)."""
    provider = E2BProvider(E2BProviderConfig())
    provider._envs[ENV_ID] = EnvironmentHandle(
        env_id=ENV_ID, sandbox_id="sbx-fake-push",
        avd_name="camscan-implementation", adb_serial="emulator-5554",
        provider_slug="e2b",
        spec=EnvironmentSpec(purpose="implementation"), capabilities={},
        # the live attempt-1 sandbox birth stamp (ib0ahyaj…, 12:06:45)
        created_utc="2026-09-26T12:06:45Z", status="provisioned")
    provider._sandboxes[ENV_ID] = sandbox
    provider._last_renewal[ENV_ID] = time.time()
    monkeypatch.setattr(
        E2BProvider, "_wait_boot",
        lambda self, env: (True, {"android_version": "11"}))
    return provider


def _trace_spy(provider: E2BProvider,
               monkeypatch: pytest.MonkeyPatch) -> list[dict[str, Any]]:
    """Record every _trace() call (kind/label/detail) — the reset
    trace's "apk" detail is pinned without touching the sandbox."""
    traces: list[dict[str, Any]] = []

    def record(env_id: str, kind: str, label: str = "",
               detail: dict[str, Any] | None = None) -> None:
        traces.append({"kind": kind, "label": label, "detail": detail})

    monkeypatch.setattr(provider, "_trace", record)
    return traces


def _sh_cmds(sandbox: _PushSandbox) -> list[str]:
    return [e[1] for e in sandbox.events if e[0] == "sh"]


def _install_cmds(sandbox: _PushSandbox) -> list[str]:
    return [c for c in _sh_cmds(sandbox) if "install -r -t" in c]


# ------------------------------------------------- part A: push-then-install

def test_reset_pushes_host_apk_then_installs_sandbox_path(tmp_path,
                                                          monkeypatch):
    """THE PUSH-THEN-INSTALL ORDER PIN: reset with reinstall_apk
    pointing at a REAL local file pushes the file's exact bytes to
    /tmp/<basename> through the transfer machinery BEFORE the adb
    install, and the install command references the IN-SANDBOX path
    (command-shape pin, the 010E pattern) — the local path never
    appears in any adb command (adb runs inside the sandbox; it cannot
    stat a host path — the live "failed to stat" failure)."""
    apk = tmp_path / "app-debug.apk"
    apk.write_bytes(APK_BYTES)
    sandbox = _PushSandbox()
    provider = _reset_ready(monkeypatch, sandbox)
    traces = _trace_spy(provider, monkeypatch)

    provider.reset(ENV_ID, ResetSpec(wipe_data=True,
                                     reinstall_apk=str(apk)))

    # the push: exactly one, the file's exact bytes, to the stable
    # in-sandbox path
    pushes = [e for e in sandbox.events if e[0] == "push"]
    assert len(pushes) == 1
    assert pushes[0] == ("push", SANDBOX_APK, APK_BYTES)
    # the transfer machinery's size-verify step ran on the remote copy
    assert f"stat -c %s {SANDBOX_APK}" in _sh_cmds(sandbox)

    # push BEFORE install — the ONE unified stream, across the two
    # transports (files.write vs commands.run)
    assert sandbox.events.index(pushes[0]) \
        < sandbox.events.index(("sh", _install_cmds(sandbox)[0]))

    # the install command: EXACT shape, the IN-SANDBOX path
    assert _install_cmds(sandbox) == [f"{ADB} install -r -t {SANDBOX_APK}"]
    # the HOST path never appears in any adb command
    adb_cmds = [c for c in _sh_cmds(sandbox) if c.startswith(ADB)]
    assert adb_cmds, "expected adb commands (the install at minimum)"
    assert all(str(apk) not in c for c in adb_cmds)

    # the reset trace's "apk" detail: BOTH paths visible (local +
    # in-sandbox)
    resets = [t for t in traces if t["label"] == "reset"]
    assert len(resets) == 1
    assert resets[0]["detail"]["apk"] == {"local": str(apk),
                                          "sandbox": SANDBOX_APK}
    assert resets[0]["detail"]["wipe_data"] is True
    # the push rode the transfer machinery's own trace (local/remote)
    transfers = [t for t in traces if t["label"] == "transfer:push"]
    assert len(transfers) == 1
    assert transfers[0]["detail"] == {"direction": "push",
                                      "local": str(apk),
                                      "remote": SANDBOX_APK,
                                      "bytes": len(APK_BYTES)}


def test_reset_in_sandbox_path_passes_through_verbatim(monkeypatch):
    """THE PASS-THROUGH PIN: a reinstall_apk that does NOT exist on
    the lab host is an in-sandbox path by contract — no push, the adb
    install command carries the path verbatim, the reset trace keeps
    today's string shape (backward compatibility)."""
    sandbox = _PushSandbox()
    provider = _reset_ready(monkeypatch, sandbox)
    traces = _trace_spy(provider, monkeypatch)

    provider.reset(ENV_ID, ResetSpec(
        wipe_data=True, reinstall_apk="/root/pre-staged/app-debug.apk"))

    # no push: the transfer machinery never ran
    assert not [e for e in sandbox.events if e[0] == "push"]
    assert not [c for c in _sh_cmds(sandbox) if c.startswith("stat -c %s")]
    # the install command: the path VERBATIM (today's behavior)
    assert _install_cmds(sandbox) == [
        f"{ADB} install -r -t /root/pre-staged/app-debug.apk"]
    # the reset trace keeps today's plain-string "apk" detail
    resets = [t for t in traces if t["label"] == "reset"]
    assert len(resets) == 1
    assert resets[0]["detail"]["apk"] == "/root/pre-staged/app-debug.apk"


def test_reset_push_failure_is_honest_no_partial_install(tmp_path,
                                                         monkeypatch):
    """THE PUSH-FAILURE HONESTY PIN: a raising push surfaces as the
    same RuntimeError shape (the known "apk reinstall failed:" prefix,
    both paths + the cause in the message) and NO adb install is
    attempted — never a partial install."""
    apk = tmp_path / "app-debug.apk"
    apk.write_bytes(APK_BYTES)
    sandbox = _PushSandbox(
        write_raises=RuntimeError("simulated push transport failure"))
    provider = _reset_ready(monkeypatch, sandbox)

    with pytest.raises(RuntimeError) as excinfo:
        provider.reset(ENV_ID, ResetSpec(wipe_data=True,
                                         reinstall_apk=str(apk)))

    message = str(excinfo.value)
    assert message.startswith("apk reinstall failed:")
    assert "push" in message
    assert str(apk) in message            # the local path
    assert SANDBOX_APK in message         # the in-sandbox destination
    assert "simulated push transport failure" in message  # the cause
    # no adb install attempted
    assert _install_cmds(sandbox) == []


# --------------------------------------- part B: provision-failure destroy

class StubProvider:
    """LabProvider-ish double for the e2b_live provision contract:
    provision() always succeeds; start/reset/report destroy raise the
    scripted exception when set (kwargs); destroy records the env id
    (and may itself fail). ``calls`` keeps the operation order."""

    slug = "stub-e2b"

    def __init__(self, **raises: Exception | None) -> None:
        self.calls: list[tuple[Any, ...]] = []
        self.destroyed: list[str] = []
        self.raises = raises

    def _maybe_raise(self, name: str) -> None:
        exc = self.raises.get(name)
        if exc is not None:
            raise exc

    def provision(self, spec: Any) -> Any:
        self.calls.append(("provision",))
        return SimpleNamespace(
            env_id="e2b-stub01", sandbox_id="sbx-stub01",
            avd_name="camscan-implementation", spec=spec, timings={})

    def start(self, env_id: str) -> dict[str, Any]:
        self.calls.append(("start", env_id))
        self._maybe_raise("start")
        return {"boot_s": 410.0, "adb_serial": "emulator-5554"}

    def reset(self, env_id: str, reset: ResetSpec) -> None:
        self.calls.append(("reset", env_id, reset.wipe_data,
                           reset.reinstall_apk))
        self._maybe_raise("reset")

    def report(self, env_id: str) -> dict[str, Any]:
        self.calls.append(("report", env_id))
        self._maybe_raise("report")
        return {"ok": True, "android_version": "11",
                "device_model": "Pixel 4", "resolution": "1080x2280",
                "density": "440", "locale": "en-US", "timezone": "UTC"}

    def destroy(self, env_id: str) -> None:
        self.calls.append(("destroy", env_id))
        self.destroyed.append(env_id)
        self._maybe_raise("destroy")


def _driver_with(monkeypatch: pytest.MonkeyPatch, stub: StubProvider,
                 tmp_path: Path) -> tuple[E2bLiveDriver, ProvisionRequest,
                                          list[str]]:
    """E2bLiveDriver wired to the stub provider through the lazy
    ``from lab.providers.e2b import E2BProvider`` site (placeholder
    env key — no credential, no SDK, no network)."""
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    import lab.providers.e2b as e2b_pkg
    monkeypatch.setattr(e2b_pkg, "E2BProvider", lambda: stub)
    scenario = resolve_scenario("S001", REPO_ROOT / "lab" / "scenarios")
    apk = tmp_path / "app-debug.apk"
    apk.write_bytes(APK_BYTES)
    lines: list[str] = []
    request = ProvisionRequest(
        run_id="20260926T120643Z-S001-live", subject="implementation",
        scenario=scenario, provider_report={"slug": "e2b"},
        step_timeout_s=scenario.step_timeout_seconds,
        timeout_s=scenario.timeout_seconds,
        apk=apk, emit=lines.append)
    return E2bLiveDriver(apk=apk), request, lines


def test_provision_reset_failure_destroys_sandbox(tmp_path, monkeypatch):
    """THE LEAK PIN (the live 2026-09-26 12:29:21 UTC shape): a reset
    that raises AFTER the sandbox exists must destroy it before the
    ORIGINAL exception propagates — provision() is outside the
    runner's teardown scope (run.py guards execute() only), so the
    driver owns this cleanup. The attempt-1 sandbox (ib0ahyaj…) stayed
    alive 11 minutes past the campaign's "sandbox cleanly aborted"
    line, killed by hand — never again."""
    live_failure = RuntimeError(
        "apk reinstall failed: Performing Streamed Install "
        "adb: failed to stat /home/z/lead-staging/impl-apk/app-debug.apk: "
        "No such file or directory")
    stub = StubProvider(reset=live_failure)
    driver, request, lines = _driver_with(monkeypatch, stub, tmp_path)

    with pytest.raises(RuntimeError) as excinfo:
        driver.provision(request)

    assert excinfo.value is live_failure      # the ORIGINAL, unwrapped
    assert stub.destroyed == ["e2b-stub01"]   # the paid sandbox destroyed
    assert stub.calls == [("provision",),
                          ("start", "e2b-stub01"),
                          ("reset", "e2b-stub01", True, str(request.apk)),
                          ("destroy", "e2b-stub01")]
    # the happy-path emit never fired
    assert not any("environment ready" in line for line in lines)


def test_provision_start_failure_destroys_sandbox(tmp_path, monkeypatch):
    """Same pin one step earlier: a failing start (boot) destroys too —
    every step after provider.provision() is inside the wrapper."""
    boot_failure = RuntimeError(
        "boot failed: emulator process died during early boot")
    stub = StubProvider(start=boot_failure)
    driver, request, lines = _driver_with(monkeypatch, stub, tmp_path)

    with pytest.raises(RuntimeError) as excinfo:
        driver.provision(request)

    assert excinfo.value is boot_failure
    assert stub.destroyed == ["e2b-stub01"]
    assert stub.calls == [("provision",),
                          ("start", "e2b-stub01"),
                          ("destroy", "e2b-stub01")]
    assert not any("environment ready" in line for line in lines)


def test_provision_report_failure_destroys_sandbox(tmp_path, monkeypatch):
    """Same pin for the report read (the last raising step before the
    handle is built)."""
    report_failure = RuntimeError("report probe failed")
    stub = StubProvider(report=report_failure)
    driver, request, lines = _driver_with(monkeypatch, stub, tmp_path)

    with pytest.raises(RuntimeError) as excinfo:
        driver.provision(request)

    assert excinfo.value is report_failure
    assert stub.destroyed == ["e2b-stub01"]
    assert not any("environment ready" in line for line in lines)


def test_provision_destroy_failure_never_masks_original(tmp_path,
                                                        monkeypatch):
    """BEST-EFFORT EMIT PIN: when destroy ITSELF fails, the original
    exception still propagates (never masked) and the destroy failure
    is emitted with the existing teardown emit shape."""
    reset_failure = RuntimeError("apk reinstall failed: push exploded")
    stub = StubProvider(reset=reset_failure,
                        destroy=RuntimeError("destroy failed (simulated)"))
    driver, request, lines = _driver_with(monkeypatch, stub, tmp_path)

    with pytest.raises(RuntimeError) as excinfo:
        driver.provision(request)

    assert excinfo.value is reset_failure   # the ORIGINAL, not the destroy's
    assert stub.calls[-1] == ("destroy", "e2b-stub01")
    # the teardown emit shape ("  <subject>: destroy failed (<exc>)")
    assert ("  implementation: destroy failed "
            "(destroy failed (simulated))") in lines


def test_provision_sigint_destroys_sandbox(tmp_path, monkeypatch):
    """BASEEXCEPTION PIN (the reference driver's 010D lesson): a
    SIGINT campaign stop during start is not an Exception — the
    wrapper still destroys the paid sandbox and re-raises the raw
    signal (the operator's stop always propagates)."""
    stub = StubProvider(start=KeyboardInterrupt())
    driver, request, _lines = _driver_with(monkeypatch, stub, tmp_path)

    with pytest.raises(KeyboardInterrupt):
        driver.provision(request)

    assert stub.destroyed == ["e2b-stub01"]


def test_provision_success_returns_handle_never_destroys(tmp_path,
                                                         monkeypatch):
    """THE OVER-TRIGGER GUARD: a clean provision returns the handle
    (with the provider wired through native for teardown) and
    destroys NOTHING — the wrapper fires only on raising paths."""
    stub = StubProvider()
    driver, request, lines = _driver_with(monkeypatch, stub, tmp_path)

    handle = driver.provision(request)

    assert handle.environment_id == "e2b-stub01"
    assert handle.subject == "implementation"
    assert handle.application["package"] == "org.payswap.camscan"
    assert handle.application["installer_sha256"] \
        == hashlib.sha256(APK_BYTES).hexdigest()
    assert handle.device["model"] == "Pixel 4"
    assert stub.destroyed == []                    # no premature destroy
    assert stub.calls == [("provision",),
                          ("start", "e2b-stub01"),
                          ("reset", "e2b-stub01", True, str(request.apk)),
                          ("report", "e2b-stub01")]
    # the driver still passes the HOST apk path through ResetSpec —
    # part A's provider-side push turns it into push-then-install
    assert any("environment ready" in line for line in lines)
