"""``report`` aggregation on a synthetic runs tree.

The tree reuses the committed 005 synthetic demo pair (PARTIAL verdict,
real reconciliation outputs) plus REAL gap yamls generated through
parity-cli's own gap writer — so the aggregation is exercised against
genuine reconciliation artifacts, not hand-written lookalikes.

Pinned properties:

- runs found: id, envs present, verdict from reconciliation/verdict.json;
- gap counts by severity from the reconciliation gap yamls;
- ledger status snapshot (statuses histogram + per-entry verdicts);
- ``--json``: parity-cli's deterministic serialization (sorted keys,
  one trailing newline) and NO wall-clock stamps (byte-stable reruns);
- ``--scenario`` filters runs + gaps + ledger by kebab id or S### stem;
- exit 0 on empty/odd trees; exit 1 only when the runs dir is unreadable;
- non-run dirs (the reference-observe observation tree) are listed under
  ``ignored``, never parsed as runs.
"""
from __future__ import annotations

import json
import shutil
from pathlib import Path

from labcli_helpers import DEMO_PAIR, DEMO_PAIR_ID, REPO_ROOT, run_cli
from tools.parity_cli.compare import compare_run
from tools.parity_cli.gaps import write_gaps


def build_tree(root: Path) -> Path:
    """A synthetic runs tree + gaps dir + ledger, anchored at root."""
    runs = root / "runs"
    runs.mkdir(parents=True)
    shutil.copytree(DEMO_PAIR, runs / DEMO_PAIR_ID)

    # a single-env run (implementation only, no reconciliation yet)
    single = runs / "20260923T130000Z-S001-recording"
    (single / "implementation").mkdir(parents=True)
    (single / "scenario.yaml").write_text(
        (REPO_ROOT / "lab" / "scenarios"
         / "S001-application-launch.yaml").read_text(encoding="utf-8"),
        encoding="utf-8")

    # a non-run dir: the reference-observe observation tree shape
    observation = runs / "camscan004-reference-20260921T224152Z"
    observation.mkdir()
    (observation / "observation-result.json").write_text("{}\n",
                                                         encoding="utf-8")

    # REAL gaps for the demo pair, through parity-cli's own writer
    gaps = root / "reconciliation-gaps"
    result = compare_run(DEMO_PAIR_ID, repo_root=REPO_ROOT, runs_dir=runs)
    outcome = write_gaps(result.diff, runs / DEMO_PAIR_ID, gaps)
    assert outcome.written, "expected the demo pair to file real gaps"

    ledger = root / "ledger.json"
    shutil.copyfile(REPO_ROOT / "lab" / "parity-ledger" / "ledger.json",
                    ledger)
    return root


def _report(root: Path, *extra: str):
    return run_cli([
        "report",
        "--runs-dir", str(root / "runs"),
        "--gaps-dir", str(root / "reconciliation-gaps"),
        "--ledger", str(root / "ledger.json"),
        "--repo-root", str(root),
        *extra,
    ])


def test_report_aggregates_the_synthetic_tree(tmp_path):
    root = build_tree(tmp_path / "tree")
    proc = _report(root)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    out = proc.stdout

    # runs found: the paired demo pair with its PARTIAL verdict
    assert f"  {DEMO_PAIR_ID}  [single-document-capture]  " \
           "envs=reference+implementation  verdict=PARTIAL" in out
    assert "(medium=1 low=4)" in out
    # the single-env run: envs=implementation, no verdict
    assert "  20260923T130000Z-S001-recording  [application-launch]  " \
           "envs=implementation  verdict=—" in out
    assert "1 run(s)" not in out  # two runs found
    assert "2 run(s)" in out
    # the observation dir is ignored, never parsed as a run
    assert "  ~ camscan004-reference-20260921T224152Z " \
           "(not a run dir — ignored, never parsed)" in out
    assert "1 ignored" in out

    # gaps: real parity-cli gap files counted by severity
    assert "gaps (" in out
    assert "1 total (critical=0 high=0 medium=1 low=0)" in out
    assert "severity=medium" in out
    assert "status=open" in out

    # ledger snapshot
    assert "18 entries — UNKNOWN=18" in out
    assert "S004 single-document-capture status=UNKNOWN" in out


