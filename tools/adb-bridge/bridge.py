"""Provider-neutral device-driving facade over any LabProvider (CAMSCAN-002).

``AdbBridge`` is the layer the scenario runner sits on: it exposes semantic
device verbs (launch / tap / type / capture / ...) and maps every one onto
**provider calls only** — it never spawns adb (or any other process) itself.
Because it talks exclusively to the LabProvider contract
(``lab/providers/LABPROVIDER.md``), the same bridge drives the e2b TCG
substrate today and any future provider (Flauz KVM, ...) tomorrow.

Determinism contract (enforced here, not by convention):

* **no sleep without a poll** — the only sleeps are the bounded poll
  intervals inside :meth:`AdbBridge.wait_for`, whose loop re-checks the
  deadline before every sleep;
* **every wait is bounded** — verbs take ``timeout`` (default 180 s, TCG
  scale: one adb shell round-trip can take 30-60 s under software
  emulation), overridable per call or per bridge;
* **target resolution is deterministic** — a semantic id resolves to a
  ui-selector match in the latest ui dump, else to its registered
  coordinates (never to a heuristic).

Import path note: this directory is the dashed work-order deliverable
``tools/adb-bridge``; ``tools/__init__.py`` aliases it as the importable
package ``tools.adb_bridge`` (Python identifiers cannot contain dashes), so
``from tools.adb_bridge.bridge import AdbBridge`` works from the repo root.

The adb invocation prefix (``adb`` by default) is *configuration*, not a
hard-coded assumption: pass ``adb=...`` or expose ``adb_command`` /
``adb_path`` on the provider. It is only ever used to compose command
strings that are handed to ``provider.execute`` — exactly like the
contract's own ``Interaction.to_cmd(adb)`` does.
"""
from __future__ import annotations

import hashlib
import html
import inspect
import re
import shutil
import tempfile
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Optional, Protocol, runtime_checkable

import yaml

from lab.providers.types import Artifact, CaptureKind, CommandResult, Interaction

__all__ = [
    "AdbBridge",
    "DEFAULT_POLL_S",
    "DEFAULT_VERB_TIMEOUT_S",
    "EnvironmentId",
    "LabProviderLike",
    "Predicate",
    "ResolvedTarget",
    "TargetEntry",
    "TargetRegistry",
    "UnknownTargetError",
    "VerbResult",
    "match_selector",
    "parse_selector",
]

#: EnvironmentId is an opaque string id from the provider (see LABPROVIDER.md).
EnvironmentId = str

#: TCG scale — one adb shell round-trip can take 30-60 s under software
#: emulation, so a verb's default budget is deliberately generous.
DEFAULT_VERB_TIMEOUT_S: float = 180.0

#: Default poll interval for bounded waits (the probe round-trip, not this
#: interval, dominates under TCG).
DEFAULT_POLL_S: float = 1.0

#: Host-side staging area inside the environment for install/push/pull.
STAGING_DIR = "/tmp/camscan-bridge"

# Device-state probe fragments. Pipes/quotes are interpreted by the
# environment's shell (the e2b provider runs `adb shell ...` exactly this
#: way — see its own dumpsys/focus probes).
_FOCUS_CMD = "dumpsys window | grep -m1 mCurrentFocus"
_RESUMED_CMD = 'dumpsys activity activities | grep -m1 -E "topResumedActivity|mResumedActivity"'
_BOOT_CMD = "getprop sys.boot_completed"


# --------------------------------------------------------------------- results


@dataclass
class VerbResult:
    """Structured outcome of every adb-bridge verb.

    Verbs never raise on *device-side* failure — they return ``ok=False``
    with the reason in ``error`` and evidence-grade context in ``detail``.
    They raise only on authoring errors (unknown semantic target, missing
    local file, non-positive poll interval).

    ``data`` carries the screenshot payload (bytes); ``text`` carries the
    ui-dump / logcat payload (str) — the structured result carries the
    payload so ``ok`` / ``detail`` / ``duration_ms`` are always present.
    """

    verb: str
    ok: bool
    detail: dict[str, Any] = field(default_factory=dict)
    duration_ms: int = 0
    data: bytes = b""
    text: str = ""
    error: str = ""

    def __repr__(self) -> str:  # readable pytest failures / log lines
        payload = ""
        if self.data:
            payload = f" data={len(self.data)}B"
        elif self.text:
            payload = f" text={len(self.text)}ch"
        return (f"VerbResult(verb={self.verb!r}, ok={self.ok}, "
                f"duration_ms={self.duration_ms}{payload})")


class UnknownTargetError(KeyError):
    """A semantic target id absent from the registry (authoring error — loud)."""

    def __init__(self, target_id: str, app: Optional[str],
                 registry: "TargetRegistry"):
        where = f"app scope {app!r}" if app else "global scope"
        known = ", ".join(sorted(registry.ids(app))) or "(none)"
        super().__init__(
            f"unknown semantic target {target_id!r} (looked in {where} and "
            f"global; known ids there: {known})")
        self.target_id = target_id


@dataclass(frozen=True)
class ResolvedTarget:
    """Where a semantic id resolved to, and why (evidence for the trace)."""

    target_id: str
    x: int
    y: int
    source: str                    # "ui-selector" | "coordinates"
    selector: str = ""
    bounds: str = ""
    node_text: str = ""
    node_resource_id: str = ""
    fallback_reason: str = ""      # set when coordinates were used because
    #                                 the selector did not match / no dump


# ------------------------------------------------------------------ selectors

_SELECTOR_TERM_RE = re.compile(
    r"""([A-Za-z][A-Za-z0-9_-]*)\s*=\s*("[^"]*"|'[^']*')""")

#: selector key aliases -> canonical node attribute / matcher key
_SELECTOR_KEYS: dict[str, str] = {
    "text": "text",
    "text_contains": "text_contains",
    "text-contains": "text_contains",
    "resource-id": "resource-id",
    "id": "resource-id",
    "content-desc": "content-desc",
    "desc": "content-desc",
    "class": "class",
    "class-name": "class",
}

_NODE_ATTRS = ("text", "resource-id", "content-desc", "class")

