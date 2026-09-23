"""Ignore-list masks — loading, application, fail-closed rules."""
from __future__ import annotations

import json
from pathlib import Path

import pytest
from helpers import DEFAULT_TRACE, TRACE_WITH_UPSELL, compare_pair, entry_ids
from tools.parity_cli.masks import DEFAULT_MASKS_DIR, apply_masks, load_masks
from tools.parity_cli.model import ParityCliError


def _mask_file(masks: Path, rules: list[dict],
               scenario: str = "single-document-capture") -> Path:
    path = masks / f"{scenario}.json"
    path.write_text(json.dumps({"scenario": scenario, "steps": rules}),
                    encoding="utf-8")
    return path


def test_mask_realignment_removes_step_count_entry(tmp_path: Path) -> None:
    """The documented use: a CamScanner-only step, masked away, lets the
    pair compare step-count-equal."""
    masks = tmp_path / "masks"
    masks.mkdir()
    _mask_file(masks, [{"action": "tap", "target": "premium-upsell-dismiss",
                        "reason": "reference-only upsell UX"}])
    result = compare_pair(tmp_path, ref_overrides={
        "action_trace": [dict(s) for s in TRACE_WITH_UPSELL]},
        masks_dir=masks)
    assert "actions-step-count" not in entry_ids(result.diff)
    masks_doc = result.diff["masks"]
    assert masks_doc["removed_reference_steps"] == [4]
    assert masks_doc["kept_reference_steps"] == [0, 1, 2, 3, 5]
    assert masks_doc["applied"][0]["reason"].startswith(
        "reference-only upsell")


def test_without_mask_step_count_diverges(tmp_path: Path) -> None:
    result = compare_pair(tmp_path, ref_overrides={
        "action_trace": [dict(s) for s in TRACE_WITH_UPSELL]})
    assert "actions-step-count" in entry_ids(result.diff)


def test_mask_target_narrowing(tmp_path: Path) -> None:
    masks = tmp_path / "masks"
    masks.mkdir()
    # target does not match the step → mask inert → divergence remains
    _mask_file(masks, [{"action": "tap", "target": "no-such-target",
                        "reason": "inert on purpose"}])
    result = compare_pair(tmp_path, ref_overrides={
        "action_trace": [dict(s) for s in TRACE_WITH_UPSELL]},
        masks_dir=masks)
    assert "actions-step-count" in entry_ids(result.diff)
    assert result.diff["masks"]["rules_considered"] == 1
    assert result.diff["masks"]["applied"] == []


def test_mask_index_narrowing(tmp_path: Path) -> None:
    masks = tmp_path / "masks"
    masks.mkdir()
    _mask_file(masks, [{"action": "tap", "index": 4,
                        "reason": "reference-only step at position 4"}])
    result = compare_pair(tmp_path, ref_overrides={
        "action_trace": [dict(s) for s in TRACE_WITH_UPSELL]},
        masks_dir=masks)
    assert "actions-step-count" not in entry_ids(result.diff)


def test_implementation_side_mask(tmp_path: Path) -> None:
    masks = tmp_path / "masks"
    masks.mkdir()
    _mask_file(masks, [{"action": "tap", "target": "telemetry-consent",
                        "side": "implementation",
                        "reason": "CamScan-only consent step"}])
    impl_trace = [dict(s) for s in DEFAULT_TRACE]
    impl_trace.insert(2, {"t_ms": 1500, "action": "tap",
                          "target": "telemetry-consent", "result": "ok"})
    result = compare_pair(tmp_path, impl_overrides={
        "action_trace": impl_trace}, masks_dir=masks)
    assert "actions-step-count" not in entry_ids(result.diff)
    assert result.diff["masks"]["removed_implementation_steps"] == [2]


def test_mask_for_other_scenario_ignored(tmp_path: Path) -> None:
    masks = tmp_path / "masks"
    masks.mkdir()
    _mask_file(masks, [{"action": "tap", "reason": "other scenario"}],
               scenario="ocr")
    result = compare_pair(tmp_path, masks_dir=masks)
    assert result.diff["masks"]["rules_considered"] == 0


def test_broken_mask_json_fails_closed(tmp_path: Path) -> None:
    masks = tmp_path / "masks"
    masks.mkdir()
    (masks / "single-document-capture.json").write_text("{broken",
                                                         encoding="utf-8")
    with pytest.raises(ParityCliError, match="unparseable"):
        compare_pair(tmp_path, masks_dir=masks)


def test_mask_without_reason_fails_closed(tmp_path: Path) -> None:
    masks = tmp_path / "masks"
    masks.mkdir()
    _mask_file(masks, [{"action": "tap", "target": "x"}])
    with pytest.raises(ParityCliError, match="no 'reason'"):
        compare_pair(tmp_path, masks_dir=masks)


def test_mask_without_action_fails_closed(tmp_path: Path) -> None:
    masks = tmp_path / "masks"
    masks.mkdir()
    _mask_file(masks, [{"target": "x", "reason": "no action field"}])
    with pytest.raises(ParityCliError, match="action"):
        compare_pair(tmp_path, masks_dir=masks)


def test_mask_bad_side_fails_closed(tmp_path: Path) -> None:
    masks = tmp_path / "masks"
    masks.mkdir()
    _mask_file(masks, [{"action": "tap", "side": "both-sides",
                        "reason": "bad side value"}])
    with pytest.raises(ParityCliError, match="side"):
        compare_pair(tmp_path, masks_dir=masks)


def test_apply_masks_first_rule_wins(tmp_path: Path) -> None:
    rules = load_masks(DEFAULT_MASKS_DIR, "single-document-capture")
    ref, impl, record = apply_masks(
        [dict(s) for s in TRACE_WITH_UPSELL],
        [dict(s) for s in DEFAULT_TRACE], rules)
    assert len(ref) == 5 and len(impl) == 5
    assert record.removed_reference_steps == [4]
    # the kept reference steps keep their ORIGINAL indices
    assert [index for index, _ in ref] == [0, 1, 2, 3, 5]


def test_default_masks_dir_is_the_tools_own() -> None:
    assert DEFAULT_MASKS_DIR.name == "masks"
    assert DEFAULT_MASKS_DIR.parent.name == "parity-cli"


def test_default_mask_matches_upsell_step() -> None:
    rules = load_masks(DEFAULT_MASKS_DIR, "single-document-capture")
    assert rules and rules[0].action == "tap"
    assert rules[0].target == "premium-upsell-dismiss"
    assert rules[0].matches(TRACE_WITH_UPSELL[4], 4)
    assert not rules[0].matches(DEFAULT_TRACE[1], 1)
