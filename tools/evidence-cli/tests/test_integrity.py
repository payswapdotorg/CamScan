"""integrity: subject walk, sidecar rules, check table."""
from __future__ import annotations

from pathlib import Path

from helpers import build_run_tree
from tools.evidence_cli.integrity import (
    CheckRow,
    canonical_sidecar,
    check_sidecars,
    format_table,
    hash_artifacts,
    parse_sidecar,
    walk_subject,
)


def _hashed_walk(subject_dir: Path):
    artifacts, sidecars, problems = walk_subject(subject_dir)
    hash_artifacts(artifacts)
    return artifacts, sidecars, problems


def test_walk_valid_tree(tmp_path):
    run = build_run_tree(tmp_path)
    artifacts, sidecars, problems = _hashed_walk(run / "implementation")
    assert problems == []
    paths = [a.path for a in artifacts]
    assert paths == sorted(paths)
    assert "screenshots/01-launch.png" in paths
    assert "logs/logcat.txt" in paths
    assert "outputs/ocr.txt" in paths
    assert sidecars == []  # build_run_tree writes no sidecars


def test_walk_rejects_unknown_category_dir(tmp_path):
    run = build_run_tree(tmp_path)
    (run / "implementation" / "extras").mkdir()
    _, _, problems = walk_subject(run / "implementation")
    assert any("unknown category dir" in p for p in problems)


def test_walk_rejects_subject_root_file(tmp_path):
    run = build_run_tree(tmp_path)
    (run / "implementation" / "README.txt").write_text("stray")
    _, _, problems = walk_subject(run / "implementation")
    assert any("subject root" in p for p in problems)


def test_walk_rejects_nested_subdir(tmp_path):
    run = build_run_tree(tmp_path)
    (run / "implementation" / "screenshots" / "sub").mkdir()
    _, _, problems = walk_subject(run / "implementation")
    assert any("subdirectory" in p for p in problems)


def test_walk_rejects_empty_category_dir(tmp_path):
    run = build_run_tree(tmp_path)
    (run / "implementation" / "recordings" / "screen.mp4").unlink()
    _, _, problems = walk_subject(run / "implementation")
    assert any("empty category dir recordings/" in p for p in problems)


def test_walk_rejects_bad_extension_and_log_name(tmp_path):
    run = build_run_tree(tmp_path)
    sub = run / "implementation"
    (sub / "screenshots" / "shot.jpg").write_bytes(b"x")
    (sub / "logs" / "other.txt").write_bytes(b"x")
    _, _, problems = walk_subject(sub)
    assert any("shot.jpg" in p and "screenshots" in p for p in problems)
    assert any("other.txt" in p and "logs" in p for p in problems)


def test_walk_missing_subject_dir(tmp_path):
    _, _, problems = walk_subject(tmp_path / "reference")
    assert problems and "does not exist" in problems[0]


def test_parse_sidecar_forms():
    good = "a" * 64
    assert parse_sidecar(good) == good
    assert parse_sidecar(f"{good}\n") == good
    assert parse_sidecar(f"{good}  doc.pdf\n") == good  # sha256sum form
    assert parse_sidecar("A" * 64) is None       # uppercase rejected
    assert parse_sidecar("deadbeef") is None
    assert parse_sidecar("") is None
    assert parse_sidecar(f"{good} doc.pdf\n") is None  # single space


def test_canonical_sidecar_is_sha256sum_compatible():
    assert canonical_sidecar("a" * 64, "outputs/doc.pdf") == f"{'a'*64}  doc.pdf\n"


def test_check_sidecars_rules(tmp_path):
    run = build_run_tree(tmp_path)
    sub = run / "implementation"
    artifacts, sidecars, problems = _hashed_walk(sub)
    assert problems == []

    # outputs artifacts require sidecars
    assert any("missing required" in p
               for p in check_sidecars(artifacts, sidecars))

    # write a correct sidecar for one output, a wrong one for another
    good = next(a for a in artifacts if a.path == "outputs/ocr.txt")
    (sub / "outputs" / "ocr.txt.sha256").write_text(
        canonical_sidecar(good.sha256, good.path))
    (sub / "outputs" / "document.pdf.sha256").write_text("b" * 64 + "\n")
    artifacts, sidecars, problems = _hashed_walk(sub)
    problems = check_sidecars(artifacts, sidecars, require_outputs=False)
    assert any("hash mismatch" in p for p in problems)

    # sidecar covering a non-artifact
    (sub / "screenshots" / "ghost.png.sha256").write_text("c" * 64 + "\n")
    # sidecar-of-sidecar
    (sub / "outputs" / "ocr.txt.sha256.sha256").write_text("d" * 64 + "\n")
    artifacts, sidecars, walk_problems = _hashed_walk(sub)
    problems = walk_problems + check_sidecars(artifacts, sidecars,
                                                require_outputs=False)
    assert any("ghost" in p and "non-artifact" in p for p in problems)
    assert any("sidecar-of-sidecar" in p for p in problems)


def test_format_table_alignment():
    rows = [
        CheckRow(path="screenshots/01-launch.png", bytes=1024,
                 sha256="ok", sidecar="-", r2="not-uploaded"),
        CheckRow(path="outputs/document.pdf", bytes=123456,
                 sha256="ok", sidecar="ok", r2="size-ok"),
    ]
    table = format_table(rows)
    lines = table.splitlines()
    assert lines[0].split() == ["PATH", "BYTES", "SHA256", "SIDECAR", "R2"]
    assert len({len(line) for line in lines}) == 1
    assert "123456" in lines[2]
