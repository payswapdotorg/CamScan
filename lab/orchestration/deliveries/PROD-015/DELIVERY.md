CAMSCAN-PROD-015 DELIVERY — Worker 3 (OCR, Tools & Verification track) — FINAL WAVE 4
================================================================================

base sha : f278bbccc227068dfa62228d89208e3a72433463 (work/CAMSCAN-PROD-014 integration head)
head sha : eab8b93af0269ffb63d9bfcbae8a7bbc9a8a4c4e (work/CAMSCAN-PROD-015)
bundle   : camscan-prod-015.bundle
sha256   : 7380b416db7164e09358e847d1b1300e0d9ec5682e5638304e753454a19acfaa
diffstat : 40 files changed, 4910 insertions(+), 0 deletions (no existing file modified)
tests    : 252 new @Test methods in the four new subtrees; full suite 1139/1139 green

ADDED FILES (all under §5-authorized paths; nothing else touched)
------------------------------------------------------------------
MAIN — app/src/main/java/org/payswap/camscan/tools/backup/
  BackupArchive.kt        (sealed BackupArchiveError.Corrupt/Missing, BackupArchiveContents,
                           deterministic BackupArchiveWriter [manifest.txt first, manifest order,
                           FIXED_EPOCH_MILLIS=946684800000 zip timestamps], BackupArchiveReader
                           [full sha256 verification per member])
  BackupEntry.kt          (BackupEntry + BackupEntryKind; pipe-separated line form, forbidden-char
                           validation, byte-identical round-trip)
  BackupFileIo.kt         (BackupFileIo seam: write/read/list + BackupPaths layout conventions)
  BackupManifest.kt       (schema v1, createdAtMillis via injected TimeSource, appVersionHint,
                           line-based form: header/entries/N lines/end footer)
  RestoreApply.kt         (applies ONLY what the plan authorizes via the same IO seam;
                           Conflicts writes nothing; overwrite detection via list())
  RestorePlanner.kt       (pure planner: RestorePolicy KeepExisting/KeepIncoming/NewerWins,
                           sealed RestorePlan Planned/Conflicts, deterministic id-asc order,
                           tie/duplicate conflicts carried never silently resolved)
  Sha256Text.kt           (SHA-256 lowercase-hex hashing + 64-hex validation)

MAIN — app/src/main/java/org/payswap/camscan/tools/conversion/
  ConversionDocument.kt   (pure model + blank-line-delimited paragraph segmentation rule)
  ConversionNames.kt      (documented 3-rule file-name sanitizer; default "document")
  ConversionXml.kt        (minimal escaping: & < > only)
  ConversionZip.kt        (deterministic zip: fixed order + FIXED_EPOCH_MILLIS timestamps)
  DocxExportAdapter.kt    (minimal WordprocessingML: [Content_Types].xml, _rels/.rels,
                           word/document.xml; one w:p per paragraph, one w:r per line,
                           w:br type=page strictly BETWEEN pages, none trailing)
  XlsxExportAdapter.kt    (minimal SpreadsheetML: 5-part package; one sheet, one row per OCR line,
                           first cell inlineStr col A, 1-based sequential r indexes)

MAIN — app/src/main/java/org/payswap/camscan/tools/printops/
  Orientation.kt          (sealed Portrait/Landscape + documented swap rule -> OrientedPaper)
  PageRanges.kt           (strict parser: "all" | "3" | "1-3,5"; trimmed tokens; duplicates kept;
                           ascending output; out-of-range/invalid -> null)
  PaperSize.kt            (exact-mm catalog: paper-a3/a4/a5/letter/legal/ledger + byId lookup)
  PrintJobPlanner.kt      (pure planner, never throws; documented content-rect math for
                           Fit contain / Fill cover / Actual 100% centered; mm+points+px views)
  PrintMargins.kt         (per-side mm margins; finite+non-negative validity)
  PrintModels.kt          (PrintPage/PrintRequest/rects/PrintPlacement/PrintJobPlan +
                           sealed PrintPlanError + PrintResult)
  PrintScaleMode.kt       (sealed Fit/Fill/Actual)
  PrintUnits.kt           (mm->points = mm*72/25.4; roundHalfUp ties-away-from-zero)

MAIN — app/src/main/java/org/payswap/camscan/tools/exportops/
  LongImageModels.kt      (LongImagePage, HorizontalAlignment LEFT/CENTER/RIGHT, SeamPolicy
                           NONE/Separator 1..8, placements, plan, sealed errors/result)
  LongImagePlanner.kt     (pure geometry: target width = max page width, per-page Double scale,
                           cumulative y-cursor, seams strictly between pages, half-up at .5)

