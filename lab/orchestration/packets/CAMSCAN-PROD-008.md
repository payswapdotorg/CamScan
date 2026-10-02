You are Worker 2 (Document Workspace & Export track) for CamScan, dispatched by the Tech Lead through the agents console. Work autonomously until CAMSCAN-PROD-008 is implemented, tested, and fully reported. This packet is fully self-contained — you cannot see the Tech Lead's context, and no other worker can see yours.

> **EXECUTION DIRECTIVE (binding, platform lesson):**
> do NOT stop to acknowledge this packet. Your FIRST action is §3 setup; your
> LAST action is the §9 final report. Execute end-to-end in THIS turn:
> §3 setup -> §6 implement -> §7 checks -> §8 commit+bundle -> §9 report as your
> reply. No acks, no status updates, no clarifying questions. If genuinely
> blocked, the report states the blocker honestly with everything you did
> complete. If your turn is interrupted mid-work, the next directive resumes
> from your persisted sandbox state.

=== CAMSCAN-PROD-008 — IMPORT, MERGE/SPLIT & PDF COMPRESSION (Worker 2) ===

# 1. ROLE

You are Worker 2 on a permanent three-worker product program building **CamScan** — an independent, clean-room
Android document-scanning application (applicationId `org.payswap.camscan`); the public CamScanner app is the
observable-behavior oracle, NEVER an implementation dependency. You own the **document workspace surface**:
persistence, viewer, export — and NOW the P1 daily workflow: image/PDF import, merge, split/extract, PDF
compression. The shared contracts in §4 are frozen and lead-owned; report needed changes as open questions
instead of making them.

# 2. PROGRAM CONTEXT

- Repository: https://github.com/payswapdotorg/CamScan.git (public, anonymous clone works). After cloning, READ
    `docs/PRODUCT-ARCHITECTURE-LOCK.md` (§4 model, §9 persistence, §13 offline-first),
    `docs/SCAN-ENGINE-CONTRACT.md` (Multi-page + Export), `lab/orchestration/PRODUCT-WORK-BOARD.yaml`.
