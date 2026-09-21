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

- Generation scripts + source images + manifests are Worker 2 deliverables
  (`documents/*.png`, `manifest.json` with per-fixture sha256 + ground-truth
  annotations: expected page geometry, text content for OCR oracles).
- The corpus expands as reference discovery reveals new behaviors (e.g. ID-card
  mode, QR codes, tables). New fixtures get ledger entries.
- Fixtures never contain personal data or third-party copyrighted content —
  synthetic/generated content only.
