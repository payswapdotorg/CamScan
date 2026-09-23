"""Real-R2 round-trip (opt-in).

Deselected by default (pytest.ini ``-m "not integration"``); runs only
when all four credential env vars are present AND the marker is
selected explicitly: ``python3 -m pytest tools/evidence-cli -m
integration``. Uses a distinctive 2099 run-id so it never collides with
lab runs.
"""
from __future__ import annotations

import pytest
from helpers import REPO_ROOT, build_run_tree
from tools.evidence_cli.bundle import bundle_run
from tools.evidence_cli.store import R2Store
from tools.evidence_cli.upload import upload_run
from tools.evidence_cli.verify import verify_manifest

pytestmark = pytest.mark.integration

_RUN_ID = "20990101T000000Z-S004-camscan006itest"


@pytest.mark.skipif(not R2Store.creds_present(),
                    reason="no R2 credentials in the environment")
def test_real_r2_bundle_upload_verify_roundtrip(tmp_path, monkeypatch):
    monkeypatch.delenv("CAMSCAN_FIXTURES_DIR", raising=False)
    run = build_run_tree(tmp_path, run_id=_RUN_ID, subject="implementation")
    assert bundle_run(run, fixtures_ref=REPO_ROOT).ok

    upload = upload_run(run, fixtures_ref=REPO_ROOT)
    assert upload.ok, upload.problems
    assert all(o["verified"] for o in upload.objects)

    result = verify_manifest(run / "manifest.json", fixtures_ref=REPO_ROOT)
    assert result.ok, result.problems
    assert result.remote_checked
    assert all(row.r2 == "size-ok" for row in result.rows)
