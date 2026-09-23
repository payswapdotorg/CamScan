"""bundle: layout validation, repairs, determinism, --check gate."""
from __future__ import annotations

import json

import pytest
from helpers import REPO_ROOT, build_run_tree, tiny_png, valid_metadata
from tools.evidence_cli import jsonio
from tools.evidence_cli.bundle import bundle_run


@pytest.fixture(autouse=True)
def _no_env_corpus(monkeypatch):
    monkeypatch.delenv("CAMSCAN_FIXTURES_DIR", raising=False)


def test_bundle_creates_valid_deterministic_manifest(tmp_path):
    run = build_run_tree(tmp_path)
    result = bundle_run(run, fixtures_ref=REPO_ROOT)
    assert result.ok, result.problems
    assert result.wrote_manifest

    text = (run / "manifest.json").read_text(encoding="utf-8")
    assert text.endswith("}\n")
    doc = json.loads(text)
    assert doc["run_id"] == run.name
    assert doc["subject"] == "implementation"
    assert len(doc["artifacts"]) == 11
    assert [a["path"] for a in doc["artifacts"]] == sorted(
        a["path"] for a in doc["artifacts"])
    # determinism: rebuilding produces identical bytes
    first = (run / "manifest.json").read_bytes()
    bundle_run(run, fixtures_ref=REPO_ROOT)
    assert (run / "manifest.json").read_bytes() == first


def test_bundle_writes_required_output_sidecars(tmp_path):
    run = build_run_tree(tmp_path)
    result = bundle_run(run, fixtures_ref=REPO_ROOT)
    assert result.ok
    sub = run / "implementation" / "outputs"
    doc = jsonio.load(run / "manifest.json")
    by_path = {a["path"]: a for a in doc["artifacts"]}
    for name in ("document.pdf", "page-01.jpg", "ocr.txt"):
        sidecar = sub / f"{name}.sha256"
        assert sidecar.is_file(), name
        expected = (f"{by_path[f'outputs/{name}']['sha256']}  {name}\n")
        assert sidecar.read_text(encoding="utf-8") == expected
        # sha256sum-compatible
        cd = sidecar.parent
        import subprocess
        proc = subprocess.run(["sha256sum", "-c", sidecar.name],
                              cwd=cd, capture_output=True, text=True,
                              check=False)
        assert proc.returncode == 0, proc.stdout + proc.stderr
    assert any("ocr.txt.sha256" in r for r in result.repairs)


def test_bundle_repairs_wrong_manifest_hash(tmp_path):
    run = build_run_tree(tmp_path)
    assert bundle_run(run, fixtures_ref=REPO_ROOT).ok
    doc = jsonio.load(run / "manifest.json")
    doc["artifacts"][0]["sha256"] = "f" * 64
    jsonio.dump(run / "manifest.json", doc)

    result = bundle_run(run, fixtures_ref=REPO_ROOT)
    assert result.ok
    assert any(".sha256:" in r and "→" in r for r in result.repairs)
    fixed = jsonio.load(run / "manifest.json")
    assert fixed["artifacts"][0]["sha256"] != "f" * 64


def test_bundle_drops_stale_r2_key(tmp_path):
    run = build_run_tree(tmp_path)
    assert bundle_run(run, fixtures_ref=REPO_ROOT).ok
    doc = jsonio.load(run / "manifest.json")
    key = (f"runs/{doc['run_id']}/implementation/"
           f"{doc['artifacts'][0]['path']}")
    doc["artifacts"][0]["r2_key"] = key
    jsonio.dump(run / "manifest.json", doc)

    # content changes after upload → stale r2_key must drop
    (run / "implementation" / doc["artifacts"][0]["path"]).write_bytes(
        tiny_png("retake"))
    result = bundle_run(run, fixtures_ref=REPO_ROOT)
    assert result.ok
    assert any("r2_key: dropped" in r for r in result.repairs)
    after = jsonio.load(run / "manifest.json")
    assert "r2_key" not in after["artifacts"][0]


def test_bundle_keeps_fresh_r2_key(tmp_path):
    run = build_run_tree(tmp_path)
    assert bundle_run(run, fixtures_ref=REPO_ROOT).ok
    doc = jsonio.load(run / "manifest.json")
    path = doc["artifacts"][0]["path"]
    key = f"runs/{doc['run_id']}/implementation/{path}"
    doc["artifacts"][0]["r2_key"] = key
    jsonio.dump(run / "manifest.json", doc)

    result = bundle_run(run, fixtures_ref=REPO_ROOT)
    assert result.ok
    after = jsonio.load(run / "manifest.json")
    assert after["artifacts"][0]["r2_key"] == key


def test_bundle_rejects_both_subject_dirs(tmp_path):
    run = build_run_tree(tmp_path, subject="implementation")
    build_run_tree(run.parent, run_id=run.name, subject="reference",
                   with_scenario=False, with_metadata=False)
    result = bundle_run(run, fixtures_ref=REPO_ROOT)
    assert not result.ok
    assert any("both reference/ and implementation/" in p
               for p in result.problems)


def test_bundle_rejects_missing_subject_dir(tmp_path):
    import shutil
    run = build_run_tree(tmp_path)
    shutil.rmtree(run / "implementation")
    result = bundle_run(run, fixtures_ref=REPO_ROOT)
    assert not result.ok
    assert any("no subject dir" in p for p in result.problems)


