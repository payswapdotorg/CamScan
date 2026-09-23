"""``report`` — the aggregate operator report (CAMSCAN-007).

Aggregates three truths, read-only:

1. **runs found** — every ``runs/<run-id>/`` dir (EVIDENCE.md run-id
   shape): which subject envs are present, and the verdict from
   ``reconciliation/verdict.json`` when present (never guessed when
   absent — ``null``).
2. **gap counts by severity** — the reconciliation gap yamls
   (``lab/reconciliation/*.gap.yaml``, GAP-FORMAT.md — what parity-cli's
   ``gap`` writes); counted by severity, with open/acted-on status
   split. ``--gaps-dir`` overrides the reconciliation area.
3. **ledger status snapshot** — ``lab/parity-ledger/ledger.json``
   (the loop's status truth): per-entry id/scenario/status/verdict +
   the status histogram.

Determinism (parity-cli's serialization rules): ``--json`` is emitted
via ``tools.evidence_cli.jsonio`` (sorted keys, 2-space indent, one
trailing newline, atomic write) and carries **no timestamps except
evidence-derived ones** (a run's ``verdict.generated_utc`` / gap
``generated_utc`` fields ride inside the evidence documents; the report
itself never stamps wall-clock time). Identical runs tree ⇒ identical
bytes.

Exit code: 0 always, unless the runs dir is unreadable (missing/not a
dir) — then 1. Unreadable verdict.json/gap files/ledger degrade into
per-item problems inside the report, never into exit codes.
"""
from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

import yaml

from tools.evidence_cli.jsonio import (
    dumps_deterministic,
    load as jsonio_load,
)
from tools.evidence_cli.schema import RUN_ID_RE, SUBJECTS
from tools.lab_cli.scenarios import LabCliError

#: GAP-FORMAT.md severities (parity-cli's vocabulary).
SEVERITIES: tuple[str, ...] = ("critical", "high", "medium", "low")


# ------------------------------------------------------------------ runs

def _run_verdict(run_dir: Path) -> dict[str, Any] | None:
    """The run's verdict document, or None (absent/invalid → problems)."""
    path = run_dir / "reconciliation" / "verdict.json"
    if not path.is_file():
        return None
    try:
        doc = jsonio_load(path)
    except (OSError, ValueError) as e:
        return {"_problem": f"verdict.json unreadable: {e}"}
    if not isinstance(doc, dict):
        return {"_problem": "verdict.json: not a JSON object"}
    return doc


def _run_scenario(run_dir: Path, verdict: dict[str, Any] | None) -> str:
    """Scenario id: verdict.json → scenario.yaml (shallow) → \"\"."""
    if verdict and isinstance(verdict.get("scenario"), str):
        return verdict["scenario"]
    path = run_dir / "scenario.yaml"
    if not path.is_file():
        return ""
    try:
        doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError):
        return ""
    if isinstance(doc, dict) and isinstance(doc.get("id"), str):
        return doc["id"]
    return ""


def _run_number(run_id: str) -> str:
    return run_id.split("-", 2)[1] if "-" in run_id else ""


def scan_runs(runs_dir: Path) -> tuple[list[dict[str, Any]], list[str]]:
    """(runs, ignored) — deterministic order (run-id sort).

    A dir is a *run* iff its name matches the EVIDENCE.md run-id shape;
    anything else (staging leftovers, observation dirs) is listed under
    ``ignored`` — never silently dropped, never mis-parsed.
    """
    runs_dir = Path(runs_dir)
    if not runs_dir.is_dir():
        raise LabCliError(f"runs dir unreadable: {runs_dir}")
    runs: list[dict[str, Any]] = []
    ignored: list[str] = []
    for entry in sorted(runs_dir.iterdir()):
        if not entry.is_dir():
            ignored.append(entry.name)
            continue
        if not RUN_ID_RE.match(entry.name):
            ignored.append(entry.name)
            continue
        verdict = _run_verdict(entry)
        problems = [verdict["_problem"]] if verdict and "_problem" in verdict \
            else []
        counts = (verdict or {}).get("counts") if isinstance(verdict, dict) \
            else None
        runs.append({
            "id": entry.name,
            "scenario": _run_scenario(entry, verdict),
            "envs": [s for s in SUBJECTS if (entry / s).is_dir()],
            "verdict": (verdict or {}).get("verdict") if isinstance(
                verdict, dict) else None,
            "verdict_counts": (dict(counts) if isinstance(counts, dict)
                               else None),
            "verdict_generated_utc": (verdict or {}).get("generated_utc")
            if isinstance(verdict, dict) else None,
            "problems": problems,
        })
    return runs, ignored


