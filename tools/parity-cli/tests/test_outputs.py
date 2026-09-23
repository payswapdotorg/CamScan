"""Dimension (e) output parity — type + count + sha256, recorded not
enforced (equality is NOT required)."""
from __future__ import annotations

from pathlib import Path

from helpers import compare_pair, default_artifacts, entry_ids, entry_map


def test_identical_outputs_no_entries(tmp_path: Path) -> None:
    diff = compare_pair(tmp_path).diff
    assert diff["dimensions"]["outputs"]["entries"] == 0


def test_sha_divergence_low(tmp_path: Path) -> None:
    artifacts = default_artifacts(shared=False)
    diff = compare_pair(tmp_path, impl_overrides={
        "artifacts": artifacts}).diff
    entry = entry_map(diff)["outputs-sha-pdf"]
    assert entry["severity"] == "low"
    assert entry["dimension"] == "outputs"
    assert entry["feature"] == "output-pdf"
    assert "outputs-sha-jpg" in entry_ids(diff)


def test_count_divergence_low(tmp_path: Path) -> None:
    artifacts = default_artifacts() + [{
        "path": "outputs/page-02.jpg", "sha256": "0" * 64, "bytes": 10}]
    artifacts.sort(key=lambda e: e["path"])
    diff = compare_pair(tmp_path, impl_overrides={
        "artifacts": artifacts}).diff
    entry = entry_map(diff)["outputs-count-jpg"]
    assert entry["severity"] == "low"
    assert entry["reference"]["count"] == 1
    assert entry["implementation"]["count"] == 2


def test_missing_output_type_medium(tmp_path: Path) -> None:
    artifacts = [entry for entry in default_artifacts()
                 if not entry["path"].endswith(".pdf")]
    diff = compare_pair(tmp_path, impl_overrides={
        "artifacts": artifacts}).diff
    entry = entry_map(diff)["outputs-type-pdf"]
    assert entry["severity"] == "medium"
    assert entry["implementation"]["count"] == 0


def test_extra_output_type_low(tmp_path: Path) -> None:
    artifacts = default_artifacts() + [{
        "path": "outputs/ocr.txt", "sha256": "0" * 64, "bytes": 10}]
    artifacts.sort(key=lambda e: e["path"])
    diff = compare_pair(tmp_path, impl_overrides={
        "artifacts": artifacts}).diff
    entry = entry_map(diff)["outputs-extra-type-txt"]
    assert entry["severity"] == "low"


def test_output_divergences_never_fail(tmp_path: Path) -> None:
    """Recorded, never enforced: even a missing output type routes to
    PARTIAL, never FAIL."""
    artifacts = [entry for entry in default_artifacts()
                 if not entry["path"].endswith(".pdf")]
    result = compare_pair(tmp_path, impl_overrides={
        "artifacts": artifacts})
    assert result.verdict["verdict"] == "PARTIAL"
    assert result.verdict["counts"]["high"] == 0


def test_low_output_divergence_still_pass(tmp_path: Path) -> None:
    artifacts = default_artifacts(shared=False)
    result = compare_pair(tmp_path, impl_overrides={
        "artifacts": artifacts})
    assert result.verdict["verdict"] == "PASS"
    assert result.verdict["counts"]["low"] >= 1
