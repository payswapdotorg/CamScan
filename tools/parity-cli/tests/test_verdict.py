"""Verdict computation — every verdict path, pinned by tests.

PASS / PARTIAL / FAIL / BLOCKED / NOT_OBSERVED and their precedence,
plus the exact verdict.json field set.
"""
from __future__ import annotations

from pathlib import Path
from typing import Any

from tools.parity_cli.verdict import compute_verdict, verdict_doc

from helpers import (DEFAULT_TRACE, compare_pair, default_artifacts,
                     entry_ids)


def _diff(counts: dict[str, int], ref: str = "ok", impl: str = "ok"
          ) -> dict[str, Any]:
    return {
        "subjects": {"reference": {"status": ref},
                     "implementation": {"status": impl}},
        "counts": {"critical": counts.get("critical", 0),
                   "high": counts.get("high", 0),
                   "medium": counts.get("medium", 0),
                   "low": counts.get("low", 0)},
    }


def test_pass_zero_divergences() -> None:
    assert compute_verdict(_diff({})) == "PASS"


def test_pass_low_only_recorded_not_blocking() -> None:
    """Work order: output equality is NOT required — low-only
    divergence must not block PASS (pinned interpretation)."""
    assert compute_verdict(_diff({"low": 3})) == "PASS"


def test_partial_medium_only() -> None:
    assert compute_verdict(_diff({"medium": 2, "low": 1})) == "PARTIAL"


def test_fail_on_high() -> None:
    assert compute_verdict(_diff({"high": 1})) == "FAIL"


def test_fail_on_critical() -> None:
    assert compute_verdict(_diff({"critical": 1, "medium": 2})) == "FAIL"


def test_fail_beats_partial() -> None:
    assert compute_verdict(_diff({"high": 1, "medium": 4})) == "FAIL"


def test_blocked_implementation_missing() -> None:
    assert compute_verdict(_diff({}, impl="missing")) == "BLOCKED"


def test_blocked_implementation_invalid() -> None:
    assert compute_verdict(_diff({}, impl="invalid")) == "BLOCKED"


def test_blocked_implementation_no_observation() -> None:
    assert compute_verdict(_diff({}, impl="no-observation")) == "BLOCKED"


def test_not_observed_reference_missing() -> None:
    assert compute_verdict(_diff({}, ref="missing")) == "NOT_OBSERVED"


def test_not_observed_reference_invalid() -> None:
    assert compute_verdict(_diff({}, ref="invalid")) == "NOT_OBSERVED"


def test_not_observed_reference_no_observation() -> None:
    assert compute_verdict(_diff({}, ref="no-observation")) == \
        "NOT_OBSERVED"


def test_blocked_takes_precedence_over_not_observed() -> None:
    assert compute_verdict(_diff({}, ref="missing", impl="missing")) \
        == "BLOCKED"


def test_blocked_beats_fail_counts() -> None:
    """A blocked run records its critical bundle entry but never routes
    to FAIL — BLOCKED wins."""
    assert compute_verdict(_diff({"critical": 1}, impl="invalid")) \
        == "BLOCKED"


def test_not_observed_never_becomes_pass(tmp_path: Path) -> None:
    """The guard the work order pins: a perfect implementation run with
    an absent reference observation is NOT_OBSERVED, never PASS."""
    result = compare_pair(tmp_path, with_reference=False)
    assert result.verdict["verdict"] == "NOT_OBSERVED"
    assert "never converted into PASS" in result.verdict["summary"]


def test_verdict_doc_field_set() -> None:
    doc = verdict_doc({
        "run_id": "r", "scenario": "s", "generated_utc": "g",
        "subjects": {"reference": {"status": "ok"},
                     "implementation": {"status": "ok"}},
        "counts": {"critical": 0, "high": 0, "medium": 1, "low": 2},
    })
    assert set(doc) == {"run_id", "scenario", "verdict", "summary",
                        "counts", "generated_utc"}
    assert doc["verdict"] == "PARTIAL"
    assert doc["counts"] == {"critical": 0, "high": 0, "medium": 1,
                             "low": 2}


def test_synthetic_pass_pair(tmp_path: Path) -> None:
    result = compare_pair(tmp_path)
    assert result.verdict["verdict"] == "PASS"
    assert entry_ids(result.diff) == set()
    assert result.wrote_diff and result.wrote_verdict


def test_synthetic_blocked_pair(tmp_path: Path) -> None:
    result = compare_pair(tmp_path, with_implementation=False)
    assert result.verdict["verdict"] == "BLOCKED"
    assert entry_ids(result.diff) == {"bundle-implementation-missing"}
    assert result.diff["counts"]["critical"] == 1
    assert "never counted as PASS" in result.verdict["summary"]


def test_synthetic_not_observed_pair(tmp_path: Path) -> None:
    result = compare_pair(tmp_path, with_reference=False)
    assert result.verdict["verdict"] == "NOT_OBSERVED"
    assert entry_ids(result.diff) == {"bundle-reference-missing"}


def _trace_with(index: int, **changes: object) -> list[dict]:
    trace = [dict(step) for step in DEFAULT_TRACE]
    trace[index].update(changes)
    return trace


def test_synthetic_fail_pair(tmp_path: Path) -> None:
    result = compare_pair(tmp_path, impl_overrides={
        "action_trace": _trace_with(0, result="failed")})
    assert result.verdict["verdict"] == "FAIL"
    assert "actions-launch-implementation" in entry_ids(result.diff)


def test_synthetic_partial_pair(tmp_path: Path) -> None:
    impl_artifacts = [entry for entry in default_artifacts()
                      if not entry["path"].startswith("ui/03")]
    result = compare_pair(tmp_path, impl_overrides={
        "artifacts": impl_artifacts})
    assert result.verdict["verdict"] == "PARTIAL"
    assert "artifacts-step-03-implementation-ui" in entry_ids(result.diff)
    assert result.diff["counts"]["medium"] == 1


def test_summary_deterministic(tmp_path: Path) -> None:
    first = compare_pair(tmp_path)
    second = compare_pair(tmp_path / "again")
    assert first.verdict == second.verdict
