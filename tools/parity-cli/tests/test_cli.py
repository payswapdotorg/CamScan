"""CLI end-to-end (main.py, exit codes, --check gate, --from-diff)."""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from helpers import RUN_ID, build_pair, build_repo
from tools.parity_cli.main import main


def _repo_with_run(tmp_path: Path) -> Path:
    repo = build_repo(tmp_path)
    build_pair(repo, run_id=RUN_ID)
    # produce the reconciliation outputs the later commands consume
    assert main(["compare", RUN_ID, "--repo-root", str(repo)]) == 0
    return repo


def test_compare_cli_end_to_end(tmp_path: Path, capsys: pytest.CaptureFixture
                                ) -> None:
    repo = _repo_with_run(tmp_path)
    code = main(["compare", RUN_ID, "--repo-root", str(repo)])
    out = capsys.readouterr().out
    assert code == 0
    assert "verdict: PASS" in out
    assert "counts: critical=0  high=0  medium=0  low=0" in out
    assert (f"wrote runs/{RUN_ID}/reconciliation/diff.json") in out
    assert (repo / "runs" / RUN_ID / "reconciliation" / "diff.json"
            ).is_file()
    assert (repo / "runs" / RUN_ID / "reconciliation" / "verdict.json"
            ).is_file()


def test_compare_cli_fail_verdict_exit_zero(tmp_path: Path,
                                            capsys: pytest.CaptureFixture
                                            ) -> None:
    """A FAIL verdict is a successful comparison (data), not a tool
    error — exit 0."""
    repo = build_repo(tmp_path)
    from helpers import DEFAULT_TRACE, build_pair
    failed_launch = [dict(step) for step in DEFAULT_TRACE]
    failed_launch[0]["result"] = "failed"
    build_pair(repo, run_id=RUN_ID, impl_overrides={
        "action_trace": failed_launch})
    code = main(["compare", RUN_ID, "--repo-root", str(repo)])
    assert code == 0
    assert "verdict: FAIL" in capsys.readouterr().out


def test_compare_cli_missing_run_dir_exit_one(tmp_path: Path) -> None:
    repo = build_repo(tmp_path)
    code = main(["compare", "20260923T999999Z-S004-nosuch",
                 "--repo-root", str(repo)])
    assert code == 1


def test_compare_check_gate(tmp_path: Path) -> None:
    repo = _repo_with_run(tmp_path)
    assert main(["compare", RUN_ID, "--repo-root", str(repo),
                 "--check"]) == 0
    # stale: corrupt the on-disk diff
    diff = repo / "runs" / RUN_ID / "reconciliation" / "diff.json"
    diff.write_text("{}\n", encoding="utf-8")
    assert main(["compare", RUN_ID, "--repo-root", str(repo),
                 "--check"]) == 1
    # check never repaired the file
    assert diff.read_text(encoding="utf-8") == "{}\n"


def test_compare_check_detects_missing_outputs(tmp_path: Path) -> None:
    repo = _repo_with_run(tmp_path)
    (repo / "runs" / RUN_ID / "reconciliation" / "verdict.json").unlink()
    assert main(["compare", RUN_ID, "--repo-root", str(repo),
                 "--check"]) == 1


def test_gap_cli_writes_lab_reconciliation(tmp_path: Path) -> None:
    repo = _repo_with_run(tmp_path)
    code = main(["gap", RUN_ID, "--repo-root", str(repo)])
    assert code == 0
    # PASS pair → no gaps
    assert (list((repo / "lab" / "reconciliation").glob("*.gap.yaml"))
            == [])


def test_gap_cli_from_diff(tmp_path: Path) -> None:
    repo = build_repo(tmp_path)
    from helpers import build_pair
    build_pair(repo, run_id=RUN_ID, with_implementation=False)
    main(["compare", RUN_ID, "--repo-root", str(repo)])
    # from-diff must not need the manifests to be re-readable
    (repo / "runs" / RUN_ID / "implementation").unlink(
        missing_ok=True)  # was never created
    code = main(["gap", RUN_ID, "--repo-root", str(repo), "--from-diff"])
    assert code == 0
    assert (repo / "lab" / "reconciliation" /
            "S004-single-document-capture.bundle-implementation.gap.yaml"
            ).is_file()


