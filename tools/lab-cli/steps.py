"""Step parsing + the step→verb mapping table (CAMSCAN-007).

The scenario DSL's ``steps`` list is provider-neutral *what*; this module
turns each step into a declarative plan of **adb-bridge verbs** (the
provider-neutral *how* layer, ``tools/adb-bridge/README.md``). The table
covers every step name used by ``lab/scenarios/*.yaml`` **and** the DSL
spec's base vocabulary (``swipe``/``type``/``press``/``wait-for``/
``assert-visible``), so no corpus step can resolve to "no verb" — a step
outside the table is an operational error (fail-loud, never a silent
skip).

Call form: each planned call names one adb-bridge verb (or the ``observe``
composite = screenshot + ui dump, the evidence pair EVIDENCE.md wants per
step). Semantic targets are recorded by id; their *resolution* is
runtime state (ui-selector match in the latest dump, else registered
coordinates — the bridge's job). The plan additionally reports each
target's registry status against the seed registry
(``tools/adb-bridge/targets.yaml``):

- ``registered(<scope>)`` — present in the global scope or an app scope
  (``shutter_button`` in the implementation app scope today);
- ``design-contract`` — an intended control id the implementation flows
  must materialize (the registry's own honesty note: ids come from live
  dumps / CAMSCAN-010, never invention presented as truth).

App package placeholder: ``APP`` in a call's params means "the package of
the environment being driven" (reference vs implementation — resolved at
dispatch time, never baked into the corpus).
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

from tools.adb_bridge.bridge import TargetRegistry
from tools.lab_cli.scenarios import LabCliError

#: Placeholder for the env's app package (reference vs implementation).
APP = "<app>"

#: ``grant-permission: <name>`` → Android permission (corpus uses camera).
PERMISSIONS: dict[str, str] = {
    "camera": "android.permission.CAMERA",
    "storage": "android.permission.WRITE_EXTERNAL_STORAGE",
    "microphone": "android.permission.RECORD_AUDIO",
}

#: Default long-press duration (same-point swipe idiom, adb `input`).
LONG_PRESS_MS = 650

#: Default crop-handle drag (bottom-center upward) — deterministic plan
#: coordinates; live runs re-resolve from the fresh ui dump.
_CROP_DRAG = {"x1": 540, "y1": 1500, "x2": 540, "y2": 900, "ms": 400}

_STEP_RE = re.compile(
    r"^(?P<action>[a-z][a-z0-9-]*)"
    r"(?:\((?P<paren>[^()]*)\))?"
    r"(?:\s*:\s*(?P<colon>.+))?$"
)


@dataclass
class Step:
    """One parsed DSL step (string or single-key mapping form)."""

    raw: Any
    action: str
    arg: Any = None

    def label(self) -> str:
        if self.arg is None or self.arg == "":
            return self.action
        if isinstance(self.arg, dict):
            inner = ",".join(f"{k}={v}" for k, v in sorted(self.arg.items()))
            return f"{self.action}({inner})"
        return f"{self.action}:{self.arg}"


@dataclass(frozen=True)
class VerbCall:
    """One declarative adb-bridge verb call (or the observe composite)."""

    verb: str                       # adb-bridge verb, or "observe"
    params: dict[str, Any] = field(default_factory=dict)
    target: str = ""                # semantic target id ("" if none)
    note: str = ""


@dataclass
class StepPlan:
    """A step + its planned verb calls + registry status of targets."""

    index: int                      # 1-based, DSL order
    step: Step
    calls: list[VerbCall]
    note: str = ""


# ------------------------------------------------------------------- parsing

def parse_step(raw: Any) -> Step:
    """Parse one DSL step entry (string or single-key mapping form)."""
    if isinstance(raw, dict):
        if len(raw) != 1:
            raise LabCliError(
                f"step must be a string or a single-key mapping, got {raw!r}")
        (action, arg), = raw.items()
        if not isinstance(action, str) or not re.fullmatch(
                r"[a-z][a-z0-9-]*", action or ""):
            raise LabCliError(f"bad step action {action!r} in {raw!r}")
        return Step(raw=raw, action=action, arg=arg)
    if not isinstance(raw, str) or not raw.strip():
        raise LabCliError(f"bad step entry {raw!r}")
    match = _STEP_RE.match(raw.strip())
    if not match:
        raise LabCliError(f"unparseable step {raw!r}")
    action = match.group("action")
    paren, colon = match.group("paren"), match.group("colon")
    arg = colon if colon is not None else paren
    return Step(raw=raw.strip(), action=action, arg=arg)


# ------------------------------------------------------------ mapping table

def _tap(target: str, note: str = "") -> list[VerbCall]:
    return [VerbCall("tap_semantic", {"target": target}, target=target,
                     note=note)]


def _calls_for(step: Step, registry: TargetRegistry | None) \
        -> tuple[list[VerbCall], str]:
    """The mapping table: one corpus/DSL step action → verb calls."""
    action, arg = step.action, step.arg
    if action == "launch":
        return ([VerbCall("launch", {"app": APP})],
                "am start -W; launcher component resolved per app")
    if action == "tap":
        target = str(arg or "")
        if not target:
            raise LabCliError(f"tap step needs a target: {step.raw!r}")
        return _tap(target), "semantic control id (SCENARIO-DSL)"
    if action == "swipe":
        params = dict(arg) if isinstance(arg, dict) else dict(_CROP_DRAG)
        return ([VerbCall("swipe", params)], "DSL base verb")
    if action == "type":
        return ([VerbCall("type_text", {"text": str(arg or "")})],
                "DSL base verb; spaces encoded per bridge")
    if action == "press":
        button = str(arg or "")
        if button not in ("back", "home"):
            raise LabCliError(
                f"press step arg must be back|home, got {arg!r}")
        return ([VerbCall(button, {})], "DSL base verb (keyevent)")
    if action == "grant-permission":
        name = str(arg or "")
        permission = PERMISSIONS.get(name, name)
        return ([VerbCall("grant", {"app": APP, "permission": permission})],
                "pm grant via bridge")
    if action == "capture":
        return _tap("shutter_button"), "shutter press triggers the capture"
    if action in ("accept-document", "accept", "confirm", "confirm-delete",
                  "done", "complete-onboarding"):
        return _tap("next_button"), "primary affirmative control"
    if action == "save":
        return _tap("save_button"), "save/persist the document"
    if action == "rotate":
        return _tap("rotate_button"), "design-contract control id (pending registry)"
    if action == "adjust-crop":
        return ([VerbCall("swipe", dict(_CROP_DRAG), target="crop_handle")],
                "crop-handle drag; coords re-resolved from the live dump")
    if action in ("observe-detection-overlay", "observe-crop-stage",
                  "observe-viewer"):
        return ([VerbCall("observe", {})],
                "observation step — no interaction; screenshot + ui dump")
    if action in ("reopen-document", "open-document"):
        return _tap("gallery_tile_1"), \
            "document identity from the step/fixture argument"
    if action == "delete-document":
        coords = _registry_coords(registry, "gallery_tile_1")
        if coords:
            params = {"x1": coords[0], "y1": coords[1],
                      "x2": coords[0], "y2": coords[1], "ms": LONG_PRESS_MS}
            call = VerbCall("swipe", params, target="gallery_tile_1")
        else:  # registry unavailable — plan the symbolic form
            call = VerbCall("tap_semantic", {"target": "gallery_tile_1"},
                            target="gallery_tile_1")
        return ([call], "long-press selection (same-point swipe idiom)")
    if action == "cycle-enhancement-modes":
        return _tap("enhance_button"), \
            "repeated taps cycle the modes (live driver repeats)"
    if action == "run-ocr":
        return _tap("ocr_button"), "design-contract control id"
    if action == "export":
        return _tap("export_button"), \
            f"export format={arg!r}; outputs/ artifacts + sidecars"
    if action == "share":
        return _tap("share_button"), "design-contract control id"
    if action == "wait-for":
        return ([VerbCall("wait_for", {"condition": str(arg or "")})],
                "bounded poll (bridge.wait_for) — the only sleep in the stack")
    if action == "assert-visible":
        return ([VerbCall("observe", {})],
                f"assert visible {arg!r} from the ui dump")
    raise LabCliError(
        f"step action {action!r} is not in the mapping table "
        f"(step {step.raw!r}) — extend tools/lab-cli/steps.py")


def _registry_coords(registry: TargetRegistry | None, target: str) \
        -> tuple[int, int] | None:
    if registry is None:
        return None
    for app in ("org.payswap.camscan", "com.intsig.camscanner", None):
        try:
            entry = registry.get(target, app)  # type: ignore[arg-type]
        except KeyError:                       # UnknownTargetError is a KeyError
            continue
        if entry.has_coordinates:
            return (entry.x, entry.y)
    return None


#: Every step action the mapping table resolves (test-pinned against the
#: real corpus: no ``lab/scenarios/*.yaml`` step may fall outside it).
KNOWN_ACTIONS: tuple[str, ...] = (
    "launch", "tap", "swipe", "type", "press", "grant-permission", "capture",
    "accept-document", "accept", "confirm", "confirm-delete", "done",
    "complete-onboarding", "save", "rotate", "adjust-crop",
    "observe-detection-overlay", "observe-crop-stage", "observe-viewer",
    "reopen-document", "open-document", "delete-document",
    "cycle-enhancement-modes", "run-ocr", "export", "share", "wait-for",
    "assert-visible",
)


# ------------------------------------------------------------------ planning

def registry_status(target: str, registry: TargetRegistry | None) -> str:
    """Where a semantic target id resolves (honesty marker).

    ``registered(<scopes>)`` — the scopes (global and/or app) whose merged
    id set contains the target; ``design-contract`` — an intended control
    id the implementation flows must materialize (the seed registry's own
    vocabulary for unmaterialized ids — never invention as truth).
    """
    if not target:
        return ""
    if registry is None:
        return "unknown (registry not loaded)"
    scopes = [name for name, member in (
        ("global", target in set(registry.ids(None))),
        ("org.payswap.camscan",
         target in set(registry.ids("org.payswap.camscan"))),
        ("com.intsig.camscanner",
         target in set(registry.ids("com.intsig.camscanner"))),
    ) if member]
    if scopes:
        return f"registered({','.join(sorted(scopes))})"
    return "design-contract"


def plan_steps(steps: list[Any], registry: TargetRegistry | None = None) \
        -> list[StepPlan]:
    """Parse + map every step; raises on any unmapped action."""
    plans: list[StepPlan] = []
    for index, raw in enumerate(steps, start=1):
        step = parse_step(raw)
        calls, note = _calls_for(step, registry)
        plans.append(StepPlan(index=index, step=step, calls=list(calls),
                              note=note))
    return plans


def render_call(call: VerbCall) -> str:
    """Compact deterministic rendering of one planned verb call."""
    if call.verb == "observe":
        return "observe(screenshot+ui_dump)"
    parts = [f"{k}={v}" for k, v in sorted(call.params.items())]
    return f"{call.verb}({', '.join(parts)})"


def render_plan_line(plan: StepPlan, registry: TargetRegistry | None) -> str:
    """One ``--plan`` step line: index, step, verbs, target status."""
    rendered = " + ".join(render_call(c) for c in plan.calls)
    statuses = [registry_status(c.target, registry)
                for c in plan.calls if c.target]
    target_note = f" target={statuses[0]}" if len(statuses) == 1 else ""
    note = f"  # {plan.note}" if plan.note else ""
    return (f"step {plan.index:02d} {plan.step.label()} → "
            f"{rendered}{target_note}{note}")
