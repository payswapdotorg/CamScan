"""``reconcile`` — scenario/ledger reconciliation automation (PROD-012).

Additive parity-cli subcommand (the existing compare/gap/ledger-update
subcommands are untouched). Walks ``lab/scenarios/*.yaml`` (sorted) plus
``lab/parity-ledger/ledger.json`` and:

(a) validates every scenario file against the SCENARIO-DSL.md v0.1
    schema requirements — REQUIRED fields present and typed, ``S###-<id>``
    file stems, status inside the ledger vocabulary, timeouts within
    floors, ``meta.requires`` a typed mapping with the required keys;
(b) cross-references scenarios against ledger entries — scenarios
    without a ledger entry, ledger entries without a scenario file, and
    status disagreements (``yaml.status`` mirrors the ledger; the ledger
    is truth);
(c) emits a deterministic report pair —
    ``lab/reconciliation/reconcile-report.json`` and
    ``reconcile-report.md`` — sorted by scenario id and byte-identical
    across runs on the same inputs (no timestamps, no paths, no
    wall-clock reads).

The ledger and the scenario files are NEVER mutated: acceptance
authority stays with the lead (AGENTS.md). Validation errors are
operational gate failures (exit 1); cross-reference gaps are REPORT
CONTENT, not errors — the whole point of the report is to surface them
deterministically.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml
from tools.evidence_cli.jsonio import dump as jsonio_dump
from tools.evidence_cli.jsonio import dumps_deterministic
from tools.evidence_cli.jsonio import load as jsonio_load
from tools.parity_cli.model import ParityCliError

#: DSL v0.1 status vocabulary (mirrors the ledger schema; ledger is truth).
STATUSES: tuple[str, ...] = (
    "UNKNOWN", "DISCOVERED", "SPECIFIED", "IMPLEMENTED", "PARTIAL",
    "BLOCKED", "PASS",
)

#: REQUIRED top-level scenario keys (SCENARIO-DSL.md v0.1).
REQUIRED_KEYS: frozenset[str] = frozenset({
    "id", "title", "status", "preconditions", "fixture", "steps",
    "assertions", "meta",
})

#: REQUIRED assertion categories (all four, per the DSL schema).
ASSERT_KEYS: frozenset[str] = frozenset({"behavior", "ui", "state", "output"})

#: meta.owner vocabulary (SCENARIO-DSL.md v0.1).
OWNERS: frozenset[str] = frozenset({
    "reference-discovery", "lead", "reconciliation", "implementation",
})

#: Boolean capability vocabulary (lab/providers/types.py, mirrored
#: read-only so parity-cli stays independent of the lab package).
BOOL_CAPABILITIES: frozenset[str] = frozenset({
    "gui", "persistent", "android_sdk", "android_cli", "android_emulator",
    "adb", "gradle", "camera_fixture", "screenshots", "recording",
    "snapshot",
})

#: Enum capability vocabulary + allowed values.
ENUM_CAPABILITIES: dict[str, tuple[str, ...]] = {
    "emulator_acceleration": ("none", "kvm", "hvf"),
}

#: REQUIRED meta.requires keys (present in every DSL v0.1 scenario;
#: camera_fixture is required only when the flow captures through the
#: camera, so it stays optional here).
REQUIRED_REQUIRES_KEYS: frozenset[str] = frozenset({
    "gui", "adb", "android_emulator", "android_sdk", "android_cli",
    "emulator_acceleration",
})

_KEBAB_RE = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*")
_STEM_RE = re.compile(r"S[0-9]{3}")


def _is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _is_kebab(value: Any) -> bool:
    return (
        isinstance(value, str)
        and bool(value)
        and value == value.strip()
        and _KEBAB_RE.fullmatch(value) is not None
    )


@dataclass
class ReconcileOutcome:
    """Pure reconciliation result: errors, gaps, deterministic reports."""

    scenario_count: int = 0
    ledger_entry_count: int = 0
    validation_errors: list[dict[str, str]] = field(default_factory=list)
    scenario_without_entry: list[dict[str, str]] = field(default_factory=list)
    entry_without_scenario: list[dict[str, str]] = field(default_factory=list)
    status_disagreements: list[dict[str, str]] = field(default_factory=list)

    @property
    def has_validation_errors(self) -> bool:
        return bool(self.validation_errors)

    def report_json(self) -> dict[str, Any]:
        """The deterministic JSON report document (content-only)."""
        return {
            "schema": "camscan-reconcile-report/0.1",
            "generated_by": "parity-cli reconcile (CAMSCAN-PROD-012)",
            "summary": {
                "scenarios": self.scenario_count,
                "ledger_entries": self.ledger_entry_count,
                "validation_errors": len(self.validation_errors),
                "scenario_without_entry": len(self.scenario_without_entry),
                "entry_without_scenario": len(self.entry_without_scenario),
                "status_disagreements": len(self.status_disagreements),
            },
            "validation_errors": self.validation_errors,
            "gaps": {
                "scenario_without_entry": self.scenario_without_entry,
                "entry_without_scenario": self.entry_without_scenario,
                "status_disagreements": self.status_disagreements,
            },
        }

    def report_json_text(self) -> str:
        return dumps_deterministic(self.report_json())

    def report_markdown(self) -> str:
        """The deterministic human-readable report (content-only)."""
        lines: list[str] = []
        lines.append("# CamScan parity reconciliation report")
        lines.append("")
        lines.append(
            "Generated by parity-cli reconcile (CAMSCAN-PROD-012). "
            "Deterministic: byte-identical for identical inputs. The "
            "ledger is the acceptance authority; this report never "
            "mutates it. Cross-reference gaps are report content, not "
            "tool errors."
        )
        lines.append("")
        lines.append("## Summary")
        lines.append("")
        lines.append(
            "| scenarios | ledger entries | validation errors | "
            "scenario without entry | entry without scenario | "
            "status disagreements |"
        )
        lines.append("| --- | --- | --- | --- | --- | --- |")
        lines.append(
            f"| {self.scenario_count} | {self.ledger_entry_count} | {len(self.validation_errors)} | {len(self.scenario_without_entry)} | {len(self.entry_without_scenario)} | {len(self.status_disagreements)} |"
        )
        lines.append("")
        lines.append("## Validation errors")
        lines.append("")
        if self.validation_errors:
            lines.append("| file | problem |")
            lines.append("| --- | --- |")
            for entry in self.validation_errors:
                lines.append(
                    "| {} | {} |".format(
                        _md_cell(entry["file"]), _md_cell(entry["problem"])
                    )
                )
        else:
            lines.append("None.")
        lines.append("")
        lines.append("## Scenario without ledger entry")
        lines.append("")
        if self.scenario_without_entry:
            lines.append("| id | scenario | status | title | file |")
            lines.append("| --- | --- | --- | --- | --- |")
            for gap in self.scenario_without_entry:
                lines.append(
                    "| {} | {} | {} | {} | {} |".format(
                        _md_cell(gap["id"]),
                        _md_cell(gap["scenario"]),
                        _md_cell(gap["status"]),
                        _md_cell(gap["title"]),
                        _md_cell(gap["file"]),
                    )
                )
        else:
            lines.append("None.")
        lines.append("")
        lines.append("## Ledger entry without scenario file")
        lines.append("")
        if self.entry_without_scenario:
            lines.append("| id | scenario | status | title |")
            lines.append("| --- | --- | --- | --- |")
            for gap in self.entry_without_scenario:
                lines.append(
                    "| {} | {} | {} | {} |".format(
                        _md_cell(gap["id"]),
                        _md_cell(gap["scenario"]),
                        _md_cell(gap["status"]),
                        _md_cell(gap["title"]),
                    )
                )
        else:
            lines.append("None.")
        lines.append("")
        lines.append("## Status disagreements (yaml vs ledger)")
        lines.append("")
        lines.append("The ledger is truth; the yaml status mirrors it.")
        lines.append("")
        if self.status_disagreements:
            lines.append("| id | scenario | yaml status | ledger status | file |")
            lines.append("| --- | --- | --- | --- | --- |")
            for gap in self.status_disagreements:
                lines.append(
                    "| {} | {} | {} | {} | {} |".format(
                        _md_cell(gap["id"]),
                        _md_cell(gap["scenario"]),
                        _md_cell(gap["yaml_status"]),
                        _md_cell(gap["ledger_status"]),
                        _md_cell(gap["file"]),
                    )
                )
        else:
            lines.append("None.")
        lines.append("")
        return "\n".join(lines)


def _md_cell(value: str) -> str:
    """Escape a markdown table cell (deterministic, minimal)."""
    # chr(92) is the backslash — spelled explicitly so no double-backslash
    # escape sequence appears in this source file.
    return str(value).replace("|", chr(92) + "|").replace("\n", " ")


def _validate_scenario(doc: Any, filename: str) -> list[dict[str, str]]:
    """DSL v0.1 schema validation of ONE parsed scenario document."""
    errors: list[dict[str, str]] = []

    def err(problem: str) -> None:
        errors.append({"file": filename, "problem": problem})

    if not isinstance(doc, dict):
        err("document must be a mapping")
        return errors

    missing = sorted(REQUIRED_KEYS - set(doc))
    if missing:
        err("missing REQUIRED keys: {}".format(", ".join(missing)))

    # -- id + file stem ---------------------------------------------------
    stem = filename.split("-", 1)[0]
    if _STEM_RE.fullmatch(stem) is None:
        err(f"file stem must be S### (got {stem!r})")
    doc_id = doc.get("id")
    if not _is_kebab(doc_id):
        err(f"id must be a non-empty kebab-case string (got {doc_id!r})")
    elif "-" in filename and doc_id != filename.split("-", 1)[1][: -len(".yaml")]:
        err(f"id/stem mismatch (id={doc_id!r}, file={filename!r})")

    # -- title / status ---------------------------------------------------
    title = doc.get("title")
    if not isinstance(title, str) or not title.strip():
        err(f"title must be a non-empty string (got {title!r})")
    status = doc.get("status")
    if status not in STATUSES:
        err("status must be one of {} (got {!r})".format(
            "|".join(STATUSES), status))

    # -- preconditions -----------------------------------------------------
    pre = doc.get("preconditions")
    if not isinstance(pre, list) or any(not isinstance(p, str) for p in pre):
        err(f"preconditions must be a list of strings (got {pre!r})")

    # -- fixture ------------------------------------------------------------
    fixture = doc.get("fixture")
    if not isinstance(fixture, dict):
        err("fixture must be a mapping with camera/sequence keys "
            f"(got {fixture!r})")
    else:
        unknown = sorted(set(fixture) - {"camera", "sequence"})
        if unknown:
            err("unknown fixture keys: {}".format(", ".join(unknown)))
        for key in ("camera", "sequence"):
            ref = fixture.get(key)
            if ref is None:
                continue
            if not _is_kebab(ref):
                err(f"fixture.{key} must be null or a kebab-case id "
                    f"(got {ref!r})")

    # -- steps ---------------------------------------------------------------
    steps = doc.get("steps")
    if not isinstance(steps, list) or not steps:
        err(f"steps must be a non-empty list (got {steps!r})")
    else:
        for index, step in enumerate(steps):
            if isinstance(step, str) and step.strip():
                continue
            if isinstance(step, dict) and step:
                continue
            err(f"step {index + 1} must be a non-empty string or a mapping "
                f"(got {step!r})")

    # -- assertions ------------------------------------------------------------
    assertions = doc.get("assertions")
    if not isinstance(assertions, dict):
        err(f"assertions must be a mapping (got {assertions!r})")
    else:
        missing_cats = sorted(ASSERT_KEYS - set(assertions))
        if missing_cats:
            err("assertions missing categories: {}".format(
                ", ".join(missing_cats)))
        unknown_cats = sorted(set(assertions) - ASSERT_KEYS)
        if unknown_cats:
            err("unknown assertion categories: {}".format(
                ", ".join(unknown_cats)))
        for key, value in sorted(assertions.items()):
            if not isinstance(value, list) or any(
                    not isinstance(item, str) for item in value):
                err(f"assertions.{key} must be a list of strings "
                    f"(got {value!r})")

    # -- meta ---------------------------------------------------------------
    meta = doc.get("meta")
    if not isinstance(meta, dict):
        err(f"meta must be a mapping (got {meta!r})")
    else:
        for key, minimum in (("timeout_seconds", 60),
                             ("step_timeout_seconds", 30)):
            value = meta.get(key)
            if not _is_int(value) or value < minimum:
                err(f"meta.{key} must be an integer >= {minimum} (got {value!r})")
        requires = meta.get("requires")
        if not isinstance(requires, dict):
            err("meta.requires must be a typed mapping "
                f"(got {requires!r})")
        else:
            missing_req = sorted(REQUIRED_REQUIRES_KEYS - set(requires))
            if missing_req:
                err("meta.requires missing required keys: {}".format(
                    ", ".join(missing_req)))
            if "android_studio" in requires:
                err("meta.requires must not declare android_studio "
                    "(CLI-first rule)")
            for key in sorted(requires):
                value = requires[key]
                if key in ENUM_CAPABILITIES:
                    valid = _validate_enum_requirement(key, value)
                    if valid is not None:
                        err(valid)
                elif key in BOOL_CAPABILITIES:
                    if not isinstance(value, bool):
                        err(f"meta.requires.{key} must be a boolean "
                            f"(got {value!r})")
                else:
                    err("meta.requires has unknown capability key "
                        f"{key!r}")
        owner = meta.get("owner")
        if owner is not None and owner not in OWNERS:
            err("meta.owner must be one of {} (got {!r})".format(
                "|".join(sorted(OWNERS)), owner))
        notes = meta.get("notes")
        if notes is not None and not isinstance(notes, str):
            err(f"meta.notes must be a string (got {notes!r})")

    return errors


def _validate_enum_requirement(key: str, value: Any) -> str | None:
    """Validate an enum capability requirement (emulator_acceleration)."""
    allowed_values = ENUM_CAPABILITIES[key]
    if not isinstance(value, dict):
        return (f"meta.requires.{key} must be a typed mapping with an "
                f"'allowed' list (got {value!r})")
    unknown = sorted(set(value) - {"allowed"})
    if unknown:
        return ("meta.requires.{} has unknown keys: {}".format(
            key, ", ".join(unknown)))
    allowed = value.get("allowed")
    if not isinstance(allowed, list) or not allowed:
        return (f"meta.requires.{key}.allowed must be a non-empty list "
                f"(got {allowed!r})")
    for item in allowed:
        if item not in allowed_values:
            return ("meta.requires.{}.allowed value {!r} not in {}".format(
                key, item, "|".join(allowed_values)))
    return None


def reconcile(scenarios_dir: Path, ledger_path: Path) -> ReconcileOutcome:
    """Pure reconciliation: reads inputs, validates, cross-references.

    Raises :class:`ParityCliError` for operational failures only (missing
    scenarios directory, no scenario files, unreadable ledger).
    """
    scenarios_dir = Path(scenarios_dir)
    ledger_path = Path(ledger_path)
    if not scenarios_dir.is_dir():
        raise ParityCliError(
            f"scenarios directory not found: {scenarios_dir}")
    files = sorted(scenarios_dir.glob("*.yaml"))
    if not files:
        raise ParityCliError(
            f"no scenario files found in {scenarios_dir}")

    outcome = ReconcileOutcome()
    parsed: list[dict[str, Any]] = []
    stems: list[str] = []
    for path in files:
        try:
            doc = yaml.safe_load(path.read_text(encoding="utf-8"))
        except yaml.YAMLError as exc:
            outcome.validation_errors.append({
                "file": path.name,
                "problem": "YAML parse error: {}".format(
                    str(exc).replace("\n", " ")),
            })
            continue
        errors = _validate_scenario(doc, path.name)
        outcome.validation_errors.extend(errors)
        if isinstance(doc, dict) and isinstance(doc.get("id"), str):
            stem = path.name.split("-", 1)[0]
            parsed.append({
                "stem": stem,
                "file": path.name,
                "id": doc.get("id"),
                "title": doc.get("title") if isinstance(
                    doc.get("title"), str) else "",
                "status": doc.get("status") if isinstance(
                    doc.get("status"), str) else "",
            })
            stems.append(stem)

    seen: dict[str, str] = {}
    for entry in parsed:
        stem = entry["stem"]
        if stem in seen:
            outcome.validation_errors.append({
                "file": entry["file"],
                "problem": f"duplicate scenario number {stem}",
            })
        else:
            seen[stem] = entry["file"]

    try:
        ledger = jsonio_load(ledger_path)
    except (OSError, ValueError) as exc:
        raise ParityCliError(
            f"ledger unreadable: {ledger_path} ({exc})") from exc
    entries = ledger.get("entries") if isinstance(ledger, dict) else None
    if not isinstance(entries, list):
        raise ParityCliError(
            f"ledger has no entries list: {ledger_path}")

    outcome.scenario_count = len(parsed)
    outcome.ledger_entry_count = len(entries)

    ledger_by_id: dict[str, dict[str, Any]] = {}
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        entry_id = entry.get("id")
        if isinstance(entry_id, str):
            ledger_by_id[entry_id] = entry

    for entry in parsed:
        if entry["stem"] not in ledger_by_id:
            outcome.scenario_without_entry.append({
                "id": entry["stem"],
                "scenario": str(entry["id"]),
                "status": str(entry["status"]),
                "title": str(entry["title"]),
                "file": entry["file"],
            })
    for entry_id, entry in sorted(ledger_by_id.items()):
        if entry_id not in seen:
            outcome.entry_without_scenario.append({
                "id": entry_id,
                "scenario": str(entry.get("scenario", "")),
                "status": str(entry.get("status", "")),
                "title": str(entry.get("title", "")),
            })
    for entry in parsed:
        ledger_entry = ledger_by_id.get(entry["stem"])
        if ledger_entry is None:
            continue
        ledger_status = ledger_entry.get("status")
        if entry["status"] != ledger_status:
            outcome.status_disagreements.append({
                "id": entry["stem"],
                "scenario": str(entry["id"]),
                "yaml_status": str(entry["status"]),
                "ledger_status": str(ledger_status),
                "file": entry["file"],
            })

    outcome.validation_errors.sort(
        key=lambda e: (e["file"], e["problem"]))
    outcome.scenario_without_entry.sort(key=lambda g: g["id"])
    outcome.entry_without_scenario.sort(key=lambda g: g["id"])
    outcome.status_disagreements.sort(key=lambda g: g["id"])
    return outcome


REPORT_JSON_NAME = "reconcile-report.json"
REPORT_MD_NAME = "reconcile-report.md"


def write_reports(report_dir: Path, outcome: ReconcileOutcome) -> None:
    """Write the deterministic report pair (never touches inputs)."""
    report_dir = Path(report_dir)
    report_dir.mkdir(parents=True, exist_ok=True)
    jsonio_dump(report_dir / REPORT_JSON_NAME, outcome.report_json())
    (report_dir / REPORT_MD_NAME).write_text(
        outcome.report_markdown(), encoding="utf-8", newline="\n")


def check_reports(report_dir: Path,
                  outcome: ReconcileOutcome) -> list[str]:
    """Compare the on-disk reports with the freshly computed ones."""
    report_dir = Path(report_dir)
    problems: list[str] = []
    json_path = report_dir / REPORT_JSON_NAME
    md_path = report_dir / REPORT_MD_NAME
    if not json_path.is_file():
        problems.append(f"missing report: {json_path.name}")
    else:
        on_disk = json_path.read_text(encoding="utf-8")
        if on_disk != outcome.report_json_text():
            problems.append(
                f"stale report: {json_path.name} (content differs from the freshly "
                "computed reconciliation)")
    if not md_path.is_file():
        problems.append(f"missing report: {md_path.name}")
    else:
        on_disk = md_path.read_text(encoding="utf-8")
        if on_disk != outcome.report_markdown():
            problems.append(
                f"stale report: {md_path.name} (content differs from the freshly "
                "computed reconciliation)")
    return problems
