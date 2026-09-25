"""CAMSCAN-010F — the trustworthy ui-dump capture pins (hermetic).

The CaptureKind.ui_hierarchy branch of lab/providers/e2b/provider.py
is the S002 blocker #2: the 2026-09-25 campaign run
20260925T180736Z proved TWO live bugs —

(a) the /sdcard read-back FLAPS (FUSE/scoped storage on the API-30
    google_apis image): the S001 evidence dump
    runs/20260925T180736Z-S001-live/reference/ui/01-launch.xml is 48
    BYTES of device-shell error text ("cat: /sdcard/window_dump.xml:
    Permission denied") while dumps taken minutes earlier in the SAME
    run (the ANR ladder, ~20:00 UTC) carried real hierarchies —
    /sdcard access is nondeterministic across a run's lifetime;
(b) the ``test -s`` check PASSED on that 48-byte error text (non-empty
    != valid XML) and the garbage rode the bridge's dump cache into
    every downstream selector resolution.

Pinned here (no network, no e2b SDK, no credentials — a FakeSandbox
double records every commands.run call and simulates the shell
semantics the branch relies on):

- DUMP-PATH PIN (the 010E lesson — shape-pinning, substring tests
  cannot see path bugs): after a scripted ui_hierarchy capture the
  exec_log carries the EXACT /data/local/tmp/window_dump.xml command
  shape and NO /sdcard/window_dump.xml entry anywhere;
- GARBAGE-REJECTION PIN: a scripted capture whose body is the
  verbatim 48-byte S001 evidence text FAILS — 3 bounded retries, then
  a raise whose message carries the body — never returned ok;
- FLAP PIN: garbage first, a real hierarchy second → the capture
  succeeds on attempt 2 (the bounded retry harvests the flap), with
  the ~5 s settle between attempts;
- BRIDGE NO-CACHE PIN: a raising capture surfaces as the bridge's
  structured ok=False and NOTHING is cached (the pre-010F poisoning
  class — ``latest_ui_dump`` stays empty).
"""
from __future__ import annotations

import hashlib
import re
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import pytest
from tools.adb_bridge.bridge import AdbBridge

from lab.providers.e2b.bootstrap import ADB
from lab.providers.e2b.provider import (
    UI_DUMP_CAPTURE_ATTEMPTS,
    UI_DUMP_DEVICE_PATH,
    UI_DUMP_RETRY_SETTLE_S,
    E2BProvider,
    E2BProviderConfig,
)
from lab.providers.types import (
    CaptureKind,
    CommandResult,
    EnvironmentHandle,
    EnvironmentSpec,
    Interaction,
)

#: The verbatim 48-byte S001 evidence artifact (runs/20260925T180736Z-
#: S001-live/reference/ui/01-launch.xml — 47 chars + the newline).
S001_EVIDENCE_GARBAGE = "cat: /sdcard/window_dump.xml: Permission denied\n"

#: A real uiautomator hierarchy (the shape the ANR-phase dumps of the
#: same run carried): starts with <?xml, contains <hierarchy.
VALID_DUMP = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes" ?>\n'
    '<hierarchy rotation="0"><node index="0" text="CamScanner" '
    'resource-id="" class="android.widget.TextView" package="com.intsig'
    '.camscanner" content-desc="" clickable="false" '
    'bounds="[0,0][1080,2280]"/></hierarchy>\n')

ENV_ID = "e2b-fake-cap"


# ------------------------------------------------------------------ double

@dataclass
class _SandboxResult:
    exit_code: int
    stdout: str = ""
    stderr: str = ""


class _FakeCommands:
    def __init__(self, sandbox: FakeSandbox) -> None:
        self._sandbox = sandbox

    def run(self, cmd: str, timeout: float | None = None,
            user: str = "root") -> _SandboxResult:
        return self._sandbox._run(cmd)


