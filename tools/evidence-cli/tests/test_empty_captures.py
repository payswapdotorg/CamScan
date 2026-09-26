"""CAMSCAN-010J (part B) — the empty-capture policy at bundle time.

Provenance — the live S002 round 2026-09-26 09:17:47 UTC: the run
completed its scenario work, and the evidence write died on

    evidence-cli bundle failed for 20260926T091747Z-S002-live/
    reference: artifacts[0].bytes must be a positive integer, got 0

A legitimately-empty capture (a blank frame / an empty buffer written
under strain — e.g. the reference driver's unconditional
logs/logcat.txt write when the capture came back empty) KILLED the
whole evidence write. The class is what matters: ANY 0-byte file in
the subject tree must never destroy the run.

The 010J policy (one policy, two readers — bundle records the
exclusion, verify must not flag a recorded empty):

- a size==0 file is EXCLUDED from ``artifacts[]`` (whose
  positive-bytes contract is unchanged for REAL artifacts) and
  recorded under the manifest's optional ``empty_captures`` key as
  ``[{"path": ..., "bytes": 0}]`` — presence recorded, never fatal;
- the bundle LOG names each excluded empty capture (the operator sees
  the gap at bundle time);
- hash/sidecar machinery untouched for non-empty artifacts;
- a tree with only positive-size files → ``empty_captures`` absent
  (schema-valid both ways);
- verify applies the same partition: a manifest-recorded empty is
  fine; a disk empty the manifest does NOT record is flagged.

Hermetic: tmp_path trees + moto only where upload is exercised (it is
not, here) — no network, no credentials.
"""
from __future__ import annotations

from helpers import REPO_ROOT, build_run_tree
from tools.evidence_cli import main as evidence_main
from tools.evidence_cli.bundle import bundle_run
from tools.evidence_cli.jsonio import load as jsonio_load
from tools.evidence_cli.schema import validate_manifest
from tools.evidence_cli.verify import verify_manifest


def _make_tree_with_empty_logcat(tmp_path, run_id="20260926T091747Z"
                                                "-S002-unit01"):
    """The live shape: a valid runner tree whose logcat capture came
    back empty (the suspected 0-byte writer — the logcat write is
    unconditional in the reference driver, unlike the guarded
    screenshot/ui-dump captures). build_run_tree's default metadata
    already matches (scenario single-document-capture, the S004
    yaml it copies)."""
    run = build_run_tree(tmp_path, run_id=run_id, subject="reference")
    (run / "reference" / "logs" / "logcat.txt").write_bytes(b"")
    return run


def test_bundle_succeeds_with_zero_byte_file_and_records_it(tmp_path):
    """TEST 3 (the core policy): a subject tree containing one 0-byte
    file + normal files → the bundle SUCCEEDS; the 0-byte file is
    absent from artifacts[], present under empty_captures in the
    manifest (path + bytes 0); the manifest stays schema-valid; the
    non-empty artifacts keep their hash/sidecar machinery untouched
    (the outputs sidecars are still required and written)."""
    run = _make_tree_with_empty_logcat(tmp_path)
    result = bundle_run(run, fixtures_ref=REPO_ROOT)
    assert result.ok, result.problems
    # excluded from artifacts[] …
    assert "logs/logcat.txt" not in [a.path for a in result.artifacts]
    assert result.empty_captures and \
        [a.path for a in result.empty_captures] == ["logs/logcat.txt"]
    # … recorded under empty_captures in the manifest
    doc = jsonio_load(run / "manifest.json")
    assert doc["empty_captures"] == [{"path": "logs/logcat.txt",
                                      "bytes": 0}]
    assert validate_manifest(doc) == []
    # the OTHER log (app-events.json) stayed a real artifact
    assert "logs/app-events.json" in [a.path for a in result.artifacts]
    # the outputs sidecar machinery untouched (required + written)
    assert any(p.startswith("outputs/") for p in result.wrote_sidecars)


def test_bundle_log_names_excluded_empty_capture(tmp_path, capsys):
    """TEST 3 (the log line): the bundle log names each excluded empty
    capture — the operator sees the gap at bundle time."""
    run = _make_tree_with_empty_logcat(tmp_path)
    rc = evidence_main.main(["bundle", str(run),
                             "--fixtures", str(REPO_ROOT)])
    assert rc == 0
    out = capsys.readouterr().out
    assert "empty capture: logs/logcat.txt (0 bytes)" in out
    assert "excluded from artifacts[], recorded in manifest " \
        "empty_captures" in out
    assert "1 empty capture(s) excluded" in out


def test_tree_without_empties_omits_empty_captures(tmp_path):
    """TEST 3 (the clean side): a tree with only positive-size files
    → empty_captures ABSENT from the manifest (schema-valid both
    ways), and the bundle log carries no empty-capture line."""
    run = build_run_tree(tmp_path)
    result = bundle_run(run, fixtures_ref=REPO_ROOT)
    assert result.ok, result.problems
    assert result.empty_captures == []
    doc = jsonio_load(run / "manifest.json")
    assert "empty_captures" not in doc
    assert validate_manifest(doc) == []