_BOUNDS_RE = re.compile(r"\[\s*(\d+)\s*,\s*(\d+)\s*\]\s*\[\s*(\d+)\s*,\s*(\d+)\s*\]")


def parse_selector(spec: str) -> tuple[tuple[str, str], ...]:
    """Parse a ui-selector spec — a conjunction of ``key="value"`` terms.

    Keys (aliases in parentheses): ``text``, ``text_contains``
    (``text-contains``), ``resource-id`` (``id``), ``content-desc``
    (``desc``), ``class`` (``class-name``). Unknown keys or unparseable
    specs raise :class:`ValueError` — the registry is *data*, and data
    errors must fail at load time, never at tap time.
    """
    terms: list[tuple[str, str]] = []
    for key, quoted in _SELECTOR_TERM_RE.findall(spec):
        canonical = _SELECTOR_KEYS.get(key.lower())
        if canonical is None:
            raise ValueError(
                f"unknown selector key {key!r} in {spec!r} "
                f"(known: {sorted(set(_SELECTOR_KEYS))})")
        terms.append((canonical, quoted[1:-1]))
    if spec.strip() and not terms:
        raise ValueError(f"unparseable selector: {spec!r}")
    return tuple(terms)


def _node_center(bounds: str) -> Optional[tuple[int, int]]:
    """Center of a uiautomator ``[left,top][right,bottom]`` bounds string."""
    match = _BOUNDS_RE.search(bounds)
    if not match:
        return None
    left, top, right, bottom = (int(g) for g in match.groups())
    if right <= left or bottom <= top:      # zero/negative-area nodes are
        return None                        # not tappable
    return (left + right) // 2, (top + bottom) // 2


def match_selector(dump_xml: str,
                   terms: tuple[tuple[str, str], ...]) -> Optional[dict[str, Any]]:
    """First ui-hierarchy node (document order) matching ALL selector terms.

    Returns ``{"x", "y", "bounds", "text", "resource-id"}`` for the matched
    node, or ``None`` (no match, zero-area node, or unparseable XML — an
    unparseable dump falls back to registered coordinates, never crashes).
    """
    if not terms:
        return None
    try:
        root = ET.fromstring(dump_xml)
    except ET.ParseError:
        return None
    for node in root.iter():
        if node.tag != "node":
            continue
        attrs = {name: (node.get(name) or "") for name in _NODE_ATTRS}
        matched = True
        for key, value in terms:
            if key == "text_contains":
                if value not in attrs["text"]:
                    matched = False
                    break
            elif attrs.get(key, "") != value:
                matched = False
                break
        if not matched:
            continue
        center = _node_center(node.get("bounds", ""))
        if center is None:
            continue
        return {"x": center[0], "y": center[1],
                "bounds": node.get("bounds", ""),
                "text": attrs["text"],
                "resource-id": attrs["resource-id"]}
    return None


# ------------------------------------------------------------------ registry


@dataclass(frozen=True)
class TargetEntry:
    """One registry line: a ui-selector, coordinates, or both."""

    target_id: str
    selector: str = ""
    x: Optional[int] = None
    y: Optional[int] = None
    note: str = ""
    terms: tuple[tuple[str, str], ...] = ()

    @property
    def has_coordinates(self) -> bool:
        return self.x is not None and self.y is not None


class TargetRegistry:
    """The semantic target registry — data, not code (``targets.yaml``).

    Per id, resolution is: ui-selector match in the latest ui dump, else the
    registered coordinates. Lookups check the app scope
    (``apps.<package>.targets``) first, then the global scope
    (``targets``) — scenario files reference semantic ids only and never
    coordinates, selectors, or code.
    """

    def __init__(self, source: "str | Path"):
        path = Path(source)
        raw = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        if not isinstance(raw, dict):
            raise ValueError(f"{path}: registry must be a mapping, got {type(raw).__name__}")
        unknown = set(raw) - {"schema", "profile", "targets", "apps"}
        if unknown:
            raise ValueError(f"{path}: unknown top-level keys {sorted(unknown)}")
        self.path = path
        self.schema = raw.get("schema")
        self.profile: dict[str, Any] = dict(raw.get("profile") or {})
        self._global: dict[str, TargetEntry] = {
            tid: self._entry(tid, body, f"targets.{tid}", path)
            for tid, body in (raw.get("targets") or {}).items()}
        self._apps: dict[str, dict[str, TargetEntry]] = {}
        for pkg, body in (raw.get("apps") or {}).items():
            entries = (body or {}).get("targets") or {}
            self._apps[str(pkg)] = {
                tid: self._entry(tid, e, f"apps.{pkg}.targets.{tid}", path)
                for tid, e in entries.items()}

    @staticmethod
    def _entry(target_id: Any, body: Any, where: str, path: Path) -> TargetEntry:
        if not isinstance(target_id, str) or not target_id:
            raise ValueError(f"{path}: {where}: bad target id {target_id!r}")
        if not isinstance(body, dict):
            raise ValueError(f"{path}: {where}: expected a mapping, "
                             f"got {type(body).__name__}")
        unknown = set(body) - {"selector", "x", "y", "note"}
        if unknown:
            raise ValueError(f"{path}: {where}: unknown keys {sorted(unknown)}")
        selector = body.get("selector", "")
        if not isinstance(selector, str):
            raise ValueError(f"{path}: {where}: selector must be a string")
        x, y = body.get("x"), body.get("y")
        for name, value in (("x", x), ("y", y)):
            if value is not None and (not isinstance(value, int)
                                      or isinstance(value, bool) or value < 0):
                raise ValueError(f"{path}: {where}: {name} must be a "
                                 f"non-negative int or null")
        if (x is None) != (y is None):
            raise ValueError(f"{path}: {where}: x and y must be given together")
        terms = parse_selector(selector) if selector else ()
        if not terms and x is None:
            raise ValueError(f"{path}: {where}: needs a ui-selector, "
                             f"coordinates, or both")
        return TargetEntry(target_id=target_id, selector=selector, x=x, y=y,
                           note=str(body.get("note", "")), terms=terms)

    def get(self, target_id: str, app: Optional[str] = None) -> TargetEntry:
        """App scope first, then global; raises :class:`UnknownTargetError`."""
        if app:
            entry = self._apps.get(app, {}).get(target_id)
            if entry is not None:
                return entry
        entry = self._global.get(target_id)
        if entry is not None:
            return entry
        raise UnknownTargetError(target_id, app, self)

    def ids(self, app: Optional[str] = None) -> list[str]:
        """Known ids in scope (app section merged over global), for errors."""
        merged = dict(self._global)
        if app:
            merged.update(self._apps.get(app, {}))
        return sorted(merged)


