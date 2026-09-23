"""Ignore-list masks for reference-app-specific trace steps.

A mask file is a small JSON document (the mask format is this tool's own
design; JSON keeps the CLI stdlib-only — see ``masks/README.md``):

```json
{
  "scenario": "single-document-capture",
  "description": "…",
  "steps": [
    {"action": "tap", "target": "premium-upsell-dismiss",
     "reason": "CamScanner-only UX documented by Worker 2",
     "side": "reference"}
  ]
}
```

- one file per scenario, ``<scenario-id>.json`` under
  ``tools/parity-cli/masks/``;
- ``steps[].action`` is required (string); ``target``/``index`` are
  optional narrowing matches (``index`` = 0-based original position);
- ``side`` narrows which side's trace the rule removes from alignment
  (``reference`` default — the documented use is CamScanner-only steps);
- ``reason`` is REQUIRED for every rule: a mask without a recorded
  justification is not a mask, it is a silent divergence — fail-closed.

Loading is fail-closed: unparseable JSON, a missing ``action``, or a
mask rule without a ``reason`` aborts the comparison (a broken ignore
list must never silently change a verdict). Mask files for other
scenarios are ignored. A rule that matches nothing is reported as
considered-but-inert (apps change versions; that is a warning, not an
error).
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .model import ParityCliError

#: Allowed mask sides.
SIDES = ("reference", "implementation", "both")

#: Default masks directory: the tool's own ``masks/``.
DEFAULT_MASKS_DIR = Path(__file__).resolve().parent / "masks"


@dataclass
class MaskRule:
    """One compiled ignore rule."""
    source: str                 # repo-relative file name for the diff
    ordinal: int                # rule position within the file (1-based)
    action: str
    target: str | None = None
    index: int | None = None
    side: str = "reference"
    reason: str = ""
    matched: int = 0

    @property
    def applies_reference(self) -> bool:
        return self.side in ("reference", "both")

    @property
    def applies_implementation(self) -> bool:
        return self.side in ("implementation", "both")

    def matches(self, step: dict[str, Any], index: int) -> bool:
        if step.get("action") != self.action:
            return False
        if self.target is not None and step.get("target") != self.target:
            return False
        return not (self.index is not None and index != self.index)


@dataclass
class MaskApplication:
    """The record of masks applied to a run comparison (diff.json)."""
    rules_considered: int = 0
    applied: list[dict[str, Any]] = field(default_factory=list)
    removed_reference_steps: list[int] = field(default_factory=list)
    removed_implementation_steps: list[int] = field(default_factory=list)
    kept_reference_steps: list[int] = field(default_factory=list)
    kept_implementation_steps: list[int] = field(default_factory=list)


def _validate_rule(raw: Any, source: str, ordinal: int) -> MaskRule:
    if not isinstance(raw, dict):
        raise ParityCliError(
            f"mask file {source}: steps[{ordinal - 1}] must be an object")
    action = raw.get("action")
    if not isinstance(action, str) or not action:
        raise ParityCliError(
            f"mask file {source}: steps[{ordinal - 1}].action must be a "
            "non-empty string")
    reason = raw.get("reason")
    if not isinstance(reason, str) or not reason.strip():
        raise ParityCliError(
            f"mask file {source}: steps[{ordinal - 1}] has no 'reason' — "
            "an undocumented ignore rule is a silent divergence (fail-closed)")
    side = raw.get("side", "reference")
    if side not in SIDES:
        raise ParityCliError(
            f"mask file {source}: steps[{ordinal - 1}].side must be one "
            f"of {list(SIDES)} (got {side!r})")
    target = raw.get("target")
    if target is not None and (not isinstance(target, str) or not target):
        raise ParityCliError(
            f"mask file {source}: steps[{ordinal - 1}].target must be a "
            "non-empty string or null")
    index = raw.get("index")
    if index is not None and (not isinstance(index, int)
                              or isinstance(index, bool) or index < 0):
        raise ParityCliError(
            f"mask file {source}: steps[{ordinal - 1}].index must be a "
            "non-negative integer or null")
    return MaskRule(source=source, ordinal=ordinal, action=action,
                    target=target, index=index, side=side,
                    reason=reason.strip())


def load_masks(masks_dir: Path, scenario: str) -> list[MaskRule]:
    """Load + validate the mask rules for one scenario (fail-closed)."""
    masks_dir = Path(masks_dir)
    if not masks_dir.is_dir():
        return []
    rules: list[MaskRule] = []
    for path in sorted(masks_dir.glob("*.json")):
        try:
            doc = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as e:
            raise ParityCliError(
                f"mask file {path.name}: unparseable ({e})") from e
        if not isinstance(doc, dict):
            raise ParityCliError(
                f"mask file {path.name}: expected a JSON object")
        mask_scenario = doc.get("scenario")
        if not isinstance(mask_scenario, str) or not mask_scenario:
            raise ParityCliError(
                f"mask file {path.name}: missing 'scenario' string")
        if mask_scenario != scenario:
            continue
        steps = doc.get("steps")
        if not isinstance(steps, list):
            raise ParityCliError(
                f"mask file {path.name}: 'steps' must be a list")
        for i, raw in enumerate(steps, start=1):
            rules.append(_validate_rule(raw, path.name, i))
    return rules


def apply_masks(reference: list[dict[str, Any]],
                implementation: list[dict[str, Any]],
                rules: list[MaskRule]
                ) -> tuple[list[tuple[int, dict[str, Any]]],
                           list[tuple[int, dict[str, Any]]],
                           MaskApplication]:
    """Remove masked steps from both traces.

    Returns ``(reference_masked, implementation_masked, record)`` where
    each masked trace is a list of ``(original_index, step)`` pairs in
    recorded order — per-step artifacts are numbered by ORIGINAL step
    position (the runner numbers them while recording), while action
    alignment works on the masked order. A step is removed at most once
    (first matching rule wins); the record lists the original 0-based
    indices removed per side and, per applied rule, the indices that
    rule removed.
    """
    record = MaskApplication(rules_considered=len(rules))

    def run_side(trace: list[dict[str, Any]],
                 side: str) -> list[tuple[int, dict[str, Any]]]:
        removed: set[int] = set()
        for rule in rules:
            applies = (rule.applies_reference if side == "reference"
                       else rule.applies_implementation)
            if not applies:
                continue
            rule_removed: list[int] = []
            for index, step in enumerate(trace):
                if index in removed:
                    continue
                if rule.matches(step, index):
                    removed.add(index)
                    rule_removed.append(index)
            if rule_removed:
                rule.matched += len(rule_removed)
                record.applied.append({
                    "file": rule.source,
                    "rule": rule.ordinal,
                    "action": rule.action,
                    "target": rule.target,
                    "side": rule.side,
                    "reason": rule.reason,
                    "side_removed_steps": rule_removed,
                })
        kept = [(index, step) for index, step in enumerate(trace)
                if index not in removed]
        return kept

    ref_masked = run_side(reference, "reference")
    impl_masked = run_side(implementation, "implementation")
    record.kept_reference_steps = [index for index, _ in ref_masked]
    record.kept_implementation_steps = [index for index, _ in impl_masked]
    record.removed_reference_steps = sorted(
        set(range(len(reference))) - set(record.kept_reference_steps))
    record.removed_implementation_steps = sorted(
        set(range(len(implementation)))
        - set(record.kept_implementation_steps))
    record.applied.sort(key=lambda a: (a["file"], a["rule"]))
    return ref_masked, impl_masked, record