class FakeSandbox:
    """Hermetic E2B sandbox double for the capture branch: records
    every commands.run call (``exec_log``) and simulates the shell
    semantics the branch relies on — the dump pipeline's redirection
    writes the next scripted body to the host path; the content check
    (BOTH the CAMSCAN-010F head/grep form and the pre-010F ``test -s``
    form, faithfully evaluated), ``head -c 200``, ``stat -c '%s'`` and
    ``sha256sum`` read it back. ``dumps`` scripts the body per dump
    attempt (the last repeats), so the /sdcard flap and the
    idle-state retry are both scriptable."""

    def __init__(self, dumps: list[str]) -> None:
        self.exec_log: list[str] = []
        self.files: dict[str, bytes] = {}
        self.dumps = list(dumps)
        self.dump_calls = 0
        self.commands = _FakeCommands(self)

    def set_timeout(self, seconds: int) -> None:      # renewal no-op
        pass

    def kill(self) -> None:                           # destroy no-op
        pass

    # -------------------------------------------------------- mini shell
    def _run(self, cmd: str) -> _SandboxResult:
        self.exec_log.append(cmd)
        if "uiautomator dump" in cmd and "exec-out cat" in cmd:
            match = re.search(r"exec-out cat (\S+) > (\S+)", cmd)
            assert match, f"unparseable dump pipeline: {cmd}"
            _device_path, host_path = match.group(1), match.group(2)
            self.dump_calls += 1
            idx = min(self.dump_calls - 1, len(self.dumps) - 1)
            self.files[host_path] = self.dumps[idx].encode()
            return _SandboxResult(0)
        body = b""
        # the CAMSCAN-010F content check (evaluated faithfully)
        if "grep -qF '<?xml'" in cmd and "grep -qF '<hierarchy'" in cmd:
            match = re.search(r"grep -qF '<hierarchy' (\S+)", cmd)
            body = self.files.get(match.group(1) if match else "", b"")
            ok = body[:5] == b"<?xml" and b"<hierarchy" in body
            return _SandboxResult(0, "CAP_OK\n" if ok else "CAP_EMPTY\n")
        # the pre-010F check form (kept faithful for mutation checks)
        if cmd.startswith("test -s "):
            parts = cmd.split()
            body = self.files.get(parts[2], b"")
            ok = len(body) > 0
            return _SandboxResult(0, "CAP_OK\n" if ok else "CAP_EMPTY\n")
        if cmd.startswith("head -c 200 "):
            body = self.files.get(cmd.split()[3], b"")
            return _SandboxResult(
                0, body[:200].decode("utf-8", "replace"))
        if cmd.startswith("stat -c '%s' "):
            body = self.files.get(cmd[len("stat -c '%s' "):], b"")
            return _SandboxResult(0, f"{len(body)}\n")
        if cmd.startswith("sha256sum "):
            body = self.files.get(cmd.split()[1], b"")
            return _SandboxResult(0, hashlib.sha256(body).hexdigest() + "\n")
        return _SandboxResult(0)                      # trace/mkdir/etc


def _capture_ready(sandbox: FakeSandbox) -> E2BProvider:
    """An E2BProvider wired to the fake sandbox (env registered ready,
    no SDK, no network — the sandbox double is injected directly)."""
    provider = E2BProvider(E2BProviderConfig())
    provider._envs[ENV_ID] = EnvironmentHandle(
        env_id=ENV_ID, sandbox_id="sbx-fake-cap", avd_name="camscan-probe",
        adb_serial="emulator-5554", provider_slug="e2b",
        spec=EnvironmentSpec(), capabilities={},
        created_utc="2026-09-25T00:00:00Z", status="ready")
    provider._sandboxes[ENV_ID] = sandbox
    provider._last_renewal[ENV_ID] = time.time()
    return provider


# ------------------------------------------------------------- path pin

def test_ui_dump_captures_to_data_local_tmp_shape_pinned(monkeypatch):
    """THE DUMP-PATH PIN: the capture's exec_log carries the EXACT
    /data/local/tmp command shape and NO /sdcard/window_dump.xml entry
    (shape-pinning — a substring needle cannot see a path bug, the
    010E lesson). The artifact is returned ok with the real body's
    size and sha256."""
    monkeypatch.setattr("lab.providers.e2b.provider.UI_DUMP_RETRY_SETTLE_S",
                        0.0)
    sandbox = FakeSandbox([VALID_DUMP])
    provider = _capture_ready(sandbox)
    artifact = provider.capture(ENV_ID, CaptureKind.ui_hierarchy)

    assert artifact.kind == "ui_hierarchy"
    assert artifact.sandbox_path == "/root/cap/0001-ui.xml"
    assert artifact.bytes == len(VALID_DUMP.encode())
    assert artifact.sha256 == hashlib.sha256(VALID_DUMP.encode()).hexdigest()
    assert sandbox.dump_calls == 1                    # first attempt ok

    dump_cmds = [c for c in sandbox.exec_log if "uiautomator dump" in c]
    assert len(dump_cmds) == 1
    expected = (f"{ADB} shell uiautomator dump {UI_DUMP_DEVICE_PATH} "
                f">/dev/null 2>&1; "
                f"{ADB} exec-out cat {UI_DUMP_DEVICE_PATH} "
                f"> /root/cap/0001-ui.xml 2>/dev/null")
    assert dump_cmds[0] == expected
    # the content check rides the same exec_log (its own shape)
    assert ("head -c 5 /root/cap/0001-ui.xml | "
            "grep -qF '<?xml' && "
            "grep -qF '<hierarchy' /root/cap/0001-ui.xml && "
            "echo CAP_OK || echo CAP_EMPTY") in sandbox.exec_log
    # the NEGATIVE pin: the flapping /sdcard path is GONE entirely
    assert not any("/sdcard/window_dump.xml" in c for c in sandbox.exec_log)


