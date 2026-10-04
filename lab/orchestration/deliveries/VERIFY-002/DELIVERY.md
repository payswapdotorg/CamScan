# CAMSCAN-VERIFY-002 — DELIVERY

UI wiring II: export-side conversions + system seams + acceptance close.

- **Base:** `b23aa6e` (verified: `git log --oneline -1` shows the LocalEmulatorProvider capability record commit)
- **Branch:** `work/CAMSCAN-VERIFY-002`
- **Head:** `3cecae28d015bb5e2f4db011300b165f43efcc5f`
- **Bundle:** `v002.bundle` (range `b23aa6e..work/CAMSCAN-VERIFY-002`)
- **Bundle sha256:** `5fea25fa7788a65feab2e016c3b68c1ea3d64689c30178f57c5c2afdc8c85c67`

## Environment note (honest)

The work order states "JDK 21 + Android SDK 35 are available". The sandbox contained
JDK 21 but **no Android SDK**. I provisioned it honestly before any build:
command-line tools 11076708 + `platforms;android-35` + `build-tools;34.0.0` +
`platform-tools` installed via sdkmanager into `/home/z/android-sdk`, licenses
accepted, `local.properties` written (git-ignored). The Gradle 8.9 wrapper
distribution downloaded intact (verified: `.ok` marker + `--version` run). All
gradle invocations were run sequentially, `--no-daemon`.

## Base sanity gate (before any change)

`./gradlew --no-daemon testDebugUnitTest` → **1175/1175 PASS, 0 failures**
(XML-aggregated), matching the work order's expectation exactly.

## Gate battery (final, real numbers — all run after the last code change)

| Gate | Command | Result |
|---|---|---|
| compile | `./gradlew --no-daemon compileDebugKotlin` | **PASS** |
| unit | `./gradlew --no-daemon testDebugUnitTest` | **1247/1247 PASS** (1175 base + 72 new), 0 failures, 0 skipped |
| lint | `./gradlew --no-daemon lintDebug` | **PASS** (abortOnError=true) |
| assemble | `./gradlew --no-daemon assembleDebug` | **PASS**, apk_bytes = **51,195,182** |
| androidTestCompile | `./gradlew --no-daemon compileDebugAndroidTestKotlin` | **PASS** |

## Changed files (39 files, +4976 / −0 — every change purely additive)

