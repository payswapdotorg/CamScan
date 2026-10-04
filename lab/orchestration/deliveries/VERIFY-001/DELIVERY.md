# CAMSCAN-VERIFY-001 — Delivery (W2: document-workspace-and-export track)

Work order: UI wiring I — P2 viewer tools + protection gating.
Base: b9b2534 · Branch: work/CAMSCAN-VERIFY-001 · Head: 0945ef63fae9984bec1642d1368ae3a81928efdd
Bundle: v001.bundle (b9b2534..work/CAMSCAN-VERIFY-001)
Bundle sha256: 5b6ef3442c7d787ed03b8b8b5c72c9b8df64c8e72d78f25141b0c5e8a47b3ba0

## 1. What was delivered

The P2 tool engines (signature, annotation, watermark, protection, render seam)
are now user-reachable from the document viewer:

1. **Viewer tools row** in DocumentViewerFragment — five entries: Sign pad,
   Apply signature, Annotate, Watermark, Protect (stable semantic ids
   viewer_tool_*; page-bound entries disabled honestly on empty documents).
2. **Signature pad** (SignaturePadFragment + SignaturePadCanvasView):
   full-screen overlay collecting touch strokes through the pure
   SignaturePadUi controller into the frozen SignaturePadReducer; strokes
   render live; save flows through SignatureStore; empty-stroke save is
   rejected with a visible message; invalid names rejected with a message.
3. **Apply signature** (SignatureApplyFragment + SignaturePlacementView):
   picks the stored signature (dialog when several; none stored → visible
   hint + deep-link button to the pad); drag-to-place feeds the pure
   SignatureApplyUi which resolves the drop into the frozen
   SignatureApplier placement vocabulary (anchor corner + width fraction +
   margin, least-squares best reachable box); Smaller/Larger step the size
   ladder; the live preview is rendered BY THE ENGINE on a proportional
   preview buffer, so preview and result cannot drift; Apply runs
   SignatureApplier on the full-resolution raster and persists through
   DocumentRepository (page survives viewer reopen).
4. **Annotate** (AnnotationEditorFragment + AnnotationSurfaceView): the three
   engine models drafted live — Ink (finger draw), Highlight (rect drag),
   TextNote (tap + text dialog); edits go through AnnotationStore (one add
   per committed annotation) and AnnotationApplier (applyPlan over the page
   raster); the engine-composited preview refreshes at gesture end while a
   Canvas live layer covers in-flight gestures; Undo removes the last draft;
   Apply bakes the raster through the repository.
5. **Watermark** (WatermarkComposerFragment): text + diagonal-vs-tile +
   strength compose the WatermarkSpec through the pure WatermarkDraftUi;
   live preview is the engine output (WatermarkPlanner + WatermarkApplier on
   the full page raster, downscaled for display); Apply persists the raster.
   Blank text / zero strength → Apply visibly disabled with a visible reason.
6. **Protect** (DocumentViewerFragment): PIN protection round-trip — set PIN
   (twice-entry confirmation via the pure PinSetFlow, 4–8 digits, mismatch
   and length errors visible); the registry gets a SecureRandom-backed salt
   source in MainActivity; after setting, THIS viewer locks immediately and
   shows the unlock overlay; opening a protected document shows the unlock
   screen (PinProtectionRegistry.verify); wrong PIN shows a visible error and
   reveals nothing; unlock state survives rotation (onSaveInstanceState
   snapshot/restore of the instance-scoped ViewerGate); re-lock on leaving
   the viewer (pop destroys the instance without saving state); removing
   protection requires the current PIN.
7. **Factory wiring**: WorkspaceFragmentFactory gained additive constructor
   params (signatureStore, annotationStore) and four additive when branches
   (SignaturePadFragment, SignatureApplyFragment, AnnotationEditorFragment,
   WatermarkComposerFragment); per-instance state (document id, page id)
   travels via the arguments bundle, matching the existing pattern.
8. **Strings**: 62 new entries appended to values/strings.xml (existing
   entries untouched; generic dialog strings reused where they match).

Design decisions worth review:
- **ViewerToolHost** (process-scoped object in tools/ui): the READ-ONLY
  HomeFragment constructs DocumentViewerFragment directly with the two
  legacy constructor parameters, so the viewer cannot take new required
  constructor params without breaking a read-only file. The four NEW
  fragments use proper constructor injection via the factory; only the
  viewer reads the process-scoped host.
