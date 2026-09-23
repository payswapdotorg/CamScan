"""``ledger-update <run-id>`` — record a verdict in the parity ledger.

Updates ``lab/parity-ledger/ledger.json`` (the status truth) from
``runs/<run-id>/reconciliation/verdict.json`` + ``diff.json`` (both
must exist — run ``compare`` first):

- ``entry.status`` ← the verdict, through the explicit mapping below;
- ``entry.last_run`` ← {utc (the verdict's evidence-derived
  generated_utc), side: "both", provider, emulator_acceleration,
  run_id, verdict} — exactly the ledger.schema.json shape;
- ``entry.evidence`` ← appended (deduplicated) schema-valid refs: the
  scenario spec (``lab/scenarios/…``), R2 manifest keys for sides whose
  bundle records ``r2_key`` entries, and gap reports in
  ``lab/reconciliation/`` that name this run;
- ``entry.notes`` ← one idempotent ``parity:`` line (previous parity
  lines replaced, other notes preserved);
- ledger ``updated``/``updated_by`` ← generated_utc / ``parity-cli``
  (deterministic — evidence-derived, never wall-clock).

Verdict → ledger status (explicit, deterministic; the ledger's status
vocabulary has no FAIL — the precise verdict always lives in
``last_run.verdict``):

    PASS → PASS      PARTIAL → PARTIAL     FAIL → IMPLEMENTED
    BLOCKED → BLOCKED   NOT_OBSERVED → UNKNOWN

Because ``tools/lab-cli/validate.py`` (CI) enforces ledger↔scenario
status mirror equality, the ``status:`` line of the scenario yaml
(``lab/scenarios/S###-<id>.yaml``) is updated too — a surgical,
comment-preserving single-line edit; the ledger stays the truth and the
yaml stays the documented human-readable mirror. ids/titles are
verified, never rewritten.

Structural invariants of the mutated ledger (required keys, status
vocabulary, evidence ref patterns, last_run shape — a stdlib mirror of
ledger.schema.json's core constraints) are checked before writing.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from tools.evidence_cli.jsonio import load as jsonio_load

from .load import parse_scenario_yaml, run_number
from .model import ParityCliError

#: Explicit verdict → ledger status mapping (see module docstring).
VERDICT_TO_STATUS: dict[str, str] = {
    "PASS": "PASS",
    "PARTIAL": "PARTIAL",
    "FAIL": "IMPLEMENTED",
    "BLOCKED": "BLOCKED",
    "NOT_OBSERVED": "UNKNOWN",
}

#: Ledger statuses (ledger.schema.json const list).
LEDGER_STATUSES = ("UNKNOWN", "DISCOVERED", "SPECIFIED", "IMPLEMENTED",
                   "PARTIAL", "BLOCKED", "PASS")

#: Evidence kinds (ledger.schema.json enum).
EVIDENCE_KINDS = ("document", "report", "manifest", "diff", "verdict",
                  "screenshot", "log")

_EVIDENCE_REF_RES = (
    re.compile(r"^(lab|docs|app|tools)/"),
    re.compile(r"^r2:camscan-parity-evidence/"),
    re.compile(r"^https://"),
)

_ENTRY_REQUIRED = ("id", "scenario", "title", "status", "capability",
                   "owner", "last_run", "evidence", "notes")


@dataclass
class LedgerUpdateResult:
    run_id: str
    scenario: str
    entry_id: str = ""
    status: str = ""
    verdict: str = ""
    last_run: dict[str, Any] = field(default_factory=dict)
    evidence_added: list[dict[str, Any]] = field(default_factory=list)
    notes_line: str = ""
    ledger_path: str = ""
    scenario_path: str = ""
    wrote_ledger: bool = False
    wrote_scenario_mirror: bool = False


def _structural_problems(ledger: dict[str, Any]) -> list[str]:
    """Stdlib mirror of ledger.schema.json's core constraints."""
    problems: list[str] = []
    for key in ("updated", "updated_by", "statuses", "blocked_dependencies",
                "resolved_dependencies", "entries"):
        if key not in ledger:
            problems.append(f"ledger: missing required key {key!r}")
    if not isinstance(ledger.get("entries"), list) or not ledger["entries"]:
        problems.append("ledger: entries must be a non-empty list")
        return problems
    for entry in ledger["entries"]:
        if not isinstance(entry, dict):
            problems.append("ledger.entries: non-object entry")
            continue
        missing = [k for k in _ENTRY_REQUIRED if k not in entry]
        if missing:
            problems.append(
                f"ledger entry {entry.get('id')}: missing keys {missing}")
        if entry.get("status") not in LEDGER_STATUSES:
            problems.append(
                f"ledger entry {entry.get('id')}: status "
                f"{entry.get('status')!r} outside the vocabulary")
        last_run = entry.get("last_run")
        if last_run is not None:
            if not isinstance(last_run, dict) or any(
                    k not in last_run for k in
                    ("utc", "side", "provider", "emulator_acceleration")):
                problems.append(
                    f"ledger entry {entry.get('id')}: last_run must carry "
                    "utc/side/provider/emulator_acceleration")
            elif last_run.get("side") not in ("reference", "implementation",
                                              "both"):
                problems.append(
                    f"ledger entry {entry.get('id')}: last_run.side "
                    f"{last_run.get('side')!r} outside the enum")
            elif last_run.get("emulator_acceleration") not in ("none",
                                                               "kvm", "hvf"):
                problems.append(
                    f"ledger entry {entry.get('id')}: last_run."
                    f"emulator_acceleration "
                    f"{last_run.get('emulator_acceleration')!r} outside "
                    "the enum")
        for ev in entry.get("evidence") or []:
            if not isinstance(ev, dict) or "kind" not in ev or "ref" not in ev:
                problems.append(
                    f"ledger entry {entry.get('id')}: evidence item without "
                    "kind/ref")
                continue
            if ev.get("kind") not in EVIDENCE_KINDS:
                problems.append(
                    f"ledger entry {entry.get('id')}: evidence kind "
                    f"{ev.get('kind')!r} outside the enum")
            ref = ev.get("ref")
            if not isinstance(ref, str) or not any(
                    pattern.match(ref) for pattern in _EVIDENCE_REF_RES):
                problems.append(
                    f"ledger entry {entry.get('id')}: evidence ref "
                    f"{ref!r} matches no allowed pattern (lab|docs|app|"
                    "tools/…, r2:camscan-parity-evidence/…, https://…)")
    return problems