| Path | +/− |
|---|---|
| app/src/main/java/org/payswap/camscan/MainActivity.kt | +8/−0 (additive factory cases + imports only) |
| app/src/main/java/org/payswap/camscan/export/DocumentExportFragment.kt | +405 |
| app/src/main/java/org/payswap/camscan/export/ExportArtifact.kt | +10 (additive MIME constants) |
| app/src/main/java/org/payswap/camscan/export/conversion/MlKitPageTextSource.kt | +68 |
| app/src/main/java/org/payswap/camscan/export/conversion/OfficeExportEngine.kt | +115 |
| app/src/main/java/org/payswap/camscan/export/conversion/PageTextSource.kt | +24 |
| app/src/main/java/org/payswap/camscan/export/longimage/AndroidStripRasterizer.kt | +114 |
| app/src/main/java/org/payswap/camscan/export/longimage/LongImageExportEngine.kt | +96 |
| app/src/main/java/org/payswap/camscan/export/longimage/StripRasterizer.kt | +31 |
| app/src/main/java/org/payswap/camscan/export/print/PrintGeometry.kt | +50 |
| app/src/main/java/org/payswap/camscan/export/print/PrintSheetRenderer.kt | +205 |
| app/src/main/java/org/payswap/camscan/export/print/RenderedPrintDocumentAdapter.kt | +106 |
| app/src/main/java/org/payswap/camscan/export/share/ShareIntents.kt | +87 (additive multi-share seam; existing members byte-identical) |
| app/src/main/java/org/payswap/camscan/library/BatchDocumentAdapter.kt | +125 |
| app/src/main/java/org/payswap/camscan/library/BatchSelectionFragment.kt | +273 |
| app/src/main/java/org/payswap/camscan/library/HomeFragment.kt | +34 (additive entries only) |
| app/src/main/java/org/payswap/camscan/settings/SettingsFragment.kt | +360 |
| app/src/main/java/org/payswap/camscan/settings/backup/AndroidBackupDestinations.kt | +74 |
| app/src/main/java/org/payswap/camscan/settings/backup/BackupCoordinator.kt | +157 |
| app/src/main/java/org/payswap/camscan/settings/backup/RestoreCoordinator.kt | +195 |
| app/src/main/java/org/payswap/camscan/tools/ui/BackupRestoreUi.kt | +118 |
| app/src/main/java/org/payswap/camscan/tools/ui/BatchSelectionUi.kt | +92 |
| app/src/main/java/org/payswap/camscan/tools/ui/ExportPickerUi.kt | +114 |
| app/src/main/java/org/payswap/camscan/tools/ui/PrintPickerUi.kt | +108 |
| app/src/main/res/layout/fragment_batch_selection.xml | +140 |
| app/src/main/res/layout/fragment_document_export.xml | +220 |
| app/src/main/res/layout/fragment_home.xml | +15 (two additive buttons) |
| app/src/main/res/layout/fragment_settings.xml | +106 |
| app/src/main/res/layout/item_batch_document_row.xml | +62 |
| app/src/main/res/values/strings.xml | +94 (APPEND-ONLY at file end; `app_name` untouched) |
| app/src/test/java/org/payswap/camscan/export/conversion/OfficeExportEngineTest.kt | +242 |
| app/src/test/java/org/payswap/camscan/export/longimage/LongImageExportEngineTest.kt | +231 |
| app/src/test/java/org/payswap/camscan/export/share/ShareIntentsMultipleTest.kt | +56 |
| app/src/test/java/org/payswap/camscan/settings/backup/BackupCoordinatorTest.kt | +186 |
| app/src/test/java/org/payswap/camscan/settings/backup/RestoreCoordinatorTest.kt | +212 |
| app/src/test/java/org/payswap/camscan/tools/ui/BackupRestoreUiTest.kt | +109 |
| app/src/test/java/org/payswap/camscan/tools/ui/BatchSelectionUiTest.kt | +118 |
| app/src/test/java/org/payswap/camscan/tools/ui/ExportPickerUiTest.kt | +107 |
| app/src/test/java/org/payswap/camscan/tools/ui/PrintPickerUiTest.kt | +109 |

Territory audit: modifications exist ONLY in `export/**`, `library/**`,
`settings/**` (new tree), `tools/ui/**` (new tree), `MainActivity.kt`
(additive factory cases), `res/layout/**`, `values/strings.xml`
(append-only) and `app/src/test/**` — exactly the ADD/MODIFY whitelist.
`local.properties` is git-ignored and untracked. No engine tree, no
`document/viewer/**`, no `androidTest/**`, no `lab/**`, no `docs/**`
was touched (`git status` at commit time verifies).

Merge-safety conventions honored:

- New `tools/ui/` files are named `ExportPickerUi`, `BatchSelectionUi`,
  `BackupRestoreUi`, `PrintPickerUi` — none collides with VERIFY-001's
  parallel set {SignaturePadUi, SignatureApplyUi, AnnotationEditorUi,
  WatermarkDraftUi, ViewerLockUi, PageFitGeometry}.
