# CAMSCAN-003 — Deterministic fixture corpus (Worker 2)

You are Worker 2 (reference/oracle worker) for the CamScan parity lab. This task:
build the deterministic camera fixture corpus — synthetic content only, no
personal data, no third-party copyrighted content.

## Setup
- Clone https://github.com/payswapdotorg/CamScan.git at base sha 9fbbc903b40793189418550f77ded9829978251e; branch `work/CAMSCAN-003`.
- Work ONLY in `lab/fixtures/`. Do not touch scenarios, ledger, providers, or tooling.

## Task
1. Read `lab/fixtures/FIXTURES.md` AND the machine-verifiable manifest contract
   `lab/fixtures/fixture.schema.json` FIRST — the manifest you produce must
   validate against that JSON Schema (CI enforces it via
   `tools/lab-cli/validate.py`; run it yourself before finishing).
2. `documents/`: the 7 static fixtures (clean-a4, skewed-document, receipt,
   business-card, handwritten, low-light, multi-page) as synthetic images
   (render text/geometry programmatically — PIL/canvas, seeded RNG only).
   Phone-camera-like frames (>=1080p), realistic viewing geometry (page fills
   majority of frame with background visible; skewed-document has a visible
   perspective skew; low-light is under-exposed; multi-page is an ordered set).
3. `sequences/`: 3 scripted sequences (document-pan, camera-motion, rotation)
   as frame sets (10-30 frames each); manifest records per-frame index+transform.
4. `manifest.json`: per fixture — id, files (path+sha256+bytes), format,
   dimensions (width_px/height_px), ground_truth: page_count, text_content
   (the exact text you rendered — the OCR oracle), page_quad_px (expected
   document quad in frame pixels), plus lighting/rotation_deg where relevant.
   NOTE: the same fixture hash will be injected into BOTH the CamScanner
   reference runs and the CamScan implementation runs — hash integrity matters.
5. `generate.py`: deterministic regeneration (fixed seed, byte-identical
   output) with a `--check` mode that regenerates to a temp dir and
   hash-verifies every manifest entry.
6. Gate: `python3 tools/lab-cli/validate.py` must stay green (it now checks
   fixture ids referenced by scenarios S004-S013 against your manifest).

## Verification (paste outputs verbatim in your report)
```
python3 lab/fixtures/generate.py --check
python3 tools/lab-cli/validate.py
```

## Final report — use EXACTLY this headline:
```
=== CAMSCAN-003 COMPLETION REPORT ===
task: fixture corpus
environment: …
what was implemented: …
verification: …
evidence: (fixture list + sha256)
assumptions: …
open questions / handoffs: …
base sha: 9fbbc903b40793189418550f77ded9829978251e
```
Push branch `work/CAMSCAN-003`.