# ------------------------------------------------------------------ gaps

def scan_gaps(gaps_dir: Path) -> dict[str, Any]:
    """Gap yamls in the reconciliation area (GAP-FORMAT.md readers)."""
    gaps_dir = Path(gaps_dir)
    files: list[dict[str, Any]] = []
    counts = {sev: 0 for sev in SEVERITIES}
    open_counts = {sev: 0 for sev in SEVERITIES}
    total = 0
    if gaps_dir.is_dir():
        for path in sorted(gaps_dir.glob("*.gap.yaml")):
            try:
                doc = yaml.safe_load(path.read_text(encoding="utf-8"))
            except (OSError, yaml.YAMLError) as e:
                files.append({"path": path.name,
                              "problem": f"unreadable: {e}"})
                continue
            gap = doc.get("gap") if isinstance(doc, dict) else None
            if not isinstance(gap, dict):
                files.append({"path": path.name,
                              "problem": "no 'gap:' mapping"})
                continue
            severity = str(gap.get("severity") or "")
            status = str(gap.get("status") or "")
            total += 1
            if severity in counts:
                counts[severity] += 1
                if status == "open":
                    open_counts[severity] += 1
            files.append({
                "path": path.name,
                "id": str(gap.get("id") or ""),
                "scenario": str(gap.get("scenario") or ""),
                "feature": str(gap.get("feature") or ""),
                "severity": severity,
                "status": status,
            })
    return {"dir": str(gaps_dir), "total": total, "counts": counts,
            "open_counts": open_counts, "files": files}


# ---------------------------------------------------------------- ledger

def ledger_snapshot(ledger_path: Path) -> dict[str, Any]:
    """The loop's status truth, read-only (never written here)."""
    ledger_path = Path(ledger_path)
    if not ledger_path.is_file():
        return {"path": str(ledger_path), "error": "ledger.json not found",
                "total": 0, "statuses": {}, "entries": []}
    try:
        doc = jsonio_load(ledger_path)
    except (OSError, ValueError) as e:
        return {"path": str(ledger_path), "error": f"unreadable: {e}",
                "total": 0, "statuses": {}, "entries": []}
    if not isinstance(doc, dict) or not isinstance(doc.get("entries"),
                                                   list):
        return {"path": str(ledger_path),
                "error": "malformed ledger (no entries list)",
                "total": 0, "statuses": {}, "entries": []}
    entries: list[dict[str, Any]] = []
    statuses: dict[str, int] = {}
    for entry in doc["entries"]:
        if not isinstance(entry, dict):
            continue
        status = str(entry.get("status") or "")
        statuses[status] = statuses.get(status, 0) + 1
        last_run = entry.get("last_run")
        verdict = last_run.get("verdict") if isinstance(last_run, dict) \
            else None
        entries.append({
            "id": str(entry.get("id") or ""),
            "scenario": str(entry.get("scenario") or ""),
            "status": status,
            "verdict": verdict,
        })
    return {"path": str(ledger_path), "total": len(entries),
            "statuses": dict(sorted(statuses.items())),
            "entries": entries}


# ------------------------------------------------------------- filtering

def _matches(query: str, *, run: dict[str, Any] | None = None,
             gap: dict[str, Any] | None = None,
             entry: dict[str, Any] | None = None) -> bool:
    """--scenario filter: kebab id or S### stem, applied per section."""
    q = query.strip()
    stem = q if q.startswith("S") and len(q) == 4 else ""
    kebab = "" if stem else q
    if run is not None:
        rid = run.get("id") or ""
        return bool((kebab and run.get("scenario") == kebab)
                    or (stem and rid.split("-", 2)[1:2] == [stem]))
    if gap is not None:
        scenario = gap.get("scenario") or ""
        return bool((kebab and (scenario.endswith(f"-{kebab}")
                                or scenario == kebab))
                    or (stem and scenario.startswith(f"{stem}-")))
    if entry is not None:
        return bool((kebab and entry.get("scenario") == kebab)
                    or (stem and entry.get("id") == stem))
    return True


# ------------------------------------------------------------- rendering

