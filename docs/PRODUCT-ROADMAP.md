# CamScan Product Roadmap

The product program explicitly targets broad CamScanner-like functionality. The parity lab remains the verification mechanism.

## Roadmap graph

~~~
FOUNDATION
├── ✅ Android CLI-first runtime
├── ✅ E2B TCG provider + LabProvider contract
├── ✅ ADB semantic bridge
├── ✅ Evidence CLI
├── ✅ Parity CLI
├── ✅ Lab CLI
├── ✅ Reference driver path
└── ⬜ product contracts

P0 SCANNER
└── ⬜ camera permission
    ├── ⬜ CameraX preview
    ├── ⬜ frame quality gate
    ├── ⬜ live document detection
    ├── ⬜ stable quadrilateral tracking
    ├── ⬜ capture guidance / auto-capture
    ├── ⬜ still capture
    ├── ⬜ perspective correction
    ├── ⬜ enhancement
    ├── ⬜ page review
    ├── ⬜ crop / rotate
    ├── ⬜ retake
    ├── ⬜ document persistence
    ├── ⬜ multi-page
    ├── ⬜ PDF export
    ├── ⬜ JPG export
    └── ⬜ native sharing

P1 WORKSPACE
├── ⬜ Home/library
├── ⬜ thumbnails and metadata
├── ⬜ rename/reopen
├── ⬜ page management
├── ⬜ import image/PDF
├── ⬜ OCR
├── ⬜ OCR share/export
├── ⬜ search
├── ⬜ merge/split
└── ⬜ compress

P2 TOOLS
├── ⬜ signatures
├── ⬜ annotations
├── ⬜ watermarks
├── ⬜ protection
├── ⬜ ID-card workflow
├── ⬜ book workflow
└── ⬜ business-card workflow

P3 ECOSYSTEM
├── ⬜ conversion adapters
├── ⬜ optional cloud account
├── ⬜ sync
├── ⬜ backup/restore
├── ⬜ collaboration
├── ⬜ print adapter
└── ⬜ fax adapter
~~~

## Waves

### Wave 0 — contracts
Domain model, scanner interfaces, persistence contracts, navigation skeleton and test harness.

### Wave 1 — scanner vertical slice
Home -> New Scan -> Camera -> Detect -> Capture -> Crop/Perspective -> Enhance -> Review -> Save -> Library -> PDF/JPG -> Share.

### Wave 2 — scanner quality
Expand across clean A4, skew, receipt, business card, handwriting, low-light, motion, rotation and multi-page fixtures.

### Wave 3 — document workspace
Library, rename, reopen, page management, import, merge, split, compress and viewer.

### Wave 4 — intelligence
OCR, indexing, search and text export/share.

### Wave 5 — document tools
Signatures, annotations, watermarks, protection and specialist modes.

### Wave 6 — ecosystem
Conversion adapters, optional sync, backup/restore, collaboration, printing and fax.

### Wave 7 — hardening
Performance, recovery, migrations, offline mode, storage pressure, permissions, rotation, backgrounding, accessibility and release regression.

## Stop conditions

A worker reports a gap rather than guessing when:
- behavior cannot be deterministic under the current substrate;
- a dependency introduces a cloud requirement into the offline scan path;
- a change crosses another worker boundary;
- proprietary code/assets would be required;
- a parity discrepancy remains unexplained.

## Definition of done

A wave is complete only when:
1. implementation exists;
2. tests pass;
3. live implementation executes;
4. current reference behavior is observed or explicitly NOT OBSERVED;
5. evidence exists on both sides;
6. no unresolved critical/high divergence remains for the wave;
7. ledger status reflects the current truth.
