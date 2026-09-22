"""In-memory LabProvider for adb-bridge unit tests (CAMSCAN-002).

Records every Interaction / capture / execute / transfer the bridge issues;
device state is programmable (window focus, resumed activity, boot flag,
ui-hierarchy XML, logcat text, screenshot bytes, a small two-filesystem
model: env-side ``remote_files`` and device-side ``device_files``).
Failure and "timeout" injection is per-surface — the latency model is
*budget-based, never wall-clocked* (a call whose timeout budget is below
the configured latency fails the way a provider timeout would), so tests
stay fast while exercising the bridge's timeout paths.

Also implements the duck-typed ``_trace`` hook the bridge appends to.
"""
from __future__ import annotations

import hashlib
import time
from pathlib import Path
from typing import Any, Callable, Optional

from lab.providers.types import (
    Artifact,
    CaptureKind,
    CommandResult,
    Interaction,
    TraceEvent,
)


def _utc() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


class FakeProvider:
    """Programmable LabProvider double satisfying the bridge's needs."""

    slug = "fake"

    def __init__(self, *, ui_xml: str = "", logcat_text: str = "",
                 screenshot_bytes: bytes = b"", focus: str = "",
                 resumed: str = "", boot_completed: bool = True):
        # call recordings
        self.env_ids_seen: list[str] = []
        self.interactions: list[Interaction] = []
        self.interaction_timeouts: list[Optional[float]] = []
        self.captures: list[CaptureKind] = []
        self.capture_timeouts: list[Optional[float]] = []
        self.executes: list[str] = []
        self.execute_timeouts: list[Optional[float]] = []
        self.transfers: list[tuple[str, str, str]] = []  # (dir, local, remote)
        self.trace_events: list[TraceEvent] = []
        # two-filesystem model: env-side files and device-side files
        self.remote_files: dict[str, bytes] = {}
        self.device_files: dict[str, bytes] = {}
        # programmable device state
        self.focus = focus                      # current window focus string
        self.focus_sequence: list[str] = []     # cycles per dumpsys-window call
        self._focus_i = 0
        self.resumed = resumed                  # topResumedActivity string
        self.boot_completed = boot_completed
        self.ui_xml = ui_xml
        self.logcat_text = logcat_text
        self.screenshot_bytes = screenshot_bytes
        # failure injection
        self.fail_interact_kinds: set[str] = set()
        self.fail_captures: set[CaptureKind] = set()
        self.execute_timeout_substrings: set[str] = set()  # substrings → timeout
        self.pm_failures: set[tuple[str, str]] = set()  # (app, permission)
        self.install_fails = False
        self.pull_failures: set[str] = set()           # remote paths
        # budget-based latency model (see module docstring)
        self.execute_latency_s: float = 0.0
        self.interact_latency_s: float = 0.0
        # escape hatch for arbitrary responses
        self.execute_hook: Optional[Callable[[str, Optional[float]], CommandResult]] = None

    # ------------------------------------------------------------ trace hook
    def _trace(self, env_id: str, kind: str, label: str = "",
               detail: Optional[dict[str, Any]] = None) -> None:
        self.trace_events.append(TraceEvent(
            ts_utc=_utc(), env_id=env_id, kind=kind, label=label,
            detail=dict(detail or {})))

    # ------------------------------------------------------------- internals
    def _saw(self, env_id: str) -> None:
        self.env_ids_seen.append(env_id)

    # ------------------------------------------------------------- operation
    def execute(self, env_id: str, cmd: str,
                timeout: Optional[float] = None) -> CommandResult:
        self._saw(env_id)
        self.executes.append(cmd)
        self.execute_timeouts.append(timeout)
        if any(s and s in cmd for s in self.execute_timeout_substrings):
            return CommandResult(-1, "", "RUN_ERROR: simulated timeout", 10, cmd)
        if (self.execute_latency_s and timeout is not None
                and timeout < self.execute_latency_s):
            return CommandResult(-1, "", "RUN_ERROR: simulated timeout", 10, cmd)
        if self.execute_hook is not None:
            return self.execute_hook(cmd, timeout)
        return self._execute_static(cmd)

    def _execute_static(self, cmd: str) -> CommandResult:
        def ok(stdout: str = "") -> CommandResult:
            return CommandResult(0, stdout, "", 10, cmd)

        tokens = cmd.split()
        # adb push/pull (host→device / device→host) — tokens[1] is the verb
        if len(tokens) >= 4 and tokens[1] == "push":
            src, dst = tokens[2], tokens[3]
            if src in self.remote_files:
                self.device_files[dst] = self.remote_files[src]
                return ok("1 file pushed, 0 skipped. 0.0 MB/s.\n")
            return CommandResult(1, "", "adb: error: failed to stat remote "
                                         "object: No such file or directory", 5, cmd)
        if len(tokens) >= 4 and tokens[1] == "pull":
            remote, dst = tokens[2], tokens[3]
            if remote in self.device_files:
                self.remote_files[dst] = self.device_files[remote]
                return ok("1 file pulled, 0 skipped. 0.0 MB/s.\n")
            return CommandResult(1, "", "adb: error: remote object not found", 5, cmd)

        if "am start" in cmd:
            return ok("Status: ok\nLaunchState: COLD\nTotalTime: 850\n")
        if "monkey" in cmd:
            return ok("Events injected: 1\n")
        if "am force-stop" in cmd:
            return ok("")
        if "pm grant " in cmd or "pm revoke " in cmd:
            i = tokens.index("pm")
            verb, app, permission = tokens[i + 1], tokens[i + 2], tokens[i + 3]
            if (app, permission) in self.pm_failures:
                return CommandResult(1, "", "Error: java.lang.SecurityException: "
                                             f"{permission} is not a runtime permission", 10, cmd)
            return ok("")
        if " install" in cmd or cmd.startswith("install"):
            if self.install_fails:
                return CommandResult(1, "Failure [INSTALL_FAILED_INVALID_APK]\n", "", 40, cmd)
            return ok("Success\n")
        if "dumpsys window" in cmd:
            if self.focus_sequence:
                focus = self.focus_sequence[self._focus_i % len(self.focus_sequence)]
                self._focus_i += 1
            else:
                focus = self.focus
            return ok(f"  mCurrentFocus=Window{{abc123}} {focus}}}\n" if focus else "")
        if "dumpsys activity" in cmd:
            return ok(f"  topResumedActivity=ActivityRecord{{1x42}} {self.resumed} t123}}\n"
                      if self.resumed else "")
        if "getprop sys.boot_completed" in cmd:
            return ok("1\n" if self.boot_completed else "\n")
        return ok("")

    def interact(self, env_id: str, action: Interaction,
                 timeout: Optional[float] = None) -> TraceEvent:
        self._saw(env_id)
        self.interactions.append(action)
        self.interaction_timeouts.append(timeout)
        if (self.interact_latency_s and timeout is not None
                and timeout < self.interact_latency_s):
            raise RuntimeError(f"interaction {action.kind} failed "
                               f"(exit -1): simulated timeout")
        if action.kind in self.fail_interact_kinds:
            raise RuntimeError(f"interaction {action.kind} failed "
                               f"(exit 1): simulated failure")
        return TraceEvent(ts_utc=_utc(), env_id=env_id, kind="interact",
                          label=action.label or action.kind, detail={})

    def capture(self, env_id: str, kind: CaptureKind,
                timeout: Optional[float] = None) -> Artifact:
        self._saw(env_id)
        self.captures.append(kind)
        self.capture_timeouts.append(timeout)
        if kind in self.fail_captures:
            raise RuntimeError(f"{kind.value} capture failed: simulated failure")
        seq = len(self.captures)
        if kind is CaptureKind.ui_hierarchy:
            path, data = f"/remote/fake/{seq:04d}-ui.xml", self.ui_xml.encode()
        elif kind is CaptureKind.screenshot:
            path, data = f"/remote/fake/{seq:04d}-screenshot.png", self.screenshot_bytes
        elif kind is CaptureKind.logcat:
            path, data = f"/remote/fake/{seq:04d}-logcat.txt", self.logcat_text.encode()
        else:
            raise ValueError(f"unsupported capture kind {kind!r}")
        self.remote_files[path] = data
        return Artifact(kind=kind.value, sandbox_path=path,
                        sha256=hashlib.sha256(data).hexdigest(),
                        bytes=len(data), duration_ms=20, taken_utc=_utc())

    # ------------------------------------------------------ evidence/transfer
    def transfer(self, env_id: str, direction: str, local: "str | Path",
                 remote: str) -> dict[str, Any]:
        self._saw(env_id)
        local_path = Path(local)
        self.transfers.append((direction, str(local_path), remote))
        if direction == "push":
            if not local_path.is_file():
                raise RuntimeError(f"push: local file missing {local_path}")
            data = local_path.read_bytes()
            self.remote_files[str(remote)] = data
            return {"direction": "push", "local": str(local_path),
                    "remote": remote, "bytes": len(data)}
        if direction == "pull":
            if str(remote) in self.pull_failures or str(remote) not in self.remote_files:
                raise RuntimeError(f"pull: no such remote file {remote}")
            local_path.parent.mkdir(parents=True, exist_ok=True)
            data = self.remote_files[str(remote)]
            local_path.write_bytes(data)
            return {"direction": "pull", "local": str(local_path),
                    "remote": remote, "bytes": len(data)}
        raise ValueError(f"direction must be push|pull, got {direction!r}")


