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


## Current reference sources

- CamScanner Android product listing: https://play.google.com/store/apps/details?id=com.intsig.camscanner
- CamScanner product site: https://www.camscanner.com/
- CamScanner FAQ: https://www.camscanner.com/faq
- CamScanner developer scanning capabilities: https://dev.camscanner.com/
