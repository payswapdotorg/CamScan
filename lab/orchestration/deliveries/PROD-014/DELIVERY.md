# CAMSCAN-PROD-014 delivery record (local integration phase)

- status: INTEGRATION GATE GREEN (local JVM/APK surface) 2026-09-29T01:2xZ — the parity/instrumented surface REMAINS OPEN (honest): the androidTest suites (Export/Import/MergeSplit/Compress flows + MlKitOcrEngineSmokeTest) are compile-verified but need an emulator-backed station run; the P1 parity loop vs the reference app needs the campaign's run dirs
- branch: work/CAMSCAN-PROD-014
- head: 283a05a31ca731daeaa50f68c105ffb31910726d
- merge: 104e99e (work/CAMSCAN-PROD-010 @ a818c7d into the PROD-008 line @ 97546c1 — CLEAN additive merge: the mlkit/catalog trees + the ML Kit catalog lines; zero conflicts; frozen contracts byte-stable through the merge)
- integration content: the COMPLETE P1 daily-workflow line on one tree — P0 scanner chain (capture/detect/process/session) + shell/library/persistence/viewer + export/share (PROD-007) + import/merge/split/compress (PROD-008) + live ML Kit OCR binding behind the frozen seam (PROD-010) + the planted smoke test
- verification (lead local): testDebugUnitTest 480/480 (448 + 32 PROD-010 tests, zero overlap); lintDebug; assembleDebug (app-debug.apk 50.9MB — bundled ML Kit model + 4-ABI pipeline; ABI strategy deferred to the hardening wave); compileDebugAndroidTestKotlin (incl. the smoke test)
- deferred to the emulator-backed station (honest): MlKitOcrEngineSmokeTest execution (box-coordinate-space assumption settles there; fix point MlKitMapper.normalizeBox); Import/MergeSplit/Compress/Export instrumentation flows; OcrEngineCatalog MainActivity wiring point (documented: OcrEngineCatalog.default(TimeSource.SYSTEM).create(PREFER_LIVE), worker-dispatcher recognize, scope-owned close — the UI affordance itself lands with PROD-011/012)
- next: wave-3 (PROD-011 signature/annotation/watermark/protection tools; PROD-012 specialist scan modes + reconciliation automation) + the parity campaign continues for S002/S003 evidence
