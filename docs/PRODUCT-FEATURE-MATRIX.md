# CamScan Product Feature Matrix

Purpose: executable scope for broad CamScanner-like functionality.

Priority:
- P0: scanner product
- P1: daily document workflow
- P2: advanced document tools
- P3: ecosystem capabilities

## P0 — scan a document like CamScanner

| Capability | Required behavior |
|---|---|
| Launch | Reach a usable Home/Scan surface without crash |
| Camera permission | Request only when needed and recover cleanly |
| Live preview | Usable document framing surface |
| Document detection | Detect a rectangular page in live frames |
| Framing guidance | Guide user according to detected quality |
| Manual capture | Always usable when camera is available |
| Auto capture | Implement when reference behavior establishes it |
| Perspective correction | Flatten skewed page |
| Smart crop | Crop to detected page bounds |
| Enhancement | Produce a clearer processed page |
| Page review | Inspect result before final save |
| Retake | Replace page without corrupting document |
| Rotate | Correct page orientation |
| Save | Persist one-page document locally |
| Multi-page | Capture several ordered pages |
| Import | Import an existing image into scan workflow |
| Specialist modes | Add document/ID/book modes as reference evidence warrants |
| PDF export | Produce readable ordered PDF |
| JPG export | Export requested page(s) |
| Share | Native Android sharing/export |

### P0 scanner acceptance

Given a synthetic paper document or permitted camera fixture, a user can open CamScan, frame/capture the page, receive document-boundary guidance, obtain a perspective-corrected enhanced page, review it, retake/edit it if needed, save it, add more pages, and export the final document as readable PDF/JPG.

## P1 — daily document workflow

- local document library
- document thumbnails and metadata
- rename and reopen
- page reorder/delete/duplicate/replace
- manual crop
- enhancement editing
- OCR
- OCR text share/export
- search by title and OCR content
- batch selection
- import image/PDF
- merge documents
- split/extract pages
- PDF compression
- PDF/JPG/TXT sharing

## P2 — advanced tools

- reusable signatures
- page annotation
- watermarks
- password protection
- page extraction
- long-image export
- batch scan
- book workflow
- ID-card workflow
- business-card workflow

## P3 — ecosystem

- optional cloud account
- sync
- backup/restore
- cross-device metadata
- Word/Excel/PPT conversion adapters
- collaboration
- wireless print adapter
- fax adapter
- translation

## Acceptance rule

Feature status is:
implemented -> verified -> reconciled -> accepted

Code existing in app/ is never sufficient for acceptance.


## Implementation status — lead audit 2026-10-03 (wave-4 close, the truthful coverage)

Vocabulary for this section (implementation truth only — NEVER parity acceptance):
- `FLOW` — engine + fragment-level UI shipped and JVM-gated (instrumented verification still OPEN)
- `ENGINE` — pure engine shipped and JVM-gated; NO UI wiring yet (lead-station seam)
- `PARTIAL` — a bounded piece shipped; a named piece open
- `NOT-IMPL` — not implemented
- `NOT-IN-SCOPE` — excluded by the offline-first clean-room mandate (program decision)

P0: Launch FLOW; Camera permission FLOW; Live preview FLOW; Document detection FLOW; Framing guidance FLOW;
Manual capture FLOW; Auto capture NOT-IMPL (reference behavior not established); Perspective correction FLOW;
Smart crop FLOW; Enhancement FLOW; Page review FLOW; Retake FLOW; Rotate FLOW; Save FLOW; Multi-page FLOW;
Import FLOW; Specialist modes ENGINE (tools/modes — PROD-012); PDF export FLOW; JPG export FLOW; Share FLOW.

P1: local document library FLOW; thumbnails and metadata FLOW; rename and reopen FLOW; page
reorder/delete/duplicate/replace FLOW; manual crop FLOW; enhancement editing FLOW; OCR FLOW (live ML Kit,
PROD-010); OCR text share/export FLOW; search by title and OCR content FLOW; batch selection PARTIAL
(repository ops exist; library multi-select UI not evidenced); import image/PDF FLOW; merge documents FLOW;
split/extract pages FLOW; PDF compression FLOW; PDF/JPG/TXT sharing FLOW.

P2: reusable signatures ENGINE (PROD-011); page annotation ENGINE (PROD-011); watermarks ENGINE (PROD-011);
password protection ENGINE (PROD-011: PIN registry + PDF standard security handler); page extraction FLOW
(PROD-008 split engine); long-image export PARTIAL (strip planner ENGINE — PROD-015; raster applier open);
batch scan NOT-IMPL; book workflow ENGINE (PROD-012 spread split planner; capture/review UI open);
ID-card workflow ENGINE (PROD-012 mode catalog + guidance geometry; capture-flow UI open);
business-card workflow ENGINE (PROD-012; capture-flow UI open).

P3: optional cloud account NOT-IN-SCOPE; sync NOT-IN-SCOPE; backup/restore ENGINE (PROD-015: manifest +
deterministic archive + restore planner; UI open); cross-device metadata NOT-IN-SCOPE;
Word/Excel/PPT conversion adapters PARTIAL (docx + xlsx text-level adapters ENGINE — PROD-015, layout parity
explicitly UNVERIFIED; pptx NOT-IMPL); collaboration NOT-IN-SCOPE; wireless print adapter PARTIAL (print job
planner ENGINE — PROD-015; Android print-framework glue open); fax adapter NOT-IN-SCOPE; translation
NOT-IN-SCOPE.

Standing honest-open surface (applies to every row above):
1. The parity ledger (lab/parity-ledger/ledger.json) remains the acceptance authority; entries S001-S018 are
   all UNKNOWN — no emulator-backed station has run the reference/evidence loop.
2. Instrumented/androidTest execution is deferred (emulator system image awaiting disk headroom).
3. Every ENGINE row needs its lead-owned UI wiring seam before end-user reachability.


## Current reference sources

- CamScanner Android product listing: https://play.google.com/store/apps/details?id=com.intsig.camscanner
- CamScanner product site: https://www.camscanner.com/
- CamScanner FAQ: https://www.camscanner.com/faq
- CamScanner developer scanning capabilities: https://dev.camscanner.com/
