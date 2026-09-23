"""Deterministic JSON I/O for evidence manifests (stdlib only).

Determinism contract (CAMSCAN-006 / lab/evidence/EVIDENCE.md):

- ``dump`` serializes with ``sort_keys=True``, ``indent=2``,
  ``ensure_ascii=False`` and exactly one trailing newline — the same
  document always produces byte-identical output, regardless of platform,
  locale or insertion order.
- ``load`` is strict: duplicate object keys are rejected (fail-closed —
  a silently-overwritten manifest key is an integrity problem, not a
  convenience).
- UTF-8 everywhere, ``"\\n"`` newlines.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any


class DuplicateKeyError(ValueError):
    """A JSON document reused an object key (rejected, fail-closed)."""


def _no_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    obj: dict[str, Any] = {}
    for key, value in pairs:
        if key in obj:
            raise DuplicateKeyError(f"duplicate JSON object key: {key!r}")
        obj[key] = value
    return obj


def loads(text: str) -> Any:
    """Parse strict JSON, rejecting duplicate object keys."""
    return json.loads(text, object_pairs_hook=_no_duplicate_keys)


def load(path: Path) -> Any:
    """Read + parse a JSON file (UTF-8, duplicate keys rejected)."""
    return loads(Path(path).read_text(encoding="utf-8"))


def dumps_deterministic(obj: Any) -> str:
    """Serialize with sorted keys, 2-space indent, one trailing newline."""
    return json.dumps(obj, sort_keys=True, indent=2,
                      ensure_ascii=False) + "\n"


def dump(path: Path, obj: Any) -> None:
    """Atomically write ``obj`` deterministically (sorted keys + newline)."""
    path = Path(path)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(dumps_deterministic(obj), encoding="utf-8", newline="\n")
    tmp.replace(path)