# ------------------------------------------------------- garbage pin

def test_ui_dump_garbage_body_fails_after_three_bounded_retries(
        monkeypatch):
    """THE GARBAGE-REJECTION PIN: a capture whose body is the verbatim
    48-byte S001 evidence text ("cat: /sdcard/window_dump.xml:
    Permission denied" — device-shell error text, not XML) must FAIL:
    3 bounded retries, then a RuntimeError whose message carries the
    body's head — never returned ok, no artifact."""
    monkeypatch.setattr("lab.providers.e2b.provider.UI_DUMP_RETRY_SETTLE_S",
                        0.0)
    assert len(S001_EVIDENCE_GARBAGE.encode()) == 48   # the verbatim size
    sandbox = FakeSandbox([S001_EVIDENCE_GARBAGE])
    provider = _capture_ready(sandbox)
    with pytest.raises(RuntimeError) as excinfo:
        provider.capture(ENV_ID, CaptureKind.ui_hierarchy)
    message = str(excinfo.value)
    # the existing RuntimeError shape, with the last body's head in it
    assert message.startswith("ui hierarchy dump failed: ")
    assert "Permission denied" in message
    # exactly UI_DUMP_CAPTURE_ATTEMPTS dump pipelines ran — the retry
    # is bounded and INSIDE the capture
    assert sandbox.dump_calls == UI_DUMP_CAPTURE_ATTEMPTS == 3
    dump_cmds = [c for c in sandbox.exec_log if "uiautomator dump" in c]
    assert len(dump_cmds) == 3
    assert all(UI_DUMP_DEVICE_PATH in c for c in dump_cmds)
    # the settled retries: 2 settles between 3 attempts
    assert len([c for c in sandbox.exec_log
                if "CAP_EMPTY" in c]) == 3


# ------------------------------------------------------------- flap pin

def test_ui_dump_flap_garbage_then_valid_recovers(monkeypatch):
    """THE FLAP PIN (the same-run S001 story): attempt 1's body is the
    48-byte garbage, attempt 2's is a real hierarchy → the capture
    SUCCEEDS on attempt 2 (the bounded retry harvests the flap) with
    exactly one ~5 s settle between the attempts."""
    sleeps: list[float] = []
    monkeypatch.setattr("lab.providers.e2b.provider.time.sleep",
                        sleeps.append)
    sandbox = FakeSandbox([S001_EVIDENCE_GARBAGE, VALID_DUMP])
    provider = _capture_ready(sandbox)
    artifact = provider.capture(ENV_ID, CaptureKind.ui_hierarchy)

    assert artifact.kind == "ui_hierarchy"
    assert artifact.bytes == len(VALID_DUMP.encode())
    assert sandbox.dump_calls == 2                    # flap → recovery
    assert sleeps == [UI_DUMP_RETRY_SETTLE_S]         # one 5 s settle
    assert UI_DUMP_RETRY_SETTLE_S == 5.0              # the ~5 s cadence


# ------------------------------------------------------ bridge no-cache

class _RaisingCaptureProvider:
    """LabProvider-ish double whose ui_hierarchy capture raises the
    hardened provider's RuntimeError (the persistent-garbage class)."""

    slug = "fake-raising-capture"

    def __init__(self) -> None:
        self.interactions: list[Interaction] = []

    def execute(self, env_id: str, cmd: str,
                timeout: float | None = None) -> CommandResult:
        return CommandResult(0, "", "", 5, cmd)

    def interact(self, env_id: str, action: Interaction,
                 timeout: float | None = None) -> None:
        self.interactions.append(action)

    def capture(self, env_id: str, kind: CaptureKind,
                timeout: float | None = None) -> Any:
        raise RuntimeError(
            f"ui hierarchy dump failed: {S001_EVIDENCE_GARBAGE[:200]}")

    def transfer(self, env_id: str, direction: str, local: Path,
                 remote: str) -> dict[str, Any]:
        return {}


def test_raising_capture_is_structured_failure_never_cached():
    """THE BRIDGE NO-CACHE PIN: the hardened capture's raise surfaces
    as the bridge's structured ok=False (error carries the provider
    message) and NOTHING is cached — ``latest_ui_dump`` stays empty,
    so the pre-010F garbage-poisoning of every downstream selector
    resolution cannot recur."""
    bridge = AdbBridge(_RaisingCaptureProvider(), "e2b-fake-raise",
                       default_timeout_s=10.0)
    result = bridge.ui_dump()
    assert result.ok is False
    assert "ui hierarchy dump failed" in result.error
    assert "Permission denied" in result.error
    assert bridge.latest_ui_dump is None             # nothing cached
