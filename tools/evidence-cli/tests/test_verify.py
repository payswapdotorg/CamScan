"""verify: re-derivation of integrity from disk (+R2 when creds exist)."""
from __future__ import annotations

import boto3
import pytest
from helpers import MOTO_ENV, REPO_ROOT, build_run_tree
from moto import mock_aws
from tools.evidence_cli import jsonio
from tools.evidence_cli.bundle import bundle_run
from tools.evidence_cli.integrity import (
    R2_MISSING,
    R2_NO_CREDS,
    R2_NOT_UPLOADED,
    R2_OK,
    SHA_MISMATCH,
    SHA_MISSING,
)
from tools.evidence_cli.verify import verify_manifest


@pytest.fixture(autouse=True)
def _no_creds(monkeypatch):
    for name in ("R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY",
                 "R2_ENDPOINT", "R2_BUCKET"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.delenv("CAMSCAN_FIXTURES_DIR", raising=False)


@pytest.fixture()
def bundled(tmp_path) -> object:
    run = build_run_tree(tmp_path)
    result = bundle_run(run, fixtures_ref=REPO_ROOT)
    assert result.ok, result.problems
    return run


def test_verify_clean_bundle(bundled, capsys):
    result = verify_manifest(bundled / "manifest.json",
                             fixtures_ref=REPO_ROOT)
    assert result.ok, result.problems
    assert len(result.rows) == 11
    assert all(r.sha256 == "ok" for r in result.rows)
    assert not result.remote_checked


def test_verify_catches_tampered_artifact(bundled):
    (bundled / "implementation" / "screenshots" / "01-launch.png").write_bytes(
        b"tampered")
    result = verify_manifest(bundled / "manifest.json",
                             fixtures_ref=REPO_ROOT)
    assert not result.ok
    row = next(r for r in result.rows
               if r.path == "screenshots/01-launch.png")
    assert row.sha256 == SHA_MISMATCH
    assert any("01-launch.png" in p for p in result.problems)


def test_verify_catches_missing_artifact(bundled):
    (bundled / "implementation" / "ui" / "02-scan.xml").unlink()
    result = verify_manifest(bundled / "manifest.json",
                             fixtures_ref=REPO_ROOT)
    assert not result.ok
    row = next(r for r in result.rows if r.path == "ui/02-scan.xml")
    assert row.sha256 == SHA_MISSING


def test_verify_catches_unmanifested_file(bundled):
    (bundled / "implementation" / "screenshots"
     / "99-stray.png").write_bytes(b"x")
    result = verify_manifest(bundled / "manifest.json",
                             fixtures_ref=REPO_ROOT)
    assert not result.ok
    assert any("not in manifest" in p for p in result.problems)


def test_verify_catches_missing_output_sidecar(bundled):
    (bundled / "implementation" / "outputs"
     / "document.pdf.sha256").unlink()
    result = verify_manifest(bundled / "manifest.json",
                             fixtures_ref=REPO_ROOT)
    assert not result.ok
    assert any("missing required" in p for p in result.problems)


def test_verify_catches_bad_sidecar(bundled):
    (bundled / "implementation" / "outputs" / "ocr.txt.sha256").write_text(
        "b" * 64 + "\n")
    result = verify_manifest(bundled / "manifest.json",
                             fixtures_ref=REPO_ROOT)
    assert not result.ok
    assert any("ocr.txt" in p for p in result.problems)


def test_verify_catches_sidecar_of_sidecar(bundled):
    (bundled / "implementation" / "outputs"
     / "ocr.txt.sha256.sha256").write_text("c" * 64 + "\n")
    result = verify_manifest(bundled / "manifest.json",
                             fixtures_ref=REPO_ROOT)
    assert any("sidecar-of-sidecar" in p for p in result.problems)


def test_verify_catches_schema_violation(bundled):
    doc = jsonio.load(bundled / "manifest.json")
    doc["subject"] = "both"
    jsonio.dump(bundled / "manifest.json", doc)
    result = verify_manifest(bundled / "manifest.json",
                             fixtures_ref=REPO_ROOT)
    assert not result.ok
    assert any("subject" in p for p in result.problems)


def test_verify_catches_run_id_dirname_mismatch(bundled):
    doc = jsonio.load(bundled / "manifest.json")
    doc["run_id"] = "20260922T103000Z-S004-other"
    jsonio.dump(bundled / "manifest.json", doc)
    result = verify_manifest(bundled / "manifest.json",
                             fixtures_ref=REPO_ROOT)
    assert any("run dir name" in p for p in result.problems)


def test_verify_catches_bad_fixture(bundled):
    doc = jsonio.load(bundled / "manifest.json")
    doc["fixtures"] = [{"id": "clean-a4", "sha256": "f" * 64}]
    jsonio.dump(bundled / "manifest.json", doc)
    result = verify_manifest(bundled / "manifest.json",
                             fixtures_ref=REPO_ROOT)
    assert any("does not match" in p for p in result.problems)


def test_verify_fails_closed_when_corpus_unlocatable(bundled):
    result = verify_manifest(bundled / "manifest.json")
    assert any("fixture corpus not locatable" in p
               for p in result.problems)


def test_verify_missing_manifest(tmp_path):
    result = verify_manifest(tmp_path / "nope.json")
    assert not result.ok
    assert any("not found" in p for p in result.problems)


def test_verify_unreadable_manifest(tmp_path):
    bad = tmp_path / "manifest.json"
    bad.write_text("{nope", encoding="utf-8")
    result = verify_manifest(bad)
    assert any("unreadable" in p for p in result.problems)


def test_verify_no_creds_is_a_note_not_a_failure(bundled):
    doc = jsonio.load(bundled / "manifest.json")
    doc["artifacts"][0]["r2_key"] = (
        f"runs/{doc['run_id']}/implementation/logs/app-events.json")
    jsonio.dump(bundled / "manifest.json", doc)
    result = verify_manifest(bundled / "manifest.json",
                             fixtures_ref=REPO_ROOT)
    assert result.ok  # offline verification stands on its own
    assert not result.remote_checked
    row = next(r for r in result.rows
               if r.path == "logs/app-events.json")
    assert row.r2 == R2_NO_CREDS
    assert all(r.r2 in (R2_NO_CREDS, R2_NOT_UPLOADED) for r in result.rows)


# ----------------------------------------------------------------- R2

@mock_aws
def test_verify_remote_size_checks(bundled, monkeypatch, tmp_path):
    import tools.evidence_cli.upload as upload_mod

    for key, value in MOTO_ENV.items():
        monkeypatch.setenv(key, value)
    client = boto3.client("s3", region_name="us-east-1")
    client.create_bucket(Bucket=MOTO_ENV["R2_BUCKET"])

    upload_mod.upload_run(bundled, fixtures_ref=REPO_ROOT)
    result = verify_manifest(bundled / "manifest.json",
                             fixtures_ref=REPO_ROOT)
    assert result.ok, result.problems
    assert result.remote_checked
    assert all(r.r2 == R2_OK for r in result.rows)

    # break one object: replace bytes with a different size
    doc = jsonio.load(bundled / "manifest.json")
    victim = doc["artifacts"][0]
    client.put_object(Bucket=MOTO_ENV["R2_BUCKET"], Key=victim["r2_key"],
                      Body=b"wrong-size")
    result = verify_manifest(bundled / "manifest.json",
                             fixtures_ref=REPO_ROOT)
    assert not result.ok
    assert any("R2 size mismatch" in p for p in result.problems)
    row = next(r for r in result.rows if r.path == victim["path"])
    assert row.r2 == "size-mismatch"

    # delete one object: missing-object
    client.delete_object(Bucket=MOTO_ENV["R2_BUCKET"],
                         Key=doc["artifacts"][1]["r2_key"])
    result = verify_manifest(bundled / "manifest.json",
                             fixtures_ref=REPO_ROOT)
    assert not result.ok
    assert any("R2 object missing" in p for p in result.problems)
    row = next(r for r in result.rows
               if r.path == doc["artifacts"][1]["path"])
    assert row.r2 == R2_MISSING
