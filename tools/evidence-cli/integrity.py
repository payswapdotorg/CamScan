"""Subject-tree walk, hash-sidecar rules and the per-artifact check table.

Implements the EVIDENCE.md bundle layout for one subject dir
(``reference/`` or ``implementation/``):

- artifacts live **flat** inside exactly one category dir:
  ``screenshots/*.png``, ``recordings/*.mp4|.webm``, ``ui/*.xml``,
  ``logs/{logcat.txt,app-events.json}``, ``outputs/*.pdf|.jpg|.txt``;
- ``*.sha256`` sidecars are allowed anywhere; they are *derived* files —
  excluded from manifest artifacts, but every existing sidecar must
  agree with the artifact it covers (bundle normalizes them; verify
  fails on disagreement);
- sidecars are **required** for ``outputs/`` artifacts (EVIDENCE.md:
  "outputs/ ... (+ sha256 sidecars)");
- canonical sidecar content is ``sha256sum``-compatible:
  ``<64-hex>␣␣<basename>\\n`` (so ``cd outputs && sha256sum -c`` works).

Everything here is deterministic: directory listings are sorted, problem
order is stable, and no timestamps are recorded anywhere (content
addressing only).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from .hashing import sha256_file
from .schema import CATEGORY_DIRS, HEX64_RE

SIDECAR_SUFFIX = ".sha256"


@dataclass(frozen=True)
class CategoryRule:
    """What files a category dir accepts."""
    extensions: frozenset[str] = frozenset()
    exact_names: frozenset[str] = frozenset()

    def allows(self, name: str) -> bool:
        if self.exact_names:
            return name in self.exact_names
        return Path(name).suffix in self.extensions

    def describe(self) -> str:
        if self.exact_names:
            return " | ".join(sorted(self.exact_names))
        return " | ".join(sorted(self.extensions)) or "-"


#: EVIDENCE.md category rules (subject-relative dirs).
CATEGORY_RULES: dict[str, CategoryRule] = {
    "screenshots": CategoryRule(extensions=frozenset({".png"})),
    "recordings": CategoryRule(extensions=frozenset({".mp4", ".webm"})),
    "ui": CategoryRule(extensions=frozenset({".xml"})),
    "logs": CategoryRule(exact_names=frozenset({"logcat.txt",
                                                "app-events.json"})),
    "outputs": CategoryRule(extensions=frozenset({".pdf", ".jpg", ".txt"})),
}


@dataclass
class ArtifactRec:
    """One walked artifact (subject-relative POSIX path)."""
    path: str
    abs_path: Path
    size: int = 0
    sha256: str = ""


@dataclass
class SidecarRec:
    """One walked ``*.sha256`` sidecar."""
    path: str                      # subject-relative, ends .sha256
    target_path: str               # the file it covers
    hex: str | None                # parsed digest, None if unparseable


def parse_sidecar(text: str) -> str | None:
    """Parse sidecar content: bare ``<hex>`` or sha256sum's
    ``<hex>␣␣<name>`` form. Returns the digest or None."""
    stripped = text.strip()
    if HEX64_RE.match(stripped):
        return stripped
    if len(stripped) > 66 and stripped[64:66] == "  ":
        head = stripped[:64]
        if HEX64_RE.match(head):
            return head
    return None


def canonical_sidecar(sha256: str, path: str) -> str:
    """The canonical sidecar content for an artifact."""
    return f"{sha256}  {Path(path).name}\n"


def walk_subject(subject_dir: Path) -> tuple[list[ArtifactRec],
                                               list[SidecarRec],
                                               list[str]]:
    """Walk a subject dir; return (artifacts, sidecars, problems)."""
    problems: list[str] = []
    artifacts: list[ArtifactRec] = []
    sidecars: list[SidecarRec] = []

    if not subject_dir.is_dir():
        return [], [], [f"subject dir {subject_dir.name}/ does not exist"]

    entries = sorted(subject_dir.iterdir())
    if not entries:
        return [], [], [f"subject dir {subject_dir.name}/ is empty"]

    for entry in entries:
        if not entry.is_dir():
            problems.append(
                f"unexpected file at subject root: {entry.name} "
                "(artifacts live under category dirs: "
                f"{list(CATEGORY_DIRS)})")
            continue
        if entry.name not in CATEGORY_RULES:
            problems.append(
                f"unknown category dir {entry.name}/ "
                f"(allowed: {list(CATEGORY_DIRS)})")
            continue
        rule = CATEGORY_RULES[entry.name]
        files = sorted(entry.iterdir())
        if not files:
            problems.append(f"empty category dir {entry.name}/ "
                            "(present but artifact-less)")
            continue
        for f in files:
            rel = f"{entry.name}/{f.name}"
            if f.is_dir():
                problems.append(f"unexpected subdirectory {rel}/ "
                                "(category dirs are flat)")
            elif f.name.endswith(SIDECAR_SUFFIX):
                target_rel = f"{entry.name}/{f.name[:-len(SIDECAR_SUFFIX)]}"
                try:
                    raw = f.read_text(encoding="utf-8")
                except OSError as e:
                    problems.append(f"sidecar {rel}: unreadable ({e})")
                    continue
                sidecars.append(SidecarRec(path=rel, target_path=target_rel,
                                           hex=parse_sidecar(raw)))
            elif rule.allows(f.name):
                artifacts.append(ArtifactRec(path=rel, abs_path=f))
            else:
                problems.append(
                    f"{rel}: not allowed in {entry.name}/ "
                    f"(allowed: {rule.describe()})")

    return artifacts, sidecars, problems


def hash_artifacts(artifacts: list[ArtifactRec]) -> None:
    """Fill ``sha256`` + ``size`` for each artifact (streamed)."""
    for art in artifacts:
        art.sha256, art.size = sha256_file(art.abs_path)


def split_empty_captures(artifacts: list[ArtifactRec]) \
        -> tuple[list[ArtifactRec], list[ArtifactRec]]:
    """CAMSCAN-010J — partition hashed artifacts into (real, empty).

    A ``size == 0`` file in the subject tree is an EMPTY CAPTURE — a
    legitimately-empty read (a blank frame, an empty buffer written
    under strain — e.g. the reference driver's unconditional
    ``logs/logcat.txt`` write when the capture came back empty). It is
    never evidence (there is nothing to hash-check or upload) and
    NEVER fatal: the manifest's ``artifacts[]`` positive-bytes
    contract stays for REAL artifacts, while empties are excluded and
    recorded under the honest ``empty_captures`` key instead of
    killing the whole bundle (the 2026-09-26 09:17:47 UTC S002 round:
    "artifacts[0].bytes must be a positive integer, got 0" destroyed
    the run's evidence write). One policy, two readers: bundle
    (records the exclusion) and verify (must not flag a recorded
    empty as an unmanifested artifact). Call AFTER
    :func:`hash_artifacts` — only then is ``size`` the on-disk truth.
    """
    real = [a for a in artifacts if a.size > 0]
    empty = [a for a in artifacts if a.size <= 0]
    return real, empty


def check_sidecars(artifacts: list[ArtifactRec],
                   sidecars: list[SidecarRec],
                   *, require_outputs: bool = True) -> list[str]:
    """Sidecar-rule problems for an already-hashed artifact set."""
    problems: list[str] = []
    by_path = {a.path: a for a in artifacts}
    covered: set[str] = set()

    for sc in sidecars:
        if sc.path.endswith(SIDECAR_SUFFIX + SIDECAR_SUFFIX):
            problems.append(f"sidecar-of-sidecar is not a thing: {sc.path}")
            continue
        art = by_path.get(sc.target_path)
        if art is None:
            problems.append(
                f"sidecar {sc.path} covers non-artifact {sc.target_path}")
            continue
        if sc.hex is None:
            problems.append(f"sidecar {sc.path}: unparseable content "
                            "(expected '<64-hex>' or sha256sum form)")
            continue
        if sc.hex != art.sha256:
            problems.append(
                f"sidecar {sc.path}: hash mismatch (sidecar says "
                f"{sc.hex[:12]}…, artifact is {art.sha256[:12]}…)")
        covered.add(sc.target_path)

    if require_outputs:
        for art in artifacts:
            if art.path.startswith("outputs/") and art.path not in covered:
                problems.append(
                    f"outputs artifact {art.path}: missing required "
                    f"{art.path}{SIDECAR_SUFFIX} sidecar")
    return problems


#: SHA256 column values.
SHA_OK, SHA_MISMATCH, SHA_MISSING = "ok", "MISMATCH", "MISSING"
#: Sidecar column values.
SC_OK, SC_MISSING, SC_MISMATCH, SC_INVALID, SC_NA = ("ok", "missing",
                                                     "mismatch", "invalid",
                                                     "-")
#: R2 column values.
R2_OK, R2_SIZE_MISMATCH, R2_MISSING, R2_NO_CREDS, R2_NOT_UPLOADED = (
    "size-ok", "size-mismatch", "missing-object", "no-creds",
    "not-uploaded")


@dataclass
class CheckRow:
    """One row of the per-artifact verification table."""
    path: str
    bytes: int
    sha256: str
    sidecar: str
    r2: str
    notes: list[str] = field(default_factory=list)


def format_table(rows: list[CheckRow]) -> str:
    """Render the aligned per-artifact table (deterministic order)."""
    headers = ("PATH", "BYTES", "SHA256", "SIDECAR", "R2")
    w_path = max([len(headers[0])] + [len(r.path) for r in rows])
    w_bytes = max([len(headers[1])] + [len(str(r.bytes)) for r in rows])
    w_sha = max([len(headers[2])] + [len(r.sha256) for r in rows])
    w_sc = max([len(headers[3])] + [len(r.sidecar) for r in rows])
    w_r2 = max([len(headers[4])] + [len(r.r2) for r in rows])

    def line(path: str, nbytes: str, sha: str, sc: str, r2: str) -> str:
        return (f"{path:<{w_path}}  {nbytes:>{w_bytes}}  {sha:<{w_sha}}  "
                f"{sc:<{w_sc}}  {r2:<{w_r2}}")

    out = [line(*headers)]
    for r in rows:
        out.append(line(r.path, str(r.bytes), r.sha256, r.sidecar, r.r2))
    return "\n".join(out)
