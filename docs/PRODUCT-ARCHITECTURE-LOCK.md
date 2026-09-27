# CamScan Product Architecture Lock

Status: ACTIVE / implementation source of truth
Date: 2026-09-27
Mission: Build an independently implemented Android document application capable of most observable CamScanner document workflows, with a first-class document scan as the highest priority.

## 1. Product goal

CamScan is now both a parity laboratory and a product implementation program.

The primary journey is:

capture document -> detect page -> guide/accept capture -> crop + perspective-correct -> enhance -> review/edit -> save -> organize -> export/share

The first release-quality capability is end-to-end document scanning. Later capabilities expand the same durable document model.

Current public CamScanner materials describe document scanning with automatic cropping/enhancement, OCR, PDF/JPG export and sharing, multi-page scanning, editing, signatures, watermarks, annotations, merge/split/compression, search/document management and synchronization. This repository implements those capability families independently; the reference app is an oracle of observable behavior, never an implementation dependency.

## 2. Independence / clean-room rule

CamScan may use public documentation, observable behavior, synthetic fixtures, and independently implemented open-source libraries and algorithms.

CamScan must not:
- copy proprietary CamScanner source code;
- extract or ship proprietary CamScanner assets;
- depend on CamScanner internals;
- decompile the reference application as an implementation shortcut;
- claim parity from screenshots alone.

## 3. Runtime architecture

Keep the first product in one Android application module. Organize code by bounded capability/package so three workers can work concurrently without unnecessary Gradle-module overhead.

~~~
org.payswap.camscan
├── core/
│   ├── model/          Document, Page, ScanSession, SearchRecord
│   ├── repository/     domain persistence contracts
│   ├── storage/        file/object storage contracts
│   └── time/           deterministic time seams
├── capture/
│   ├── camera/         CameraX lifecycle + preview
│   ├── detection/      live document/quad detection
│   ├── guidance/       stability and framing guidance
│   └── capture/        still-image acquisition
├── processing/
│   ├── geometry/       quad ordering + perspective transform
│   ├── enhancement/    scan enhancement transforms
│   └── quality/        blur/exposure/glare/edge quality
├── document/
│   ├── session/        scan session/page workflow
│   ├── editor/         crop/rotate/reorder/delete/retake
│   └── library/        local document browser
├── export/
│   ├── pdf/            PDF construction
│   ├── image/          JPG/PNG export
│   └── share/          Android share/export intents
├── ocr/
│   ├── engine/         OCR interface + provider adapter
│   └── document/       text indexing / export
├── tools/
│   ├── signature/
│   ├── annotation/
│   ├── watermark/
│   ├── protection/
│   └── conversion/
└── settings/
~~~

Workers own disjoint subtrees. Shared contracts are defined first and changed deliberately.

## 4. Canonical document model

Every scan becomes a durable Document containing ordered Pages.

A Page retains:
- stable page id;
- original capture reference;
- processed image reference;
- crop/perspective metadata when available;
- enhancement mode;
- rotation;
- created/updated timestamps;
- optional OCR result reference.

A Document retains:
- stable document id;
- ordered page ids;
- title/name;
- created/updated timestamps;
- source/type metadata;
- searchable OCR index reference when available.

Editing is non-destructive. Source captures remain available for reprocessing until explicitly deleted.

## 5. Capture and processing pipeline

~~~
CameraX preview
  -> frame quality gate
  -> document detector
  -> quad scoring + temporal stabilization
  -> user guidance / optional auto-capture
  -> still capture
  -> quad refinement
  -> perspective transform
  -> enhancement
  -> page preview/editor
  -> document persistence
  -> PDF/JPG/OCR/share
~~~

Detection, geometry, enhancement and OCR are interfaces so algorithms can evolve without rewriting the domain/UI.

The initial implementation may use OpenCV or equivalent independently implemented/open-source image processing.

## 6. Camera requirements

The scan screen must support:
- live camera preview;
- camera permission flow;
- document framing guidance;
- live document boundary overlay;
- manual capture;
- reference-driven auto-capture where observed;
- flash control when supported;
- front/back camera selection where relevant;
- stable capture under modest camera motion;
- graceful no-document / low-quality states.

Exact labels, control ordering, onboarding, timing and edge states come from reference scenarios rather than guesses.

## 7. Geometry requirements

Support:
- four-corner document quadrilateral detection;
- corner ordering;
- perspective correction;
- rotation;
- manual crop correction;
- arbitrary aspect ratios;
- portrait/landscape pages;
- page replacement/retake.

## 8. Enhancement requirements

Support an enhancement abstraction with independently testable transforms.

Initial modes:
- original;
- grayscale;
- black and white;
- contrast/clarity;
- sharpening;
- low-light cleanup.

Reference discovery determines exact product naming and additional modes.

Processing must be deterministic for the same input and settings.

## 9. Persistence

Use a local-first architecture:
- structured metadata in Room/SQLite or equivalent;
- assets in app-private storage;
- content hashes for large derived artifacts where useful;
- no account required for core scanning;
- migrations preserve existing local documents.

Cloud/account features sit above the local domain layer.

## 10. Export

First-class local export:
- PDF;
- JPG/image;
- text after OCR.

PDF tests are semantic: file opens, page count/order/orientation are correct and content is stable for fixed fixtures.

## 11. OCR

OCR is an adapter behind a stable interface. Prefer an on-device engine for the first implementation.

OCR never blocks the primary scan path:
scan/save works without OCR; OCR/indexing may run after save.

## 12. UI architecture

Use the existing Android stack unless a worker demonstrates a concrete reason to change it. Android Studio is never a runtime requirement.

Primary surfaces:

~~~
Home / Library
  - New Scan
  - Import
  - Search
  - Recent documents
  - Tools

Scan
  - Camera preview
  - capture/guidance
  - page session

Page Editor
  - crop
  - rotate
  - enhance
  - retake/delete
  - accept

Document Viewer
  - page navigation/reorder
  - OCR
  - export/share
  - document tools
~~~

Semantic controls must have stable ids/content descriptions so ADB-driven parity tests remain robust.

## 13. Offline-first principle

Core scanning, local editing, local persistence and PDF/JPG export must work without an account.

Network-dependent features are adapters:
- sync;
- cloud backup;
- online OCR fallback;
- document conversion services;
- collaboration.

No cloud dependency may enter the critical scan path.

## 14. Scanner quality gates

A scanner feature is not complete until:
1. unit tests cover its processing contracts;
2. synthetic fixtures exercise the algorithm;
3. implementation executes on the lab AVD;
4. reference behavior is observed where applicable;
5. evidence exists on both sides;
6. Worker 3 reconciles;
7. the lead records PASS only when evidence supports it.

Core regression corpus:
clean A4, skewed document, receipt, business card, handwritten page, low-light page, 3-page sequence, document-pan and camera-motion.

## 15. Architectural no-drift rules

- Android CLI is the worker runtime; Android Studio is optional human tooling.
- Core scan is local-first.
- Document data is durable and non-destructive.
- Capture/processing/OCR/export use replaceable interfaces.
- UI controls have stable semantic identifiers.
- Reference CamScanner is an oracle, never an implementation dependency.
- Provider-specific behavior stays behind LabProvider.
- Evidence and ledger are the acceptance authority.
