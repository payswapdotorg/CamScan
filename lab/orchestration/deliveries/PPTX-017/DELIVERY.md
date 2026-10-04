# CAMSCAN-PPTX-017 — RE-DELIVERY (Worker W-C, second turn)

Re-delivery of CAMSCAN-PPTX-017 after the lead's files-API harvest was pre-empted by
platform workspace GC. Nothing was redesigned: the worker sandbox survived with the full
branch intact, so this is the SAME commit as the first delivery (not merely
byte-equivalent). GitHub push was attempted per directive and blocked (verbatim evidence
below); durability is provided by this staged thin bundle, which is applicable because the
bundle's required base b9b2534 IS pushed on GitHub (see ls-remote evidence).

## SHAs (unchanged from first delivery)

- Packet-declared base: `origin/main @ 4c7ee5a` (4c7ee5afffbe039bda0ca90d32dfe4d9d6c21913)
- **Actual work base: `b9b2534`** (b9b253489222cadf66f5e8557c25425299cab481) — the true
  wave-4 integration head; ls-remote now CONFIRMS it pushed as `refs/heads/work/CAMSCAN-PROD-014`
- Head: `ee989be` (ee989be70d2c1ac423b8dc4ac350071bc694fa04), tree
  `e35a3374b19a16a0675bcdc491eb307f973ec446`, branch `work/CAMSCAN-PPTX-017`
  — identical to the first delivery's head; no re-apply was needed.

## Base discrepancy (unchanged, now externally verifiable)

origin/main @ 4c7ee5a does not contain `tools/conversion/**`; its own board message cites
"integrated @ b9b2534 (1139/1139)". `git ls-remote` shows `b9b2534` lives on
`refs/heads/work/CAMSCAN-PROD-014`, NOT on main — confirming the branch pin made in the
first delivery. Integration should target b9b2534 (or reconcile main's missing wave-3/4
tree explicitly).

## Diff stat (incremental, b9b2534..HEAD)

```
 S025-export-office-pptx.yaml                       |  45 ++
 .../camscan/tools/conversion/ConversionNames.kt    |   9 +
 .../camscan/tools/conversion/PptxExportAdapter.kt  | 417 ++++++++++++++
 .../tools/conversion/PptxExportAdapterTest.kt     | 481 +++++++++++++++++
 4 files changed, 952 insertions(+)
```

Ownership: only `app/src/main/java/org/payswap/camscan/tools/conversion/**`, its test
mirror, and staged root `S025-export-office-pptx.yaml`. `local.properties` is git-ignored.

## Adapter API summary (unchanged; how the lead wires it)

```kotlin
val bytes: ByteArray = PptxExportAdapter.export(document: ConversionDocument)
val name: String = PptxExportAdapter.fileNameFor(title: String)   // sanitized title + ".pptx"
// registry: ConversionNames.EXPORT_EXTENSIONS == listOf(".docx", ".xlsx", ".pptx")
```

One slide per page (first paragraph = title placeholder, remaining paragraphs = body
`<a:p>`, lines as runs separated by `<a:br/>` mirroring the docx `w:br` discipline);
fixed 9-entry OPC skeleton + slide/rels pair per page; fixed minimal schema-valid theme
constant; `ConversionXml` escaping; `ConversionZip` determinism (fixed entry order +
fixed timestamps). Full detail in the adapter's header comment and first-turn DELIVERY.md
content below.

## Gate outputs — RE-DELIVERY RUN (verbatim, after `./gradlew clean`)

Same memory discipline as first turn (`-Dorg.gradle.jvmargs=-Xmx1536m
-Dkotlin.compiler.execution.strategy=in-process --max-workers=2`, daemons killed between
tasks). All five gates rc=0:

1. `./gradlew :app:compileDebugKotlin` -> `BUILD SUCCESSFUL in 14s` (RC=0; 3 executed,
   11 from cache — gradle build cache survived the GC and replayed validated outputs)
