You are Worker 1 (Capture & Scan Engine track) for CamScan, dispatched by the Tech Lead through the agents console. Work autonomously until CAMSCAN-PROD-003 is implemented, tested, and fully reported. This packet is fully self-contained — you cannot see the Tech Lead's context, and no other worker can see yours.

> **EXECUTION DIRECTIVE (binding, platform lesson):**
> do NOT stop to acknowledge this packet. Your FIRST action is §3 setup; your
> LAST action is the §9 final report. Execute end-to-end in THIS turn:
> §3 setup -> §6 implement -> §7 checks -> §8 commit+bundle -> §9 report as your
> reply. No acks, no status updates, no clarifying questions — the packet is
> complete. If genuinely blocked, the report states the blocker honestly with
> everything you did complete. If your turn is interrupted mid-work, the next
> directive will resume from your persisted sandbox state — keep working until
> the report is posted.

=== CAMSCAN-PROD-003 — GEOMETRY & PROCESSING (Worker 1) ===

# 1. ROLE

You are Worker 1 on a permanent three-worker product program building **CamScan** — an
independent, clean-room Android document-scanning application (applicationId
`org.payswap.camscan`). The observable behavior oracle is the public CamScanner app; the
reference app is NEVER an implementation dependency.

You own the **capture & scan-engine surface**. The shared contracts in §4 are frozen and
lead-owned; implement inside your ownership boundary and report any needed contract
change in your final report instead of making it yourself.

# 2. PROGRAM CONTEXT

- Repository: https://github.com/payswapdotorg/CamScan (public, anonymous clone works).
  After cloning, READ these files in your clone for the full program frame:
  - `docs/PRODUCT-ARCHITECTURE-LOCK.md` (architecture authority)
  - `docs/SCAN-ENGINE-CONTRACT.md` — your §6.2/§6.3 seam names come from its Geometry
    and Enhancement sections
  - `lab/orchestration/PRODUCT-WORK-BOARD.yaml` (your path ownership)
- **CAMSCAN-PROD-001 (capture foundation) and CAMSCAN-PROD-002 (document detection) are
  DELIVERED and are your base.** Your own prior work — CameraController/ScanFragment
  with ImageAnalysis wiring, EdgeQuadDetector, DetectionStabilizer, FramingOverlayView,
  123 green JVM tests — is the branch head you build on. This work order adds the
  **geometry & processing layer**: perspective correction, enhancement, quality gates.
  The scan session (review/crop/rotate/retake/multi-page) arrives in PROD-004 — do NOT
  implement it now, but keep your seams clean so the session plugs in.
- Priority rule: P0 "scan a document" outranks everything. Do not build P1/P2/P3 features.
- Offline-first: no network in the scan path. No account. No cloud.

# 3. ENVIRONMENT SETUP (run exactly, in order)

```bash
git clone https://github.com/payswapdotorg/CamScan.git CamScan
cd CamScan
git fetch origin work/CAMSCAN-PROD-002
git checkout -b work/CAMSCAN-PROD-003 b08ca438fe408c051f7e72158cbfed6bab3349c1
git config user.name "CamScan Worker 1"
git config user.email "worker1@camscan.invalid"
git rev-parse HEAD   # MUST print b08ca438fe408c051f7e72158cbfed6bab3349c1
ls app/src/main/java/org/payswap/camscan/capture/   # camera/ + detect/ (your PROD-001/002 trees)
```

If your sandbox retains a prior CamScan clone with the toolchain (JDK + android-sdk +
Gradle cache), reusing it is fine — the base sha and clean branch are what matter.
Your PROD-002 delivery ran the full Gradle gate green in-sandbox; aim to repeat that.

# 4. SHARED CONTRACT LAYER (lead-owned, frozen — already on your base commit)

The wave-0 contracts landed on `main` as the single lead-owned PRODUCT-FOUNDATION commit
and are ancestors of your base. Nobody may modify them. Read before writing code:

