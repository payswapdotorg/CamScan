"""schema: the hand-rolled EVIDENCE.md manifest validator."""
from __future__ import annotations

import pytest
from helpers import valid_metadata
from tools.evidence_cli.schema import check_artifact_path, validate_manifest


@pytest.fixture()
def manifest() -> dict:
    doc = valid_metadata("20260922T103000Z-S004-unit01")
    doc["artifacts"] = [{
        "path": "screenshots/01-launch.png",
        "sha256": "a" * 64, "bytes": 100,
    }]
    return doc


def test_valid_manifest_has_no_problems(manifest):
    assert validate_manifest(manifest) == []


def test_top_level_keys_enforced(manifest):
    manifest["extra"] = 1
    assert any("unknown keys" in p for p in validate_manifest(manifest))
    del manifest["device"]
    assert any("missing keys" in p for p in validate_manifest(manifest))


def test_run_id_shape(manifest):
    manifest["run_id"] = "not-a-run-id"
    assert any("run_id" in p for p in validate_manifest(manifest))


def test_subject_enum(manifest):
    manifest["subject"] = "both"
    assert any("subject" in p for p in validate_manifest(manifest))


def test_provider_shape(manifest):
    manifest["provider"]["extra"] = 1
    assert any("provider: unknown keys" in p
               for p in validate_manifest(manifest))
    manifest["provider"]["capabilities"] = {}
    assert any("capabilities" in p for p in validate_manifest(manifest))


def test_application_installer_sha256(manifest):
    manifest["application"]["installer_sha256"] = "XYZ"
    assert any("installer_sha256" in p for p in validate_manifest(manifest))


def test_device_screen_shape(manifest):
    manifest["device"]["screen"] = "1080x2280"
    assert any("device.screen" in p for p in validate_manifest(manifest))


def test_artifact_path_rules(manifest):
    manifest["artifacts"][0]["path"] = "/abs/01.png"
    assert check_artifact_path("/abs/01.png") is not None
    assert any("subject-relative" in p for p in validate_manifest(manifest))


def test_artifact_path_must_have_category_prefix():
    assert check_artifact_path("loose-file.png") is not None
    assert check_artifact_path("screenshots/../../etc") is not None
    assert check_artifact_path("screenshots/with space.png") is not None
    assert check_artifact_path("screenshots/01.png.sha256") is not None
    assert check_artifact_path("outputs/doc.pdf") is None


def test_artifact_hash_and_bytes(manifest):
    manifest["artifacts"][0]["sha256"] = "A" * 64
    assert any("lowercase hex" in p for p in validate_manifest(manifest))
    manifest["artifacts"][0]["sha256"] = "a" * 64
    manifest["artifacts"][0]["bytes"] = 0
    assert any("bytes" in p for p in validate_manifest(manifest))


def test_artifacts_must_be_non_empty(manifest):
    manifest["artifacts"] = []
    assert any("must not be empty" in p
               for p in validate_manifest(manifest))


def test_duplicate_artifact_paths(manifest):
    manifest["artifacts"].append(dict(manifest["artifacts"][0]))
    assert any("duplicate artifact path" in p
               for p in validate_manifest(manifest))


def test_r2_key_exact_form(manifest):
    manifest["artifacts"][0]["r2_key"] = "runs/wrong-key"
    assert any("r2_key" in p for p in validate_manifest(manifest))
    manifest["artifacts"][0]["r2_key"] = (
        "runs/20260922T103000Z-S004-unit01/implementation/"
        "screenshots/01-launch.png")
    assert validate_manifest(manifest) == []


def test_timestamps(manifest):
    manifest["started_at"] = "2026-09-22T10:30:00"
    assert any("started_at" in p for p in validate_manifest(manifest))
    manifest["started_at"] = "2026-09-22T10:30:00Z"
    manifest["finished_at"] = "2026-09-22T09:00:00Z"
    assert any("before started_at" in p
               for p in validate_manifest(manifest))


def test_action_trace_rules(manifest):
    manifest["action_trace"][0].pop("t_ms")
    assert any("t_ms" in p for p in validate_manifest(manifest))
    manifest["action_trace"][0].update(t_ms=-1, action="launch")
    assert any("non-negative" in p for p in validate_manifest(manifest))


def test_fixtures_entry_rules(manifest):
    manifest["fixtures"] = [{"id": "clean-a4", "sha256": "b" * 64,
                             "extra": 1}]
    assert any("fixtures[0]" in p for p in validate_manifest(manifest))
    manifest["fixtures"] = [{"id": "clean-a4"}, {"id": "clean-a4",
                                                 "sha256": "c" * 64}]
    assert any("duplicate fixture id" in p
               for p in validate_manifest(manifest))


def test_non_object_manifest_rejected():
    assert validate_manifest([1, 2]) == [
        "manifest: expected a JSON object at top level"]