class MinimalProvider:
    """Smallest provider surface the bridge must tolerate: no trace hook,
    no timeout keywords on interact/capture (pure contract signatures)."""

    slug = "minimal"

    def __init__(self) -> None:
        self.interactions: list[Interaction] = []
        self.captures: list[CaptureKind] = []
        self.executes: list[tuple[str, float]] = []
        self.transfers: list[tuple[str, str, str]] = []
        self.remote_files: dict[str, bytes] = {
            "/remote/min/ui.xml": b"<hierarchy/>",
            "/remote/min/shot.png": b"\x89PNG-fake",
        }
        self._paths = {CaptureKind.ui_hierarchy: "/remote/min/ui.xml",
                       CaptureKind.screenshot: "/remote/min/shot.png"}

    def execute(self, env_id: str, cmd: str,
                timeout: float) -> CommandResult:
        self.executes.append((cmd, timeout))
        # satisfy both the am-start and the monkey success patterns
        return CommandResult(0, "Status: ok\nEvents injected: 1\n", "", 10, cmd)

    def interact(self, env_id: str, action: Interaction) -> None:
        self.interactions.append(action)

    def capture(self, env_id: str, kind: CaptureKind) -> Artifact:
        self.captures.append(kind)
        data = self.remote_files[self._paths[kind]]
        return Artifact(kind=kind.value, sandbox_path=self._paths[kind],
                        sha256=hashlib.sha256(data).hexdigest(),
                        bytes=len(data), duration_ms=1, taken_utc="")

    def transfer(self, env_id: str, direction: str, local: "str | Path",
                 remote: str) -> None:
        local_path = Path(local)
        self.transfers.append((direction, str(local_path), remote))
        if direction == "push":
            self.remote_files[str(remote)] = local_path.read_bytes()
        elif direction == "pull":
            local_path.parent.mkdir(parents=True, exist_ok=True)
            local_path.write_bytes(self.remote_files[str(remote)])
        else:
            raise ValueError(f"direction must be push|pull, got {direction!r}")
