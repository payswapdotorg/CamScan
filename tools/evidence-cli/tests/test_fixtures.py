"""fixtures: corpus location (fail-closed) + whitelist checks."""
from __future__ import annotations

import pytest
from helpers import CORPUS_MANIFEST, REPO_ROOT, corpus_fixture
from tools.evidence_cli.fixtures import FixtureCorpus
from tools.evidence_cli.schema import EvidenceCliError


@pytest.fixture(autouse=True)
def _no_env_corpus(monkeypatch):
    monkeypatch.delenv("CAMSCAN_FIXTURES_DIR", raising=False)


def test_locate_via_explicit_ref():
    corpus = FixtureCorpus.locate("/nonexistent", REPO_ROOT)
    assert corpus.path == CORPUS_MANIFEST


def test_locate_via_explicit_manifest_file():
    corpus = FixtureCorpus.locate("/nonexistent", CORPUS_MANIFEST)
    assert corpus.path == CORPUS_MANIFEST


def test_locate_via_env(monkeypatch, tmp_path):
    monkeypatch.setenv("CAMSCAN_FIXTURES_DIR", str(REPO_ROOT))
    corpus = FixtureCorpus.locate(tmp_path)
    assert corpus.path == CORPUS_MANIFEST


def test_locate_walk_up_from_repo_run_dir():
    # tmp dir *inside* the repo — the walk-up finds lab/fixtures/.
    run_dir = REPO_ROOT / "runs" / ".pytest-fixture-locate-probe"
    corpus = FixtureCorpus.locate(run_dir)
    assert corpus.path == REPO_ROOT / "lab" / "fixtures" / "manifest.json"


def test_locate_fail_closed(tmp_path):
    with pytest.raises(EvidenceCliError, match="fixture corpus not locatable"):
        FixtureCorpus.locate(tmp_path)


def test_check_accepts_corpus_fixture():
    corpus = FixtureCorpus.locate("/nonexistent", REPO_ROOT)
    assert corpus.check([corpus_fixture("clean-a4")]) == []


def test_check_rejects_unknown_id():
    corpus = FixtureCorpus.locate("/nonexistent", REPO_ROOT)
    problems = corpus.check([{"id": "no-such-fixture", "sha256": "a" * 64}])
    assert any("not declared" in p for p in problems)


def test_check_rejects_wrong_hash():
    corpus = FixtureCorpus.locate("/nonexistent", REPO_ROOT)
    entry = {"id": "clean-a4", "sha256": "f" * 64}
    assert any("does not match" in p for p in corpus.check([entry]))


def test_check_accepts_any_member_file_of_multi_file_fixture():
    corpus = FixtureCorpus.locate("/nonexistent", REPO_ROOT)
    multi = next(e for e in corpus.raw["documents"]
                 if e["id"] == "multi-page")
    for member in multi["files"]:
        assert corpus.check(
            [{"id": "multi-page", "sha256": member["sha256"]}]) == []


def test_sequences_are_whitelisted_too():
    corpus = FixtureCorpus.locate("/nonexistent", REPO_ROOT)
    seq = corpus.raw["sequences"][0]
    entry = {"id": seq["id"], "sha256": seq["frames"][0]["sha256"]}
    assert corpus.check([entry]) == []


def test_malformed_corpus_fails_closed(tmp_path):
    bad = tmp_path / "manifest.json"
    bad.write_text('{"documents": "not-a-list"}', encoding="utf-8")
    with pytest.raises(EvidenceCliError):
        FixtureCorpus(bad)