def _provider_fields(diff: dict[str, Any]) -> tuple[str, str]:
    """(provider, emulator_acceleration) for last_run from both sides."""
    subjects = diff.get("subjects") or {}
    slugs: list[str] = []
    accels: list[str] = []
    for side in ("reference", "implementation"):
        provider = (subjects.get(side) or {}).get("provider") or {}
        slug = provider.get("slug")
        accel = provider.get("emulator_acceleration")
        if isinstance(slug, str) and slug:
            slugs.append(slug)
        if accel in ("none", "kvm", "hvf"):
            accels.append(accel)
    if not slugs:
        provider = "unknown"
    elif len(set(slugs)) == 1:
        provider = slugs[0]
    else:
        provider = "+".join(slugs)
    if len(set(accels)) == 1 and accels:
        acceleration = accels[0]
    else:
        # mixed or absent substrate classes → record the conservative
        # TCG class ("none"); the pair incompatibility is flagged in the
        # environment dimension of diff.json
        acceleration = "none"
    return provider, acceleration


def _gap_refs(repo_root: Path, stem: str, run_id: str) -> list[str]:
    """Existing gap reports that name this run (deterministic order)."""
    reconciliation = repo_root / "lab" / "reconciliation"
    refs: list[str] = []
    if not reconciliation.is_dir():
        return refs
    for path in sorted(reconciliation.glob(f"{stem}.*.gap.yaml")):
        try:
            text = path.read_text(encoding="utf-8")
        except OSError:
            continue
        if run_id in text:
            refs.append(f"lab/reconciliation/{path.name}")
    return refs


