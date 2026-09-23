"""Gap emission per GAP-FORMAT.md."""
from __future__ import annotations

from pathlib import Path

import pytest
from helpers import build_pair, build_repo, compare_pair, default_artifacts
from tools.parity_cli.gaps import gap_documents, render_gap_yaml, write_gaps
from tools.parity_cli.model import ParityCliError


def test_gap_emitted_for_medium(tmp_path: Path) -> None:
    artifacts = [entry for entry in default_artifacts()
                 if not entry["path"].startswith("ui/03")]
    result = compare_pair(tmp_path, impl_overrides={
        "artifacts": artifacts})
    outcome = write_gaps(result.diff, Path(result.run_dir),
                         tmp_path / "reconciliation-out")
    assert len(outcome.written) == 1
    path = Path(outcome.written[0])
    assert path.name == ("S004-single-document-capture."
                         "step-evidence-03.gap.yaml")
    text = path.read_text(encoding="utf-8")
    assert text.startswith("gap:\n")
    assert 'id: "S004-single-document-capture-step-evidence-03-1"' in text
    assert 'feature: "step-evidence-03"' in text
    assert 'scenario: "S004-single-document-capture"' in text
    assert 'severity: "medium"' in text
    assert 'status: "open"' in text
    assert "manifest.json" in text           # evidence paths both sides
    assert "behavior:" in text
    assert "required_change:" in text
    assert "verification:" in text
    assert 'assertions: ["document-detected", "crop-stage-visible"' in text


def test_no_gaps_for_low_only(tmp_path: Path) -> None:
    result = compare_pair(tmp_path, impl_overrides={
        "artifacts": default_artifacts(shared=False)})
    outcome = write_gaps(result.diff, Path(result.run_dir),
                         tmp_path / "out")
    assert outcome.written == []
    assert outcome.considered == 0


def test_gaps_for_high_and_critical(tmp_path: Path) -> None:
    from helpers import DEFAULT_TRACE
    failed_launch = [dict(step) for step in DEFAULT_TRACE]
    failed_launch[0]["result"] = "failed"
    result = compare_pair(tmp_path, impl_overrides={
        "action_trace": failed_launch})
    outcome = write_gaps(result.diff, Path(result.run_dir),
                         tmp_path / "out")
    assert len(outcome.written) == 1
    text = Path(outcome.written[0]).read_text(encoding="utf-8")
    assert 'severity: "critical"' in text
    assert 'feature: "launch"' in text


def test_bundle_gap_for_blocked_run(tmp_path: Path) -> None:
    result = compare_pair(tmp_path, with_implementation=False)
    outcome = write_gaps(result.diff, Path(result.run_dir),
                         tmp_path / "out")
    assert len(outcome.written) == 1
    assert Path(outcome.written[0]).name == (
        "S004-single-document-capture.bundle-implementation.gap.yaml")


def test_existing_open_gap_rewritten(tmp_path: Path) -> None:
    artifacts = [entry for entry in default_artifacts()
                 if not entry["path"].startswith("ui/03")]
    result = compare_pair(tmp_path, impl_overrides={
        "artifacts": artifacts})
    out = tmp_path / "out"
    first = write_gaps(result.diff, Path(result.run_dir), out)
    text = Path(first.written[0]).read_text(encoding="utf-8")
    # rewrite: a second emission refreshes the open gap
    second = write_gaps(result.diff, Path(result.run_dir), out)
    assert second.written == first.written
    assert Path(second.written[0]).read_text(encoding="utf-8") == text


def test_non_open_gap_not_clobbered(tmp_path: Path) -> None:
    artifacts = [entry for entry in default_artifacts()
                 if not entry["path"].startswith("ui/03")]
    result = compare_pair(tmp_path, impl_overrides={
        "artifacts": artifacts})
    out = tmp_path / "out"
    first = write_gaps(result.diff, Path(result.run_dir), out)
    path = Path(first.written[0])
    annotated = path.read_text(encoding="utf-8").replace(
        'status: "open"', 'status: "implemented"')
    path.write_text(annotated, encoding="utf-8")
    second = write_gaps(result.diff, Path(result.run_dir), out)
    assert second.written == []
    assert second.skipped == [{"path": str(path), "status": "implemented"}]
    assert path.read_text(encoding="utf-8") == annotated


def test_gap_requires_scenario_yaml(tmp_path: Path) -> None:
    run_dir = build_pair(tmp_path, with_reference=False)
    (run_dir / "scenario.yaml").unlink()
    from tools.parity_cli.compare import compare_run
    masks = tmp_path / "masks-empty"
    masks.mkdir(exist_ok=True)
    result = compare_run(run_dir.name, runs_dir=tmp_path / "runs",
                         masks_dir=masks)
    with pytest.raises(ParityCliError, match="scenario.yaml"):
        write_gaps(result.diff, run_dir, tmp_path / "out")


def test_gap_yaml_round_trips_through_safe_load(tmp_path: Path) -> None:
    """The emitted yaml parses with the lab's yaml stack (CI-side)."""
    yaml = pytest.importorskip("yaml")
    artifacts = [entry for entry in default_artifacts()
                 if not entry["path"].startswith("ui/03")]
    result = compare_pair(tmp_path, impl_overrides={"artifacts": artifacts})
    gaps = gap_documents(result.diff, {
        "id": "single-document-capture", "title": "t", "status": "UNKNOWN",
        "assertions": {"behavior": ["document-detected"],
                       "ui": [], "state": [], "output": ["pdf-created"]}})
    doc = yaml.safe_load(render_gap_yaml(gaps[0]))
    assert doc["gap"]["feature"] == "step-evidence-03"
    assert doc["gap"]["severity"] == "medium"
    assert doc["gap"]["status"] == "open"
    assert doc["gap"]["verification"]["assertions"] == [
        "document-detected", "pdf-created"]
    assert doc["gap"]["reference"]["evidence"].startswith("runs/")


def test_default_gaps_dir_is_lab_reconciliation(tmp_path: Path) -> None:
    """Without --gaps-dir, gaps land in the repo's lab/reconciliation/
    (Worker 3's directory, GAP-FORMAT.md's pinned location)."""
    repo = build_repo(tmp_path)
    result = compare_pair(repo, with_implementation=False)
    outcome = write_gaps(result.diff, Path(result.run_dir))
    assert outcome.written
    assert Path(outcome.written[0]).parent == repo / "lab" / "reconciliation"