TESTS — app/src/test/java/org/payswap/camscan/tools/
  backup/InMemoryBackupIo.kt      (IO fake)
  backup/BackupEntryTest.kt       (24 @Test)
  backup/BackupManifestTest.kt    (24 @Test)
  backup/BackupArchiveTest.kt     (21 @Test)
  backup/RestorePlannerTest.kt    (20 @Test)
  backup/RestoreApplyTest.kt      (11 @Test)
  conversion/ConversionMappingTest.kt  (9 @Test)
  conversion/ConversionNamesTest.kt    (17 @Test incl. ConversionXml)
  conversion/DocxExportAdapterTest.kt  (15 @Test)
  conversion/XlsxExportAdapterTest.kt  (15 @Test)
  printops/PaperAndUnitsTest.kt        (21 @Test: paper/orientation/margins/units)
  printops/PageRangesTest.kt           (28 @Test)
  printops/PrintJobPlannerTest.kt      (27 @Test)
  exportops/LongImagePlannerTest.kt    (20 @Test)
  TOTAL: 252 @Test, zero `import android.` under the four subtrees (main + test)

SCENARIOS — lab/scenarios/ (DSL v0.1, S004 shape, status UNKNOWN, ledger is truth)
  S022-local-backup-restore.yaml   (camera_fixture: false; owner: lead)
  S023-export-office-docx.yaml     (camera_fixture: false; owner: lead)
  S024-print-document.yaml         (camera_fixture: false; owner: lead)

ENVIRONMENT (honest)
--------------------
Sandbox: Debian Linux, OpenJDK 21 (JDK 17 source level per build config), network available.
No Android SDK was preinstalled; provisioned within the toolchain-repair budget:
cmdline-tools + platforms;android-35 + build-tools;35.0.0 into /home/z/android-sdk,
sdk.dir written to the gitignored local.properties (environment-only, not part of the commit).
Gradle wrapper + AGP resolved from network.

VERIFICATION TRANSCRIPT (verbatim summaries; commands run at repo root)
-----------------------------------------------------------------------
1. git rev-parse HEAD (after checkout -b work/CAMSCAN-PROD-015 f278bbc...)
   -> f278bbccc227068dfa62228d89208e3a72433463
2. python3 -c "import json; d=json.load(open('lab/parity-ledger/ledger.json')); print(len(d['entries']))"
   -> 18
3. ./gradlew :app:testDebugUnitTest --console=plain
   -> BUILD SUCCESSFUL; JUnit XML aggregation: TOTAL_TESTS=1139 FAILURES=0 SKIPPED=0
      (887 base suite + 252 new; base gate was 887/887 at f278bbc)
4. ./gradlew :app:lintDebug :app:assembleDebug :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --console=plain
   -> BUILD SUCCESSFUL in 3m
5. Static checks (grep at final tree):
   - new-subtree @Test count: 252 (>= 100 required)
   - `import android` under the four subtrees (main+test): 0
   - dollar signs in the four subtrees + 3 yaml: 0 (transit protocol)
   - double-backslash sequences: 0 (transit protocol)
   - multi-line KDoc closers: 0 (all KDoc single-line; transit protocol)
   - git status: only the four subtrees + 3 scenarios + this DELIVERY file; ZERO existing files modified
6. Scenario yaml parse (twice, python yaml, incl. typed meta.requires field verification):
   -> local-backup-restore 2400 False / export-office-docx 2400 False / print-document 2400 False
      -> YAML-PARSE-1-OK, YAML-PARSE-2-OK (typed fields verified)
7. python3 tools/parity-cli/main.py reconcile
   -> reconcile: 24 scenarios, 18 ledger entries
      validation errors: 0
      scenario without entry: 6   (S019, S020, S021 pre-existing wave-3 gaps + S022, S023, S024 new — HONEST: the ledger has no entries for the new scenarios and only the lead may add them)
      entry without scenario: 0
      status disagreements: 0
   Committed lab/reconciliation reports were re-generated by the run and then RESTORED to
   their committed wave-3 state (git checkout -- lab/reconciliation/) per the work order —
   the lead regenerates them at integration.

COMPILE ITERATIONS (honest — three fix rounds before green)
1. BackupArchive.kt: nullable ZipEntry type annotation -> smart-cast fix (elvis break).
2. RestoreDecision sealed hierarchy: added abstract val documentId so base-typed usages compile.
3. Test-side authoring bugs: two sanitizer expectations missing trailing hyphens (behavior was
   correct per documented rules), one copyIndex expectation off-by-one, SeamPolicy.Separator
   promoted to data class for structural equality in the determinism test.

HONEST SCOPE (binding statements)
---------------------------------
- Conversion adapters are TEXT-LEVEL (paragraph/row structure only), NOT layout/fonts/images;
  parity vs the reference converters is explicitly UNVERIFIED.
- Long-image export is GEOMETRY ONLY; raster composition (bitmap stitching) is the lead-owned
  applier seam.
- Print is PLANNING ONLY; Android print-framework glue + real-printer verification are
  lead-station / out-of-substrate.
- Cloud account / sync / collaboration / fax / translation are OUT OF SCOPE by program decision
  (offline-first clean-room mandate); no code touches them.
- Scenarios S022-S024 stay status UNKNOWN — the ledger is truth and has no entries; workers
  never declare parity acceptance.