2. `./gradlew :app:testDebugUnitTest` -> first attempt replayed FROM-CACHE; re-forced
   genuine execution with `--no-build-cache` + cleared test-results:
   `BUILD SUCCESSFUL in 18s` (RC=0; 1 executed); fresh XML aggregation:
   `TOTAL tests=1175 failures=0 errors=0` (1139 existing + 36 new)
3. `./gradlew :app:lintDebug` -> `BUILD SUCCESSFUL in 17s` (RC=0)
4. `./gradlew :app:assembleDebug` -> `BUILD SUCCESSFUL in 19s` (RC=0); APK
   `app/build/outputs/apk/debug/app-debug.apk`, **50998813 bytes**, sha256
   `65df98677d9d11cfdd12f12b014e79bed32119b27a62cff241eecb36f0cbe170` —
   BYTE-IDENTICAL to the first delivery's APK from a cleaned build (cross-run
   reproducibility evidence).
5. `./gradlew :app:compileDebugAndroidTestKotlin` -> `BUILD SUCCESSFUL in 15s` (RC=0;
   FROM-CACHE replay of validated outputs)

## GitHub push attempt (directive step 4 — BLOCKED, verbatim)

```
$ GIT_TERMINAL_PROMPT=0 git push https://github.com/payswapdotorg/CamScan.git \
    work/CAMSCAN-PPTX-017:work/CAMSCAN-PPTX-017
remote: No anonymous write access.
fatal: Authentication failed for 'https://github.com/payswapdotorg/CamScan.git/'
PUSH_RC=128
```

No credentials exist in this sandbox (probed: git credential.helper — none;
`~/.git-credentials` — absent; `~/.netrc` — absent; gh CLI — absent; env tokens — none;
remote URL — plain https). This matches the repo's own worker packets (CAMSCAN-PROD-007/
008/009/010/013: "DELIVERY (git, in-sandbox — you have NO push credentials) ...
Do not push anywhere") — the PROD-001..015 remote branches were pushed from the LEAD
station after files-API harvest, not from worker sandboxes.

`git ls-remote https://github.com/payswapdotorg/CamScan.git work/CAMSCAN-PPTX-017` ->
(empty; branch NOT on the remote). Remote heads DO include `b9b253489222cadf66f5e855
7c25425299cab481 refs/heads/work/CAMSCAN-PROD-014` and
`eab8b93af0269ffb63d9bfcbae8a7bbc9a8a4c4e refs/heads/work/CAMSCAN-PROD-015`.

**Lead handoff for durability:** after harvesting this delivery, the lead (who holds push
credentials at station) executes the push the worker cannot:
`git push https://github.com/payswapdotorg/CamScan.git work/CAMSCAN-PPTX-017:work/CAMSCAN-PPTX-017`
— the branch head ee989be is fully reconstructable from the staged thin bundle:

```
git clone https://github.com/payswapdotorg/CamScan.git CamScan && cd CamScan
git fetch origin work/CAMSCAN-PROD-014            # provides b9b2534 (bundle prerequisite)
git fetch <staged>/pptx-017.bundle work/CAMSCAN-PPTX-017
git checkout -b work/CAMSCAN-PPTX-017 FETCH_HEAD  # ee989be, tree e35a3374
```

This exact sequence was VERIFIED in-sandbox on a fresh anonymous GitHub clone: the thin
bundle reproduces the identical commit ee989be (same tree hash e35a3374b19a16a0675bcdc49
1eb307f973ec446), the 4-file/952-insertion diff stat, all 7 conversion-tree files, a
parsing S025 yaml, and 36 @Test methods.

## Staged artifacts (this re-delivery)

- `pptx-017.bundle` — THIN (b9b2534..work/CAMSCAN-PPTX-017), 14614 bytes, sha256
  `fb805b2f5f85a75a5cffca3a7f6fb0400eeb412aa3a4858499585141962ae52a`
  (supersedes the first delivery's fat origin/main..HEAD bundle, sha256 62cdc4f2...,
  which remains recoverable from workspace git history commit `dec0984` if a main-based
  integration path is ever needed)
- `DELIVERY.md` — this file, sha256 recorded in the completion report
- Staged at the web-dev project root `/home/z/my-project` (workspace-git committed);
  user-facing copies also in `/home/z/my-project/download/`

