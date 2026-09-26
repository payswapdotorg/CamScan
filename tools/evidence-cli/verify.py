"""``evidence-cli verify`` — re-derive a bundle's integrity from disk.

Checks, in order:

1. **schema** — the manifest validates against the EVIDENCE.md contract
   (hand-rolled, see :mod:`.schema`);
2. **hashes** — every artifact is streamed-hashed from disk and must
   match the manifest's ``sha256``/``bytes`` (the manifest is truth; the
   sidecar must agree with both);
3. **sidecars** — ``outputs/`` artifacts require one, every existing
   sidecar must agree, and sidecars covering non-artifacts fail;
4. **layout** — disk contents match the manifest exactly (no missing,
   no unmanifested files, category rules hold); CAMSCAN-010J: size==0
   files are EMPTY CAPTURES under the same exclusion policy the bundle
   applies (``integrity.split_empty_captures``) — a manifest-recorded
   ``empty_captures`` entry is the honest record of the gap; a disk
   empty the manifest does NOT record is flagged as unmanifested;
5. **fixtures** — declared ids/hashes are whitelisted in the corpus;
6. **R2** (only when all four credential env vars are present) — every
   recorded ``r2_key`` is HEAD-checked: object exists and its size
   matches the manifest.

Any problem → non-zero exit; the per-artifact table is printed either
way. Missing credentials are a *skip note*, not a failure (offline
verification of hashes/sidecars/layout/fixtures stands on its own).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from . import jsonio
from .fixtures import FixtureCorpus
from .integrity import (
    R2_MISSING,
    R2_NO_CREDS,
    R2_NOT_UPLOADED,
    R2_OK,
    R2_SIZE_MISMATCH,
    SC_INVALID,
    SC_MISMATCH,
    SC_MISSING,
    SC_NA,
    SC_OK,
    SHA_MISMATCH,
    SHA_MISSING,
    SHA_OK,
    CheckRow,
    hash_artifacts,
    split_empty_captures,
    walk_subject,
)
from .schema import SUBJECTS, EvidenceCliError, validate_manifest
from .store import R2Store


@dataclass
class VerifyResult:
    manifest_path: Path
    run_dir: Path
    run_id: str = ""
    subject: str = ""
    rows: list[CheckRow] = field(default_factory=list)
    problems: list[str] = field(default_factory=list)
    remote_checked: bool = False

    @property
    def ok(self) -> bool:
        return not self.problems


def verify_manifest(manifest_path: Path, *,
                    fixtures_ref: Path | None = None,
                    check_remote: bool = True,
                    store: R2Store | None = None) -> VerifyResult:
    """Re-verify an evidence bundle; see the module docstring."""
    manifest_path = Path(manifest_path).resolve()
    result = VerifyResult(manifest_path=manifest_path,
                          run_dir=manifest_path.parent)

    if not manifest_path.is_file():
        result.problems.append(f"manifest not found: {manifest_path}")
        return result
    try:
        doc = jsonio.load(manifest_path)
    except (OSError, ValueError) as e:
        result.problems.append(f"manifest unreadable: {e}")
        return result
    result.problems.extend(validate_manifest(doc))
    if not isinstance(doc, dict):
        return result

    run_id = doc.get("run_id")
    subject = doc.get("subject")
    result.run_id = run_id if isinstance(run_id, str) else ""
    result.subject = subject if isinstance(subject, str) else ""
    if isinstance(run_id, str) and run_id != result.run_dir.name:
        result.problems.append(
            f"manifest run_id {run_id!r} != run dir name "
            f"{result.run_dir.name!r}")
    if subject not in SUBJECTS:
        return result

    entries = doc.get("artifacts") if isinstance(doc.get("artifacts"),
                                                 list) else []
    manifest_paths = {e.get("path") for e in entries if isinstance(e, dict)}

    # ------------------------------------------------------------- layout
    artifacts, sidecars, walk_problems = walk_subject(result.run_dir / subject)
    result.problems.extend(walk_problems)
    hash_artifacts(artifacts)
    # CAMSCAN-010J: the SAME empty-capture partition the bundle applies
    # (integrity.split_empty_captures — one policy, two readers): a
    # size==0 file on disk is an EXCLUDED capture, not an artifact. A
    # disk empty the manifest records under empty_captures is fine (the
    # honest record); one it does NOT record is an unmanifested gap —
    # flagged below like any other disk/manifest disagreement.
    artifacts, disk_empty_caps = split_empty_captures(artifacts)
    raw_empty = doc.get("empty_captures")
    manifest_empty_paths = (
        {e.get("path") for e in raw_empty if isinstance(e, dict)}
        if isinstance(raw_empty, list) else set())
    for art in sorted(disk_empty_caps, key=lambda a: a.path):
        if art.path not in manifest_empty_paths:
            result.problems.append(
                "on-disk empty capture not in manifest empty_captures: "
                f"{art.path}")
    disk_by_path = {a.path: a for a in artifacts}

    sidecar_by_target: dict[str, str | None] = {}
    for sc in sidecars:
        if sc.path.endswith(".sha256.sha256"):
            result.problems.append(f"sidecar-of-sidecar: {sc.path}")
        elif sc.target_path not in manifest_paths:
            result.problems.append(
                f"sidecar {sc.path} covers non-artifact {sc.target_path}")
        else:
            sidecar_by_target[sc.target_path] = sc.hex

    # ---------------------------------------------------------- per-row
    def sidecar_status(path: str) -> tuple[str, list[str]]:
        if path not in sidecar_by_target:
            if path.startswith("outputs/"):
                return SC_MISSING, [f"{path}: missing required sidecar"]
            return SC_NA, []
        hexdigest = sidecar_by_target[path]
        if hexdigest is None:
            return SC_INVALID, [f"{path}.sha256: unparseable content"]
        return SC_OK, []

    for entry in sorted(entries,
                         key=lambda e: e.get("path", "")
                         if isinstance(e, dict) else ""):
        if not isinstance(entry, dict):
            continue
        path = entry.get("path", "")
        notes: list[str] = []
        art = disk_by_path.pop(path, None)
        if art is None:
            sha_status, nbytes = SHA_MISSING, 0
            notes.append(f"{path}: missing on disk")
        else:
            nbytes = art.size
            if (art.sha256 != entry.get("sha256")
                    or art.size != entry.get("bytes")):
                sha_status = SHA_MISMATCH
                notes.append(
                    f"{path}: disk sha256 {art.sha256} / {art.size} B "
                    f"!= manifest {entry.get('sha256')} / "
                    f"{entry.get('bytes')} B")
            else:
                sha_status = SHA_OK
        sc_status, sc_notes = sidecar_status(path)
        notes.extend(sc_notes)
        if sc_status == SC_OK:
            hexdigest = sidecar_by_target.get(path)
            if hexdigest is not None and hexdigest != entry.get("sha256"):
                sc_status = SC_MISMATCH
                notes.append(
                    f"{path}.sha256: sidecar hash != manifest hash")
        row = CheckRow(path=path, bytes=nbytes, sha256=sha_status,
                       sidecar=sc_status,
                       r2=R2_NOT_UPLOADED if not entry.get("r2_key")
                       else "pending",
                       notes=notes)
        result.rows.append(row)

    for path in sorted(disk_by_path):
        result.problems.append(
            f"on-disk artifact not in manifest: {path}")
        result.rows.append(CheckRow(path=path,
                                    bytes=disk_by_path[path].size,
                                    sha256=SHA_OK, sidecar=SC_NA,
                                    r2=R2_NOT_UPLOADED,
                                    notes=[f"{path}: unmanifested"]))
    result.problems.extend(
        note for row in result.rows for note in row.notes)

    # ------------------------------------------------------------ fixtures
    if doc.get("fixtures"):
        try:
            corpus = FixtureCorpus.locate(result.run_dir, fixtures_ref)
        except EvidenceCliError as e:
            result.problems.append(str(e))
        else:
            result.problems.extend(corpus.check(doc["fixtures"]))

    # ----------------------------------------------------------------- R2
    r2_entries = [e for e in entries if isinstance(e, dict) and e.get("r2_key")]
    if check_remote and R2Store.creds_present():
        result.remote_checked = True
        store = store if store is not None else R2Store.from_env()
        by_path = {row.path: row for row in result.rows}
        for entry in r2_entries:
            path, key = entry.get("path", ""), entry.get("r2_key", "")
            head = store.head(key)
            row = by_path.get(path)
            if not head["exists"]:
                status = R2_MISSING
                result.problems.append(
                    f"{path}: R2 object missing: {key}")
            elif head["size"] != entry.get("bytes"):
                status = R2_SIZE_MISMATCH
                result.problems.append(
                    f"{path}: R2 size mismatch for {key}: "
                    f"{head['size']} != {entry.get('bytes')}")
            else:
                status = R2_OK
            if row is not None:
                row.r2 = status
        r2_manifest = result.run_dir / "r2-manifest.json"
        if r2_manifest.is_file():
            try:
                record = jsonio.load(r2_manifest)
                mkey = record.get("manifest_key")
                mbytes = record.get("manifest_bytes")
                if isinstance(mkey, str) and isinstance(mbytes, int):
                    head = store.head(mkey)
                    if not head["exists"]:
                        result.problems.append(
                            f"r2-manifest: R2 object missing: {mkey}")
                    elif head["size"] != mbytes:
                        result.problems.append(
                            f"r2-manifest: R2 size mismatch for {mkey}: "
                            f"{head['size']} != {mbytes}")
            except (OSError, ValueError) as e:
                result.problems.append(f"r2-manifest.json unreadable: {e}")
    elif r2_entries:
        for row in result.rows:
            if row.r2 == "pending":
                row.r2 = R2_NO_CREDS
    return result
