"""ledger-update — verdict → ledger status + scenario mirror."""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from helpers import (build_pair, build_repo, compare_pair, ledger_doc)
from tools.evidence_cli.jsonio import dump as jsonio_dump
from tools.evidence_cli.jsonio import load as jsonio_load
from tools.parity_cli.ledger import VERDICT_TO_STATUS, update_ledger
from tools.parity_cli.model import ParityCliError

RUN_ID = "20260923T120000Z-S004-unit01"


def _verdict_run(repo: Path, tmp_path: Path, **pair_kwargs: object) -> Path:
    """A repo run dir with reconciliation outputs ready for the ledger."""
    compare_pair(repo, run_id=RUN_ID, **pair_kwargs)
    return repo / "runs" / RUN_ID


def test_verdict_to_status_mapping_table() -> None:
    assert VERDICT_TO_STATUS == {
        "PASS": "PASS", "PARTIAL": "PARTIAL", "FAIL": "IMPLEMENTED",
        "BLOCKED": "BLOCKED", "NOT_OBSERVED": "UNKNOWN",
    }


def test_pass_updates_status_and_last_run(tmp_path: Path) -> None:
    repo = build_repo(tmp_path)
    run_dir = _verdict_run(repo, tmp_path)
    result = update_ledger(run_dir, repo_root=repo)
    assert result.status == "PASS"
    assert result.verdict == "PASS"
    ledger = jsonio_load(repo / "lab" / "parity-ledger" / "ledger.json")
    entry = ledger["entries"][0]
    assert entry["status"] == "PASS"
    assert entry["last_run"] == {
        "utc": "2026-09-23T12:01:04Z",
        "side": "both",
        "provider": "e2b",
        "emulator_acceleration": "none",
        "run_id": RUN_ID,
        "verdict": "PASS",
    }
    assert ledger["updated"] == "2026-09-23T12:01:04Z"
    assert ledger["updated_by"] == "parity-cli"
    assert entry["notes"] == (
        "provisional \u2014 pending reference discovery\n"
        f"parity: PASS — run {RUN_ID} (2026-09-23T12:01:04Z)")
    assert entry["title"] == "Single-document capture end-to-end"


def test_scenario_status_mirror_updated_surgically(tmp_path: Path) -> None:
    repo = build_repo(tmp_path)
    run_dir = _verdict_run(repo, tmp_path)
    update_ledger(run_dir, repo_root=repo)
    scenario = (repo / "lab" / "scenarios" /
                "S004-single-document-capture.yaml").read_text(
                    encoding="utf-8")
    # the mirror line changed, the comment and every other line survived
    assert "status: PASS" in scenario.splitlines()
    assert scenario.startswith("# migrated")
    assert "title: Single-document capture end-to-end" in scenario
    assert "timeout_seconds: 2400" in scenario


def test_evidence_refs_recorded(tmp_path: Path) -> None:
    repo = build_repo(tmp_path)
    run_dir = _verdict_run(repo, tmp_path)
    update_ledger(run_dir, repo_root=repo)
    ledger = jsonio_load(repo / "lab" / "parity-ledger" / "ledger.json")
    evidence = ledger["entries"][0]["evidence"]
    assert evidence == [{
        "kind": "document",
        "ref": "lab/scenarios/S004-single-document-capture.yaml",
        "run_id": RUN_ID,
        "side": None,
        "note": f"scenario spec executed by run {RUN_ID}",
    }]


