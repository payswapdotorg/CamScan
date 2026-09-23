"""``gap <run-id>`` — emit gap yamls for open divergences.

One file per feature: ``lab/reconciliation/<scenario-stem>.<feature>
.gap.yaml`` (GAP-FORMAT.md, Worker 3's directory). A gap coalesces the
diff entries of one feature whose severity is **medium or above**
(low divergences — e.g. output-byte sha differences — are recorded in
diff.json but are not actionable work items).

Emission rules (deterministic, order = feature sort):

- gap ``id`` = ``<scenario-stem>-<feature>-1``; ``scenario`` field is
  the full stem (``S004-single-document-capture``);
- ``severity`` = the harshest entry severity in the feature;
- reference/implementation ``behavior`` is assembled from the diff
  entries' values (evidence-only — no app self-reports);
- ``evidence`` names the manifest path of each side, plus the R2 key
  prefix when that side's manifest records ``r2_key`` entries (proof
  the bundle was uploaded);
- ``required_change`` comes from per-dimension templates (precise
  instructions; never a rewrite of Worker 1's code);
- ``verification.assertions`` is the scenario's own assertion list,
  read from ``runs/<run-id>/scenario.yaml`` (the verbatim copy
  EVIDENCE.md requires — missing file fails closed);
- ``status`` starts ``open``. An existing gap file whose status is NOT
  ``open`` (implemented/verified/accepted/rejected) is left untouched —
  worker/lead annotations are never clobbered; only ``open`` gaps (or
  absent files) are (re)written.

YAML is emitted by a hand-rolled deterministic writer (stdlib-only
deliverable; double-quoted scalars via ``json.dumps`` — JSON string
syntax is valid YAML 1.2 double-quoted style), so identical diffs
produce byte-identical gap files.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .load import run_number, scenario_assertions, scenario_fields_for_run
from .model import SEVERITY_RANK, ParityCliError

#: Entry severities that become gap work items.
GAP_WORTHY_SEVERITIES = ("critical", "high", "medium")

#: Gap statuses that mean "a human already acted on this".
NON_OPEN_STATUSES = ("implemented", "verified", "accepted", "rejected")


@dataclass
class GapResult:
    written: list[str] = field(default_factory=list)
    skipped: list[dict[str, str]] = field(default_factory=list)
    considered: int = 0


def _q(value: str) -> str:
    """Double-quoted YAML scalar (JSON string syntax ⊂ YAML 1.2)."""
    return json.dumps(value, ensure_ascii=False)


def _fmt_value(value: Any) -> str:
    if value is None:
        return "«absent»"
    if isinstance(value, str):
        return value
    return json.dumps(value, sort_keys=True, ensure_ascii=False)


def _behavior(entries: list[dict[str, Any]], side: str) -> str:
    parts: list[str] = []
    for entry in entries:
        value = entry.get(side)
        path = entry.get("path") or entry.get("id")
        if value is None:
            parts.append(f"{path}: no {side} evidence recorded")
        else:
            parts.append(f"{path}: {_fmt_value(value)}")
    return "; ".join(parts)


def _evidence(diff: dict[str, Any], side: str) -> str:
    run_id = diff.get("run_id")
    subjects = diff.get("subjects") or {}
    subject = subjects.get(side) or {}
    path = f"runs/{run_id}/{side}/manifest.json"
    if subject.get("r2_uploaded"):
        return (f"{path} + "
                f"r2:camscan-parity-evidence/runs/{run_id}/{side}/")
    return path


def _required_change(dimension: str, entries: list[dict[str, Any]],
                     run_id: str) -> str:
    ids = ", ".join(entry["id"] for entry in entries)
    if dimension == "bundle":
        side = "implementation" if any(
            e["id"].startswith("bundle-implementation") for e in entries
        ) else "reference"
        return (f"complete the {side} side of run {run_id}: re-run the "
                "scenario to produce a full single-subject evidence "
                f"bundle, assemble it into runs/{run_id}/{side}/, then "
                "re-run compare (external blockers are recorded in the "
                "ledger, never counted as PASS)")
    if dimension == "environment":
        return ("re-run the scenario pair with matched environment "
                "parameters (device profile, locale, timezone, permission "
                "baseline — EVIDENCE.md run-pair requirements); "
                "environment parity is a precondition for judging parity, "
                "not a CamScan code change")
    if dimension == "fixtures":
        return ("re-bundle the run pair against the pinned fixture corpus "
                "(lab/fixtures/manifest.json): identical fixture ids + "
                "sha256 on both sides — different input bytes invalidate "
                "the comparison")
    if dimension == "artifacts":
        return ("re-capture the missing per-step evidence (screenshot/ui "
                "dump) for the recorded steps named in the difference, so "
                "every surviving action_trace step has its evidence in the "
                "manifest")
    if dimension == "actions":
        return (f"make CamScan reproduce the reference action outcomes "
                f"({ids}); the exact per-step outcomes are in the "
                "difference field — evidence is both manifests' "
                "action_trace, never app self-reports")
    if dimension == "outputs":
        return ("implement the missing output production on the "
                 "implementation side (outputs are compared by type + "
                 "count + sha256; byte equality is not required — the "
                 "gap is the missing capability, not different bytes)")
    return f"resolve the divergence ({ids})"


def render_gap_yaml(gap: dict[str, Any]) -> str:
    """Deterministic GAP-FORMAT.md yaml for one gap document."""
    lines = [
        "gap:",
        f"  id: {_q(gap['id'])}",
        f"  scenario: {_q(gap['scenario'])}",
        f"  feature: {_q(gap['feature'])}",
        "  reference:",
        f"    behavior: {_q(gap['reference']['behavior'])}",
        f"    evidence: {_q(gap['reference']['evidence'])}",
        "  implementation:",
        f"    behavior: {_q(gap['implementation']['behavior'])}",
        f"    evidence: {_q(gap['implementation']['evidence'])}",
        f"  difference: {_q(gap['difference'])}",
        f"  severity: {_q(gap['severity'])}",
        f"  required_change: {_q(gap['required_change'])}",
        "  verification:",
        f"    scenario: {_q(gap['verification']['scenario'])}",
        "    assertions: ["
        + ", ".join(_q(a) for a in gap["verification"]["assertions"])
        + "]",
        f"  status: {_q(gap['status'])}",
    ]
    return "\n".join(lines) + "\n"


def _gap_status_on_disk(path: Path) -> str | None:
    """Shallow scan of a gap yaml's ``status:`` value (None if absent)."""
    if not path.is_file():
        return None
    try:
        text = path.read_text(encoding="utf-8")
    except OSError:
        return None
    for raw in text.splitlines():
        stripped = raw.strip()
        if stripped.startswith("status:"):
            value = stripped.partition(":")[2].strip().strip("'\"")
            return value or None
    return None


