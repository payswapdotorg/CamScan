# evidence-cli — bundle · upload · verify (CAMSCAN-006)

Worker 3's evidence pipeline: turns a runner's raw run directory into the
`lab/evidence/EVIDENCE.md` bundle contract, ships the bulk bytes to
Cloudflare R2, and re-derives integrity from disk at any later time.

``python3 tools/evidence-cli/main.py <command>`` — stdlib + boto3 only
(boto3 imported lazily; bundle/verify run without it).

## bundle <run-dir> [--check]

Validates + normalizes a `runs/<run-id>/` directory to the
`lab/evidence/EVIDENCE.md` layout, streams a SHA-256 over every artifact,
and writes a deterministic `manifest.json`:

- JSON: sorted keys, 2-space indent, one trailing newline;
- `artifacts[]` sorted by path, `fixtures[]` kept in declared order,
  `action_trace[]` kept in recorded (chronological) order;
- **no timestamps of the tool's own making** — `started_at`/`finished_at`
  come from run metadata; nothing else stamps time.

Run-dir contract:

| entry | role |
|---|---|
| `scenario.yaml` | required verbatim copy of the executed scenario |
| `reference/` or `implementation/` | exactly **one** subject dir |
| `manifest.json` | the bundle contract (written by bundle) |
| `run-metadata.json` | optional runner metadata source; wins over `manifest.json` on conflict |
| `r2-manifest.json` | upload record (written by upload) |
| `reconciliation/` | paired-run workspace (parity-cli's; ignored here) |

Subject-dir layout (flat, fail-closed on anything else):
`screenshots/*.png`, `recordings/*.mp4|.webm`, `ui/*.xml`,
`logs/{logcat.txt,app-events.json}`, `outputs/*.pdf|.jpg|.txt`.

Sidecars: `*.sha256` files are derived, never manifest artifacts. Bundle
normalizes them to the canonical sha256sum form
(`<64-hex>␣␣<basename>\n`), and **writes** the required sidecars for
`outputs/` artifacts. A stale `r2_key` is dropped (with a printed repair)
when the artifact content changed since its upload.

`--check` writes nothing and exits non-zero when the on-disk bundle is
stale — it is the precheck `upload` runs. Fixture ids/hashes are checked
against `lab/fixtures/manifest.json` whenever the manifest declares
fixtures; a run declaring **no** fixtures needs no locatable corpus.

## upload <run-dir>

Uploads every artifact to R2 under `runs/<run-id>/<subject>/<path>`, then
HEAD-verifies each object (size must match the manifest), stamps
`r2_key` into `manifest.json`, uploads the stamped manifest under
`runs/<run-id>/<subject>/manifest.json`, HEAD-verifies it, and writes
`r2-manifest.json` (keys + sha256 + bytes + `verified` flags — no
timestamps, so identical content ⇒ identical record).

`scenario.yaml` is deliberately not uploaded: durable truth lives in git,
bulk bytes in R2 (LAB.md). Any failed step aborts before
`r2-manifest.json` is written, so its `verified: true` is meaningful.

## verify <manifest.json>

Re-derives everything from disk: manifest schema, per-artifact hashes +
sizes, sidecar agreement (required for `outputs/`), layout exactness (no
missing/unmanifested files), fixture whitelist, and — when all four R2
credential env vars are present — every recorded R2 key is HEAD-checked
for existence + size. Any problem exits non-zero; a per-artifact table is
printed either way. Missing credentials are a printed skip note, not a
failure.

## Credentials (SECURITY.md — binding)

Read **only** from the environment, all four required, never defaulted,
never logged, never written to any manifest:

```
R2_ACCESS_KEY_ID  R2_SECRET_ACCESS_KEY  R2_ENDPOINT  R2_BUCKET
```

Error text is scrubbed of credential values before it can surface
(`store.R2Store.sanitize`); `repr(R2Store)` is redacted.

## Fixture corpus location

`--fixtures <repo-root|lab/fixtures|manifest.json>`, else
`CAMSCAN_FIXTURES_DIR` (same forms), else a walk up from the run dir
(keeps `runs/<run-id>` under the repo working with no configuration).
Unlocatable corpus + declared fixtures ⇒ fail closed.

## Importing

``tools/__init__.py`` registers this dashed dir as `tools.evidence_cli`:

```python
from tools.evidence_cli.api import (EvidenceCliError, R2Store,
                                     bundle_run, upload_run, verify_manifest)
```

## Tests

``python3 -m pytest tools/evidence-cli -q`` — fake-fs run trees + moto 5
mocked S3. moto interception needs an AWS-shaped `R2_ENDPOINT` in tests;
the store itself is endpoint-agnostic. Real-R2 round-trip:
``-m integration`` (skipped unless the four env vars are set).

## Open questions (recorded, not decided here)

1. EVIDENCE.md's layout drawing shows both `reference/` and
   `implementation/` under one run dir, but its manifest schema carries
   a single `subject` value — this tool bundles one subject per run dir
   and rejects both-subject dirs. A paired-run layout (or a manifest
   change) is a lead decision.
2. `scenario.yaml` upload to R2 is intentionally omitted (git-side truth).