- `values/strings.xml` is append-only (all 94 new lines after `app_name`;
  zero modifications of existing entries). Note: the work order names
  `values/strings.xml`; that file holds only `app_name` (the app's real
  workspace string table lives in `strings_workspace.xml`, which I did
  NOT touch — keeping every VERIFY-002 string inside the literal
  whitelist file minimizes collision with VERIFY-001's appends).
- `MainActivity.kt`: additive `when` branches + imports only; no
  restructuring. All existing fragments' factory cases untouched.
- No existing layout was restructured: `fragment_home.xml` gained two
  buttons; all other layouts are new files.

## What was wired (per work-order task)

1. **PPTX conversion UI entry (flagship)** — `DocumentExportFragment`
   (new, `export/**`) offers "PowerPoint (.pptx)" per document,
   reachable from the library list's per-row Export action. Flow:
   `ExportPickerUi` format catalog → `OfficeExportEngine` → OCR text via
   `MlKitPageTextSource` (OcrEngineCatalog PREFER_LIVE, PROD-014's
   documented integration point) → `ConversionDocument` →
   `PptxExportAdapter.export` → ContentStore `exports/<docId>-<ts>-<n>p.pptx`
   → `ExportArtifact` → snackbar + share chooser (the PROD-007 PDF/JPG
   FLOW pattern, verbatim).
2. **DOCX/XLSX entries** — same pattern through the docx/xlsx adapters;
   the "text-level, layout not preserved" declaration is a VISIBLE
   caption in the picker (not a post-hoc toast).
3. **Long-image export** — `LongImageExportEngine`: decoded bounds →
   `LongImagePlanner.plan` (Center alignment, no separators) →
   `AndroidStripRasterizer` composes the strip via android.graphics →
   PNG → `exports/<docId>-<ts>-<n>p-long.png`. 90/270 rotations swap
   planned sides and rotate at draw time.
4. **Library batch selection** — `BatchSelectionFragment` (new) +
   `BatchDocumentAdapter` (own row layout; the shared
   `document/DocumentRowAdapter` was NOT modified): long-press enters
   selection with the row preselected; multi-select; wired batch actions
   = delete (repository op) + share-as-multiple (each selected document
   exported as PDF via `ExportEngine.exportPdf`, then ONE
   ACTION_SEND_MULTIPLE share sheet through the new additive
   `ShareIntents.shareArtifacts`); move-to-folder and tag render
   visibly DISABLED with "(pending)" labels. Reached from Home's new
   "Select documents" entry.
5. **Backup/restore UI** — `SettingsFragment` (new `settings/**` tree;
   none existed): "Back up now" builds the manifest + deterministic
   archive through `BackupCoordinator` (per-document
   `documents/<id>/index.json` via `IndexJsonCodec` + page image
   members, sha256-verified by the engine on read), writes to a
   system-picker destination (`CreateDocument("application/zip")`),
   falling back to app-external-files with the path SHOWN. "Restore"
   picks an archive → `BackupArchiveReader` (full sha256 verification) →
   `RestorePlanner.planFromManifest` under NewerWins →
   `RestoreCoordinator.apply` into the LIVE repository (public
   `upsertDocument` API; page images re-put through the ContentStore) →
   visible per-document summary (added/replaced/kept/failed). No
   scheduling UI (the engine has none — nothing fakes it).
6. **Wireless print glue** — from the same document export surface:
   `PrintPickerUi` (A4/Letter, Fit/Fill/Actual) → `PrintRequest` →
   `PrintSheetRenderer` (bounds → `PrintGeometry` mm at the documented
   assumed 300 dpi → `PrintJobPlanner.plan` → 150-dpi sheet bitmaps at
   the planned placement rects → JPEG → `PdfWriter` PDF) →
   `RenderedPrintDocumentAdapter` (PrintDocumentAdapter serving the
   pre-rendered bytes) → `PrintManager.print`. Render runs on
   Dispatchers.IO with an honest "Working…" state.
7. **Factory wiring + strings** — three additive constructor-injected
   factory cases in `WorkspaceFragmentFactory`; per-instance state via
   arguments bundles (`ARG_DOCUMENT_ID`); strings append-only.

## New test inventory (72 tests, all JVM-side)

| Test class | n | Coverage |
|---|---|---|
| `tools.ui.ExportPickerUiTest` | 9 | format catalog order/ids/extensions vs `ConversionNames.EXPORT_EXTENSIONS`, honesty flags, busy guard, outcome recording |
| `tools.ui.BatchSelectionUiTest` | 10 | long-press entry, toggling, pruning vanished ids, action gates, pending-action catalog |
| `tools.ui.BackupRestoreUiTest` | 9 | backup/restore phase machine, one-operation guard, structured Done/Blocked/Failed phases |
| `tools.ui.PrintPickerUiTest` | 8 | picker defaults/selection guards, request building, planner integration, `PrintGeometry` mm math + rotation swap |
| `export.conversion.OfficeExportEngineTest` | 11 | pptx/docx/xlsx adapter-output equality, key vocabulary, index-order OCR feed, honest nulls, determinism |
| `export.longimage.LongImageExportEngineTest` | 8 | planner-driven strip geometry (incl. 90° side swap), rasterizer contract, key vocabulary, honest nulls, determinism |
| `export.share.ShareIntentsMultipleTest` | 5 | ACTION_SEND_MULTIPLE constant, pure multi-spec construction + defensive copy, flag mapping |
| `settings.backup.BackupCoordinatorTest` | 6 | empty-library answer, documented archive layout verified through the delivered reader, skipped-page honesty, determinism, manifest stamp |
| `settings.backup.RestoreCoordinatorTest` | 6 | full round-trip into a second repository, NewerWins both directions, mixed add/keep, unreadable-archive nulls |

## Honest residual seams (declared, not papered over)

| # | Seam | Status |
|---|---|---|
| 1 | Office conversions are TEXT-LEVEL: paragraph/row structure only; layout/fonts/images are not preserved; parity vs the reference converters is UNVERIFIED (carried from the engine declarations; the picker shows the declaration). | open (engine-side by design) |
| 2 | Long-image raster composition uses android.graphics.Canvas in UI glue — the delivered DrawSurface render seam exposes no scaled-bitmap blit op, so a deterministic JVM-side raster applier remains an engine-side seam; geometry is 100% planner-driven. | open (engine-side) |
| 3 | Restore executes planner decisions into the live repository through `RestoreCoordinator`; the delivered `RestoreApply` file-tree applier is not used for the live store (the live index is one global index.json + ContentStore refs, not the archive's per-document file tree). | integration decision, declared |
| 4 | Backups contain document indexes + processed page images only; source captures and OCR text are NOT archived (no persisted OCR results exist in the app today); restored pages lose `sourceCaptureRef`. Shown in the Settings scope note. | open (scope) |
| 5 | Print page millimetres derive from pixel dimensions at a DOCUMENTED assumed 300 dpi; pages captured at other densities print proportionally off under Actual-size (Fit/Fill unaffected). | assumption, documented |
| 6 | Parity ledger S001–S018 and instrumented androidTest execution remain lead-station domains (untouched per the work order). | open (lead-station) |
| 7 | The document viewer's own export bar (PDF/JPG/compress) was NOT extended (document/viewer/** is read-only for this order); the new conversion entries live on the new export surface reached from the library list. | boundary decision |
| 8 | ML Kit OCR availability governs Office conversions at runtime; on devices where the live engine cannot construct, conversion fails with the honest "text recognition failed or is unavailable" message (no fake success). | runtime-bound, honest failure path |

## Reproduce

```bash
git clone <bundle-fetched repo>; cd camscan-verify-002
git checkout work/CAMSCAN-VERIFY-002   # head 3cecae2
./gradlew --no-daemon testDebugUnitTest        # 1247/1247
./gradlew --no-daemon lintDebug                # PASS
./gradlew --no-daemon assembleDebug            # PASS, 51,195,182 bytes
./gradlew --no-daemon compileDebugAndroidTestKotlin  # PASS
```