def test_report_json_deterministic_no_wall_clock(tmp_path):
    root = build_tree(tmp_path / "tree")
    first = _report(root, "--json")
    second = _report(root, "--json")
    assert first.returncode == 0 and second.returncode == 0
    assert first.stdout == second.stdout  # byte-stable
    assert first.stdout.endswith("}\n")

    doc = json.loads(first.stdout)
    assert doc["runs_dir"].endswith("runs")
    ids = [r["id"] for r in doc["runs"]]
    assert ids == sorted(ids)
    assert DEMO_PAIR_ID in ids
    demo = next(r for r in doc["runs"] if r["id"] == DEMO_PAIR_ID)
    # EVIDENCE.md subject order (reference first — deterministic)
    assert demo["envs"] == ["reference", "implementation"]
    assert demo["verdict"] == "PARTIAL"
    assert demo["verdict_counts"]["medium"] == 1
    assert demo["verdict_counts"]["low"] == 4
    # evidence-derived timestamp rides inside the run entry only
    assert demo["verdict_generated_utc"] == "2026-09-23T12:01:04Z"
    # no report-level wall-clock stamp (only the four fixed sections)
    assert set(doc) == {"gaps", "ignored", "ledger", "runs", "runs_dir"}
    assert doc["ignored"] == ["camscan004-reference-20260921T224152Z"]

    assert doc["gaps"]["counts"]["medium"] == 1
    assert doc["gaps"]["total"] == 1
    assert doc["gaps"]["files"][0]["severity"] == "medium"
    assert doc["gaps"]["files"][0]["status"] == "open"

    assert doc["ledger"]["total"] == 18
    assert doc["ledger"]["statuses"] == {"UNKNOWN": 18}
    assert doc["ledger"]["entries"][0]["id"] == "S001"


def test_report_scenario_filter(tmp_path):
    root = build_tree(tmp_path / "tree")
    for spelling in ("S004", "single-document-capture"):
        proc = _report(root, "--scenario", spelling)
        assert proc.returncode == 0, proc.stdout + proc.stderr
        out = proc.stdout
        assert DEMO_PAIR_ID in out
        assert "20260923T130000Z-S001-recording" not in out
        assert "1 run(s)" in out
        # ledger filtered to the S004 entry
        assert out.count("status=UNKNOWN") == 1
        assert "S004 single-document-capture status=UNKNOWN" in out

    proc = _report(root, "--scenario", "S001")
    assert proc.returncode == 0
    out = proc.stdout
    assert "20260923T130000Z-S001-recording" in out
    assert DEMO_PAIR_ID not in out
    assert "0 total (critical=0 high=0 medium=0 low=0)" in out


def test_report_empty_and_odd_trees(tmp_path):
    # empty runs dir → exit 0, zero runs
    empty = tmp_path / "empty"
    (empty / "runs").mkdir(parents=True)
    proc = _report(empty)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    assert "0 run(s)" in proc.stdout
    assert "0 total (critical=0 high=0 medium=0 low=0)" in proc.stdout

    # a run dir with a corrupt verdict.json degrades to a problem,
    # never an exit code
    odd = tmp_path / "odd"
    (odd / "runs" / "20260923T160000Z-S002-broken").mkdir(parents=True)
    (odd / "runs" / "20260923T160000Z-S002-broken" / "reconciliation") \
        .mkdir()
    (odd / "runs" / "20260923T160000Z-S002-broken" / "reconciliation"
     / "verdict.json").write_text("{not json", encoding="utf-8")
    proc = _report(odd)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    assert "verdict.json unreadable" in proc.stdout
    assert "verdict=None" in proc.stdout or "verdict=—" in proc.stdout


def test_report_unreadable_runs_dir_exits_1(tmp_path):
    proc = run_cli([
        "report", "--runs-dir", str(tmp_path / "missing" / "runs"),
        "--repo-root", str(tmp_path),
    ])
    assert proc.returncode == 1
    assert "runs dir unreadable" in proc.stderr


def test_report_defaults_to_repo_runs(tmp_path):
    # default --runs-dir = <repo-root>/runs (the committed demo pair)
    proc = run_cli(["report", "--repo-root", str(REPO_ROOT)])
    assert proc.returncode == 0, proc.stdout + proc.stderr
    assert DEMO_PAIR_ID in proc.stdout
    # default gaps dir = lab/reconciliation (no gap yamls committed yet)
    assert "0 total (critical=0 high=0 medium=0 low=0)" in proc.stdout
