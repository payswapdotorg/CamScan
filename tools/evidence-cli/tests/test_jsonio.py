"""jsonio: deterministic serialization + strict parsing."""
from __future__ import annotations

import pytest
from tools.evidence_cli.jsonio import (
    DuplicateKeyError,
    dump,
    dumps_deterministic,
    load,
    loads,
)


def test_dump_is_sorted_deterministic_and_newline_terminated(tmp_path):
    doc = {"zeta": 1, "alpha": {"beta": [1, 2], "aa": 0}, "mid": "x"}
    first = dumps_deterministic(doc)
    second = dumps_deterministic({"alpha": {"aa": 0, "beta": [1, 2]},
                                   "mid": "x", "zeta": 1})
    assert first == second
    assert first.endswith("}\n") and not first.endswith("\n\n")
    assert first.index('"alpha"') < first.index('"mid"') < first.index('"zeta"')
    assert '  "alpha": {' in first  # 2-space indent


def test_dump_unicode_preserved(tmp_path):
    path = tmp_path / "u.json"
    dump(path, {"note": "héllo — …"})
    assert '"note": "héllo — …"' in path.read_text(encoding="utf-8")


def test_load_rejects_duplicate_keys():
    with pytest.raises(DuplicateKeyError):
        loads('{"a": 1, "b": 2, "a": 3}')
    with pytest.raises(ValueError):
        loads('{"x": {"y": 1, "y": 2}}')


def test_load_rejects_invalid_json():
    with pytest.raises(ValueError):
        loads("{not json")


def test_roundtrip(tmp_path):
    path = tmp_path / "doc.json"
    doc = {"run_id": "r", "artifacts": [{"path": "a", "sha256": "b"}]}
    dump(path, doc)
    assert load(path) == doc


def test_dump_is_atomic_no_tmp_residue(tmp_path):
    path = tmp_path / "doc.json"
    dump(path, {"a": 1})
    dump(path, {"a": 2})
    assert sorted(p.name for p in tmp_path.iterdir()) == ["doc.json"]
