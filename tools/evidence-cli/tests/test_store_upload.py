"""store + upload: credential hygiene + moto-mocked R2 pipeline.

moto 5 intercepts boto3 at the client layer — the store's endpoint must
be AWS-shaped for the interception to engage (real R2 endpoints differ;
the store itself is endpoint-agnostic — see README).
"""
from __future__ import annotations

import boto3
import pytest
from helpers import MOTO_ENV, REPO_ROOT, build_run_tree
from moto import mock_aws
from tools.evidence_cli import jsonio
from tools.evidence_cli.bundle import bundle_run
from tools.evidence_cli.schema import EvidenceCliError
from tools.evidence_cli.store import CREDENTIAL_ENV_VARS, R2Store
from tools.evidence_cli.upload import upload_run


@pytest.fixture(autouse=True)
def _clean_creds(monkeypatch):
    for name in CREDENTIAL_ENV_VARS:
        monkeypatch.delenv(name, raising=False)
    monkeypatch.delenv("CAMSCAN_FIXTURES_DIR", raising=False)


@pytest.fixture()
def moto_env(monkeypatch, tmp_path):
    """AWS-shaped env + a fresh bucket, with moto engaged for the whole
    test (setup + body + teardown); returns the run-tree parent."""
    for key, value in MOTO_ENV.items():
        monkeypatch.setenv(key, value)
    with mock_aws():
        boto3.client("s3", region_name="us-east-1").create_bucket(
            Bucket=MOTO_ENV["R2_BUCKET"])
        yield tmp_path


def _bundled_run(base, **kwargs):
    run = build_run_tree(base, **kwargs)
    result = bundle_run(run, fixtures_ref=REPO_ROOT)
    assert result.ok, result.problems
    return run


def _s3():
    return boto3.client("s3", region_name="us-east-1")


# ------------------------------------------------------------ credential

def test_from_env_names_missing_vars_without_values(monkeypatch):
    monkeypatch.setenv("R2_ENDPOINT", "https://x.example")
    with pytest.raises(EvidenceCliError) as excinfo:
        R2Store.from_env()
    message = str(excinfo.value)
    for name in ("R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY", "R2_BUCKET"):
        assert name in message
    assert "https://x.example" not in message  # values never surface


def test_creds_present_requires_all_four(monkeypatch):
    assert not R2Store.creds_present()
    monkeypatch.setenv("R2_ACCESS_KEY_ID", "a")
    monkeypatch.setenv("R2_BUCKET", "b")
    assert not R2Store.creds_present()  # endpoint+secret still missing
    monkeypatch.setenv("R2_ENDPOINT", "https://x")
    monkeypatch.setenv("R2_SECRET_ACCESS_KEY", "s")
    assert R2Store.creds_present()


def test_repr_and_sanitize_never_leak_secrets():
    store = R2Store("keyid-123", "very-secret-value", "https://x", "bkt")
    assert "very-secret-value" not in repr(store)
    assert "keyid-123" not in repr(store)
    assert "***REDACTED***" in repr(store)
    assert store.sanitize("boom keyid-123 very-secret-value boom") == \
        "boom ***REDACTED*** ***REDACTED*** boom"


def test_error_text_is_sanitized():
    store = R2Store("keyid-123", "very-secret-value",
                    "https://no-such-host.invalid", "bkt")
    with pytest.raises(EvidenceCliError) as excinfo:
        store.put_bytes("k", b"x")
    message = str(excinfo.value)
    assert "very-secret-value" not in message
    assert "keyid-123" not in message


# ----------------------------------------------------------- moto upload

def test_upload_roundtrip_heads_and_records(moto_env):
    run = _bundled_run(moto_env)
    result = upload_run(run, fixtures_ref=REPO_ROOT)
    assert result.ok, result.problems
    assert len(result.objects) == 11

    client = _s3()
    doc = jsonio.load(run / "manifest.json")
    for entry in doc["artifacts"]:
        expected_key = (f"runs/{doc['run_id']}/{doc['subject']}/"
                        f"{entry['path']}")
        assert entry["r2_key"] == expected_key
        head = client.head_object(Bucket=MOTO_ENV["R2_BUCKET"],
                                  Key=expected_key)
        assert head["ContentLength"] == entry["bytes"]
    assert result.manifest_key == (
        f"runs/{doc['run_id']}/{doc['subject']}/manifest.json")

    # r2-manifest: keys + sha256 + bytes + verified, NO timestamps
    record = jsonio.load(run / "r2-manifest.json")
    assert record["run_id"] == doc["run_id"]
    assert record["bucket"] == MOTO_ENV["R2_BUCKET"]
    assert [o["key"] for o in record["objects"]] == sorted(
        o["key"] for o in record["objects"])
    assert all(o["verified"] is True for o in record["objects"])
    assert not any("time" in k.lower() or "date" in k.lower()
                   for k in record)
    assert record["manifest_bytes"] == (run / "manifest.json").stat().st_size


def test_upload_is_idempotent_and_deterministic(moto_env):
    run = _bundled_run(moto_env)
    first = upload_run(run, fixtures_ref=REPO_ROOT)
    record_before = (run / "r2-manifest.json").read_bytes()
    manifest_before = (run / "manifest.json").read_bytes()
    second = upload_run(run, fixtures_ref=REPO_ROOT)
    assert second.ok
    assert (run / "r2-manifest.json").read_bytes() == record_before
    assert (run / "manifest.json").read_bytes() == manifest_before
    assert [o["key"] for o in second.objects] == [
        o["key"] for o in first.objects]


def test_upload_refuses_stale_bundle(moto_env):
    run = _bundled_run(moto_env)
    (run / "implementation" / "outputs" / "ocr.txt").write_bytes(b"tamper")
    with pytest.raises(EvidenceCliError, match="precheck"):
        upload_run(run, fixtures_ref=REPO_ROOT)
    assert not (run / "r2-manifest.json").exists()


def test_upload_aborts_when_head_disagrees(moto_env, monkeypatch):
    run = _bundled_run(moto_env)
    store = R2Store.from_env()
    monkeypatch.setattr(R2Store, "head",
                        lambda self, key: {"exists": True, "size": 1})
    with pytest.raises(EvidenceCliError, match="R2 verification failed"):
        upload_run(run, fixtures_ref=REPO_ROOT, store=store)
    assert not (run / "r2-manifest.json").exists()


def test_head_reports_missing_object_cleanly(moto_env):
    store = R2Store.from_env()
    assert store.head("runs/nope/nope/none.bin") == {
        "exists": False, "size": None}