# ----------------------------------------------------------------- predicates


class Predicate:
    """A named zero-arg callable probing device state (returns bool).

    Probe exceptions are recorded in ``last_error`` and count as "not yet
    satisfied" — a failed probe never crashes a bounded wait.
    """

    __slots__ = ("name", "fn", "last_error")

    def __init__(self, name: str, fn: Callable[[], bool]):
        self.name = name
        self.fn = fn
        self.last_error = ""

    def __call__(self) -> bool:
        self.last_error = ""
        try:
            return bool(self.fn())
        except Exception as exc:  # noqa: BLE001 — probe failure ⇒ not satisfied
            self.last_error = f"{type(exc).__name__}: {exc}"
            return False


# ------------------------------------------------------------------ protocol


@runtime_checkable
class LabProviderLike(Protocol):
    """The structural slice of the LabProvider contract the bridge consumes.

    Any provider implementing ``lab/providers/LABPROVIDER.md`` satisfies it;
    ``interact``/``capture`` may omit the ``timeout`` keyword (the bridge
    probes the signature once — providers are never called with keywords
    they do not accept).
    """

    slug: str

    def execute(self, env: EnvironmentId, cmd: str,
                timeout: Optional[float] = None) -> CommandResult: ...

    def interact(self, env: EnvironmentId, action: Interaction,
                 timeout: Optional[float] = None) -> Any: ...

    def capture(self, env: EnvironmentId, kind: CaptureKind,
                timeout: Optional[float] = None) -> Artifact: ...

    def transfer(self, env: EnvironmentId, direction: str,
                 local: Path, remote: str) -> Any: ...


# -------------------------------------------------------------------- helpers


def _ms_since(t0: float) -> int:
    return round((time.monotonic() - t0) * 1000)


def _parse_field(pattern: str, text: str) -> str:
    match = re.search(pattern, text)
    return match.group(1) if match else ""


def _interaction_fields(action: Interaction) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for name in ("x", "y", "x2", "y2"):
        value = getattr(action, name)
        if value:
            out[name] = value
    if action.duration_ms:
        out["duration_ms"] = action.duration_ms
    if action.text:
        out["text"] = action.text
    if action.button:
        out["button"] = action.button
    if action.keycode:
        out["keycode"] = action.keycode
    return out


def _accepts_kw(provider: Any, method: str, keyword: str) -> bool:
    """Does the provider's method accept `keyword`? Probed once, never guessed."""
    try:
        params = inspect.signature(getattr(provider, method)).parameters
    except (TypeError, ValueError):          # builtins / no signature
        return False
    param = params.get(keyword)
    return param is not None and param.kind in (param.POSITIONAL_OR_KEYWORD,
                                                param.KEYWORD_ONLY)


def _discover_adb(provider: Any) -> str:
    """Duck-typed adb invocation prefix: provider attribute, else ``adb``."""
    for attr in ("adb_command", "adb_path", "adb"):
        value = getattr(provider, attr, None)
        if isinstance(value, str) and value:
            return value
    return "adb"


# -------------------------------------------------------------------- bridge