def _evidence_entries(diff: dict[str, Any], run_id: str, stem: str,
                      repo_root: Path) -> list[dict[str, Any]]:
    """New evidence items for the entry (all schema-valid refs)."""
    entries: list[dict[str, Any]] = [{
        "kind": "document",
        "ref": f"lab/scenarios/{stem}.yaml",
        "run_id": run_id,
        "side": None,
        "note": f"scenario spec executed by run {run_id}",
    }]
    subjects = diff.get("subjects") or {}
    for side in ("reference", "implementation"):
        if (subjects.get(side) or {}).get("r2_uploaded"):
            entries.append({
                "kind": "manifest",
                "ref": (f"r2:camscan-parity-evidence/runs/{run_id}/{side}/"
                        "manifest.json"),
                "run_id": run_id,
                "side": side,
                "note": f"{side} bundle manifest (R2-uploaded)",
            })
    for ref in _gap_refs(repo_root, stem, run_id):
        entries.append({
            "kind": "report",
            "ref": ref,
            "run_id": run_id,
            "side": None,
            "note": f"gap report for run {run_id}",
        })
    return entries


def _mirror_status_line(scenario_path: Path, new_status: str) -> None:
    """Surgical top-level ``status:`` replacement (comments preserved)."""
    text = scenario_path.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)
    for index, line in enumerate(lines):
        if line.startswith("status:"):
            ending = "\n" if line.endswith("\n") else ""
            lines[index] = f"status: {new_status}{ending}"
            scenario_path.write_text("".join(lines), encoding="utf-8",
                                     newline="")
            return
    raise ParityCliError(
        f"{scenario_path}: no top-level 'status:' line to mirror — the "
        "scenario file does not follow the DSL contract")


