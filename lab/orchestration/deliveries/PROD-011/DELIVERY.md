CAMSCAN-PROD-011 DELIVERY — advanced tools (signature / annotation / watermark / protection)
Worker 3 (OCR, Tools & Verification track)
Branch: work/CAMSCAN-PROD-011
Base:  283a05a31ca731daeaa50f68c105ffb31910726d (wave-2 integration head, CAMSCAN-PROD-014)
Head:  2dda0ea26a367834eea41b3e0b10597948ef9deb
Bundle: camscan-prod-011.bundle
Bundle sha256: 6ff6e4a49fc00764afd5d24ad1e0dd26eb4daca088d0dc8e203986e94e9d8290
Diff stat vs base: 36 files changed, 6128 insertions(+), 0 deletions(-)

=== OWNERSHIP AUDIT (section 5) ===
All 36 added files live under app/src/main/java/org/payswap/camscan/tools/** and
app/src/test/java/org/payswap/camscan/tools/** ONLY. Zero tracked files modified
(git diff --name-only HEAD is empty before the commit). No file outside section 5
was touched. tools/modes/** was NOT created (sibling worker's tree).

=== ADDED FILES (main tree, 21) ===
app/src/main/java/org/payswap/camscan/tools/render/DrawGeometry.kt
app/src/main/java/org/payswap/camscan/tools/render/DrawPlan.kt
app/src/main/java/org/payswap/camscan/tools/render/DrawSurface.kt
app/src/main/java/org/payswap/camscan/tools/render/TextGlyphSource.kt
app/src/main/java/org/payswap/camscan/tools/signature/SignatureModel.kt
app/src/main/java/org/payswap/camscan/tools/signature/SignaturePadReducer.kt
app/src/main/java/org/payswap/camscan/tools/signature/SignatureStore.kt
app/src/main/java/org/payswap/camscan/tools/signature/SignatureApplier.kt
app/src/main/java/org/payswap/camscan/tools/annotation/Annotation.kt
app/src/main/java/org/payswap/camscan/tools/annotation/AnnotationStore.kt
app/src/main/java/org/payswap/camscan/tools/annotation/AnnotationApplier.kt
app/src/main/java/org/payswap/camscan/tools/watermark/WatermarkSpec.kt
app/src/main/java/org/payswap/camscan/tools/watermark/WatermarkPlanner.kt
app/src/main/java/org/payswap/camscan/tools/watermark/WatermarkApplier.kt
app/src/main/java/org/payswap/camscan/tools/protection/DocumentProtectionPolicy.kt
app/src/main/java/org/payswap/camscan/tools/protection/PinProtectionRegistry.kt
app/src/main/java/org/payswap/camscan/tools/protection/PdfPermissions.kt
app/src/main/java/org/payswap/camscan/tools/protection/Rc4.kt
app/src/main/java/org/payswap/camscan/tools/protection/StandardSecurity.kt
app/src/main/java/org/payswap/camscan/tools/protection/PdfWriterLayout.kt
app/src/main/java/org/payswap/camscan/tools/protection/PdfEncryptor.kt

=== ADDED FILES (test tree, 15) ===
app/src/test/java/org/payswap/camscan/tools/FakeTimeSource.kt
app/src/test/java/org/payswap/camscan/tools/render/DrawPlanTest.kt
app/src/test/java/org/payswap/camscan/tools/render/DrawSurfaceTest.kt
app/src/test/java/org/payswap/camscan/tools/render/TextGlyphSourceTest.kt
app/src/test/java/org/payswap/camscan/tools/signature/SignaturePadReducerTest.kt
app/src/test/java/org/payswap/camscan/tools/signature/SignatureSketchTest.kt  (sketch + store test classes)
app/src/test/java/org/payswap/camscan/tools/signature/SignatureApplierTest.kt
app/src/test/java/org/payswap/camscan/tools/annotation/AnnotationStoreTest.kt
app/src/test/java/org/payswap/camscan/tools/annotation/AnnotationRenderTest.kt
app/src/test/java/org/payswap/camscan/tools/watermark/WatermarkPlannerTest.kt
app/src/test/java/org/payswap/camscan/tools/watermark/WatermarkApplierTest.kt
app/src/test/java/org/payswap/camscan/tools/protection/PinProtectionRegistryTest.kt
app/src/test/java/org/payswap/camscan/tools/protection/Rc4Test.kt
app/src/test/java/org/payswap/camscan/tools/protection/StandardSecurityTest.kt
app/src/test/java/org/payswap/camscan/tools/protection/PdfEncryptorTest.kt

=== @Test COUNT PER TREE (requirement: >= 120 total) ===
render:     76 @Test  (DrawPlanTest 16, DrawSurfaceTest 41, TextGlyphSourceTest 19)
signature:  48 @Test  (Reducer 18, Sketch/Store 17, Applier 13)
annotation: 29 @Test  (Store 19, Render 10)
watermark:  29 @Test  (Planner 18, Applier 11)
protection: 94 @Test  (Registry 19, Rc4 10, StandardSecurity 30, PdfEncryptor 35)
TOTAL NEW: 276 @Test methods (plus the pre-existing 480 on the base = 756 suite total)

=== HONEST VERIFICATION TRANSCRIPT (run in-sandbox, verbatim) ===
Environment: Debian sandbox, OpenJDK 21.0.12.1 (Gradle daemon JVM), Gradle 8.9
via the repository wrapper, Android SDK cmdline-tools + platforms;android-35 +
build-tools;35.0.0 installed in-sandbox (network available; no SDK pre-existed).
local.properties (gitignored) points at the in-sandbox SDK.

$ git rev-parse HEAD (base checkout)
283a05a31ca731daeaa50f68c105ffb31910726d

$ ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin
> Task :app:lintDebug
> Task :app:compileDebugAndroidTestKotlin
BUILD SUCCESSFUL in 1m 15s
58 actionable tasks: 11 executed, 47 up-to-date

testDebugUnitTest aggregate (from app/build/test-results/testDebugUnitTest/TEST-*.xml):
TOTAL tests: 756, failures+errors: 0
  tools.annotation.AnnotationRenderTest tests=10 failures=0
  tools.annotation.AnnotationStoreTest tests=19 failures=0
  tools.protection.PdfEncryptorTest tests=35 failures=0
  tools.protection.PinProtectionRegistryTest tests=19 failures=0
  tools.protection.Rc4Test tests=10 failures=0
  tools.protection.StandardSecurityTest tests=30 failures=0
  tools.render.DrawPlanTest tests=16 failures=0
  tools.render.DrawSurfaceTest tests=41 failures=0
  tools.render.TextGlyphSourceTest tests=19 failures=0
  tools.signature.SignatureApplierTest tests=13 failures=0
  tools.signature.SignaturePadReducerTest tests=18 failures=0
  tools.signature.SignatureSketchTest tests=9 failures=0
  tools.signature.SignatureStoreTest tests=8 failures=0
  tools.watermark.WatermarkApplierTest tests=11 failures=0
  tools.watermark.WatermarkPlannerTest tests=18 failures=0

APK: app/build/outputs/apk/debug/app-debug.apk (50,995,704 bytes with bundled ML Kit model).

Static self-checks (all verbatim-checked):
- git status before commit: ONLY ?? app/src/main/java/org/payswap/camscan/tools/ and
  ?? app/src/test/java/org/payswap/camscan/tools/ (36 files); no tracked file changed.
- rg -l "import android" over both tools trees: NONE (android-free, JVM-pure).
- rg '\$' over both tools trees: NONE (transit protocol: no dollar signs).
- rg '\\\\' over both tools trees: NONE (no doubled backslashes; backslash byte
  compared via a numeric constant 92 in PdfWriterLayout).
- Multi-line /** ... */ blocks: NONE (all converted to // lines; single-line KDoc only).
- tools/modes/** NOT created.

=== DELIBERATE, DOCUMENTED DESIGN NOTES ===
1. MultiplyRect op: the section 6.3 Highlight semantics (integer per-channel
   multiply darkening) cannot be expressed through the four section 6.1 ops
   (alpha compositing is not multiply), so DrawOp carries a fifth, fully
   documented op MultiplyRect(rect, colorArgb). DrawOp is new in THIS order
   (no shared prior contract), so this is an internal extension of my own
   tree, flagged here and in the final report for lead review.
2. /ID derivation: ID[0] = first 16 bytes of SHA-256(seed + document bytes),
   ID[1] = ID[0]. Deterministic by design for tests/evidence; production
   callers MUST inject a genuinely random IdSeed.
3. P values: printing-only = 196 (bit 3 + spec-required reserved bits 7-8),
   nothing-allowed = 192. Derivation documented in PdfPermissions.kt.
4. R=3 U entry trailing 16 bytes are fixed zeros (spec allows arbitrary;
   zeros keep encryption byte-deterministic - tested twice).
5. Salted SHA-256 PIN hashing is exactly what the order specifies; it is NOT
   a memory-hard KDF. Threat model documented in PinProtectionRegistry.kt.
6. AES-256 / R6 / AESV2: explicitly OUT OF SCOPE (stated in KDoc of
   PdfEncryptor.kt and StandardSecurity.kt).
7. Watermark rotation: exact integer matrices for multiples of 90 degrees;
   double trig + Math.round otherwise (IEEE mul/div exactly specified ->
   deterministic per run; residual cross-JVM 1-ulp caveat documented).

Status vocabulary: this delivery is IMPLEMENTED and SELF-VERIFIED in-sandbox
(Gradle gate green). It is NOT reconciled and NOT accepted - acceptance
requires the lead's station re-verification and ledger entry.