- **Wave 1 (the P0 scanner vertical slice) is DELIVERED and gate-GREEN** (PROD-013 integration: 311/311 JVM tests,
    APK assembled, after the lead's station repair): W1's capture/processing, your
    shell/library/persistence/viewer/export, and W3's OCR seam are on the integrated line. Wave 2 = the daily
    workflow; its integration gate is CAMSCAN-PROD-014; this order is the wave-2 W2 slice. P1 outranks P2/P3.
- Offline-first: no network, no account, no cloud. Import reads content the user picks locally; nothing leaves the
    device.

# 3. ENVIRONMENT SETUP (run exactly, in order)

```bash
git clone https://github.com/payswapdotorg/CamScan.git CamScan
cd CamScan
git fetch origin work/CAMSCAN-PROD-006
git checkout -b work/CAMSCAN-PROD-008 3e448ce31bb9d3db6fefc2580d81853a111b3303
git config user.name "CamScan Worker 2"
git config user.email "worker2@camscan.invalid"
git rev-parse HEAD   # MUST print 3e448ce31bb9d3db6fefc2580d81853a111b3303
ls app/src/main/java/org/payswap/camscan/document/   # your PROD-005/006 files
ls app/src/main/java/org/payswap/camscan/export/ 2>/dev/null   # PROD-007 tree — see note
```

(3e448ce31bb9d3db6fefc2580d81853a111b3303 is pinned in the dispatch message header — the lead confirms it here:
BASE_SHA: 3e448ce31bb9d3db6fefc2580d81853a111b3303)

Base note: this is the PROD-006 head — the SAME base convention as PROD-007, which branched from it and has
landed on that line. If PROD-007's `export/**` tree is absent at your checkout, the LEAD rebases
work/CAMSCAN-PROD-008 onto the integrated PROD-007 head: build §6.5's components as self-contained additions
inside `export/` that merge cleanly under that rebase; never hand-copy PROD-007's files; record the observed
base state honestly in §9.

# 4. SHARED CONTRACT LAYER (lead-owned, frozen — consume read-only)

Code against the REAL signatures at your base — never remembered or imagined ones (gate-1 charged 32 compile
errors against invented shapes; that must not recur):

- `core/model/Documents.kt` — Document / Page with `DocumentSource { SCAN, IMPORTED }` (IMPORTED exists for THIS
    order); Page.documentId is single-valued — a page belongs to exactly one document.
    `core/repository/DocumentRepository.kt` — observeDocuments(), getDocument(id), getPages(documentId),
    upsertDocument(document, pages) — TWO arguments, no single-argument variant — deleteDocument(id).
    `core/storage/ContentStore.kt` — put(key, bytes) -> ref, open(ref), delete(ref), exists(ref): EVERY binary
    (imported originals, rendered PDF pages, compressed artifacts) goes THROUGH the store.
- `core/time/TimeSource.kt` (`TimeSource.SYSTEM`, nowMillis()), `core/navigation/ScanEntry.kt`,
    `gradle/libs.versions.toml`, `app/build.gradle.kts`, `AndroidManifest.xml`.
- W1's `capture/**` / `processing/**` and W3's `ocr/**`: other workers' trees — CONSUME read-only via constructor
    injection where §6.1 calls for it; never modify.

Do not modify any of them. Report needs as open questions.

# 5. YOUR OWNERSHIP BOUNDARY (binding)

You MAY create/modify:
- `app/src/main/java/org/payswap/camscan/imports/**` (new tree — this order's import surface; the lead records the
    W2 board-path amendment with this dispatch. NOTE: the tree is `imports`, NOT `import` — `import` is a Kotlin
    hard keyword and would force backtick-escaped package lines in every file, which is transit-fragile.)
- `document/**` (extend with `document/merge/` + `document/split/`), `library/**` (import/merge affordances),
    `export/**` (compression — §6.5)
- `app/src/test/java/org/payswap/camscan/{imports,document,library,export}/**`;
    `app/src/androidTest/java/org/payswap/camscan/**` (instrumentation skeletons)
- your res files: new `layout/fragment_*.xml` / `item_*.xml`, additions to `values/strings_workspace.xml` (your
    file)

You MUST NOT touch: the §4 contract layer — anything under `core/**`, `capture/**`, `processing/**`, `ocr/**`,
`tools/**`, `search/**` (consume at most, read-only); `AndroidManifest.xml`, `res/values/strings.xml`,
`res/values/themes.xml`, `activity_main.xml` ids, `MainActivity.kt` (fragment-registered result launchers keep
you inside your own files); anything outside `app/`.

# 6. TASK — IMPORT, MERGE/SPLIT, PDF COMPRESSION

1. **Image import** (`imports/`): `ImportIntents` — pure intent-construction seam, JVM-testable (the PROD-007
      ShareIntents pattern): ACTION_OPEN_DOCUMENT constructor (mime image/* + application/pdf, single pick) +
      documented ACTION_GET_CONTENT fallback. `ImportEngine` — bytes through an injected `UriReader` seam (android
      adapter = ContentResolver.openInputStream + display-name query; JVM tests fake it), bounds through an
      injected `BoundsDecoder` (inJustDecodeBounds); documented caps MAX_IMPORT_BYTES = 25 MB and MAX_IMPORT_PIXELS
      = 40,000,000; honest sealed `ImportFailure` taxonomy (values, not exceptions): EMPTY_SELECTION,
      URI_UNREADABLE, IMAGE_UNDECODABLE, IMAGE_TOO_LARGE echoing the violating numbers, plus the PDF failures
      below. Routing through the EXISTING capture pipeline seam: constructor-injected `ImportPagePipeline` (an
      interface you define in imports/) whose DEFAULT implementation passes the image through unchanged — the
      android adapter MAY delegate detection/enhancement to W1's delivered seams read-only; where a seam shape at
      your base does not fit, keep the default pipeline + open question; NEVER edit capture/ or processing/. Page
      field semantics IDENTICAL to camera captures: sourceCaptureRef = the imported original (non-destructive),
      processedImageRef = the page image, cropQuad = null unless the pipeline supplies one, enhancement = ORIGINAL
      unless overridden. Flows: single-page -> NEW Document (sourceType IMPORTED; title rule: picker display name
      when obtainable, else "Imported " + UTC timestamp via an injectable fixed-pattern formatter);
      append-to-existing -> Page(s) at indices priorCount..n, updatedAtMillis bumped, via upsertDocument(document,
      pages). Bytes THROUGH ContentStore (key vocabulary `imports/<ts>-<id>.img` /
      `imports/<ts>-pdf<id>-p<idx>.jpg` — yours, documented, deterministic under injected ids/time); ids via
      IdGenerator; time via TimeSource.
2. **PDF import, bounded** (`imports/pdf/`): `PdfImporter` on `android.graphics.pdf.PdfRenderer` (platform, API 26+
      — NO new dependency): page count; each page rendered at a documented bounded scale (144 dpi-equivalent =
      scale 2.0 over page points, reduced to fit a 40MP-per-page cap), JPEG-encoded (quality 85, ctor-configurable
      — reuse PROD-007's PageJpegEncoder if present, else a thin equivalent), stored THROUGH ContentStore; one Page
      per PDF page in order, in a new IMPORTED document. Bounded honestly: MAX_PDF_IMPORT_PAGES = 50 (beyond ->
      PDF_TOO_MANY_PAGES(found, cap)); unopenable/encrypted -> PDF_UNREADABLE; per-page render failure ->
      PDF_PAGE_RENDER_FAILED(pageIndex) — all-or-nothing: NO partial document is upserted. Determinism limits
      documented loudly: PdfRenderer output is PLATFORM-RENDERED (varies across Android versions/devices); the
      PIPELINE is deterministic given fixed rendered bytes + injected ids/time, but rendered bytes are not
      cross-version stable — JVM tests inject fake rendered bytes; platform rendering is verified
      lead-station-only.
3. **Merge** (`document/merge/DocumentMergeEngine.kt`): PURE Kotlin core
      `merge(documents, pagesByDocument, mode, idGenerator, timeSource): MergePlan` — a value object (merged
      Document + re-minted Pages with coherent pageIds + the ordered repository call plan); a thin executor applies
      it. Order law: pages in source-document order, stable within each source; indices 0..N-1; >= 2 sources
      required (fewer -> IllegalArgumentException per the documented API contract, mirroring PROD-007's PdfWriter
      malformed-input rule). Title rule (documented): first source title + " + " + (n-1) + " more". Ids: page ids
      ALWAYS re-minted, page.documentId = the fresh merged id (the frozen model requires it: single-valued
      documentId; unique ids keep deletion safe); sourceType = SCAN only when EVERY source is SCAN, else IMPORTED
      (provenance rule). ContentStore refs (the documented choice): DEFAULT mode CONSUMES the sources — refs
      PRESERVED, not byte-copied; executor order = upsert merged FIRST, then delete sources — PROD-006's orphan
      sweep deletes only refs unreferenced by the CURRENT index, so preserved refs survive, and the order is
      load-bearing (reversed, the deletes sweep refs the merged document is about to record). COPY mode retains the
      sources: every ref re-put under a new key BEFORE the merged upsert, so a later source deletion can never
      sweep a live ref. Both modes in KDoc + tests; verify consume-mode against your real
      PersistentDocumentRepository.
4. **Split/extract** (`document/split/DocumentSplitEngine.kt`): PURE core
      `extract(document, pages, selectedPageIds, idGenerator, timeSource): SplitPlan` — new Document (fresh id;
      title = source title + " (extracted)"; coherent pageIds), selected pages re-minted (new ids, documentId = new
      id, indices 0..M-1 in SOURCE order); the complement stays in the source, indices compacted 0..K-1, relative
      order preserved, updatedAtMillis bumped. Semantics: extract = MOVE (delete-pages-with-extract) — selected
      pages LEAVE the source; plain delete stays PROD-006's document_page_delete. Refs preserved, ownership
      TRANSFERS: upsert the extracted document FIRST, then the compacted source — reversed, the source's sweep
      deletes the transferred refs; the order is load-bearing — test it. Empty selection or unknown page ids ->
      IllegalArgumentException (documented); ALL pages selectable (source becomes an honest empty document —
      PROD-006's empty state + delete affordance apply).
5. **PDF compression** (`export/compress/`): `CompressPdfOptions(quality = 70, maxLongEdgePx = 1600)` — defaults
      documented; quality clamped 0..100; never upscale. `DownsamplePlanner` (pure): per-page target dims + the
      skip rule (a page already within target passes its bytes through, recorded honestly). `ImageResampler`
      interface + android `BitmapResampler` adapter (decode -> sample -> re-encode JPEG); decision logic JVM-side,
      Bitmap ops thin. `CompressedPdfWriter`: caller supplies per-page JPEG bytes + dims + rotations; resamples
      each page; assembles the final PDF THROUGH PROD-007's PdfWriter (constructor-injected as a `PdfAssembler`
      functional interface you define — one adapter line joins the real writer; page order/rotation identical to
      normal export); returns PDF bytes + report. Size reporting HONEST and OUTSIDE the frozen model:
      `CompressionReport(originalBytes, compressedBytes, pageCount, per-page before/after)` +
      `CompressedExportResult(artifact: ExportArtifact, report: CompressionReport)` — ExportArtifact itself
      UNCHANGED (PROD-007's delivered shape: ref, mime, displayName, sizeBytes, pageCount; sizeBytes reports the
      COMPRESSED size, the report carries the delta); NO core/model edits; compressedBytes MAY exceed originalBytes
      for already-small inputs — NEVER silently fall back; report the truth. If PROD-007's export tree is absent at
      your exact base: ship all components above (they are standalone); the PdfAssembler adapter line + viewer
      wiring wait for the lead's rebase — the compress button falls back to an honest "compression pending
      export-layer integration" message (documented, PROD-007's share-fallback pattern); §9 lists the handoff.
      Artifacts THROUGH ContentStore (key vocabulary `exports/<docId>-<ts>-<n>p-c.pdf`).
6. **UI wiring minimal** (your trees): Library — `library_import_button` (system picker via ImportIntents;
      ImportEngine with progress + honest failure snackbars from the taxonomy); `library_merge_button` (merge
      selection mode); `library_merge_confirm_button` (enabled only with >= 2 selected) +
      `library_merge_cancel_button`. Viewer — `document_merge_button` (merge THIS document with others — routes
      into library selection mode with this document preselected); `document_append_import_button` (imported images
      appended to THIS document); `document_split_button` (page-selection mode); `document_split_confirm_button`
      (extract the selected pages into a new document) + `document_split_cancel_button`; `export_compress_button`
      (compressed PDF export in the PROD-007 export row; snackbar "original X -> compressed Y" from
      CompressionReport, or the failure reason — honest states only). All NEW ids stable, contentDescriptions from
      `strings_workspace.xml`; ALL prior ids (PROD-005/006/007 sets) unchanged.
7. **JVM tests** (`app/src/test/java/org/payswap/camscan/`): ImportEngine (fake
      UriReader/BoundsDecoder/store/ids/time) — full error taxonomy with echoed values; single-page new-document
      flow; append index continuity; IMPORTED sourceType; title rule; key vocabulary + ref recording. PdfImporter
      (fake rendered bytes) — page count -> Page count/order; cap -> PDF_TOO_MANY_PAGES; mid-render failure ->
      all-or-nothing (no upsert). Merge — order law across 3 mixed documents; stability within each source; title
      rule; id re-minting (no id shared with any source page); consume-mode -> zero store puts; copy-mode -> puts
      for every ref; executor call order (upsert BEFORE deletes); >=2 sources contract. Split — subset extraction +
      complement compaction; re-minted ids; zero store puts (refs preserved/transferred); title rule;
      empty/all/unknown-id contracts; executor call order (extracted upsert BEFORE compacted source). Compression —
      planner math (target, clamp, never-upscale, skip rule); report truthfulness (bytes echoed from a fake
      resampler); CompressedExportResult metadata; end-to-end byte-determinism (fixed fake resampler bytes -> the
      same PDF built twice is byte-identical). Instrumentation skeletons (lead-station-only annotations):
      `ImportFlowTest.kt`, `MergeSplitFlowTest.kt`, `CompressExportFlowTest.kt` — the exact ids above exist and the
      affordances open.

**Stable semantic ids (NEW):** `library_import_button`, `library_merge_button`, `library_merge_confirm_button`,
`library_merge_cancel_button`, `document_merge_button`, `document_append_import_button`,
`document_split_button`, `document_split_confirm_button`, `document_split_cancel_button`,
`export_compress_button`. All prior ids unchanged.

**Discipline:** Kotlin 2.0, JVM 17, minSdk 26; NO new dependencies (PdfRenderer is platform API 26+);
deterministic pure cores + thin android adapters; ids via IdGenerator, time via TimeSource; full Gradle gate if
the toolchain is warm.

**TRANSIT-CORRUPTION PROTOCOL (binding, new 2026-09-28):** the chat renderer that
carries your delivered code corrupts it in transit — it eats dollar-sign characters
in string templates (math-delimiter rendering), halves double backslashes, mangles
multi-line KDoc closers, and can eat closing braces. The gate-2 forensics proved this
on the raw transcripts. Therefore, in EVERY file you deliver:
- Use plain string CONCATENATION instead of interpolated templates wherever a
  template would appear — build strings with the `+` operator and explicit
  `.toString()` calls. NO dollar signs inside any delivered string literal.
- Prefer single-line KDoc (`/** Text. */`) or `//` comments; avoid multi-line
  `/** ... */` blocks whose closers can be mangled.
- Avoid double-backslash escape sequences where concatenation of explicit
  characters works.
The lead station-repairs any residual damage and the gate re-verifies — but code
delivered per this protocol survives transit verbatim.

# 7. VERIFICATION (honest, bounded)

Full Gradle gate if the toolchain is warm
(`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin`) —
verbatim summaries; static self-checks always (clean tree, `git diff --stat` review, ownership audit against §5,
stable-id grep audit, XML parse, brace balance); toolchain repair budget ~20 min. NEVER fabricate output.

# 8. DELIVERY (git, in-sandbox — you have NO push credentials)

```bash
cd CamScan
git add -A
git commit -m "CAMSCAN-PROD-008: import, merge/split, PDF compression — ImportEngine (image + PdfRenderer), DocumentMergeEngine, DocumentSplitEngine, CompressedPdfWriter + honest size reports on the wave-1 base"
git bundle create camscan-prod-008.bundle 3e448ce31bb9d3db6fefc2580d81853a111b3303..work/CAMSCAN-PROD-008
cp camscan-prod-008.bundle ../camscan-prod-008.bundle
sha256sum camscan-prod-008.bundle
git diff --stat 3e448ce31bb9d3db6fefc2580d81853a111b3303..work/CAMSCAN-PROD-008
```

Also write `CamScan/DELIVERY-PROD-008.txt` (every added/changed file path + the bundle sha256) and copy it + the
bundle to the workspace root. Your sandbox files ARE the delivery channel — the Tech Lead harvests them (pod
files-API or an inline-transit order). Do not delete your work. Do not push anywhere.

# 9. FINAL REPORT (your last chat message — report ONLY, no file dumps)

```
=== CAMSCAN-PROD-008 COMPLETION REPORT ===
task: image/PDF import, merge, split/extract, PDF compression
environment: sandbox OS + toolchain actually available
what was implemented/observed: …
verification: commands + outputs (verbatim — never invented)
evidence: artifact list + sha256 (bundle path, DELIVERY-PROD-008.txt) — no raw bytes
assumptions: …
open questions / handoffs: … (MUST include: observed base state re the PROD-007 export
tree; any W1 seam wiring left on the default pipeline; the note that the import tree
is `imports/` per §5)
base sha: 3e448ce31bb9d3db6fefc2580d81853a111b3303
```

Status vocabulary is binding: `implemented` ≠ `verified` ≠ `reconciled` ≠ `accepted`.
Report exactly what exists. A green self-review is not a pass.
