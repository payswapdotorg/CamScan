"""Dimension (c) artifact parity — per-step evidence coverage."""
from __future__ import annotations

from pathlib import Path

from helpers import compare_pair, default_artifacts, entry_ids, entry_map


def test_all_step_artifacts_present_no_entries(tmp_path: Path) -> None:
    diff = compare_pair(tmp_path).diff
    assert diff["dimensions"]["artifacts"]["entries"] == 0


def test_missing_step_screenshot_high(tmp_path: Path) -> None:
    artifacts = [entry for entry in default_artifacts()
                 if entry["path"] != "screenshots/02-tap.png"]
    diff = compare_pair(tmp_path, impl_overrides={
        "artifacts": artifacts}).diff
    entry = entry_map(diff)["artifacts-step-02-implementation-screenshot"]
    assert entry["severity"] == "high"
    assert entry["dimension"] == "artifacts"
    assert entry["feature"] == "step-evidence-02"


def test_missing_reference_screenshot_high(tmp_path: Path) -> None:
    artifacts = [entry for entry in default_artifacts()
                 if entry["path"] != "screenshots/04-accept-document.png"]
    diff = compare_pair(tmp_path, ref_overrides={
        "artifacts": artifacts}).diff
    entry = entry_map(diff)["artifacts-step-04-reference-screenshot"]
    assert entry["severity"] == "high"


def test_missing_ui_dump_medium(tmp_path: Path) -> None:
    artifacts = [entry for entry in default_artifacts()
                 if not entry["path"].startswith("ui/03")]
    diff = compare_pair(tmp_path, impl_overrides={
        "artifacts": artifacts}).diff
    entry = entry_map(diff)["artifacts-step-03-implementation-ui"]
    assert entry["severity"] == "medium"


def test_unnumbered_screenshots_not_step_checked(tmp_path: Path) -> None:
    """Only numeric-prefixed files map to steps; others are counted."""
    artifacts = [entry for entry in default_artifacts()
                 if entry["path"] != "screenshots/05-save.png"]
    artifacts.append({"path": "screenshots/final.png",
                      "sha256": "0" * 64, "bytes": 10})
    artifacts.sort(key=lambda e: e["path"])
    diff = compare_pair(tmp_path, impl_overrides={
        "artifacts": artifacts}).diff
    # step 5's screenshot is genuinely missing → high entry stays
    assert "artifacts-step-05-implementation-screenshot" in entry_ids(diff)


def test_category_count_divergence_low(tmp_path: Path) -> None:
    artifacts = [entry for entry in default_artifacts()
                 if entry["path"] != "recordings/screen.mp4"]
    diff = compare_pair(tmp_path, impl_overrides={
        "artifacts": artifacts}).diff
    entry = entry_map(diff)["artifacts-count-recordings"]
    assert entry["severity"] == "low"
    assert entry["reference"] == 1
    assert entry["implementation"] == 0


def test_outputs_count_not_double_reported_in_artifacts(
        tmp_path: Path) -> None:
    diff = compare_pair(tmp_path).diff
    assert "artifacts-count-outputs" not in entry_ids(diff)


def test_masked_step_artifacts_not_required(tmp_path: Path) -> None:
    """A masked (reference-only) step's evidence is not required on the
    reference side — masking removes the requirement, not the evidence."""
    import json
    masks = tmp_path / "masks"
    masks.mkdir()
    (masks / "single-document-capture.json").write_text(json.dumps({
        "scenario": "single-document-capture",
        "steps": [{"action": "wait-for", "target": "upsell",
                   "reason": "reference-only step"}]}), encoding="utf-8")
    # reference: 6 steps (extra wait-for at the end) but only artifacts
    # numbered 01..05 → step 6 has no artifacts, yet it is masked
    trace = [{"t_ms": 0, "action": "launch", "result": "ok"},
             {"t_ms": 100, "action": "tap", "target": "scan",
              "result": "ok"},
             {"t_ms": 200, "action": "capture", "result": "ok"},
             {"t_ms": 300, "action": "accept-document", "result": "ok"},
             {"t_ms": 400, "action": "save", "result": "ok"},
             {"t_ms": 500, "action": "wait-for", "target": "upsell",
              "result": "ok"}]
    diff = compare_pair(tmp_path, ref_overrides={"action_trace": trace},
                        masks_dir=masks).diff
    assert "artifacts-step-06-reference-screenshot" not in entry_ids(diff)
    assert "artifacts-step-06-reference-ui" not in entry_ids(diff)
    # and the step count realigned (no step-count entry)
    assert "actions-step-count" not in entry_ids(diff)


def test_missing_step_screenshot_is_fail(tmp_path: Path) -> None:
    artifacts = [entry for entry in default_artifacts()
                 if entry["path"] != "screenshots/01-launch.png"]
    result = compare_pair(tmp_path, impl_overrides={
        "artifacts": artifacts})
    assert result.verdict["verdict"] == "FAIL"


def test_missing_ui_dump_is_partial(tmp_path: Path) -> None:
    artifacts = [entry for entry in default_artifacts()
                 if not entry["path"].startswith("ui/02")]
    result = compare_pair(tmp_path, impl_overrides={
        "artifacts": artifacts})
    assert result.verdict["verdict"] == "PARTIAL"
