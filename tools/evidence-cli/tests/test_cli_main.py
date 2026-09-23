"""main: CLI argument handling, exit codes, output."""
from __future__ import annotations

import boto3
import pytest
from helpers import MOTO_ENV, REPO_ROOT, build_run_tree
from moto import mock_aws
from tools.evidence_cli import jsonio
from tools.evidence_cli import main as evidence_main


@pytest.fixture(autouse=True)
def _no_creds(monkeypatch):
    for name in ("R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY",
                 "R2_ENDPOINT", "R2_BUCKET"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.delenv("CAMSCAN_FIXTURES_DIR", raising=False)


def test_bundle_command_creates_manifest(tmp_path, capsys):
    run = build_run_tree(tmp_path)
    rc = evidence_main.main(["bundle", str(run),
                             "--fixtures", str(REPO_ROOT)])
    assert rc == 0
    out = capsys.readouterr().out
    assert "bundle ok:" in out
    assert (run / "manifest.json").is_file()
    doc = jsonio.load(run / "manifest.json")
    assert doc["run_id"] == run.name


def test_bundle_check_command(tmp_path, capsys):
    run = build_run_tree(tmp_path)
    assert evidence_main.main(["bundle", str(run),
                                "--fixtures", str(REPO_ROOT)]) == 0
    rc = evidence_main.main(["bundle", "--check", str(run),
                             "--fixtures", str(REPO_ROOT)])
    assert rc == 0
    assert "bundle check ok" in capsys.readouterr().out
    (run / "implementation" / "outputs" / "ocr.txt").write_bytes(b"x")
    rc = evidence_main.main(["bundle", "--check", str(run),
                             "--fixtures", str(REPO_ROOT)])
    assert rc == 1
    assert "re-bundle required" in capsys.readouterr().err


def test_bundle_failure_goes_to_stderr_with_nonzero(tmp_path, capsys):
    run = build_run_tree(tmp_path, with_scenario=False)
    rc = evidence_main.main(["bundle", str(run),
                             "--fixtures", str(REPO_ROOT)])
    assert rc == 1
    captured = capsys.readouterr()
    assert "bundle FAILED" in captured.err
    assert "scenario.yaml missing" in captured.err


def test_verify_command_table_and_summary(tmp_path, capsys):
    run = build_run_tree(tmp_path)
    evidence_main.main(["bundle", str(run), "--fixtures", str(REPO_ROOT)])
    rc = evidence_main.main(["verify", str(run / "manifest.json"),
                             "--fixtures", str(REPO_ROOT)])
    assert rc == 0
    out = capsys.readouterr().out
    assert "PATH" in out and "SHA256" in out  # table header
    assert "verify ok: 11 artifact(s)" in out
    assert "remote checks skipped" in out  # no creds note


def test_verify_command_failure_nonzero(tmp_path, capsys):
    run = build_run_tree(tmp_path)
    evidence_main.main(["bundle", str(run), "--fixtures", str(REPO_ROOT)])
    (run / "implementation" / "logs" / "logcat.txt").write_bytes(b"tamper")
    rc = evidence_main.main(["verify", str(run / "manifest.json"),
                             "--fixtures", str(REPO_ROOT)])
    assert rc == 1
    assert "VERIFY FAILED" in capsys.readouterr().err


def test_upload_command_without_creds(tmp_path, capsys):
    run = build_run_tree(tmp_path)
    evidence_main.main(["bundle", str(run), "--fixtures", str(REPO_ROOT)])
    rc = evidence_main.main(["upload", str(run),
                             "--fixtures", str(REPO_ROOT)])
    assert rc == 1
    assert "missing R2 credential environment variables" \
        in capsys.readouterr().err


@mock_aws
def test_upload_command_roundtrip(tmp_path, monkeypatch, capsys):
    for key, value in MOTO_ENV.items():
        monkeypatch.setenv(key, value)
    boto3.client("s3", region_name="us-east-1").create_bucket(
        Bucket=MOTO_ENV["R2_BUCKET"])
    run = build_run_tree(tmp_path)
    evidence_main.main(["bundle", str(run), "--fixtures", str(REPO_ROOT)])
    rc = evidence_main.main(["upload", str(run),
                             "--fixtures", str(REPO_ROOT)])
    assert rc == 0
    out = capsys.readouterr().out
    assert "head-verified" in out
    assert "upload ok: 11 objects + manifest" in out
    assert (run / "r2-manifest.json").is_file()
    # follow with verify incl. remote checks — must pass end-to-end
    rc = evidence_main.main(["verify", str(run / "manifest.json"),
                             "--fixtures", str(REPO_ROOT)])
    assert rc == 0
    assert "R2 sizes verified" in capsys.readouterr().out


def test_missing_args_is_usage_error():
    with pytest.raises(SystemExit) as excinfo:
        evidence_main.main([])
    assert excinfo.value.code == 2


def test_verify_nonexistent_manifest(tmp_path, capsys):
    rc = evidence_main.main(["verify", str(tmp_path / "nope.json")])
    assert rc == 1
    assert "not found" in capsys.readouterr().err
