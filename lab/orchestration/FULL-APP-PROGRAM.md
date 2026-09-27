# Full CamScan Product Implementation Program

Authority: Tech Lead operating contract.

## 1. Operating mode

The Tech Lead is the single orchestrator and integrator.

The lead owns:
- architecture lock;
- feature matrix;
- reference CamScanner oracle execution;
- scenario specification;
- parity ledger;
- work-order dispatch;
- integration and merge;
- acceptance/release gates.

Workers operate concurrently on disjoint implementation surfaces.

## 2. Three permanent worker tracks

### Worker 1 — Capture & Scan Engine

Owns:
~~~
app/**/capture/**
app/**/processing/**
app/**/core/model/**   # only inside agreed contract changes
app/**/core/image/**
~~~

Outcomes:
- camera lifecycle;
- document detection;
- stable quadrilateral tracking;
- capture;
- geometry;
- perspective correction;
- enhancement;
- scan session;
- retake/crop/rotate.

### Worker 2 — Document Workspace & Export

Owns:
~~~
app/**/document/**
app/**/library/**
app/**/export/**
app/**/settings/**
~~~

Outcomes:
- local document persistence;
- home/library;
- document viewer;
- page management;
- import;
- PDF/JPG export;
- sharing;
- merge/split/compress;
- navigation.

### Worker 3 — OCR, Tools & Verification

Owns:
~~~
app/**/ocr/**
app/**/tools/**
app/**/search/**
lab/reconciliation/**
tools/parity-cli/**
tools/evidence-cli/**   # only by explicit work order
~~~

Outcomes:
- OCR adapter;
- text indexing;
- search;
- signatures;
- annotations;
- watermarks;
- protection;
- specialist scan tools;
- reconciliation and gap automation.

Worker 3 never silently rewrites Worker 1 or Worker 2 product code.

## 3. Reference oracle

The reference CamScanner environment stays lead-controlled because the E2B credential remains lead-held.

For each product slice the lead archives:
- screenshots;
- UI hierarchy;
- action trace;
- package/device facts;
- outputs;
- environment/version manifest.

Workers receive evidence, not credentials.

## 4. Dispatch model

At each wave, issue three bounded packets:

~~~
W1: implementation slice
W2: independent implementation slice
W3: independent implementation slice + verification/reconciliation slice
~~~

No worker waits for another when ownership permits parallelism.

## 5. Shared contracts

Before incompatible domain structures appear, the lead updates:
- docs/PRODUCT-ARCHITECTURE-LOCK.md
- docs/SCAN-ENGINE-CONTRACT.md
- relevant executable scenarios

Shared model changes are contract-first and small.

## 6. Two nested loops

Product loop:
contract -> parallel implementation -> integration -> build/test

Parity loop:
reference observe -> executable scenario -> reference + implementation run -> evidence -> reconcile -> gap -> implement -> rerun -> accept

Do not wait for the entire reference application to be mapped before implementation begins. Build vertical slices, then reconcile each slice.

## 7. First three-worker campaign

### W1
Implement:
- camera permission;
- preview;
- live document detection;
- stable quad;
- manual capture;
- perspective correction;
- enhancement;
- review/retake/crop/rotate.

### W2
Implement:
- app navigation;
- home/library;
- document/page persistence;
- document viewer;
- PDF/JPG export;
- native sharing.

### W3
Implement:
- OCR adapter contract;
- deterministic OCR test harness;
- scan-session verification;
- product scan scenarios;
- reconciliation rules for scan outputs.

## 8. First product milestone

~~~
Home
 -> New Scan
 -> camera permission
 -> camera preview
 -> detection/guidance
 -> capture
 -> crop/perspective
 -> enhancement
 -> review
 -> save
 -> library
 -> reopen
 -> PDF/JPG export
 -> share
~~~

This journey must run on the implementation AVD and be compared with the current reference.

## 9. Worker completion

Every worker reports:

~~~
=== CAMSCAN-<WO-ID> COMPLETION REPORT ===
task: …
environment: …
what was implemented/observed: …
verification: commands + outputs (verbatim)
evidence: artifact list + sha256 + R2 keys
assumptions: …
open questions / handoffs: …
base sha: <40-hex>
~~~

The lead independently reruns Gradle build, tests, lint, validation and relevant interactive scenarios before merge.

## 10. Product definition of done

A feature is accepted only after:
- implementation exists;
- tests pass;
- interactive verification succeeds;
- reference behavior is observed when applicable;
- evidence is archived;
- reconciliation has no unresolved critical/high divergence;
- ledger says PASS.

## 11. Priority rule

P0 scan a document > P1 daily document workflow > P2 tools > P3 ecosystem.

Do not let P2/P3 block P0.

## 12. Final target

CamScan is complete only when it is a usable document application covering the majority of the capabilities represented in PRODUCT-FEATURE-MATRIX.md, with the scanner workflow production-grade and the parity ledger documenting what was actually observed, implemented and accepted.