def test_ledger_update_cli(tmp_path: Path, capsys: pytest.CaptureFixture
                           ) -> None:
    repo = _repo_with_run(tmp_path)
    code = main(["ledger-update", RUN_ID, "--repo-root", str(repo)])
    out = capsys.readouterr().out
    assert code == 0
    assert "status → PASS" in out
    ledger = json.loads((repo / "lab" / "parity-ledger" / "ledger.json")
                        .read_text(encoding="utf-8"))
    assert ledger["entries"][0]["status"] == "PASS"
    scenario = (repo / "lab" / "scenarios" /
                "S004-single-document-capture.yaml").read_text(
                    encoding="utf-8")
    assert "status: PASS" in scenario.splitlines()


def test_ledger_update_cli_dry_run(tmp_path: Path) -> None:
    repo = _repo_with_run(tmp_path)
    before = (repo / "lab" / "parity-ledger" / "ledger.json").read_text(
        encoding="utf-8")
    code = main(["ledger-update", RUN_ID, "--repo-root", str(repo),
                 "--dry-run"])
    assert code == 0
    assert (repo / "lab" / "parity-ledger" / "ledger.json").read_text(
        encoding="utf-8") == before


def test_ledger_update_requires_compare_first(tmp_path: Path) -> None:
    repo = build_repo(tmp_path)
    from helpers import build_pair
    build_pair(repo, run_id=RUN_ID)
    code = main(["ledger-update", RUN_ID, "--repo-root", str(repo)])
    assert code == 1


def test_scenario_yaml_required_at_run_root(tmp_path: Path) -> None:
    """compare works without scenario.yaml (manifests carry the id);
    gap fails closed on the missing verbatim copy."""
    repo = _repo_with_run(tmp_path)
    (repo / "runs" / RUN_ID / "scenario.yaml").unlink()
    assert main(["compare", RUN_ID, "--repo-root", str(repo)]) == 0
    code = main(["gap", RUN_ID, "--repo-root", str(repo)])
    assert code == 1


def test_no_masks_flag(tmp_path: Path) -> None:
    repo = build_repo(tmp_path)
    from helpers import TRACE_WITH_UPSELL, build_pair
    build_pair(repo, run_id=RUN_ID, ref_overrides={
        "action_trace": [dict(s) for s in TRACE_WITH_UPSELL]})
    # the tool's own masks dir (with the upsell rule) is the default
    code = main(["compare", RUN_ID, "--repo-root", str(repo)])
    assert code == 0
    with_masks = json.loads(
        (repo / "runs" / RUN_ID / "reconciliation" / "diff.json")
        .read_text(encoding="utf-8"))
    code = main(["compare", RUN_ID, "--repo-root", str(repo),
                 "--no-masks"])
    assert code == 0
    unmasked = json.loads(
        (repo / "runs" / RUN_ID / "reconciliation" / "diff.json")
        .read_text(encoding="utf-8"))
    assert with_masks["masks"]["removed_reference_steps"] == [4]
    assert "actions-step-count" not in {
        entry["id"] for entry in with_masks["entries"]}
    assert "actions-step-count" in {
        entry["id"] for entry in unmasked["entries"]}


def test_scenarios_unchanged_by_test_run() -> None:
    """Guard: the unit suite never touches the REAL repo control plane
    (all CLI invocations above used --repo-root tmp trees)."""
    real = Path(__file__).resolve().parents[3] / "lab" / "scenarios"
    text = (real / "S004-single-document-capture.yaml").read_text(
        encoding="utf-8")
    assert "status: UNKNOWN" in text.splitlines()