class AdbBridge:
    """Thin, typed facade over any LabProvider — the scenario-runner layer.

    Every verb maps to provider calls only (``interact`` for input,
    ``capture`` + ``transfer`` for evidence, ``execute`` for device shell
    verbs), adds an explicit timeout, and appends a semantic event to the
    provider's action trace when the provider exposes one. Verbs return a
    :class:`VerbResult` and never raise on device-side failure.

    The adb prefix (default ``adb``) only composes command strings handed to
    ``provider.execute`` — the bridge never runs adb itself.
    """

    def __init__(self,
                 provider: LabProviderLike,
                 env_id: EnvironmentId,
                 *,
                 adb: Optional[str] = None,
                 targets: "str | Path | TargetRegistry | None" = None,
                 default_timeout_s: float = DEFAULT_VERB_TIMEOUT_S,
                 poll_s: float = DEFAULT_POLL_S,
                 workdir: "str | Path | None" = None,
                 dump_max_age_s: Optional[float] = None):
        self.provider = provider
        self.env_id = env_id
        self.adb = adb or _discover_adb(provider)
        self.default_timeout_s = float(default_timeout_s)
        if self.default_timeout_s <= 0:
            raise ValueError(f"default_timeout_s must be > 0 (got {default_timeout_s})")
        self.poll_s = float(poll_s)
        if self.poll_s <= 0:
            raise ValueError(f"poll_s must be > 0 (got {poll_s})")
        self.dump_max_age_s = (None if dump_max_age_s is None
                               else float(dump_max_age_s))
        if isinstance(targets, TargetRegistry):
            self.registry = targets
        else:
            default = Path(__file__).resolve().parent / "targets.yaml"
            self.registry = TargetRegistry(targets if targets is not None else default)
        self.workdir = (Path(workdir) if workdir is not None
                        else Path(tempfile.mkdtemp(prefix=f"adb-bridge-{env_id}-")))
        self.workdir.mkdir(parents=True, exist_ok=True)
        # current app scope for target resolution (set by launch() or set_app)
        self._app: Optional[str] = None
        # latest ui dump cache (refreshed by ui_dump()/text_visible probes)
        self._dump_xml: Optional[str] = None
        self._dump_ts: float = 0.0
        self._dump_artifact: Optional[Artifact] = None
        self._artifact_seq: int = 0
        # provider signature probes (contract allows interact/capture
        # without a timeout keyword — never call what isn't accepted)
        self._interact_takes_timeout = _accepts_kw(provider, "interact", "timeout")
        self._capture_takes_timeout = _accepts_kw(provider, "capture", "timeout")

    # ------------------------------------------------------------ properties

    @property
    def current_app(self) -> Optional[str]:
        """App scope used for target resolution (set by ``launch``/``set_app``)."""
        return self._app

    @property
    def latest_ui_dump(self) -> Optional[str]:
        """The latest ui-hierarchy XML the bridge has seen (cache), if any."""
        return self._dump_xml

    def set_app(self, app: Optional[str]) -> None:
        """Set/clear the app scope for semantic-target resolution."""
        self._app = app

    def cleanup(self) -> None:
        """Best-effort removal of the local artifact workdir (scratch copies —
        the durable evidence lives in the provider's evidence bundles)."""
        shutil.rmtree(self.workdir, ignore_errors=True)

    # --------------------------------------------------------------- plumbing

    def _timeout(self, timeout: Optional[float]) -> float:
        return self.default_timeout_s if timeout is None else float(timeout)

    def _adb_cmd(self, *parts: str) -> str:
        return " ".join((self.adb, *parts))

    def _shell(self, fragment: str,
               timeout: Optional[float] = None) -> CommandResult:
        """Run an *Android* shell fragment via provider.execute (host-side
        pipes/quotes follow the e2b provider precedent)."""
        return self.provider.execute(self.env_id,
                                     self._adb_cmd("shell", fragment),
                                     timeout=self._timeout(timeout))

    def _provider_interact(self, action: Interaction, budget: float) -> Any:
        if self._interact_takes_timeout:
            return self.provider.interact(self.env_id, action, timeout=budget)
        return self.provider.interact(self.env_id, action)

    def _provider_capture(self, kind: CaptureKind, budget: float) -> Artifact:
        if self._capture_takes_timeout:
            return self.provider.capture(self.env_id, kind, timeout=budget)
        return self.provider.capture(self.env_id, kind)

    def _trace(self, kind: str, label: str, detail: dict[str, Any]) -> None:
        """Append to the provider's action trace when available (duck-typed,
        best-effort — tracing must never break a verb)."""
        for attr in ("trace", "_trace"):
            hook = getattr(self.provider, attr, None)
            if not callable(hook):
                continue
            try:
                try:
                    hook(self.env_id, kind, label=label, detail=detail)
                except TypeError:
                    hook(self.env_id, kind, label, detail)   # positional form
            except Exception:  # noqa: BLE001 — trace is never fatal
                return
            return

    def _verb_done(self, verb: str, kind: str, label: str, t0: float,
                   ok: bool, detail: dict[str, Any], error: str = "",
                   *, data: bytes = b"", text: str = "") -> VerbResult:
        result = VerbResult(verb=verb, ok=ok, detail=detail,
                            duration_ms=_ms_since(t0), data=data, text=text,
                            error=error)
        self._trace(kind, label, {"ok": ok, "duration_ms": result.duration_ms,
                                  "error": error[:200],
                                  "cmd": str(detail.get("cmd", ""))[:160]})
        return result

    def _verb_error(self, verb: str, kind: str, label: str, t0: float,
                    detail: dict[str, Any], exc: BaseException) -> VerbResult:
        error = f"{type(exc).__name__}: {exc}"
        result = VerbResult(verb=verb, ok=False, detail=detail,
                            duration_ms=_ms_since(t0), error=error)
        self._trace(kind, label, {"ok": False, "error": error[:200],
                                  "cmd": str(detail.get("cmd", ""))[:160]})
        return result

    # ---------------------------------------------------- app lifecycle verbs

    def launch(self, app: str, activity: Optional[str] = None,
               *, timeout: Optional[float] = None) -> VerbResult:
        """Start ``app``, optionally at ``activity``, and wait for launch.

        With an activity: ``am start -W -n app/activity`` (waits for the
        launch to settle; ``Status``/``LaunchState``/``TotalTime`` land in
        ``detail``). Without: the launcher intent via ``monkey`` (the only
        component-less form that works for unknown activities). A successful
        launch also sets the bridge's app scope for target resolution.
        """
        t0 = time.monotonic()
        budget = self._timeout(timeout)
        self._app = app
        component = self._component(app, activity) if activity else ""
        if activity:
            cmd = self._adb_cmd("shell", f"am start -W -n {component}")
        else:
            cmd = self._adb_cmd("shell",
                                f"monkey -p {app} -c android.intent.category.LAUNCHER 1")
        detail: dict[str, Any] = {"app": app, "activity": activity or "",
                                  "cmd": cmd, "timeout_s": budget}
        try:
            res = self.provider.execute(self.env_id, cmd, timeout=budget)
        except Exception as exc:  # noqa: BLE001 — provider/transport failure
            return self._verb_error("launch", "lifecycle", f"launch:{app}",
                                    t0, detail, exc)
        detail["exit_code"] = res.exit_code
        detail["stdout_tail"] = res.stdout[-400:]
        detail["stderr_tail"] = res.stderr[-400:]
        error = ""
        if activity:
            status = _parse_field(r"Status:\s*(\S+)", res.stdout)
            detail["launch_status"] = status
            detail["launch_state"] = _parse_field(r"LaunchState:\s*(\S+)", res.stdout)
            total = _parse_field(r"TotalTime:\s*(\d+)", res.stdout)
            detail["total_time_ms"] = int(total) if total else None
            ok = res.exit_code == 0 and status == "ok"
            if not ok:
                error = f"am start failed (exit {res.exit_code}, status {status!r})"
        else:
            # monkey exits 0 even when it finds no activities — require the
            # injected-events confirmation, not just the exit code
            ok = res.exit_code == 0 and "Events injected" in res.stdout
            if not ok:
                error = f"monkey launch failed (exit {res.exit_code})"
        return self._verb_done("launch", "lifecycle", f"launch:{app}",
                               t0, ok, detail, error)

    @staticmethod
    def _component(app: str, activity: str) -> str:
        if "/" in activity:                    # already a component spec
            return activity
        if activity.startswith(".") or "." in activity:
            return f"{app}/{activity}"         # .Main or fully-qualified class
        return f"{app}/.{activity}"            # short name → relative class

    def force_stop(self, app: str, *, timeout: Optional[float] = None) -> VerbResult:
        """Stop ``app`` via ``am force-stop`` (no data wipe)."""
        t0 = time.monotonic()
        budget = self._timeout(timeout)
        cmd = self._adb_cmd("shell", f"am force-stop {app}")
        detail: dict[str, Any] = {"app": app, "cmd": cmd, "timeout_s": budget}
        try:
            res = self.provider.execute(self.env_id, cmd, timeout=budget)
        except Exception as exc:  # noqa: BLE001
            return self._verb_error("force_stop", "lifecycle",
                                    f"force_stop:{app}", t0, detail, exc)
        detail["exit_code"] = res.exit_code
        detail["stderr_tail"] = res.stderr[-400:]
        ok = res.exit_code == 0
        error = "" if ok else (f"am force-stop failed (exit {res.exit_code}): "
                               f"{res.stderr.strip()[:200]}")
        return self._verb_done("force_stop", "lifecycle", f"force_stop:{app}",
                               t0, ok, detail, error)

    def install(self, apk_path: "str | Path",
                *, timeout: Optional[float] = None) -> VerbResult:
        """Install a *local* APK: provider-push it into the environment, then
        ``adb install -r -t`` from there.

        Note: under TCG, large APKs can exceed the default 180 s budget —
        pass ``timeout=600`` (the e2b provider's install budget) when
        installing the full app build. Split-APK bundles (install-multiple)
        are out of scope for this verb; the reference-observe driver owns
        that flow.
        """
        apk = Path(apk_path)
        if not apk.is_file():
            raise FileNotFoundError(f"apk not found: {apk}")
        t0 = time.monotonic()
        budget = self._timeout(timeout)
        staging = f"{STAGING_DIR}/{apk.name}"
        detail: dict[str, Any] = {"apk": str(apk), "bytes": apk.stat().st_size,
                                  "staging": staging, "timeout_s": budget}
        try:
            self.provider.transfer(self.env_id, "push", apk, staging)
            cmd = self._adb_cmd("install", "-r", "-t", staging)
            detail["cmd"] = cmd
            res = self.provider.execute(self.env_id, cmd, timeout=budget)
        except Exception as exc:  # noqa: BLE001
            return self._verb_error("install", "lifecycle",
                                    f"install:{apk.name}", t0, detail, exc)
        detail["exit_code"] = res.exit_code
        detail["stdout_tail"] = res.stdout[-400:]
        ok = res.exit_code == 0 and "Success" in res.stdout
        error = "" if ok else (f"adb install failed (exit {res.exit_code}): "
                               f"{(res.stdout + res.stderr).strip()[:200]}")
        return self._verb_done("install", "lifecycle", f"install:{apk.name}",
                               t0, ok, detail, error)

    # ------------------------------------------------------ permission verbs

    def _pm_permission(self, verb_name: str, app: str, permission: str,
                       timeout: Optional[float]) -> VerbResult:
        t0 = time.monotonic()
        budget = self._timeout(timeout)
        cmd = self._adb_cmd("shell", f"pm {verb_name} {app} {permission}")
        label = f"{verb_name}:{app}:{permission}"
        detail: dict[str, Any] = {"app": app, "permission": permission,
                                  "cmd": cmd, "timeout_s": budget}
        try:
            res = self.provider.execute(self.env_id, cmd, timeout=budget)
        except Exception as exc:  # noqa: BLE001
            return self._verb_error(verb_name, "execute", label, t0, detail, exc)
        detail["exit_code"] = res.exit_code
        detail["stderr_tail"] = res.stderr[-400:]
        ok = res.exit_code == 0
        error = "" if ok else (f"pm {verb_name} failed (exit {res.exit_code}): "
                               f"{res.stderr.strip()[:200]}")
        return self._verb_done(verb_name, "execute", label, t0, ok, detail, error)

    def grant(self, app: str, permission: str,
              *, timeout: Optional[float] = None) -> VerbResult:
        """Grant a runtime permission (``pm grant``); a denial (e.g. a
        non-runtime permission) returns ``ok=False`` with pm's stderr."""
        return self._pm_permission("grant", app, permission, timeout)

    def revoke(self, app: str, permission: str,
               *, timeout: Optional[float] = None) -> VerbResult:
        """Revoke a runtime permission (``pm revoke``)."""
        return self._pm_permission("revoke", app, permission, timeout)

    # ------------------------------------------------------ interaction verbs

    def _interact_verb(self, verb: str, action: Interaction,
                       timeout: Optional[float],
                       extra: Optional[dict[str, Any]] = None) -> VerbResult:
        t0 = time.monotonic()
        budget = self._timeout(timeout)
        label = action.label or action.kind
        detail: dict[str, Any] = {"kind": action.kind, "label": action.label,
                                  "timeout_s": budget,
                                  **_interaction_fields(action)}
        if extra:
            detail.update(extra)
        try:
            self._provider_interact(action, budget)
        except Exception as exc:  # noqa: BLE001
            return self._verb_error(verb, "interact", label, t0, detail, exc)
        return self._verb_done(verb, "interact", label, t0, True, detail)

    def tap(self, x: int, y: int, *, timeout: Optional[float] = None) -> VerbResult:
        """Tap absolute device coordinates."""
        action = Interaction(kind="tap", x=int(x), y=int(y),
                             label=f"tap:{int(x)},{int(y)}")
        return self._interact_verb("tap", action, timeout)

    def tap_semantic(self, target_id: str, *, app: Optional[str] = None,
                     refresh: bool = False,
                     timeout: Optional[float] = None) -> VerbResult:
        """Tap a semantic target from the registry (scenario files use these
        ids only — never coordinates).

        Resolution order: ui-selector match in the latest ui dump (auto-
        fetching one when no dump is cached), else the registered
        coordinates. An unmatched selector-only target is a structured
        ``ok=False`` (runtime state); an unknown id raises
        :class:`UnknownTargetError` (authoring error — loud).
        """
        t0 = time.monotonic()
        budget = self._timeout(timeout)
        scope = app or self._app
        spec = self.registry.get(target_id, scope)   # raises for unknown ids
        detail: dict[str, Any] = {"target": target_id, "app_scope": scope or "",
                                  "selector": spec.selector, "timeout_s": budget}
        resolved = self.resolve_target(target_id, app=scope, refresh=refresh,
                                       timeout=budget)
        if resolved is None:
            error = (f"target {target_id!r} unresolvable: selector "
                     f"{spec.selector!r} not matched in the latest ui dump "
                     f"and no coordinate fallback is registered")
            return self._verb_done("tap_semantic", "interact",
                                   f"tap_semantic:{target_id}", t0, False,
                                   detail, error)
        detail["resolved_x"] = resolved.x
        detail["resolved_y"] = resolved.y
        detail["source"] = resolved.source
        if resolved.fallback_reason:
            detail["fallback_reason"] = resolved.fallback_reason
        action = Interaction(kind="tap", x=resolved.x, y=resolved.y,
                             label=target_id)
        detail.update(kind="tap", label=target_id)
        try:
            self._provider_interact(action, budget)
        except Exception as exc:  # noqa: BLE001
            return self._verb_error("tap_semantic", "interact",
                                    f"tap_semantic:{target_id}", t0, detail, exc)
        return self._verb_done("tap_semantic", "interact",
                               f"tap_semantic:{target_id}", t0, True, detail)

    def swipe(self, x1: int, y1: int, x2: int, y2: int, ms: int = 300,
              *, timeout: Optional[float] = None) -> VerbResult:
        """Swipe from (x1, y1) to (x2, y2) over ``ms`` milliseconds."""
        action = Interaction(kind="swipe", x=int(x1), y=int(y1), x2=int(x2),
                             y2=int(y2), duration_ms=int(ms),
                             label=f"swipe:{int(x1)},{int(y1)},{int(x2)},{int(y2)}:{int(ms)}")
        return self._interact_verb("swipe", action, timeout)

    def type_text(self, text: str, *, timeout: Optional[float] = None) -> VerbResult:
        """Type text via ``input text`` — raw spaces cannot survive the adb
        command line, so they are encoded as ``%s`` (adb's own escape); the
        original text is preserved in ``detail``. Empty text is a no-op."""
        if text == "":
            return VerbResult(verb="type_text", ok=True,
                              detail={"no_op": True, "reason": "empty text"},
                              duration_ms=0)
        encoded = text.replace(" ", "%s")
        action = Interaction(kind="type", text=encoded,
                             label=f"type:{text[:40]!r}")
        return self._interact_verb("type_text", action, timeout,
                                   extra={"text": text, "encoded": encoded})

    def key(self, code: str, *, timeout: Optional[float] = None) -> VerbResult:
        """Press a keycode by name (``"ENTER"`` and ``"KEYCODE_ENTER"`` both
        work; names are normalized to ``KEYCODE_*``)."""
        keycode = code if code.startswith("KEYCODE_") else f"KEYCODE_{code.upper()}"
        action = Interaction(kind="key", keycode=keycode, label=f"key:{keycode}")
        return self._interact_verb("key", action, timeout)

    def back(self, *, timeout: Optional[float] = None) -> VerbResult:
        """Press BACK."""
        action = Interaction(kind="press", button="back", label="back")
        return self._interact_verb("back", action, timeout)

    def home(self, *, timeout: Optional[float] = None) -> VerbResult:
        """Press HOME."""
        action = Interaction(kind="press", button="home", label="home")
        return self._interact_verb("home", action, timeout)

    def wake(self, *, timeout: Optional[float] = None) -> VerbResult:
        """Wake the device (KEYCODE_WAKEUP)."""
        action = Interaction(kind="wake", label="wake")
        return self._interact_verb("wake", action, timeout)

    # ---------------------------------------------------------- target lookup

    def resolve_target(self, target_id: str, *, app: Optional[str] = None,
                       refresh: bool = False,
                       timeout: Optional[float] = None) -> Optional[ResolvedTarget]:
        """Resolve a semantic id per the registry's resolution order.

        Returns ``None`` when the target cannot be resolved at runtime
        (selector unmatched and no coordinates). Raises
        :class:`UnknownTargetError` for ids that are not registered at all.
        """
        scope = app or self._app
        spec = self.registry.get(target_id, scope)
        fallback_reason = ""
        if spec.terms:
            node: Optional[dict[str, Any]] = None
            try:
                xml, _refreshed, _artifact = self._ui_dump_xml(
                    refresh=refresh, timeout=timeout)
                node = match_selector(xml, spec.terms)
                if node is None:
                    fallback_reason = (f"selector {spec.selector!r} not "
                                       f"matched in the latest ui dump")
            except Exception as exc:  # noqa: BLE001 — dump unavailable
                fallback_reason = (f"ui dump unavailable "
                                   f"({type(exc).__name__}: {exc})")
            if node is not None:
                return ResolvedTarget(target_id=target_id, x=node["x"],
                                      y=node["y"], source="ui-selector",
                                      selector=spec.selector,
                                      bounds=node["bounds"],
                                      node_text=node["text"],
                                      node_resource_id=node["resource-id"])
        if spec.has_coordinates:
            return ResolvedTarget(target_id=target_id, x=int(spec.x or 0),
                                  y=int(spec.y or 0), source="coordinates",
                                  selector=spec.selector,
                                  fallback_reason=fallback_reason)
        return None

    # ---------------------------------------------------------- capture verbs

    def _capture_to_local(self, kind: CaptureKind, suffix: str,
                          budget: float) -> tuple[Artifact, Path]:
        """Provider capture + provider transfer(pull) into the bridge workdir
        (provider-neutral: bytes always cross via transfer, never ad hoc)."""
        artifact = self._provider_capture(kind, budget)
        self._artifact_seq += 1
        local = self.workdir / f"{self._artifact_seq:04d}-{suffix}"
        self.provider.transfer(self.env_id, "pull", local, artifact.sandbox_path)
        return artifact, local

    def _ui_dump_xml(self, refresh: bool,
                     timeout: Optional[float]) -> tuple[str, bool, Optional[Artifact]]:
        """Ui-hierarchy XML, cache-aware (``dump_max_age_s`` optional
        staleness bound); returns ``(xml, refreshed, artifact)``."""
        now = time.monotonic()
        cache_ok = (self._dump_xml is not None and not refresh
                    and (self.dump_max_age_s is None
                         or now - self._dump_ts <= self.dump_max_age_s))
        if cache_ok:
            return self._dump_xml or "", False, self._dump_artifact
        artifact, local = self._capture_to_local(
            CaptureKind.ui_hierarchy, "ui.xml", self._timeout(timeout))
        xml = local.read_text(encoding="utf-8", errors="replace")
        self._dump_xml = xml
        self._dump_ts = time.monotonic()
        self._dump_artifact = artifact
        return xml, True, artifact

    def screenshot(self, *, timeout: Optional[float] = None) -> VerbResult:
        """Capture a screenshot; the PNG bytes ride in ``result.data`` and
        the sha256 (provider-side vs bridge-side) is cross-checked."""
        t0 = time.monotonic()
        budget = self._timeout(timeout)
        detail: dict[str, Any] = {"timeout_s": budget}
        try:
            artifact, local = self._capture_to_local(
                CaptureKind.screenshot, "screenshot.png", budget)
        except Exception as exc:  # noqa: BLE001
            return self._verb_error("screenshot", "capture", "screenshot",
                                    t0, detail, exc)
        data = local.read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        detail.update(sandbox_path=artifact.sandbox_path,
                      local_path=str(local), bytes=len(data), sha256=digest)
        ok, error = True, ""
        if artifact.sha256 and artifact.sha256 != digest:
            ok = False
            error = (f"screenshot integrity mismatch: provider "
                     f"{artifact.sha256} != local {digest}")
        return self._verb_done("screenshot", "capture", "screenshot", t0,
                               ok, detail, error, data=data)

    def ui_dump(self, *, timeout: Optional[float] = None) -> VerbResult:
        """Capture a fresh ui-hierarchy dump (also refreshes the target-
        resolution cache); the XML rides in ``result.text``."""
        t0 = time.monotonic()
        budget = self._timeout(timeout)
        detail: dict[str, Any] = {"timeout_s": budget}
        try:
            xml, _refreshed, artifact = self._ui_dump_xml(refresh=True,
                                                          timeout=budget)
        except Exception as exc:  # noqa: BLE001
            return self._verb_error("ui_dump", "capture", "ui_dump",
                                    t0, detail, exc)
        detail.update(sandbox_path=artifact.sandbox_path if artifact else "",
                      bytes=len(xml.encode("utf-8", "replace")))
        return self._verb_done("ui_dump", "capture", "ui_dump", t0, True,
                               detail, text=xml)

    def logcat(self, filter: Optional["str | list[str]"] = None,   # noqa: A002 — task-mandated name
               tail: Optional[int] = None,
               *, timeout: Optional[float] = None) -> VerbResult:
        """Capture the logcat buffer (provider capture) and post-process it
        deterministically bridge-side: ``tail`` keeps the last N lines,
        ``filter`` keeps lines containing any of the given substrings
        (case-sensitive). Processed text rides in ``result.text``."""
        t0 = time.monotonic()
        budget = self._timeout(timeout)
        detail: dict[str, Any] = {"timeout_s": budget}
        try:
            artifact, local = self._capture_to_local(
                CaptureKind.logcat, "logcat.txt", budget)
            text = local.read_text(encoding="utf-8", errors="replace")
        except Exception as exc:  # noqa: BLE001
            return self._verb_error("logcat", "capture", "logcat",
                                    t0, detail, exc)
        lines = text.splitlines()
        total = len(lines)
        if tail is not None:
            if tail < 0:
                raise ValueError(f"tail must be >= 0 (got {tail})")
            lines = lines[-tail:] if tail else []
        needles = [filter] if isinstance(filter, str) else list(filter or [])
        if needles:
            lines = [ln for ln in lines if any(n in ln for n in needles)]
        detail.update(sandbox_path=artifact.sandbox_path,
                      total_lines=total, kept_lines=len(lines),
                      tail=tail, filter=needles)
        return self._verb_done("logcat", "capture", "logcat", t0, True,
                               detail, text="\n".join(lines))

    # ---------------------------------------------------------- file transfer

    def push(self, local: "str | Path", remote: str,
             *, timeout: Optional[float] = None) -> VerbResult:
        """Push a *local* file to a **device** path (e.g. ``/sdcard/...``):
        provider-push into the environment, then ``adb push`` onto the device
        (the provider's transfer is env-side; the device hop is adb)."""
        src = Path(local)
        if not src.is_file():
            raise FileNotFoundError(f"local file not found: {src}")
        t0 = time.monotonic()
        budget = self._timeout(timeout)
        staging = f"{STAGING_DIR}/{src.name}"
        detail: dict[str, Any] = {"local": str(src), "remote": remote,
                                  "staging": staging, "bytes": src.stat().st_size,
                                  "timeout_s": budget}
        try:
            self.provider.transfer(self.env_id, "push", src, staging)
            cmd = self._adb_cmd("push", staging, remote)
            detail["cmd"] = cmd
            res = self.provider.execute(self.env_id, cmd, timeout=budget)
        except Exception as exc:  # noqa: BLE001
            return self._verb_error("push", "lifecycle", f"push:{remote}",
                                    t0, detail, exc)
        detail["exit_code"] = res.exit_code
        ok = res.exit_code == 0 and "file pushed" in res.stdout.lower()
        error = "" if ok else (f"adb push failed (exit {res.exit_code}): "
                               f"{(res.stdout + res.stderr).strip()[:200]}")
        return self._verb_done("push", "lifecycle", f"push:{remote}",
                               t0, ok, detail, error)

    def pull(self, remote: str, local: "str | Path",
             *, timeout: Optional[float] = None) -> VerbResult:
        """Pull a **device** path to a *local* file: ``adb pull`` into the
        environment, then provider-pull to the lab host."""
        dst = Path(local)
        t0 = time.monotonic()
        budget = self._timeout(timeout)
        staging = f"{STAGING_DIR}/{dst.name}"
        detail: dict[str, Any] = {"remote": remote, "local": str(dst),
                                  "staging": staging, "timeout_s": budget}
        try:
            cmd = self._adb_cmd("pull", remote, staging)
            detail["cmd"] = cmd
            res = self.provider.execute(self.env_id, cmd, timeout=budget)
            detail["exit_code"] = res.exit_code
            if res.exit_code != 0 or "file pulled" not in res.stdout.lower():
                error = (f"adb pull failed (exit {res.exit_code}): "
                         f"{(res.stdout + res.stderr).strip()[:200]}")
                return self._verb_done("pull", "lifecycle", f"pull:{remote}",
                                       t0, False, detail, error)
            self.provider.transfer(self.env_id, "pull", dst, staging)
        except Exception as exc:  # noqa: BLE001
            return self._verb_error("pull", "lifecycle", f"pull:{remote}",
                                    t0, detail, exc)
        detail["bytes"] = dst.stat().st_size
        return self._verb_done("pull", "lifecycle", f"pull:{remote}",
                               t0, True, detail)

    # ---------------------------------------------------------------- waiting

    def wait_for(self, predicate: Callable[[], bool],
                 timeout: Optional[float] = None,
                 poll: Optional[float] = None) -> VerbResult:
        """Poll ``predicate()`` every ``poll`` seconds until true or the
        bounded ``timeout`` expires (the only sleep in the bridge lives
        here, and it is deadline-bounded: ``sleep(min(poll, remaining))``).

        The predicate is evaluated *before* the first sleep (fast path), so
        an already-true condition costs exactly one probe. Probe exceptions
        count as "not yet satisfied" and surface in ``detail.last_error``.
        """
        budget = self._timeout(timeout)
        interval = self.poll_s if poll is None else float(poll)
        if interval <= 0:
            raise ValueError(f"poll must be > 0 (got {interval})")
        name = (getattr(predicate, "name", None)
                or getattr(predicate, "__name__", None)
                or repr(predicate))
        t0 = time.monotonic()
        polls = 0
        last_error = ""
        while True:
            polls += 1
            try:
                satisfied = bool(predicate())
            except Exception as exc:  # noqa: BLE001 — raw callables may raise
                satisfied = False
                last_error = f"{type(exc).__name__}: {exc}"
            if satisfied:
                detail = {"predicate": name, "polls": polls,
                          "elapsed_s": round(time.monotonic() - t0, 3),
                          "timeout_s": budget, "poll_s": interval}
                return self._verb_done("wait_for", "execute",
                                       f"wait_for:{name}", t0, True, detail)
            probe_error = getattr(predicate, "last_error", "")
            if probe_error:
                last_error = probe_error     # Predicate-recorded probe failure
            remaining = budget - (time.monotonic() - t0)
            if remaining <= 0:
                detail = {"predicate": name, "polls": polls,
                          "elapsed_s": round(time.monotonic() - t0, 3),
                          "timeout_s": budget, "poll_s": interval}
                if last_error:
                    detail["last_error"] = last_error
                error = f"condition {name!r} not met within {budget}s"
                return self._verb_done("wait_for", "execute",
                                       f"wait_for:{name}", t0, False,
                                       detail, error)
            time.sleep(min(interval, remaining))

    def wait_idle(self, timeout: Optional[float] = None,
                  poll: Optional[float] = None, *,
                  stable_polls: int = 2) -> VerbResult:
        """Wait (bounded, poll-based) until the device is settled: boot
        completed and the window focus identical across ``stable_polls``
        consecutive probes. Never a bare sleep — see :meth:`wait_for`."""
        if stable_polls < 2:
            raise ValueError(f"stable_polls must be >= 2 (got {stable_polls})")
        predicate = self.device_idle(stable_polls=stable_polls)
        result = self.wait_for(predicate, timeout=timeout, poll=poll)
        result.verb = "wait_idle"
        result.detail["predicate"] = "device_idle"
        result.detail["stable_polls"] = stable_polls
        return result

    # -------------------------------------------------------------- predicates

    def activity_resumed(self, name: str) -> Predicate:
        """True when the top/resumed activity matches ``name`` (substring —
        a short class name, ``.MainActivity`` or a full component all work)."""
        def check() -> bool:
            res = self._shell(_RESUMED_CMD)
            line = next((ln.strip() for ln in res.stdout.splitlines()
                         if ln.strip()), "")
            return bool(line) and name in line
        return Predicate(f"activity_resumed({name!r})", check)

    def text_visible(self, text: str) -> Predicate:
        """True when ``text`` appears in a fresh ui-hierarchy dump (raw XML
        or entity-decoded). Each check refreshes the dump cache."""
        def check() -> bool:
            xml, _refreshed, _artifact = self._ui_dump_xml(
                refresh=True, timeout=self.default_timeout_s)
            return text in xml or text in html.unescape(xml)
        return Predicate(f"text_visible({text!r})", check)

    def package_foreground(self, pkg: str) -> Predicate:
        """True when the current window focus belongs to ``pkg``."""
        def check() -> bool:
            res = self._shell(_FOCUS_CMD)
            return pkg in res.stdout
        return Predicate(f"package_foreground({pkg!r})", check)

    def device_idle(self, *, stable_polls: int = 2) -> Predicate:
        """True when boot is complete and the window focus has been identical
        across the last ``stable_polls`` probes (the boot flag is probed
        until it reads 1, then cached — it does not flip mid-scenario)."""
        state: dict[str, Any] = {"boot_ok": False, "focus_run": []}

        def check() -> bool:
            if not state["boot_ok"]:
                boot = self._shell(_BOOT_CMD)
                state["boot_ok"] = (boot.exit_code == 0
                                    and boot.stdout.strip().endswith("1"))
                if not state["boot_ok"]:
                    return False
            focus = self._shell(_FOCUS_CMD)
            line = next((ln.strip() for ln in focus.stdout.splitlines()
                         if ln.strip()), "")
            if not line:
                return False
            run: list[str] = state["focus_run"]
            run.append(line)
            recent = run[-stable_polls:]
            return len(recent) == stable_polls and len(set(recent)) == 1
        return Predicate(f"device_idle(stable_polls={stable_polls})", check)