def gap_documents(diff: dict[str, Any],
                  scenario_fields: dict[str, Any]) -> list[dict[str, Any]]:
    """Build the gap documents for one diff (deterministic order)."""
    run_id = diff.get("run_id")
    scenario = diff.get("scenario")
    if not run_id or not scenario:
        raise ParityCliError(
            "diff.json lacks run_id/scenario — cannot derive gap stems")
    if scenario_fields.get("id") and scenario_fields["id"] != scenario:
        raise ParityCliError(
            f"scenario.yaml declares id {scenario_fields['id']!r} but the "
            f"manifests record {scenario!r} — the run's verbatim scenario "
            "copy must match")
    assertions = scenario_assertions(scenario_fields)
    if not assertions:
        raise ParityCliError(
            f"runs/{run_id}/scenario.yaml carries no assertions — "
            "GAP-FORMAT.md requires verification assertions and the "
            "verbatim scenario copy is the source")
    stem = f"{run_number(run_id)}-{scenario}"

    features: dict[str, list[dict[str, Any]]] = {}
    for entry in diff.get("entries") or []:
        if entry.get("severity") not in GAP_WORTHY_SEVERITIES:
            continue
        feature = entry.get("feature") or entry.get("id")
        features.setdefault(feature, []).append(entry)

    gaps: list[dict[str, Any]] = []
    for feature in sorted(features):
        entries = sorted(features[feature], key=lambda e: e.get("id", ""))
        severity = max(
            (e["severity"] for e in entries),
            key=lambda s: -SEVERITY_RANK[s])
        dimension = entries[0].get("dimension", "")
        gaps.append({
            "id": f"{stem}-{feature}-1",
            "scenario": stem,
            "feature": feature,
            "reference": {
                "behavior": _behavior(entries, "reference"),
                "evidence": _evidence(diff, "reference"),
            },
            "implementation": {
                "behavior": _behavior(entries, "implementation"),
                "evidence": _evidence(diff, "implementation"),
            },
            "difference": "; ".join(
                entry.get("difference", "") for entry in entries),
            "severity": severity,
            "required_change": _required_change(dimension, entries, run_id),
            "verification": {
                "scenario": stem,
                "assertions": assertions,
            },
            "status": "open",
        })
    return gaps


def write_gaps(diff: dict[str, Any], run_dir: Path,
               gaps_dir: Path | None = None) -> GapResult:
    """Emit gap yamls for a diff; see the module docstring."""
    result = GapResult()
    scenario_fields = scenario_fields_for_run(Path(run_dir))
    gaps = gap_documents(diff, scenario_fields)
    result.considered = len(gaps)
    if not gaps:
        return result
    stem = gaps[0]["scenario"]
    out_dir = Path(gaps_dir) if gaps_dir is not None else (
        Path(run_dir).resolve().parents[1] / "lab" / "reconciliation")
    out_dir.mkdir(parents=True, exist_ok=True)
    for gap in gaps:
        path = out_dir / f"{stem}.{gap['feature']}.gap.yaml"
        status = _gap_status_on_disk(path)
        if status is not None and status != "open":
            result.skipped.append({"path": str(path), "status": status})
            continue
        tmp = path.with_name(path.name + ".tmp")
        tmp.write_text(render_gap_yaml(gap), encoding="utf-8",
                       newline="\n")
        tmp.replace(path)
        result.written.append(str(path))
    return result