- `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- `app/src/main/java/org/payswap/camscan/core/model/Documents.kt` — **Page / PageEnhancementMode
  (ORIGINAL, GRAYSCALE, BLACK_AND_WHITE, CONTRAST, SHARPEN, LOW_LIGHT) / Corner /
  cropQuad / processedImageRef / rotationDegrees** — your §6.3 consumes these exact types
- `core/repository/DocumentRepository.kt`, `core/storage/ContentStore.kt`,
  `core/time/TimeSource.kt`, `core/navigation/ScanEntry.kt`

Do not modify any of them. Do not add sibling files under `core/` — report needed core
changes as open questions instead.

# 5. YOUR OWNERSHIP BOUNDARY (binding)

You MAY create/modify:
- `app/src/main/java/org/payswap/camscan/processing/**` (new — this work order's tree)
- `app/src/test/java/org/payswap/camscan/processing/**` (your JVM tests)
- `app/src/androidTest/java/org/payswap/camscan/processing/**` (instrumentation)
- your own prior trees: `capture/camera/**`, `capture/detect/**` and their tests —
  extend ONLY where §6.6 requires wiring; do not refactor what §6 does not require
- `app/src/main/res/layout/fragment_scan.xml`, `values/strings_capture.xml`,
  `values/colors_capture.xml` (your resources ONLY — only if §6.6 needs them)

You MUST NOT touch:
- the §4 contract layer (read-only)
- `MainActivity.kt`, `activity_main.xml`, `res/values/strings.xml`, any other res file,
  or any file under `document/`, `library/`, `export/`, `settings/`, `ocr/`, `tools/`,
  `search/` (other workers' surfaces / the shell — PROD-005's shell is now on main
  history but NOT on your branch base; do not merge it in)
- anything outside `app/` (lab/, tools/, docs/ are lead-owned)

# 6. TASK — GEOMETRY & PROCESSING

Implement, in `app/src/main/java/org/payswap/camscan/processing/` (plus tests below).
The seam names are contractual (`docs/SCAN-ENGINE-CONTRACT.md`:
`PerspectiveCorrector.correct(image, quad) -> ProcessedPage`,
`EnhancementEngine.process(page, mode) -> ProcessedPage` — exact class names may vary,
the seams may not disappear).

1. **Image domain types** (pure Kotlin, android-free):
   - `ImageBuffer(width: Int, height: Int, argb: IntArray)` — platform-neutral ARGB
     image (packed 0xAARRGGBB, row-major, stride = width). Cheap to construct from a
     Bitmap and back (the thin adapter lives in 6.5).
   - `QuadF` — four ordered float corners TL/TR/BR/BL in source-image space (your
     DocumentDetection corners are frame-space Ints; PROD-002 already noted the
     frame-space→image-space mapping is this work order's job: expose a scale helper).
   - `ProcessedGeometry` — source quad, output width/height, estimated source page
     aspect; retained per the contract ("retain source-to-page geometry metadata").
2. **Geometry core** (pure Kotlin):
   - `Homography` — 4-point DLT (Gaussian elimination on the 8x8 linear system; no
     library) producing a 3x3 matrix (normalized so h33 = 1); a `map(point)` forward
     transform and an `invert()` (adjugate / determinant). Degenerate (collinear)
     quads return null — never throws.
   - `PerspectiveCorrector` (interface + implementation): `correct(image: ImageBuffer,
     quad: QuadF): ProcessedPage?` — (a) canonical corner ordering (reuse your
     QuadGeometry discipline — a processing-side float variant), (b) target dimension
     estimation from edge-length statistics (mean of opposite edge pairs, rounded,
     clamped to sane bounds), (c) inverse mapping with **bilinear** sampling of the
     source (no nearest-neighbor jaggies), (d) edge pixels beyond source bounds sample
     clamped (or transparent — pick ONE, document it), (e) ProcessedGeometry retained.
   - `ProcessedImage` — your engine's output page: ImageBuffer + ProcessedGeometry +
     `enhancement: PageEnhancementMode` (ORIGINAL at correction time) + a stable id.
     (The domain `Page` model is lead-owned; conversion happens at the session seam in
     PROD-004 — do not modify core/model.)
3. **EnhancementEngine** (interface + implementation, pure Kotlin):
   `process(page: ProcessedImage, mode: PageEnhancementMode): ProcessedImage` —
   deterministic, non-destructive (input untouched; new buffer out):
   - ORIGINAL: identity copy.
   - GRAYSCALE: ITU-R BT.601 luma → equal RGB channels; alpha preserved.
   - BLACK_AND_WHITE: global Otsu threshold on luma histogram → 0/255 (255 = paper);
     document the tie-break rule for bimodal-flat histograms.
   - CONTRAST: percentile-based linear stretch (0.5th/99.5th luma percentiles → 0/255),
     clamped; document the degenerate (flat) input behavior.
   - SHARPEN: 3x3 unsharp mask (center 5, cross -1; 1-pass separable is fine if you
     document it) with clamped arithmetic; document the border policy (edge clamp).
   - LOW_LIGHT: deterministic gamma correction (document the constant, e.g. 1/1.6) +
     the CONTRAST stretch, luma-domain, chroma preserved proportionally.
   - **Determinism + byte-stability**: same input + mode → byte-identical output
     (pure integer/float math; no RNG, no time, no device state). Test this explicitly.
4. **Quality gates** (pure Kotlin): `PageQuality` + `QualityGates.evaluate(image)` —
   sharpness (normalized Laplacian variance on luma), contrast (99.5–0.5 percentile
   spread / 255), blown-highlight fraction (luma ≥ 250), dark fraction (≤ 5);
   thresholds as ctor-configurable values with defaults; flags (SHARP/BLURRED,
   GOOD_CONTRAST/LOW_CONTRAST, GLARE, TOO_DARK). Deterministic. Never throws.
5. **Android adapters** (thin): `BitmapImageAdapter` — Bitmap ↔ ImageBuffer conversions
   (ARGB_8888, row-major copy); runs on a caller-supplied executor contract is fine.
   No UI, no Activity/Fragment imports.
6. **Pipeline wiring** (minimal, engine-side only — the session UI is PROD-004):
   `ProcessingPipeline.processCapture(source: ImageBuffer, detectionQuad: QuadF?,
   mode: PageEnhancementMode): ProcessedImage?` — detectionQuad null ⇒ full-frame copy
   (no warp); else correct() then enhance(). A tiny ScanFragment hook is ALLOWED but
   NOT required this work order — do not build review UI.

7. **JVM unit tests** (`app/src/test/java/org/payswap/camscan/processing/`), pure JUnit 4:
   - Homography: identity quad → identity matrix; known axis-aligned scale/translate
     quads → exact expected matrices; a known perspective quad → maps the 4 corners to
     the 4 targets within 1e-3; invert() roundtrip; collinear quad → null.
   - PerspectiveCorrector: synthetic gradient/checkerboard ImageBuffer warped by a known
     quad → expected output pixel values at sampled positions (bilinear math you can
     hand-compute); output dimensions = estimated target; degenerate quad → null;
     determinism (same input twice → equal arrays).
   - EnhancementEngine: each of the 6 modes on small hand-computed fixtures (e.g. a 3x3
     image with known luma values → exact expected ARGB outputs); byte-stability (run
     twice, compare arrays); non-destructiveness (input buffer unchanged); mode chain
     (correct→enhance retains geometry).
   - QualityGates: synthetic sharp (high-frequency) vs blurred (flat+smooth) images →
     correct flags; blown-highlight fixture → GLARE; dark fixture → TOO_DARK; ctor
     threshold overrides.
   - ImageBuffer/QuadF helpers: scale mapping from frame-space Int corners.
   These tests MUST compile and run under `./gradlew :app:testDebugUnitTest`.
8. **Instrumentation skeleton** (`app/src/androidTest/java/org/payswap/camscan/processing/`):
   `BitmapAdapterRoundTripTest.kt` — Bitmap→ImageBuffer→Bitmap roundtrip preserves
   dimensions + sample pixels; comment that it runs at the lead's integration station.

**Discipline:**
- Kotlin 2.0, JVM target 17, minSdk 26 — no APIs above SDK 26 without fallback.
- No new dependencies beyond the §4 catalog. No Compose. No multi-module. No OpenCV.
- Deterministic where pure: same input → same output; time only via TimeSource.
- Keep 6.1–6.4 + 6.7 free of `android.*` imports; adapters (6.5) stay thin.
- No new res files unless §6.6 truly needs them (it should not).

# 7. VERIFICATION (honest, bounded)

1. If your sandbox has the JDK + android-sdk + Gradle cache (your PROD-002 gate ran
   green), run the FULL gate: `./gradlew :app:testDebugUnitTest :app:lintDebug
   :app:assembleDebug :app:compileDebugAndroidTestKotlin` and report verbatim
   summaries. Budget-box toolchain repair to ~20 minutes; beyond that, deliver
   statically and say so plainly.
2. Static self-checks (always): clean-tree discipline; `git diff --stat` review;
   re-read every Kotlin file for syntax discipline.
3. NEVER fabricate test output.

# 8. DELIVERY (git, in-sandbox — you have NO push credentials)

```bash
cd CamScan
git add -A
git commit -m "CAMSCAN-PROD-003: geometry & processing — perspective corrector, enhancement engine, quality gates on the PROD-002 detection base"
git bundle create camscan-prod-003.bundle b08ca438fe408c051f7e72158cbfed6bab3349c1..work/CAMSCAN-PROD-003
cp camscan-prod-003.bundle ../camscan-prod-003.bundle
sha256sum camscan-prod-003.bundle
git diff --stat b08ca438fe408c051f7e72158cbfed6bab3349c1..work/CAMSCAN-PROD-003
```

Also write `CamScan/DELIVERY-PROD-003.txt` (every added/changed path + bundle sha256) and
copy it + the bundle to the workspace root next to your PROD-001/002 artifacts. Your
sandbox files ARE the delivery channel — the Tech Lead harvests them (pod files-API or,
if unreachable, an inline-transit order will follow). Do not delete your work. Do not
push anywhere.

# 9. FINAL REPORT (your last chat message — report ONLY, no file dumps)

```
=== CAMSCAN-PROD-003 COMPLETION REPORT ===
task: geometry & processing — perspective correction, enhancement, quality gates
environment: sandbox OS + toolchain actually available
what was implemented/observed: …
verification: commands + outputs (verbatim — including failed attempts; never invented)
evidence: artifact list + sha256 (bundle path, DELIVERY-PROD-003.txt) — no raw bytes in chat
assumptions: …
open questions / handoffs: …
base sha: b08ca438fe408c051f7e72158cbfed6bab3349c1
```

Status vocabulary is binding: `implemented` ≠ `verified` ≠ `reconciled` ≠ `accepted`.
Report exactly what exists. A green self-review is not a pass.
