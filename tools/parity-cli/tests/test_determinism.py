"""Determinism — identical evidence ⇒ byte-identical outputs.

The work order's determinism test: two runs produce byte-identical
diff.json. Here: diff.json AND verdict.json, across two independently
built trees AND across re-runs over the same tree (idempotent
overwrite).
"""
from __future__ import annotations

from pathlib import Path

from helpers import compare_pair


def _outputs(run_dir: Path) -> tuple[bytes, bytes]:
    return ((run_dir / "reconciliation" / "diff.json").read_bytes(),
            (run_dir / "reconciliation" / "verdict.json").read_bytes())


def test_two_independent_builds_byte_identical(tmp_path: Path) -> None:
    first = compare_pair(tmp_path / "one")
    second = compare_pair(tmp_path / "two")
    assert _outputs(Path(first.run_dir)) == _outputs(Path(second.run_dir))


def test_rerun_over_same_tree_is_idempotent(tmp_path: Path) -> None:
    first = compare_pair(tmp_path)
    before = _outputs(Path(first.run_dir))
    second = compare_pair(tmp_path)  # rebuilds + recompares in place
    after = _outputs(Path(second.run_dir))
    assert before == after


def test_verdict_json_byte_identical(tmp_path: Path) -> None:
    first = compare_pair(tmp_path / "one")
    second = compare_pair(tmp_path / "two")
    assert (Path(first.run_dir) / "reconciliation" / "verdict.json"
            ).read_bytes() == (Path(second.run_dir) / "reconciliation" /
                               "verdict.json").read_bytes()


def test_deterministic_with_divergences(tmp_path: Path) -> None:
    """Determinism holds with entries present (sorted, no set-iteration
    order leaks), not just for the empty-diff PASS case."""
    overrides = {
        "impl_overrides": {
            "device": {"locale": "fr-FR"},
            "fixtures": [{"id": "clean-a4", "sha256": "0" * 64}],
        }}
    first = compare_pair(tmp_path / "one", **overrides)
    second = compare_pair(tmp_path / "two", **overrides)
    assert first.verdict["verdict"] == "FAIL"
    assert _outputs(Path(first.run_dir)) == _outputs(
        Path(second.run_dir))


def test_no_wall_clock_stamp_leaks(tmp_path: Path) -> None:
    """generated_utc is evidence-derived; the same manifests re-compared
    minutes apart produce the same stamp (no datetime.now() anywhere in
    the outputs)."""
    result = compare_pair(tmp_path)
    assert result.diff["generated_utc"] == "2026-09-23T12:01:04Z"
    assert result.verdict["generated_utc"] == "2026-09-23T12:01:04Z"
