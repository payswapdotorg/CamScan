# Deterministic fixture corpus (v0 spec)

No humans holding documents at cameras for the core regression suite. Fixtures are
content-addressed (SHA-256 recorded in each manifest) and applied **identically** to
CamScanner (reference) and CamScan (implementation) via the provider
`camera_fixture` capability (virtual camera injection).

## documents/ (static frames)

| id | purpose |
|---|---|
| `clean-a4` | plain A4 text page, ideal lighting — detection/capture baseline |
| `skewed-document` | perspective-skewed page — manual crop + perspective correction |
| `receipt` | narrow thermal receipt — non-A4 geometry |
| `business-card` | small-format card — detection of small documents |
| `handwritten` | handwritten notes — OCR hardness class |
| `low-light` | under-exposed page — enhancement paths |
| `multi-page` | ordered page set — multi-page scanning |

## sequences/ (dynamic camera motion)

| id | purpose |
|---|---|
| `document-pan` | slow pan across document — live detection overlay behavior |
| `camera-motion` | handheld shake — detection stability/refocus behavior |
| `rotation` | device rotation during capture — orientation behavior |

## Rules

- The machine-verifiable manifest contract is `fixture.schema.json` (JSON
  Schema, CI-enforced): every fixture carries id, file list, format,
  dimensions, ground-truth annotations, and sha256 per file.
- Generation scripts + source images + manifests are Worker 2 deliverables
  (`documents/*.png`, `sequences/*` frames, `manifest.json` with per-fixture
  sha256 + ground-truth: expected page geometry, text content for OCR
  oracles, expected page count).
- The **exact same fixture hash** is applied to the CamScanner reference run
  and the CamScan implementation run — fixture identity is content-addressed.
- The corpus expands as reference discovery reveals new behaviors (e.g. ID-card
  mode, QR codes, tables). New fixtures get ledger entries.
- Fixtures never contain personal data or third-party copyrighted content —
  synthetic/generated content only.

## Corpus conventions (v0.1 — CAMSCAN-003)

Regeneration: `python3 lab/fixtures/generate.py` (in place, byte-identical);
`python3 lab/fixtures/generate.py --check` regenerates the corpus to a temp
dir and hash-verifies every manifest entry (files **and** manifest.json).
Environment: Pillow (>=9, built on 11.3) + FreeType with DejaVu fonts
(system dejavu dir, matplotlib bundle as fallback). The manifest
`generated_utc` is a fixed corpus epoch — not wall-clock — so regeneration
stays byte-identical; the recorded sha256s remain the cross-environment
integrity truth.

- Frames are 1080x1920 portrait (>=1080p class), phone-camera-like viewing
  geometry: the page fills the majority of the frame with background visible.
- `page_quad_px` point order is `[TL, TR, BR, BL]`, x right / y down, frame
  pixels. The recorded quad is exactly the quad the generator warped to
  (2-decimal quantized) — it is the detection ground truth.
- Documents are PNG (lossless — they are the OCR oracles); sequences are
  JPEG quality 87 (camera-like motion frames). `ground_truth.text_content`
  is the exact text rendered into the page.
- `documents/multi-page/` is an ordered 3-page set sharing one camera
  position (fixed mount, repeatable placement), so a single quad is truth
  for all pages; the per-file `page` field carries the order.
- Sequence per-frame transform grammar:
  `pan(dx,dy)` · `shake(dx,dy,theta,scale)` · `rotate(theta,scale)`.
  `document-pan` intentionally lets the page partially exit the frame at the
  pan extremes (detection tracking); `rotation` ends with the page landscape
  (90 deg in-plane).
- Lighting vocabulary: `even-daylight`, `neutral-indoor`, `dim-tungsten`
  (low-light is under-exposed to ~1/3 brightness with high sensor grain).
