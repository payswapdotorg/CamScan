"""``evidence-cli upload`` — put a verified bundle into R2.

Key layout (EVIDENCE.md): every artifact goes to
``runs/<run-id>/<subject>/<path>``; the stamped manifest itself goes to
``runs/<run-id>/<subject>/manifest.json``.

Order of operations (all-or-nothing reporting):

1. **local precheck** — ``bundle --check`` must pass (stale or tampered
   local bundles are never uploaded);
2. PUT each artifact, then **HEAD-verify** it (object size must equal
   the manifest's ``bytes``) before recording it;
3. stamp ``r2_key`` into ``manifest.json`` (EVIDENCE.md rules: the
   manifest records the R2 key) and rewrite it deterministically;
4. PUT the stamped manifest, HEAD-verify it;
5. write ``r2-manifest.json`` — the upload record: keys + sha256 +
   bytes + verified flags. **No timestamps** (content-addressed
   determinism: re-uploading identical bytes yields an identical file).

``scenario.yaml`` is deliberately NOT uploaded — durable truth lives in
git (LAB.md); R2 carries bulk bytes only.

Any failed step aborts before ``r2-manifest.json`` is written, so the
record's ``verified: true`` is meaningful.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from . import jsonio
from .bundle import bundle_run
from .hashing import sha256_bytes
from .schema import EvidenceCliError
from .store import R2Store


@dataclass
class UploadResult:
    run_dir: Path
    run_id: str
    subject: str
    bucket: str
    objects: list[dict] = field(default_factory=list)
    manifest_key: str = ""
    manifest_sha256: str = ""
    manifest_bytes: int = 0
    problems: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.problems


def upload_run(run_dir: Path, *, fixtures_ref: Path | None = None,
               store: R2Store | None = None) -> UploadResult:
    """Upload a bundled run dir to R2; see the module docstring."""
    run_dir = Path(run_dir).resolve()

    # 1. local precheck — refuse to upload stale/tampered bundles.
    precheck = bundle_run(run_dir, check=True, fixtures_ref=fixtures_ref)
    if not precheck.ok or precheck.manifest is None:
        detail = "\n  - ".join(precheck.problems[:8])
        more = (f"\n  (+{len(precheck.problems) - 8} more)"
                if len(precheck.problems) > 8 else "")
        raise EvidenceCliError(
            "local bundle failed precheck — run `evidence-cli bundle` "
            f"first:\n  - {detail}{more}")

    manifest = precheck.manifest
    run_id: str = manifest["run_id"]
    subject: str = manifest["subject"]
    store = store if store is not None else R2Store.from_env()

    result = UploadResult(run_dir=run_dir, run_id=run_id, subject=subject,
                          bucket=store.bucket)

    # 2. upload + HEAD-verify every artifact.
    for entry in sorted(manifest["artifacts"], key=lambda e: e["path"]):
        key = f"runs/{run_id}/{subject}/{entry['path']}"
        store.put_file(key, run_dir / subject / entry["path"])
        head = store.head(key)
        if not head["exists"] or head["size"] != entry["bytes"]:
            raise EvidenceCliError(
                f"R2 verification failed for {key}: HEAD size "
                f"{head['size']!r} != manifest bytes {entry['bytes']} "
                "— object not recorded")
        result.objects.append({"key": key, "sha256": entry["sha256"],
                               "bytes": entry["bytes"], "verified": True})

    # 3. stamp r2_key + rewrite the manifest deterministically.
    for entry in manifest["artifacts"]:
        entry["r2_key"] = f"runs/{run_id}/{subject}/{entry['path']}"
    jsonio.dump(run_dir / "manifest.json", manifest)
    manifest_data = (run_dir / "manifest.json").read_bytes()
    result.manifest_sha256 = sha256_bytes(manifest_data)
    result.manifest_bytes = len(manifest_data)

    # 4. upload the stamped manifest, HEAD-verify it.
    manifest_key = f"runs/{run_id}/{subject}/manifest.json"
    result.manifest_key = manifest_key
    store.put_bytes(manifest_key, manifest_data)
    head = store.head(manifest_key)
    if not head["exists"] or head["size"] != result.manifest_bytes:
        raise EvidenceCliError(
            f"R2 verification failed for {manifest_key}: HEAD size "
            f"{head['size']!r} != {result.manifest_bytes} — upload not "
            "recorded")
    jsonio.dump(run_dir / "r2-manifest.json", {
        "run_id": run_id,
        "subject": subject,
        "bucket": store.bucket,
        "objects": result.objects,
        "manifest_key": manifest_key,
        "manifest_sha256": result.manifest_sha256,
        "manifest_bytes": result.manifest_bytes,
    })
    return result
