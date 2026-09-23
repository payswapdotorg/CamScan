"""Deterministic fixture corpus access (fail-closed).

``lab/fixtures/manifest.json`` (CAMSCAN-003) is the whitelist: every
fixture ``id`` + ``sha256`` recorded in an evidence manifest must be
declared there — reference and implementation runs must use *the exact
same fixture bytes* (EVIDENCE.md rules).

Corpus location order:

1. the ``--fixtures`` CLI argument (repo root, ``lab/fixtures/`` dir, or
   the ``manifest.json`` file itself);
2. the ``CAMSCAN_FIXTURES_DIR`` environment variable (same dir forms);
3. a walk up from the run directory looking for
   ``<ancestor>/lab/fixtures/manifest.json`` (run dirs under the repo
   resolve this way).

If none resolves, bundle/verify **fail closed** rather than trust an
unpinned fixture. The corpus is only consulted when the run manifest
declares fixtures — fixture-less runs (e.g. S001 launch) do not require a
locatable corpus.
"""
from __future__ import annotations

import os
from pathlib import Path
from typing import Any

from .jsonio import load
from .schema import EvidenceCliError

#: How far above the run directory to walk looking for the corpus.
_WALK_UP_LIMIT = 8


def _manifest_candidates(root: Path, *, include_bare: bool) -> list[Path]:
    """Corpus candidates for a search root.

    ``include_bare`` (explicit roots only: ``--fixtures`` /
    ``CAMSCAN_FIXTURES_DIR``, which may point at ``lab/fixtures/`` itself)
    also tries ``<root>/manifest.json``. Walk-up ancestors never use the
    bare form — a run dir's own ``manifest.json`` must never be adopted
    as the fixture corpus.
    """
    candidates = [root / "lab" / "fixtures" / "manifest.json"]
    if include_bare:
        candidates.append(root / "manifest.json")
    return candidates


def _resolve_explicit(path: Path) -> list[Path]:
    """Turn an explicit --fixtures / CAMSCAN_FIXTURES_DIR into candidates."""
    path = Path(path)
    if path.is_file():
        return [path]
    if path.is_dir():
        return _manifest_candidates(path, include_bare=True)
    return []


def locate_corpus(start: Path,
                  explicit: Path | None = None) -> Path:
    """Return the corpus ``manifest.json`` path or raise (fail-closed)."""
    candidates: list[Path] = []
    if explicit is not None:
        candidates.extend(_resolve_explicit(Path(explicit)))
    env_dir = os.environ.get("CAMSCAN_FIXTURES_DIR")
    if env_dir:
        candidates.extend(_resolve_explicit(Path(env_dir)))
    start = Path(start)
    for ancestor in [start, *list(start.parents)[:_WALK_UP_LIMIT]]:
        candidates.append(ancestor / "lab" / "fixtures" / "manifest.json")
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise EvidenceCliError(
        "fixture corpus not locatable (searched --fixtures, "
        "CAMSCAN_FIXTURES_DIR, and "
        f"{_WALK_UP_LIMIT} ancestor dirs of {start}); pass --fixtures "
        "<repo-root> or set CAMSCAN_FIXTURES_DIR — bundles with fixtures "
        "are rejected rather than trusted on unpinned fixture bytes")


class FixtureCorpus:
    """The validated fixture whitelist (documents + sequences)."""

    def __init__(self, manifest_path: Path) -> None:
        self.path = Path(manifest_path)
        try:
            doc = load(self.path)
        except (OSError, ValueError) as e:
            raise EvidenceCliError(
                f"fixture corpus {self.path}: unreadable ({e})") from e
        if not isinstance(doc, dict):
            raise EvidenceCliError(
                f"fixture corpus {self.path}: not a JSON object")
        self.raw: dict = doc
        self.ids: set[str] = set()
        self.hashes: dict[str, set[str]] = {}
        for group, files_key in (("documents", "files"),
                                 ("sequences", "frames")):
            entries = doc.get(group, [])
            if not isinstance(entries, list):
                raise EvidenceCliError(
                    f"fixture corpus {self.path}: '{group}' must be a list")
            for entry in entries:
                if not isinstance(entry, dict):
                    raise EvidenceCliError(
                        f"fixture corpus {self.path}: '{group}' entries "
                        "must be objects")
                fid = entry.get("id")
                if not isinstance(fid, str) or not fid:
                    raise EvidenceCliError(
                        f"fixture corpus {self.path}: entry without id")
                members = entry.get(files_key, [])
                if not isinstance(members, list):
                    raise EvidenceCliError(
                        f"fixture corpus {self.path}: fixture {fid!r} "
                        f"'{files_key}' must be a list")
                allowed = self.hashes.setdefault(fid, set())
                for member in members:
                    if not isinstance(member, dict):
                        raise EvidenceCliError(
                            f"fixture corpus {self.path}: fixture {fid!r} "
                            "file entries must be objects")
                    sha = member.get("sha256")
                    if not isinstance(sha, str):
                        raise EvidenceCliError(
                            f"fixture corpus {self.path}: fixture {fid!r} "
                            "file without sha256")
                    allowed.add(sha)
                self.ids.add(fid)

    @classmethod
    def locate(cls, start: Path,
               explicit: Path | None = None) -> FixtureCorpus:
        return cls(locate_corpus(start, explicit))

    def check(self, entries: Any) -> list[str]:
        """Problems for manifest ``fixtures`` entries (whitelist match)."""
        problems: list[str] = []
        if not isinstance(entries, list):
            return ["fixtures: expected a list of {id, sha256} entries"]
        for i, entry in enumerate(entries):
            if not isinstance(entry, dict):
                problems.append(f"fixtures[{i}]: expected an object")
                continue
            fid = entry.get("id")
            sha = entry.get("sha256")
            if fid not in self.ids:
                problems.append(
                    f"fixtures[{i}]: id {fid!r} is not declared in the "
                    f"fixture corpus ({self.path})")
            elif not isinstance(sha, str) or sha not in self.hashes.get(fid, set()):
                problems.append(
                    f"fixtures[{i}]: {fid!r} sha256 does not match any "
                    f"corpus file for that id ({self.path})")
        return problems
