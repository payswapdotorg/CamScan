"""Dimension (d) action outcome parity — the step-by-step trace."""
from __future__ import annotations

from pathlib import Path

from helpers import compare_pair, entry_ids, entry_map


def _trace_with(index: int, **changes: object) -> list[dict]:
    from helpers import DEFAULT_TRACE
    trace = [dict(step) for step in DEFAULT_TRACE]
    trace[index].update(changes)
    return trace


def test_identical_traces_no_entries(tmp_path: Path) -> None:
    diff = compare_pair(tmp_path).diff
    assert diff["dimensions"]["actions"]["entries"] == 0


def test_ok_literals_within_class_compare_equal(tmp_path: Path) -> None:
    """'saved' vs 'ok' are both ok-class: no divergence (runner literal
    vocabulary differs; the outcome class is the parity-relevant level)."""
    diff = compare_pair(tmp_path, impl_overrides={
        "action_trace": _trace_with(4, result="ok")}).diff
    assert entry_ids(diff) == set()


def test_step_count_divergence_high(tmp_path: Path) -> None:
    trace = _trace_with(4)[:-1]
    diff = compare_pair(tmp_path, impl_overrides={
        "action_trace": trace}).diff
    entry = entry_map(diff)["actions-step-count"]
    assert entry["severity"] == "high"
    assert entry["reference"]["steps"] == 5
    assert entry["implementation"]["steps"] == 4
    assert entry["feature"] == "action-trace-shape"


def test_step_action_mismatch_high(tmp_path: Path) -> None:
    diff = compare_pair(tmp_path, impl_overrides={
        "action_trace": _trace_with(1, action="wait-for")}).diff
    entry = entry_map(diff)["actions-step-02-action"]
    assert entry["severity"] == "high"
    assert entry["reference"] == "tap"
    assert entry["implementation"] == "wait-for"


def test_impl_failed_where_ref_ok_high(tmp_path: Path) -> None:
    diff = compare_pair(tmp_path, impl_overrides={
        "action_trace": _trace_with(2, result="timeout")}).diff
    entry = entry_map(diff)["actions-step-03-result"]
    assert entry["severity"] == "high"
    assert entry["reference"] == "document-detected"
    assert entry["implementation"] == "timeout"


def test_impl_denied_where_ref_ok_critical(tmp_path: Path) -> None:
    """Permission flip (granted vs denied) is critical."""
    diff = compare_pair(tmp_path, impl_overrides={
        "action_trace": _trace_with(2, result="denied")}).diff
    entry = entry_map(diff)["actions-step-03-result"]
    assert entry["severity"] == "critical"


def test_ref_failed_where_impl_ok_medium(tmp_path: Path) -> None:
    """A broken reference observation is not the implementation's
    fault — medium, comparison partial."""
    diff = compare_pair(tmp_path, ref_overrides={
        "action_trace": _trace_with(2, result="crash")}).diff
    entry = entry_map(diff)["actions-step-03-result"]
    assert entry["severity"] == "medium"


def test_ref_denied_where_impl_ok_medium(tmp_path: Path) -> None:
    diff = compare_pair(tmp_path, ref_overrides={
        "action_trace": _trace_with(2, result="denied")}).diff
    entry = entry_map(diff)["actions-step-03-result"]
    assert entry["severity"] == "medium"


def test_both_failed_same_class_no_entry(tmp_path: Path) -> None:
    """Both sides failing identically is outcome parity (of a failure)."""
    trace_ref = _trace_with(2, result="timeout")
    trace_impl = _trace_with(2, result="timed-out")
    diff = compare_pair(tmp_path, ref_overrides={
        "action_trace": trace_ref}, impl_overrides={
        "action_trace": trace_impl}).diff
    assert "actions-step-03-result" not in entry_ids(diff)


def test_unclassified_literals_differ_medium(tmp_path: Path) -> None:
    diff = compare_pair(tmp_path, impl_overrides={
        "action_trace": _trace_with(2, result="purple")},
        ref_overrides={"action_trace": _trace_with(2,
                                                   result="blue")}).diff
    entry = entry_map(diff)["actions-step-03-result"]
    assert entry["severity"] == "medium"


def test_unrecorded_results_both_sides_no_entry(tmp_path: Path) -> None:
    ref = _trace_with(2)
    for step in ref:
        step.pop("result", None)
    impl = _trace_with(2)
    for step in impl:
        step.pop("result", None)
    diff = compare_pair(tmp_path, ref_overrides={"action_trace": ref},
                        impl_overrides={"action_trace": impl}).diff
    assert "actions-step-03-result" not in entry_ids(diff)


def test_impl_launch_failed_critical(tmp_path: Path) -> None:
    diff = compare_pair(tmp_path, impl_overrides={
        "action_trace": _trace_with(0, result="anr")}).diff
    entry = entry_map(diff)["actions-launch-implementation"]
    assert entry["severity"] == "critical"


def test_ref_launch_failed_medium(tmp_path: Path) -> None:
    diff = compare_pair(tmp_path, ref_overrides={
        "action_trace": _trace_with(0, result="crash")}).diff
    entry = entry_map(diff)["actions-launch-reference"]
    assert entry["severity"] == "medium"


def test_launch_outcome_flip_is_fail(tmp_path: Path) -> None:
    result = compare_pair(tmp_path, impl_overrides={
        "action_trace": _trace_with(0, result="failed")})
    assert result.verdict["verdict"] == "FAIL"


def test_impl_step_failure_is_fail(tmp_path: Path) -> None:
    result = compare_pair(tmp_path, impl_overrides={
        "action_trace": _trace_with(3, result="error")})
    assert result.verdict["verdict"] == "FAIL"


def test_action_mismatch_then_no_result_entry(tmp_path: Path) -> None:
    """Action mismatch short-circuits the result comparison for that
    position (misaligned steps report the misalignment, not invented
    outcome divergence)."""
    diff = compare_pair(tmp_path, impl_overrides={
        "action_trace": _trace_with(1, action="swipe")}).diff
    assert "actions-step-02-action" in entry_ids(diff)
    assert "actions-step-02-result" not in entry_ids(diff)


def test_target_strings_not_compared(tmp_path: Path) -> None:
    """SCENARIO-DSL: targets are semantic ids resolved per app — target
    naming differences are not divergences (outcome evidence is)."""
    diff = compare_pair(tmp_path, impl_overrides={
        "action_trace": _trace_with(1, target="btn_scan")}).diff
    assert entry_ids(diff) == set()