- **Edited rasters bake display rotation**: tools decode the page with the
  viewer's rotation applied, edit in that space (WYSIWYG), persist the new
  raster and reset page rotationDegrees to 0 (RepositoryToolOps.
  replacePageRaster). Non-destructive discipline preserved: sourceCaptureRef
  untouched.
- **RC4/PdfEncryptor**: NOT reachable from this order's territory (export
  surface is out of scope) — the reachable part (PIN gating + policy state)
  is wired; the encryption seam is declared below.

## 2. Changed files (27: 4 modified, 23 new; +4284 / −235)

| Status | Path | +/− |
|---|---|---|
| M | app/src/main/java/org/payswap/camscan/MainActivity.kt | +47/−1 |
| M | app/src/main/java/org/payswap/camscan/document/viewer/DocumentViewerFragment.kt | +250/−1 |
| M | app/src/main/res/layout/fragment_document_viewer.xml | +364/−233 |
| M | app/src/main/res/values/strings.xml | +109/−0 (append-only) |
| A | app/src/main/java/org/payswap/camscan/document/persistence/RepositoryToolOps.kt | +44 |
| A | app/src/main/java/org/payswap/camscan/document/viewer/AnnotationEditorFragment.kt | +477 |
| A | app/src/main/java/org/payswap/camscan/document/viewer/PageRasterBridge.kt | +96 |
| A | app/src/main/java/org/payswap/camscan/document/viewer/SignatureApplyFragment.kt | +439 |
| A | app/src/main/java/org/payswap/camscan/document/viewer/SignaturePadFragment.kt | +181 |
| A | app/src/main/java/org/payswap/camscan/document/viewer/WatermarkComposerFragment.kt | +238 |
| A | app/src/main/java/org/payswap/camscan/tools/ui/AnnotationEditorUi.kt | +204 |
| A | app/src/main/java/org/payswap/camscan/tools/ui/PageFitGeometry.kt | +74 |
| A | app/src/main/java/org/payswap/camscan/tools/ui/SignatureApplyUi.kt | +192 |
| A | app/src/main/java/org/payswap/camscan/tools/ui/SignaturePadUi.kt | +68 |
| A | app/src/main/java/org/payswap/camscan/tools/ui/ViewerLockUi.kt | +140 |
| A | app/src/main/java/org/payswap/camscan/tools/ui/WatermarkDraftUi.kt | +54 |
| A | app/src/main/res/layout/fragment_annotation_editor.xml | +114 |
| A | app/src/main/res/layout/fragment_signature_apply.xml | +126 |
| A | app/src/main/res/layout/fragment_signature_pad.xml | +92 |
| A | app/src/main/res/layout/fragment_watermark_composer.xml | +134 |
| A | app/src/test/java/org/payswap/camscan/document/persistence/RepositoryToolOpsTest.kt | +119 |
| A | app/src/test/java/org/payswap/camscan/tools/ui/AnnotationEditorUiTest.kt | +179 |
| A | app/src/test/java/org/payswap/camscan/tools/ui/PageFitGeometryTest.kt | +63 |
| A | app/src/test/java/org/payswap/camscan/tools/ui/SignatureApplyUiTest.kt | +155 |
| A | app/src/test/java/org/payswap/camscan/tools/ui/SignaturePadUiTest.kt | +99 |
| A | app/src/test/java/org/payswap/camscan/tools/ui/ViewerLockUiTest.kt | +155 |
| A | app/src/test/java/org/payswap/camscan/tools/ui/WatermarkDraftUiTest.kt | +71 |

Territory audit: NO file under core/, capture/, processing/, ocr/, imports/,
export/, tools/{signature,annotation,watermark,protection,render,modes,backup,
conversion,printops,exportops}, androidTest, lab/, tools/parity-cli/, docs/
was modified (git status verified). Engines consumed via public APIs only.

## 3. Gate battery (all run sequentially, --no-daemon, final code state)

| Gate | Command | Result |
|---|---|---|
| compile | ./gradlew --no-daemon compileDebugKotlin | **PASS** |
| unit | ./gradlew --no-daemon testDebugUnitTest | **PASS — 1198/1198** (1139 existing + 59 new; 0 failures, 0 errors, 0 skipped) |
| lint | ./gradlew --no-daemon lintDebug | **PASS** — 0 errors, 70 warnings (abortOnError=true; warnings are the pre-existing class, e.g. ExifInterface, plus 3 minor DrawAllocation/ClickableView notes that remain) |
| assemble | ./gradlew --no-daemon assembleDebug | **PASS — apk_bytes=51,112,492** |
| androidTestCompile | ./gradlew --no-daemon compileDebugAndroidTestKotlin | **PASS** |