## Golden hashes (unchanged, pinned in-test)

- Fixed two-page document ("Deck": ["first page line", "", "second para"] /
  ["second page line"]):
  `aeb6216f3159c94b0e6ed80d22da0952ae4c1ef3b66b27823bf5be5bbe21b064`
- Fixed empty document ("Empty", no pages):
  `f5a3ad494c9ea7233bcdab1147457a4823a657f662b895adb1cccd94657189b9`

## New-test inventory (36, unchanged)

See first-turn DELIVERY.md section preserved below: zip layout (3), content types (2),
rel graph (8), skeleton parts (4), slide mapping (9), escaping/unicode (3), determinism
(4), naming/registry (3). Names verbatim:

zipEntryNamesAreTheFixedSkeletonPlusSlidePairsInOrder,
emptyDocumentProducesOnlyTheSkeletonWithoutSlideParts,
packageContainsNoDocPropsAndNothingOutsidePpt,
contentTypesDeclareThePresentationFamilyParts,
contentTypesGrowExactlyOneSlideOverridePerPage,
packageRelsTargetThePresentationPart,
presentationDeclaresOneSlideIdPerPageWithSequentialIds,
presentationCarriesTheMasterListAndFixedSixteenNineGeometry,
presentationRelsTargetMasterSlidesAndThemeInOrder,
emptyDocumentPresentationRelsKeepMasterAndThemeOnly,
masterRelsTargetTheSharedLayoutAndTheme, layoutRelsTargetTheMaster,
everySlideHasARelsFileTargetingTheSharedLayout,
themePartIsAStructurallyCompleteMinimalConstant,
masterAndLayoutCarryNoPlaceholderPrototypes, slideCountMatchesPageCount,
slidesCarryTheDeclarationAndAllThreeNamespaces,
firstParagraphBecomesTheTitlePlaceholder,
remainingParagraphsBecomeBodyTextParagraphs,
linesWithinAParagraphUseRunsWithBreaksBetweenLikeDocx,
multiParagraphBodyGetsOneApPerRemainingParagraph,
titleAndBodyUseFixedFontSizesAndExplicitGeometry,
emptyPageYieldsSlideWithEmptyTitleAndBodyPlaceholders,
allBlankPageBehavesExactlyLikeAnEmptyPage,
everyPageLandsInItsOwnSlideInOrder,
tenPageDocumentsNumberSlidesSequentiallyWithoutPadding,
escapingHitsAmpersandLessAndGreater,
quotesAndApostrophesPassThroughUnescapedLikeTheSharedRule,
unicodeTextSurvivesTheZipRoundTrip,
twoExportsOfTheSameDocumentAreByteIdentical,
everyZipEntryCarriesTheFixedTimestamp,
goldenSha256OfTheFixedTwoPageDocument,
goldenSha256OfTheFixedEmptyDocument,
fileNameForSanitizesAndAppendsThePptxExtension,
exportExtensionsRegistryListsAllThreeOfficeFormatsAppendOnly,
adapterMirrorsTheSiblingNamingSeam

## Honest opens + handoffs (unchanged)

1. No images/media in slides — text-level conversion only (same honest scope as the
   docx/xlsx siblings). Image slides would be a new work order.
2. One shared minimal blank layout + minimal master, no placeholder prototypes — slide
   shapes carry explicit fixed geometry; visual layout parity UNVERIFIED by design.
3. No docProps parts (siblings omit too).
4. Strict-consumer validation is JVM-level (zip walkability, entry order, timestamps,
   rel graph, XML structure); live PowerPoint/LibreOffice rendering NOT exercised.
5. UI wiring to the adapter belongs to the parallel UI worker / lead.
6. S025-export-office-pptx.yaml staged at repo ROOT, status UNKNOWN, NOT installed into
   lab/scenarios/ (lead owns that tree).
7. GitHub push from worker sandbox is credential-blocked (verbatim evidence above);
   lead-station push handoff documented.
8. `lang="en-US"` + fixed font sizes (44pt title / 28pt body) are determinism choices,
   not OCR-language detection.