def test_r2_manifest_evidence_recorded_when_uploaded(tmp_path: Path
                                                     ) -> None:
    from tools.parity_cli.compare import compare_run
    repo = build_repo(tmp_path)
    build_pair(repo, run_id=RUN_ID)
    run_dir = repo / "runs" / RUN_ID
    doc = jsonio_load(run_dir / "implementation" / "manifest.json")
    doc["artifacts"][0]["r2_key"] = (
        f"runs/{RUN_ID}/implementation/{doc['artifacts'][0]['path']}")
    jsonio_dump(run_dir / "implementation" / "manifest.json", doc)
    masks = repo / "masks-empty"
    masks.mkdir(exist_ok=True)
    result = compare_run(RUN_ID, runs_dir=repo / "runs", masks_dir=masks)
    assert result.diff["subjects"]["implementation"]["r2_uploaded"]
    update_ledger(run_dir, repo_root=repo)
    ledger = jsonio_load(repo / "lab" / "parity-ledger" / "ledger.json")
    refs = [ev["ref"] for ev in ledger["entries"][0]["evidence"]]
    assert (f"r2:camscan-parity-evidence/runs/{RUN_ID}/implementation/"
            "manifest.json") in refs


def test_gap_report_evidence_recorded(tmp_path: Path) -> None:
    from tools.parity_cli.gaps import write_gaps
    repo = build_repo(tmp_path)
    result = compare_pair(repo, run_id=RUN_ID, with_implementation=False)
    write_gaps(result.diff, repo / "runs" / RUN_ID)
    update_ledger(repo / "runs" / RUN_ID, repo_root=repo)
    ledger = jsonio_load(repo / "lab" / "parity-ledger" / "ledger.json")
    refs = [ev["ref"] for ev in ledger["entries"][0]["evidence"]]
    assert ("lab/reconciliation/S004-single-document-capture."
            "bundle-implementation.gap.yaml") in refs


def test_ledger_update_is_idempotent(tmp_path: Path) -> None:
    repo = build_repo(tmp_path)
    run_dir = _verdict_run(repo, tmp_path)
    update_ledger(run_dir, repo_root=repo)
    first = (repo / "lab" / "parity-ledger" / "ledger.json").read_text(
        encoding="utf-8")
    scenario_first = (repo / "lab" / "scenarios" /
                      "S004-single-document-capture.yaml").read_text(
                          encoding="utf-8")
    update_ledger(run_dir, repo_root=repo)
    assert (repo / "lab" / "parity-ledger" / "ledger.json").read_text(
        encoding="utf-8") == first
    assert (repo / "lab" / "scenarios" /
            "S004-single-document-capture.yaml").read_text(
                encoding="utf-8") == scenario_first


def test_fail_verdict_maps_to_implemented(tmp_path: Path) -> None:
    from helpers import DEFAULT_TRACE
    repo = build_repo(tmp_path)
    failed_launch = [dict(step) for step in DEFAULT_TRACE]
    failed_launch[0]["result"] = "failed"
    run_dir = _verdict_run(repo, tmp_path, impl_overrides={
        "action_trace": failed_launch})
    result = update_ledger(run_dir, repo_root=repo)
    assert result.status == "IMPLEMENTED"
    assert result.last_run["verdict"] == "FAIL"


def test_blocked_verdict_maps_to_blocked(tmp_path: Path) -> None:
    repo = build_repo(tmp_path)
    run_dir = _verdict_run(repo, tmp_path, with_implementation=False)
    result = update_ledger(run_dir, repo_root=repo)
    assert result.status == "BLOCKED"


def test_not_observed_verdict_maps_to_unknown(tmp_path: Path) -> None:
    repo = build_repo(tmp_path)
    run_dir = _verdict_run(repo, tmp_path, with_reference=False)
    result = update_ledger(run_dir, repo_root=repo)
    assert result.status == "UNKNOWN"
    assert result.last_run["verdict"] == "NOT_OBSERVED"


def test_unknown_scenario_errors(tmp_path: Path) -> None:
    repo = build_repo(tmp_path)
    run_dir = _verdict_run(repo, tmp_path)
    update_ledger(run_dir, repo_root=repo)
    # a run whose manifest scenario has no ledger entry
    run_id2 = "20260923T130000Z-S099-unit02"
    compare_pair(repo, run_id=run_id2, ref_overrides={
        "scenario": "some-unled-scenario"},
        impl_overrides={"scenario": "some-unled-scenario"})
    with pytest.raises(ParityCliError, match="no ledger entry"):
        update_ledger(repo / "runs" / run_id2, repo_root=repo)