Base sanity gate before any change: 1139/1139 PASS.

## 4. New-test inventory (59 tests, all green)

| Class | Tests | Coverage |
|---|---|---|
| tools/ui PageFitGeometryTest | 6 | fit-center math, letterboxing, view↔page mapping, degenerate dims |
| tools/ui SignaturePadUiTest | 8 | stroke capture through the frozen reducer, dot taps, mid-stroke save, thinning, clear, name validation |
| tools/ui SignatureApplyUiTest | 9 | size ladder clamps, drop→(anchor,margin) resolution per corner, least-squares margin, previewRect page bounds + engine-agreement (applier ink lands inside previewRect ± brush), default placement, ink box scaling |
| tools/ui AnnotationEditorUiTest | 11 | ink gestures, accumulation order, page clamping, highlight normalize/clamp/tap-discard, text anchor+confirm+blank rejection, undo, draft preview plan (in-flight ink + draft rect), commit plan equals engine extension, anchor clear |
| tools/ui WatermarkDraftUiTest | 5 | diagonal/tile rotation, strength→opacity rounding, clamping, spec fields + trimming, apply gating |
| tools/ui ViewerLockUiTest (ViewerGate 9 + PinSetFlow 6) | 15 | lock states, wrong-PIN stay-locked, unlock, rotation snapshot/restore, fresh-instance re-lock, relock, protection removal, full round-trip against the frozen registry; PIN entry rules 4–8 digits, non-digit rejection, mismatch, Ready→register→verify |
| document/persistence RepositoryToolOpsTest | 5 | replacePageRaster ref swap + rotation zeroing + stamps, other pages untouched, unknown doc/page null, empty ref rejected, non-destructive sourceCaptureRef |

## 5. Seams table (honest residuals)

| # | Seam | Status |
|---|---|---|
| S1 | RC4 PDF encryption (PdfEncryptor 40/128-bit, PdfPermissions) applies only at PDF EXPORT time; the export surface is outside this order's territory. Wired here: PIN gating + DocumentProtectionPolicy state. Residual: export-time /Encrypt injection into PdfWriter output. | declared gap (lead engine-amendment/territory decision) |
| S2 | SignatureStore is the pure in-memory engine core (engine docs declare persistence wiring a later handoff); stored signatures live for the process lifetime only — no durable signature library across app restarts. Apply flow deep-links to the pad when empty (honest reachable behavior). | declared gap |
| S3 | PinProtectionRegistry exposes toSerializableForm/fromSerializableForm but no durable registry storage is wired in this order; protection state is process-scoped (after process death the document opens unlocked until re-protected). | declared gap |
| S4 | AnnotationStore contents are session-scoped; applied annotations are baked durably into the page raster, but annotation history is not separately persisted across process death. | declared gap |
| S5 | Watermark applies to the CURRENT page (the page the preview showed); whole-document watermarking is a follow-up. | scoped decision |
| S6 | Signature apply live preview runs on a proportional ≤720px buffer (fraction-based placements are resolution-independent; ±1px rounding vs the full-res apply). Watermark/annotation previews render the full-resolution engine output downscaled for display. | approximation, engine-authoritative apply |
| S7 | Live in-flight ink strokes during a gesture draw via anti-aliased Android Canvas until the engine-composited preview refreshes at gesture end; the applied raster is always the engine's deterministic output. | UX-only approximation |
| S8 | Raster edits operate on whole page buffers in memory (≈2× IntArray); no tiled pipeline for pathological (>12MP) pages. | capacity note |

## 6. Reproduction

~~~bash
git clone v001.bundle camscan-verify-001-clone
cd camscan-verify-001-clone
git checkout work/CAMSCAN-VERIFY-001
./gradlew --no-daemon testDebugUnitTest   # 1198/1198
~~~

Environment note: the Android SDK had to be provisioned in this sandbox
(cmdline-tools + platforms;android-35 + build-tools;34.0.0 under
/home/z/android-sdk, local.properties gitignored) before the gates could
run; no repo build files were changed for it.