def build_report(runs_dir: Path, gaps_dir: Path, ledger_path: Path,
                 scenario: str | None = None) -> dict[str, Any]:
    """The aggregate document (deterministic; no wall-clock stamps)."""
    runs, ignored = scan_runs(runs_dir)
    gaps = scan_gaps(gaps_dir)
    ledger = ledger_snapshot(ledger_path)
    if scenario:
        runs = [r for r in runs if _matches(scenario, run=r)]
        gaps["files"] = [g for g in gaps["files"]
                         if _matches(scenario, gap=g)]
        _recount_gaps(gaps)
        ledger["entries"] = [e for e in ledger["entries"]
                             if _matches(scenario, entry=e)]
        ledger["total"] = len(ledger["entries"])
        ledger["statuses"] = dict(sorted(
            (s, sum(1 for e in ledger["entries"] if e["status"] == s))
            for s in {e["status"] for e in ledger["entries"]}))
    return {
        "runs_dir": str(runs_dir),
        "runs": runs,
        "ignored": ignored,
        "gaps": gaps,
        "ledger": ledger,
    }


def _recount_gaps(gaps: dict[str, Any]) -> None:
    counts = {sev: 0 for sev in SEVERITIES}
    open_counts = {sev: 0 for sev in SEVERITIES}
    total = 0
    for gap in gaps["files"]:
        if "severity" not in gap:
            continue
        total += 1
        if gap["severity"] in counts:
            counts[gap["severity"]] += 1
            if gap.get("status") == "open":
                open_counts[gap["severity"]] += 1
    gaps["counts"] = counts
    gaps["open_counts"] = open_counts
    gaps["total"] = total


def render_human(report: dict[str, Any]) -> str:
    """The human table (deterministic; same content as --json)."""
    lines: list[str] = []
    runs = report["runs"]
    lines.append(f"runs-dir: {report['runs_dir']} "
                 f"({len(runs)} run(s), {len(report['ignored'])} ignored)")
    for name in report["ignored"]:
        lines.append(f"  ~ {name} (not a run dir — ignored, never parsed)")
    for run in runs:
        verdict = run["verdict"] or "—"
        envs = "+".join(run["envs"]) or "—"
        scenario = run["scenario"]
        line = (f"  {run['id']}  [{scenario}]  envs={envs}  "
                f"verdict={verdict}" if scenario else
                f"  {run['id']}  envs={envs}  verdict={verdict}")
        counts = run.get("verdict_counts")
        if counts:
            parts = " ".join(f"{sev}={counts.get(sev, 0)}"
                             for sev in SEVERITIES if counts.get(sev))
            if parts:
                line += f"  ({parts})"
        lines.append(line)
        for problem in run.get("problems") or []:
            lines.append(f"    ! {problem}")
    gaps = report["gaps"]
    gap_parts = " ".join(f"{sev}={gaps['counts'][sev]}"
                         for sev in SEVERITIES)
    lines.append(f"gaps ({gaps['dir']}): {gaps['total']} total "
                 f"({gap_parts})")
    for gap in gaps["files"]:
        if "problem" in gap:
            lines.append(f"  ! {gap['path']}: {gap['problem']}")
        else:
            lines.append(f"  {gap['path']}  severity={gap['severity']} "
                         f"status={gap['status']}  [{gap['scenario']}]")
    ledger = report["ledger"]
    if ledger.get("error"):
        lines.append(f"ledger: {ledger['error']} ({ledger['path']})")
    else:
        status_parts = " ".join(f"{status}={count}" for status, count
                                in ledger["statuses"].items())
        lines.append(f"ledger ({ledger['path']}): {ledger['total']} entries "
                     f"— {status_parts}")
        for entry in ledger["entries"]:
            verdict = f" last_run={entry['verdict']}" if entry["verdict"] \
                else ""
            lines.append(f"  {entry['id']} {entry['scenario']} "
                         f"status={entry['status']}{verdict}")
    return "\n".join(lines)


def cmd_report(args: Any) -> int:
    """CLI entry for ``report`` (see main.py for the argparse surface)."""
    repo_root = Path(args.repo_root) if args.repo_root \
        else Path(__file__).resolve().parents[2]
    runs_dir = Path(args.runs_dir) if args.runs_dir else repo_root / "runs"
    gaps_dir = Path(args.gaps_dir) if args.gaps_dir \
        else repo_root / "lab" / "reconciliation"
    ledger_path = Path(args.ledger) if args.ledger \
        else repo_root / "lab" / "parity-ledger" / "ledger.json"
    try:
        report = build_report(runs_dir, gaps_dir, ledger_path,
                              scenario=args.scenario)
    except LabCliError as e:
        print(f"lab-cli report: {e}", file=sys.stderr)
        return 1
    if args.json:
        print(dumps_deterministic(report), end="")
    else:
        print(render_human(report))
    return 0