def test_run_id_scenario_number_mismatch_errors(tmp_path: Path) -> None:
    repo = build_repo(tmp_path)
    # manifest scenario is ledgered, but the run id encodes S099
    run_id = "20260923T130000Z-S099-unit03"
    compare_pair(repo, run_id=run_id)
    with pytest.raises(ParityCliError, match="encodes scenario number"):
        update_ledger(repo / "runs" / run_id, repo_root=repo)


def test_title_mirror_mismatch_errors(tmp_path: Path) -> None:
    repo = build_repo(tmp_path)
    run_dir = _verdict_run(repo, tmp_path)
    ledger = ledger_doc()
    ledger["entries"][0]["title"] = "A different title"
    (repo / "lab" / "parity-ledger" / "ledger.json").write_text(
        json.dumps(ledger, indent=2), encoding="utf-8")
    with pytest.raises(ParityCliError, match="title"):
        update_ledger(run_dir, repo_root=repo)


def test_missing_verdict_errors(tmp_path: Path) -> None:
    repo = build_repo(tmp_path)
    run_dir = build_pair(repo, run_id=RUN_ID)
    with pytest.raises(ParityCliError, match="run compare first"):
        update_ledger(run_dir, repo_root=repo)


def test_structural_validation_rejects_bad_evidence_ref(tmp_path: Path
                                                        ) -> None:
    repo = build_repo(tmp_path)
    run_dir = _verdict_run(repo, tmp_path)
    update_ledger(run_dir, repo_root=repo)
    # hand-corrupt the ledger with an out-of-vocabulary ref and try again
    ledger = jsonio_load(repo / "lab" / "parity-ledger" / "ledger.json")
    ledger["entries"][0]["evidence"].append({
        "kind": "verdict", "ref": "runs/nowhere/verdict.json",
        "run_id": RUN_ID, "side": None, "note": "bad ref"})
    (repo / "lab" / "parity-ledger" / "ledger.json").write_text(
        json.dumps(ledger, indent=2), encoding="utf-8")
    with pytest.raises(ParityCliError, match="evidence ref"):
        update_ledger(run_dir, repo_root=repo)


def test_ledger_key_order_preserved(tmp_path: Path) -> None:
    """The ledger keeps its document key order (minimal, reviewable
    diffs); entries' new fields append deterministically."""
    repo = build_repo(tmp_path)
    run_dir = _verdict_run(repo, tmp_path)
    update_ledger(run_dir, repo_root=repo)
    ledger = json.loads((repo / "lab" / "parity-ledger" / "ledger.json")
                        .read_text(encoding="utf-8"))
    assert list(ledger) == ["$schema", "updated", "updated_by",
                            "statuses", "blocked_dependencies",
                            "resolved_dependencies", "entries"]
    assert list(ledger["entries"][0])[:2] == ["id", "scenario"]


def test_dry_run_writes_nothing(tmp_path: Path) -> None:
    repo = build_repo(tmp_path)
    run_dir = _verdict_run(repo, tmp_path)
    before_ledger = (repo / "lab" / "parity-ledger" / "ledger.json"
                     ).read_text(encoding="utf-8")
    before_scenario = (repo / "lab" / "scenarios" /
                       "S004-single-document-capture.yaml").read_text(
                           encoding="utf-8")
    result = update_ledger(run_dir, repo_root=repo, dry_run=True)
    assert result.status == "PASS" and not result.wrote_ledger
    assert (repo / "lab" / "parity-ledger" / "ledger.json").read_text(
        encoding="utf-8") == before_ledger
    assert (repo / "lab" / "scenarios" /
            "S004-single-document-capture.yaml").read_text(
                encoding="utf-8") == before_scenario