def update_ledger(run_dir: Path, *, repo_root: Path | None = None,
                  ledger_path: Path | None = None,
                  dry_run: bool = False) -> LedgerUpdateResult:
    """Apply a run's verdict to the ledger (+ scenario status mirror)."""
    run_dir = Path(run_dir)
    repo_root = Path(repo_root) if repo_root is not None else \
        run_dir.resolve().parents[1]
    verdict_path = run_dir / "reconciliation" / "verdict.json"
    diff_path = run_dir / "reconciliation" / "diff.json"
    for path, name in ((verdict_path, "verdict.json"),
                       (diff_path, "diff.json")):
        if not path.is_file():
            raise ParityCliError(
                f"runs/{run_dir.name}/reconciliation/{name} missing — "
                "run compare first")
    try:
        verdict = jsonio_load(verdict_path)
        diff = jsonio_load(diff_path)
    except (OSError, ValueError) as e:
        raise ParityCliError(f"reconciliation outputs unreadable: {e}") from e
    run_id = verdict.get("run_id")
    scenario = verdict.get("scenario")
    verdict_name = verdict.get("verdict")
    if not isinstance(run_id, str) or not isinstance(scenario, str) \
            or verdict_name not in VERDICT_TO_STATUS:
        raise ParityCliError(
            f"verdict.json is not well-formed (run_id/scenario/verdict): "
            f"{verdict!r}")

    ledger_file = Path(ledger_path) if ledger_path is not None else \
        repo_root / "lab" / "parity-ledger" / "ledger.json"
    try:
        ledger = jsonio_load(ledger_file)
    except (OSError, ValueError) as e:
        raise ParityCliError(f"ledger unreadable: {e}") from e
    if not isinstance(ledger, dict) or not isinstance(
            ledger.get("entries"), list):
        raise ParityCliError("ledger.json: not a ledger document")

    entry = next((e for e in ledger["entries"]
                  if isinstance(e, dict)
                  and e.get("scenario") == scenario), None)
    if entry is None:
        available = sorted(str(e.get("scenario")) for e in ledger["entries"])
        raise ParityCliError(
            f"scenario {scenario!r} has no ledger entry (ledger scenarios: "
            f"{available}) — the manifest scenario id must be the kebab "
            "scenario id of a ledgered scenario")
    entry_id = entry.get("id")
    number = run_number(run_id)
    if entry_id != number:
        raise ParityCliError(
            f"run id {run_id!r} encodes scenario number {number!r} but the "
            f"ledger entry for {scenario!r} is {entry_id!r} — ids must "
            "mirror scenario files")
    stem = f"{entry_id}-{scenario}"

    scenario_file = repo_root / "lab" / "scenarios" / f"{stem}.yaml"
    if not scenario_file.is_file():
        raise ParityCliError(
            f"scenario file lab/scenarios/{stem}.yaml not found — ids/"
            "titles must mirror scenario files")
    scenario_fields = parse_scenario_yaml(scenario_file)
    if scenario_fields.get("id") != scenario:
        raise ParityCliError(
            f"lab/scenarios/{stem}.yaml declares id "
            f"{scenario_fields.get('id')!r}, expected {scenario!r}")
    if scenario_fields.get("title") != entry.get("title"):
        raise ParityCliError(
            f"lab/scenarios/{stem}.yaml title "
            f"{scenario_fields.get('title')!r} does not mirror ledger "
            f"title {entry.get('title')!r} — ledger-update never rewrites "
            "titles; fix the mirror first")

    new_status = VERDICT_TO_STATUS[verdict_name]
    provider, acceleration = _provider_fields(diff)
    last_run = {
        "utc": verdict.get("generated_utc"),
        "side": "both",
        "provider": provider,
        "emulator_acceleration": acceleration,
        "run_id": run_id,
        "verdict": verdict_name,
    }
    notes_line = (f"parity: {verdict_name} — run {run_id} "
                  f"({verdict.get('generated_utc')})")

    existing_evidence = entry.get("evidence") or []
    known = {(ev.get("kind"), ev.get("ref"), ev.get("run_id"))
             for ev in existing_evidence if isinstance(ev, dict)}
    new_evidence = [ev for ev in _evidence_entries(diff, run_id, stem,
                                                   repo_root)
                    if (ev["kind"], ev["ref"], ev["run_id"]) not in known]

    old_notes = entry.get("notes") or ""
    kept = [line.strip() for line in old_notes.splitlines()
            if line.strip() and not line.strip().startswith("parity: ")]
    kept.append(notes_line)
    new_notes = "\n".join(kept)

    # ---------------- mutate a copy, validate, then write ------------
    updated = json.loads(json.dumps(ledger))  # deep copy, order preserved
    updated_entry = next(e for e in updated["entries"]
                         if isinstance(e, dict)
                         and e.get("scenario") == scenario)
    updated_entry["status"] = new_status
    updated_entry["last_run"] = last_run
    if new_evidence:
        updated_entry["evidence"] = list(existing_evidence) + new_evidence
    updated_entry["notes"] = new_notes
    updated["updated"] = verdict.get("generated_utc")
    updated["updated_by"] = "parity-cli"
    problems = _structural_problems(updated)
    if problems:
        raise ParityCliError(
            "mutated ledger violates the schema contract: "
            + "; ".join(problems))

    result = LedgerUpdateResult(
        run_id=run_id, scenario=scenario, entry_id=str(entry_id),
        status=new_status, verdict=str(verdict_name),
        last_run=last_run, evidence_added=new_evidence,
        notes_line=notes_line,
        ledger_path=str(ledger_file),
        scenario_path=str(scenario_file))
    if dry_run:
        return result

    text = json.dumps(updated, indent=2) + "\n"
    tmp = ledger_file.with_name(ledger_file.name + ".tmp")
    tmp.write_text(text, encoding="utf-8", newline="\n")
    tmp.replace(ledger_file)
    result.wrote_ledger = True
    _mirror_status_line(scenario_file, new_status)
    result.wrote_scenario_mirror = True
    return result