def test_bundle_rejects_unknown_root_entry(tmp_path):
    run = build_run_tree(tmp_path)
    (run / "notes.txt").write_text("stray")
    result = bundle_run(run, fixtures_ref=REPO_ROOT)
    assert any("unexpected root entry" in p for p in result.problems)


def test_bundle_rejects_run_id_dirname_mismatch(tmp_path):
    run = build_run_tree(tmp_path, run_id="20260922T103000Z-S004-a")
    meta = valid_metadata("20260922T103000Z-S004-b")
    (run / "run-metadata.json").write_text(jsonio.dumps_deterministic(meta))
    result = bundle_run(run, fixtures_ref=REPO_ROOT)
    assert any("run_id" in p and "run dir" in p
               for p in result.problems)


def test_bundle_rejects_subject_mismatch(tmp_path):
    run = build_run_tree(tmp_path, subject="implementation")
    meta = valid_metadata(run.name, subject="reference")
    (run / "run-metadata.json").write_text(jsonio.dumps_deterministic(meta))
    result = bundle_run(run, fixtures_ref=REPO_ROOT)
    assert any("subject" in p for p in result.problems)


def test_bundle_rejects_missing_scenario(tmp_path):
    run = build_run_tree(tmp_path, with_scenario=False)
    result = bundle_run(run, fixtures_ref=REPO_ROOT)
    assert any("scenario.yaml missing" in p for p in result.problems)


def test_bundle_scenario_id_disagreement(tmp_path):
    run = build_run_tree(tmp_path)
    meta = valid_metadata(run.name, scenario="onboarding")
    (run / "run-metadata.json").write_text(jsonio.dumps_deterministic(meta))
    result = bundle_run(run, fixtures_ref=REPO_ROOT)
    assert any("scenario.yaml declares" in p for p in result.problems)


def test_run_metadata_wins_over_manifest(tmp_path):
    run = build_run_tree(tmp_path)
    assert bundle_run(run, fixtures_ref=REPO_ROOT).ok
    doc = jsonio.load(run / "manifest.json")
    doc["device"]["locale"] = "fr-FR"
    jsonio.dump(run / "manifest.json", doc)
    # run-metadata.json still says en-US → the runner file wins
    result = bundle_run(run, fixtures_ref=REPO_ROOT)
    assert result.ok
    assert jsonio.load(run / "manifest.json")["device"]["locale"] == "en-US"


def test_check_gate_passes_when_clean(tmp_path):
    run = build_run_tree(tmp_path)
    assert bundle_run(run, fixtures_ref=REPO_ROOT).ok
    before = (run / "manifest.json").read_bytes()
    result = bundle_run(run, check=True, fixtures_ref=REPO_ROOT)
    assert result.ok, result.problems
    assert (run / "manifest.json").read_bytes() == before  # nothing written


def test_check_gate_fails_when_stale(tmp_path):
    run = build_run_tree(tmp_path)
    assert bundle_run(run, fixtures_ref=REPO_ROOT).ok
    (run / "implementation" / "outputs" / "ocr.txt").write_bytes(b"tamper")
    result = bundle_run(run, check=True, fixtures_ref=REPO_ROOT)
    assert not result.ok
    assert any("differs from the recomputed bundle" in p
               for p in result.problems)


def test_check_gate_fails_when_manifest_missing(tmp_path):
    run = build_run_tree(tmp_path)
    result = bundle_run(run, check=True, fixtures_ref=REPO_ROOT)
    assert not result.ok
    assert any("manifest.json missing" in p for p in result.problems)


def test_bundle_rejects_unknown_fixture(tmp_path):
    run = build_run_tree(tmp_path)
    meta = valid_metadata(run.name)
    meta["fixtures"] = [{"id": "nonexistent-fixture", "sha256": "a" * 64}]
    (run / "run-metadata.json").write_text(jsonio.dumps_deterministic(meta))
    result = bundle_run(run, fixtures_ref=REPO_ROOT)
    assert any("not declared" in p for p in result.problems)


def test_bundle_fails_closed_when_corpus_unlocatable(tmp_path):
    run = build_run_tree(tmp_path)
    result = bundle_run(run)  # no fixtures_ref, tmp_path has no corpus
    assert any("fixture corpus not locatable" in p
               for p in result.problems)


def test_fixtureless_run_needs_no_corpus(tmp_path):
    run = build_run_tree(tmp_path)
    meta = valid_metadata(run.name)
    meta["fixtures"] = []
    (run / "run-metadata.json").write_text(jsonio.dumps_deterministic(meta))
    result = bundle_run(run)  # no fixtures_ref, corpus unlocatable — fine
    assert result.ok, result.problems


def test_bundle_rejects_bad_metadata(tmp_path):
    run = build_run_tree(tmp_path)
    meta = valid_metadata(run.name)
    meta["device"]["screen"] = "1080x2280"
    (run / "run-metadata.json").write_text(jsonio.dumps_deterministic(meta))
    result = bundle_run(run, fixtures_ref=REPO_ROOT)
    assert any("device.screen" in p for p in result.problems)


def test_bundle_missing_run_dir(tmp_path):
    result = bundle_run(tmp_path / "nope")
    assert not result.ok
    assert any("does not exist" in p for p in result.problems)
