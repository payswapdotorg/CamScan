"""``evidence-cli bundle`` — validate/normalize a run dir to the
EVIDENCE.md layout and (re)build its ``manifest.json``.

The run directory (``runs/<run-id>/``) contract:

- ``scenario.yaml`` — required verbatim copy of the executed scenario;
- exactly **one** subject dir, ``reference/`` or ``implementation/``
  (EVIDENCE.md's manifest has a single ``subject`` value; both-subject
  run dirs are rejected — see README open questions);
- ``manifest.json`` — the bundle contract (written here, deterministic);
- ``run-metadata.json`` — optional runner-written metadata source
  (same fields as the manifest minus ``artifacts``); on any conflict it
  wins over an existing ``manifest.json`` (runner truth > derived file);
- ``r2-manifest.json`` — upload record (written by ``upload``);
- ``reconciliation/`` — paired-run workspace (parity-cli's domain;
  present-and-ignored here).

Behavior:

- every artifact under the subject dir is streamed-hashed; sidecars are
  normalized to the canonical sha256sum form; sidecars are *required*
  (and written) for ``outputs/`` artifacts;
- an existing ``manifest.json`` is repaired in place (per-key repairs
  are printed); a stale ``r2_key`` is dropped when the artifact content
  changed since its upload;
- ``--check`` gates: nothing is written; a non-zero exit means the
  on-disk bundle is stale or invalid (used by ``upload`` as precheck).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from . import jsonio
from .fixtures import FixtureCorpus
from .integrity import (
    SIDECAR_SUFFIX,
    ArtifactRec,
    canonical_sidecar,
    hash_artifacts,
    walk_subject,
)
from .schema import SUBJECTS, EvidenceCliError, validate_manifest

#: Root entries a run dir may contain (EVIDENCE.md layout + this tool's
#: documented runner-side files).
ALLOWED_ROOT_ENTRIES = frozenset({
    "scenario.yaml", "manifest.json", "run-metadata.json",
    "r2-manifest.json", "reference", "implementation", "reconciliation",
})


@dataclass
class BundleResult:
    run_dir: Path
    run_id: str
    subject: str
    manifest: dict | None = None
    artifacts: list[ArtifactRec] = field(default_factory=list)
    repairs: list[str] = field(default_factory=list)
    problems: list[str] = field(default_factory=list)
    wrote_manifest: bool = False
    wrote_sidecars: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.problems

    @property
    def total_bytes(self) -> int:
        return sum(a.size for a in self.artifacts)


def _scenario_id_lines(text: str) -> list[str]:
    """The ``id:`` values declared in a scenario.yaml (shallow, stdlib)."""
    ids: list[str] = []
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("id:"):
            value = stripped[3:].strip()
            if value:
                ids.append(value)
    return ids


def _short(hash_value: object, length: int = 12) -> str:
    text = hash_value if isinstance(hash_value, str) else str(hash_value)
    return f"{text[:length]}…" if len(text) > length else text


def diff_manifests(old: dict, new: dict) -> list[str]:
    """Human-readable per-key differences between two manifests."""
    lines: list[str] = []
    meta_keys = (set(old) | set(new)) - {"artifacts"}
    for key in sorted(meta_keys):
        if old.get(key) != new.get(key):
            lines.append(f"{key}: {_short(old.get(key))} → {_short(new.get(key))}")
    old_arts = {e.get("path"): e for e in old.get("artifacts", [])
                if isinstance(e, dict)}
    new_arts = {e.get("path"): e for e in new.get("artifacts", [])
                if isinstance(e, dict)}
    for path in sorted(set(new_arts) - set(old_arts)):
        lines.append(f"artifacts[{path}]: added")
    for path in sorted(set(old_arts) - set(new_arts)):
        lines.append(f"artifacts[{path}]: removed")
    for path in sorted(set(old_arts) & set(new_arts)):
        oe, ne = old_arts[path], new_arts[path]
        if oe.get("sha256") != ne.get("sha256"):
            lines.append(f"artifacts[{path}].sha256: "
                         f"{_short(oe.get('sha256'))} → "
                         f"{_short(ne.get('sha256'))}")
        if oe.get("bytes") != ne.get("bytes"):
            lines.append(f"artifacts[{path}].bytes: "
                         f"{oe.get('bytes')} → {ne.get('bytes')}")
        if "r2_key" in oe and "r2_key" not in ne:
            lines.append(f"artifacts[{path}].r2_key: dropped "
                         "(content changed since upload)")
    return lines


def bundle_run(run_dir: Path, *, check: bool = False,
               fixtures_ref: Path | None = None) -> BundleResult:
    """Validate + (re)build a run dir's manifest; see module docstring."""
    run_dir = Path(run_dir).resolve()
    result = BundleResult(run_dir=run_dir, run_id=run_dir.name, subject="")

    if not run_dir.is_dir():
        result.problems.append(f"run dir does not exist: {run_dir}")
        return result

    # ------------------------------------------------------ root layout
    for entry in sorted(run_dir.iterdir()):
        if entry.name not in ALLOWED_ROOT_ENTRIES:
            result.problems.append(
                f"unexpected root entry {entry.name!r} "
                f"(allowed: {sorted(ALLOWED_ROOT_ENTRIES)})")
    present = [s for s in SUBJECTS if (run_dir / s).is_dir()]
    if not present:
        result.problems.append(
            "no subject dir (expected exactly one of reference/ or "
            "implementation/)")
        return result
    if len(present) > 1:
        result.problems.append(
            "both reference/ and implementation/ present — a run dir "
            "bundles ONE subject (EVIDENCE.md's manifest has a single "
            "subject value; paired runs live in separate dirs; see "
            "README open questions)")
        return result
    subject = present[0]
    result.subject = subject

    scenario_path = run_dir / "scenario.yaml"
    if not scenario_path.is_file():
        result.problems.append(
            "scenario.yaml missing (EVIDENCE.md requires a verbatim copy "
            "of the executed scenario)")

    # --------------------------------------------------------- metadata
    meta: dict = {}
    old_manifest: dict = {}
    for source_name, target in (("manifest.json", "old"),
                                 ("run-metadata.json", "meta")):
        path = run_dir / source_name
        if not path.is_file():
            continue
        try:
            doc = jsonio.load(path)
        except (OSError, ValueError) as e:
            result.problems.append(f"{source_name}: unreadable ({e})")
            continue
        if not isinstance(doc, dict):
            result.problems.append(f"{source_name}: not a JSON object")
            continue
        if target == "old":
            old_manifest = doc
        meta.update(doc)  # run-metadata.json iterated last: runner wins

    if not meta:
        result.problems.append(
            "no metadata source: need run-metadata.json (runner-written) "
            "or an existing manifest.json")
        return result
    meta.pop("artifacts", None)  # artifacts are rebuilt from disk

    if meta.get("run_id") != result.run_id:
        result.problems.append(
            f"run_id {meta.get('run_id')!r} does not match the run dir "
            f"name {result.run_id!r}")
    if meta.get("subject") != subject:
        result.problems.append(
            f"metadata subject {meta.get('subject')!r} does not match the "
            f"present subject dir {subject!r}")

    scenario_ids = _scenario_id_lines(scenario_path.read_text(
        encoding="utf-8")) if scenario_path.is_file() else []
    if scenario_ids and meta.get("scenario") not in scenario_ids:
        result.problems.append(
            f"scenario.yaml declares id {scenario_ids!r} but metadata "
            f"scenario is {meta.get('scenario')!r}")

    # -------------------------------------------------------------- walk
    artifacts, sidecars, walk_problems = walk_subject(run_dir / subject)
    result.problems.extend(walk_problems)
    if result.problems:
        return result
    if not artifacts:
        result.problems.append(f"no artifacts under {subject}/")
        return result
    hash_artifacts(artifacts)
    result.artifacts = artifacts

    # ----------------------------------------------------- artifacts[]
    old_arts = {e.get("path"): e for e in old_manifest.get("artifacts", [])
                if isinstance(e, dict)}
    artifact_entries: list[dict] = []
    for art in sorted(artifacts, key=lambda a: a.path):
        entry = {"path": art.path, "sha256": art.sha256, "bytes": art.size}
        old = old_arts.get(art.path)
        # an r2_key survives only when the artifact content is unchanged
        # since its upload; changed artifacts drop their stale key (the
        # manifest diff below reports that repair)
        if (isinstance(old, dict) and old.get("r2_key")
                and old.get("sha256") == art.sha256):
            entry["r2_key"] = old["r2_key"]
        artifact_entries.append(entry)

    manifest = dict(meta)
    manifest["artifacts"] = artifact_entries
    result.manifest = manifest
    result.problems.extend(validate_manifest(manifest))

    # ------------------------------------------------------------ fixtures
    if manifest.get("fixtures"):
        try:
            corpus = FixtureCorpus.locate(run_dir, fixtures_ref)
        except EvidenceCliError as e:
            result.problems.append(str(e))
        else:
            result.problems.extend(corpus.check(manifest["fixtures"]))

    # ----------------------------------------------------------- sidecars
    by_path = {a.path: a for a in artifacts}
    sidecar_by_target: dict[str, str | None] = {}
    for sc in sidecars:
        if sc.path.endswith(SIDECAR_SUFFIX * 2):
            result.problems.append(f"sidecar-of-sidecar: {sc.path}")
            continue
        if sc.target_path not in by_path:
            result.problems.append(
                f"sidecar {sc.path} covers non-artifact {sc.target_path}")
            continue
        sidecar_by_target[sc.target_path] = sc.hex

    sidecars_to_write: list[ArtifactRec] = []
    for art in artifacts:
        existing = sidecar_by_target.get(art.path, "absent")
        required = art.path.startswith("outputs/")
        if existing == "absent":
            if required:
                sidecars_to_write.append(art)
                result.repairs.append(
                    f"{art.path}{SIDECAR_SUFFIX}: missing → writing "
                    f"({_short(art.sha256)})")
            continue
        if existing is None or existing != art.sha256:
            sidecars_to_write.append(art)
            reason = ("unparseable" if existing is None
                      else f"stale ({_short(existing)})")
            result.repairs.append(
                f"{art.path}{SIDECAR_SUFFIX}: {reason} → rewriting "
                f"({_short(art.sha256)})")

    if result.problems:
        return result

    # ------------------------------------------------------------ compare
    if old_manifest:
        result.repairs.extend(diff_manifests(old_manifest, manifest))

    # -------------------------------------------------------------- write
    if check:
        if sidecars_to_write:
            for art in sidecars_to_write:
                result.problems.append(
                    f"sidecar {art.path}{SIDECAR_SUFFIX} needs writing — "
                    "re-bundle required")
        old_path = run_dir / "manifest.json"
        if not old_path.is_file():
            result.problems.append("manifest.json missing — re-bundle "
                                  "required")
        else:
            try:
                on_disk = old_path.read_text(encoding="utf-8")
            except OSError as e:
                result.problems.append(f"manifest.json unreadable ({e})")
            else:
                if on_disk != jsonio.dumps_deterministic(manifest):
                    result.problems.append(
                        "manifest.json differs from the recomputed bundle "
                        "— re-bundle required")
        return result

    for art in sidecars_to_write:
        sidecar_path = run_dir / subject / (art.path + SIDECAR_SUFFIX)
        sidecar_path.write_text(canonical_sidecar(art.sha256, art.path),
                                encoding="utf-8", newline="\n")
        result.wrote_sidecars.append(art.path + SIDECAR_SUFFIX)

    jsonio.dump(run_dir / "manifest.json", manifest)
    result.wrote_manifest = True
    return result