def test_rebundle_and_check_stable_with_empty_captures(tmp_path):
    """Determinism: a re-bundle (and the upload precheck --check) is
    STABLE on a tree with an empty capture — the manifest with
    empty_captures is byte-reproducible from the walk, and the stale
    value from an old manifest never leaks (the key is disk-derived,
    like artifacts)."""
    run = _make_tree_with_empty_logcat(tmp_path)
    first = bundle_run(run, fixtures_ref=REPO_ROOT)
    assert first.ok, first.problems
    first_bytes = (run / "manifest.json").read_bytes()
    # a SECOND bundle over the same tree reproduces the same manifest
    second = bundle_run(run, fixtures_ref=REPO_ROOT)
    assert second.ok, second.problems
    assert (run / "manifest.json").read_bytes() == first_bytes
    # the --check gate (upload's precheck) passes on the clean bundle
    check = bundle_run(run, check=True, fixtures_ref=REPO_ROOT)
    assert check.ok, check.problems
    # healing direction: the empty capture FILLS (non-empty now) →
    # re-bundle drops the record and reports the repair
    (run / "reference" / "logs" / "logcat.txt").write_bytes(
        b"09-26 10:17:50.000 I/CamScan( 9254): recovered\n")
    healed = bundle_run(run, fixtures_ref=REPO_ROOT)
    assert healed.ok, healed.problems
    doc = jsonio_load(run / "manifest.json")
    assert "empty_captures" not in doc
    assert "logs/logcat.txt" in [a.path for a in healed.artifacts]
    assert any("empty_captures[logs/logcat.txt]: removed"
               in line for line in healed.repairs)


def test_verify_passes_with_recorded_empty_captures(tmp_path):
    """Coherence (verify, the second reader): a bundle whose manifest
    records the empty capture verifies CLEAN — the excluded 0-byte
    file is neither an unmanifested artifact nor a hash problem; the
    real artifacts verify as before."""
    run = _make_tree_with_empty_logcat(tmp_path)
    result = bundle_run(run, fixtures_ref=REPO_ROOT)
    assert result.ok, result.problems
    verdict = verify_manifest(run / "manifest.json",
                              fixtures_ref=REPO_ROOT, check_remote=False)
    assert verdict.ok, verdict.problems
    assert not any("logcat" in problem for problem in verdict.problems)


def test_verify_flags_unrecorded_disk_empty(tmp_path):
    """Coherence (the strict side): a 0-byte file on disk the manifest
    does NOT record under empty_captures is an unmanifested gap —
    flagged by name (a post-bundle tamper that emptied an artifact is
    seen for what it is)."""
    run = build_run_tree(tmp_path)
    result = bundle_run(run, fixtures_ref=REPO_ROOT)
    assert result.ok, result.problems
    assert result.empty_captures == []
    # post-bundle tamper: empty an existing artifact on disk
    (run / "implementation" / "ui" / "01-launch.xml").write_bytes(b"")
    verdict = verify_manifest(run / "manifest.json",
                              fixtures_ref=REPO_ROOT, check_remote=False)
    assert not verdict.ok
    assert any("on-disk empty capture not in manifest empty_captures: "
               "ui/01-launch.xml" in problem
               for problem in verdict.problems)


def test_all_empty_tree_still_fails_honestly(tmp_path):
    """The boundary: a subject tree whose EVERY file is 0-byte has no
    real evidence at all — the pre-existing 'no artifacts' failure
    stands (a run with no artifacts is not evidence; the empty-capture
    policy records gaps, it never fabricates evidence)."""
    run = build_run_tree(tmp_path, run_id="20260926T091747Z-S002-unit02")
    sub = run / "implementation"
    for path in sorted(sub.rglob("*")):
        if path.is_file() and not path.name.endswith(".sha256"):
            path.write_bytes(b"")
    result = bundle_run(run, fixtures_ref=REPO_ROOT)
    assert not result.ok
    assert any("no artifacts under implementation/" in problem
               for problem in result.problems)


# ------------------------------------------------------ schema unit shapes

def _valid_manifest_with_empty_captures() -> dict:
    from helpers import valid_metadata

    doc = valid_metadata("20260922T103000Z-S004-unit01")
    doc["artifacts"] = [{
        "path": "screenshots/01-launch.png",
        "sha256": "a" * 64, "bytes": 100,
    }]
    doc["empty_captures"] = [{"path": "logs/logcat.txt", "bytes": 0}]
    return doc


def test_schema_accepts_optional_empty_captures():
    """The schema: a manifest carrying a well-formed empty_captures
    list validates clean (presence recorded, never fatal); the same
    manifest WITHOUT the key validates too (optional both ways)."""
    doc = _valid_manifest_with_empty_captures()
    assert validate_manifest(doc) == []
    del doc["empty_captures"]
    assert validate_manifest(doc) == []


def test_schema_empty_captures_entry_rules():
    """The schema's entry contract: bytes must be EXACTLY the integer
    0 (a recorded gap — never a positive-bytes artifact smuggled into
    the record, never a non-integer); the path follows the artifact
    path rules; duplicates and artifact-collisions are rejected; a
    non-list value is rejected."""
    doc = _valid_manifest_with_empty_captures()
    doc["empty_captures"][0]["bytes"] = 7
    assert any("must be exactly 0" in p for p in validate_manifest(doc))
    doc["empty_captures"][0]["bytes"] = False
    assert any("must be exactly 0" in p for p in validate_manifest(doc))
    doc["empty_captures"][0] = {"path": "logs/logcat.txt", "bytes": 0,
                                "sha256": "a" * 64}
    assert any("empty_captures[0]: unknown keys"
               in p for p in validate_manifest(doc))
    doc["empty_captures"][0] = {"path": "screenshots/01-launch.png",
                                "bytes": 0}
    assert any("already listed as an artifact"
               in p for p in validate_manifest(doc))
    doc["empty_captures"] = [{"path": "logs/logcat.txt", "bytes": 0},
                             {"path": "logs/logcat.txt", "bytes": 0}]
    assert any("duplicate empty-capture path"
               in p for p in validate_manifest(doc))
    doc["empty_captures"] = "none"
    assert any("empty_captures: expected a list"
               in p for p in validate_manifest(doc))
