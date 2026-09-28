You are Worker 1 (Capture & Scan Engine track) for CamScan, dispatched by the Tech Lead through the agents console. Work autonomously until CAMSCAN-PROD-002 is implemented, tested, and fully reported. This packet is fully self-contained — you cannot see the Tech Lead's context, and no other worker can see yours.

> **EXECUTION DIRECTIVE (binding, lesson from your own PROD-001 dispatch):**
> do NOT stop to acknowledge this packet. Your FIRST action is §3 setup; your
> LAST action is the §9 final report. Execute end-to-end in THIS turn:
> §3 setup → §6 implement → §7 checks → §8 commit+bundle → §9 report as your
> reply. No acks, no status updates, no clarifying questions — the packet is
> complete. If genuinely blocked, the report states the blocker honestly with
> everything you did complete.

=== CAMSCAN-PROD-002 — DOCUMENT DETECTION (Worker 1) ===

# 1. ROLE

You are Worker 1 on a permanent three-worker product program building **CamScan** — an
independent, clean-room Android document-scanning application (applicationId
`org.payswap.camscan`). The observable behavior oracle is the public CamScanner app; the
reference app is NEVER an implementation dependency (no proprietary code/assets/internals).

You own the **capture & scan-engine surface**. You are not the architect: the shared
contracts in §4 are frozen and lead-owned; implement inside your ownership boundary and
report any needed contract change in your final report instead of making it yourself.

# 2. PROGRAM CONTEXT

- Repository: https://github.com/payswapdotorg/CamScan (public, anonymous clone works).
  After cloning, READ these files in your clone for the full program frame:
  - `docs/PRODUCT-ARCHITECTURE-LOCK.md` (architecture authority)
  - `docs/SCAN-ENGINE-CONTRACT.md` (the scanner capability contract — your §6.1 seam
    names come from its Detection section)
  - `lab/orchestration/PRODUCT-WORK-BOARD.yaml` (your path ownership)
- **CAMSCAN-PROD-001 is DELIVERED and is your base.** Your own capture foundation
  (CameraPermissionGate, CameraStateMachine, StableCameraState, CameraController,
  ScanFragment, CameraScanLauncher + 66 JVM tests) is the branch head you build on. This
  work order adds the **live document detection layer** on top of it:
  quad detection, confidence, quality flags, temporal stabilization, and the framing
  overlay. Perspective correction, enhancement and the scan session arrive in
  PROD-003/004 — do NOT implement them now, but keep your seams clean so they plug in.
- Priority rule: P0 "scan a document" outranks everything. Do not build P1/P2/P3 features.
- Offline-first: no network in the scan path. No account. No cloud.

# 3. ENVIRONMENT SETUP (run exactly, in order)

```bash
git clone https://github.com/payswapdotorg/CamScan.git CamScan
cd CamScan
git fetch origin work/CAMSCAN-PROD-001
git checkout -b work/CAMSCAN-PROD-002 f1356c986b09ea435baa30328db8e822464a327d
git config user.name "CamScan Worker 1"
git config user.email "worker1@camscan.invalid"
git rev-parse HEAD   # MUST print f1356c986b09ea435baa30328db8e822464a327d
ls app/src/main/java/org/payswap/camscan/capture/camera/   # your PROD-001 files must be here
```

Your sandbox very likely has NO Android SDK and no Gradle cache. That is expected and
acceptable — the Tech Lead re-runs the full Gradle gate (build + tests + lint) at the
integration station on the lab substrate and NEVER trusts reported numbers. Your duty is
correct, complete, disciplined code plus honest reporting. See §7.

# 4. SHARED CONTRACT LAYER (lead-owned, frozen — already on your base commit)

The wave-0 contracts landed on `main` as the single lead-owned PRODUCT-FOUNDATION commit
and are ancestors of your base. All workers build on the exact same bytes — that is why
nobody may modify them. Read them before writing code; your task references their types:

- `gradle/libs.versions.toml` — version catalog (CameraX 1.4.1 set incl. camera-core/
  camera-camera2/camera-lifecycle; lifecycle, coroutines, JVM + instrumentation test deps)
- `app/build.gradle.kts` — module build (SDK levels, dependency wiring, AndroidJUnitRunner)
- `app/src/main/AndroidManifest.xml` — CAMERA permission + optional camera feature
- `app/src/main/java/org/payswap/camscan/core/model/Documents.kt` — Document/Page domain
  model (Corner, cropQuad, enhancement modes)
- `app/src/main/java/org/payswap/camscan/core/repository/DocumentRepository.kt`,
  `core/storage/ContentStore.kt`, `core/time/TimeSource.kt` (deterministic time seam),
  `core/navigation/ScanEntry.kt` (shell ⇄ scan-engine seam)

Do not modify any of them. Do not add sibling files under `core/` — if you need a core
change, report it as an open question in your final report and implement nothing
core-side.

# 5. YOUR OWNERSHIP BOUNDARY (binding)

You MAY create/modify:
- `app/src/main/java/org/payswap/camscan/capture/detect/**` (new — this work order's tree)
- `app/src/test/java/org/payswap/camscan/capture/detect/**` (your JVM tests)
- `app/src/androidTest/java/org/payswap/camscan/capture/detect/**` (instrumentation)
- `app/src/main/java/org/payswap/camscan/capture/camera/**` (YOUR OWN PROD-001 files —
  extend them where §6 says so; do not refactor what §6 does not require)
- `app/src/test/java/org/payswap/camscan/capture/camera/**` (extend only where your
  camera-layer change alters behavior under test)
- `app/src/main/res/layout/fragment_scan.xml`, `app/src/main/res/values/strings_capture.xml`,
  `app/src/main/res/values/colors_capture.xml` (new file; your resources ONLY)

You MUST NOT touch:
- the §4 contract layer (read-only, already on your base)
- `MainActivity.kt`, `activity_main.xml`, `res/values/strings.xml`, any other res file,
  or any file under `document/`, `library/`, `export/`, `settings/`, `ocr/`, `tools/`,
  `search/` (other workers' surfaces / the shell)
- anything outside `app/` (lab/, tools/, docs/ are lead-owned)

# 6. TASK — DOCUMENT DETECTION

Implement, in `app/src/main/java/org/payswap/camscan/capture/detect/` (plus the camera
wiring, res files and tests below). The seam names in 6.1/6.3 are contractual
(`docs/SCAN-ENGINE-CONTRACT.md`: `DocumentDetector.detect(frame) -> DocumentDetection?`,
`DetectionStabilizer.update(detection) -> StableDetection?` — exact class names may vary,
the seams may not disappear).

1. **Detection domain types** (pure Kotlin, android-free, same file or `model/`):
   - `DetectorFrame(width: Int, height: Int, luminance: ByteArray)` — an 8-bit
     grayscale frame; rows = height, row stride = width (no padding). This is the
     detector's ONLY input; tests construct synthetic frames directly.
   - `DocumentDetection` — four ordered corners `TL, TR, BR, BL` (each `Corner(x: Int,
     y: Int)` in frame coordinates), `confidence: Float` in [0,1],
     `qualityFlags: Set<DetectionQualityFlag>`,
     `frameTimestampMs: Long` (from TimeSource, never `System`).
   - `enum class DetectionQualityFlag { NO_PAGE, PARTIAL_PAGE, BLUR, GLARE,
     LOW_CONTRAST, MOTION_UNSTABLE }` — the contract's degrade/reject vocabulary.
   - `StableDetection` — the quad the UI may trust: corners, confidence, plus
     `stableForFrames: Int` and `qualityFlags`.
2. **DocumentDetector** — interface + your pure-Kotlin implementation
   (`EdgeQuadDetector` or better name you choose):
   - `fun detect(frame: DetectorFrame, timestampMs: Long): DocumentDetection?`
   - Algorithm is YOURS to design (architecture lock: "OpenCV or equivalent
     independently implemented/open-source image processing" — you have NO new
     dependencies, §Discipline, so implement in pure Kotlin). A sufficient clean-room
     shape: downsample → per-pixel gradient magnitude → strong-edge threshold →
     accumulate line/contour support → hypothesize the largest convex quadrilateral →
     order corners TL/TR/BR/BL → confidence from edge support + quad regularity →
     quality flags from evidence (edge density too low ⇒ LOW_CONTRAST; quad touching
     frame border ⇒ PARTIAL_PAGE; no supported quad ⇒ NO_PAGE; …).
   - **Deterministic**: identical (frame, timestamp) inputs produce an identical
     `DocumentDetection` — no randomness, no time-of-day, no device state.
   - Reject or degrade gracefully (flag, not exception) for: no page, partial page,
     severe blur, extreme glare, insufficient contrast, unstable motion. A flagged
     detection is still returned when a quad exists; `null` only when nothing qualifies.
   - Synthetic-fixture competence (verified in §6.7 tests): a clean bright page on a
     darker background at reasonable margins ⇒ detected with corners within a few
     percent of ground truth; a skewed (rotated/perspective) page ⇒ detected; a small
     receipt-aspect page ⇒ detected; blank/uniform frames ⇒ NO_PAGE or null.
3. **DetectionStabilizer** — pure Kotlin:
   - `fun update(detection: DocumentDetection?): StableDetection?`
   - Emits a StableDetection only after `requiredAgreementFrames` (default 3, ctor
     configurable) consecutive detections whose quads agree: per-corner distance ≤
     `cornerTolerancePx` AND pairwise IoU ≥ `iouThreshold` (pick sane defaults; expose
     all as ctor params).
   - Hysteresis on loss: hold the last stable quad for `holdoutFrames` (default 5)
     missed/disagreeing frames before emitting `null` (the UI must not flicker).
   - `MOTION_UNSTABLE` flag surfaces on a stable quad whose supporting detections
     exceeded corner tolerance more than once in the window (a hint, not a reject).
   - Never throws; every transition legal or ignored-with-diagnostic (follow your own
     CameraStateMachine discipline).
4. **QuadGeometry** (pure Kotlin object, android-free): canonical corner ordering
   (TL/TR/BR/BL by angle-around-centroid), convexity test, shoelace area, quad IoU,
   aspect ratio, per-corner distance. Your detector and stabilizer both use it —
   one implementation, exhaustively tested.
5. **DetectionAnalyzer** — the CameraX glue (thin, android-side):
   - implements `ImageAnalysis.Analyzer`; converts `ImageProxy` (YUV_420_888) to a
     `DetectorFrame` from the luminance plane (handle row-stride padding; crop to width);
   - resolution target ~640x480 class, `STRATEGY_KEEP_ONLY_LATEST`, and a frame throttle
     (process at most one frame per `analyzeIntervalMs`, default 250 — configurable);
   - runs detection + stabilizer on a single background executor (serial), posts
     `StableDetection?` + flags to a listener on the main thread;
   - closes the ImageProxy exactly once on every path.
6. **CameraController extension + ScanFragment overlay wiring**:
   - CameraController: bind `ImageAnalysis` alongside `Preview` + `ImageCapture` in the
     SAME `ProcessCameraProvider` bind (add a `setAnalyzer(analyzer)` hook); rebinds
     (lens switch, recoverable error) must re-attach the analyzer. No behavior change to
     existing capture paths (your PROD-001 tests must still pass unmodified).
   - `FramingOverlayView` (custom View, `capture/detect/`): draws the stable quad as a
     polygon border with corner ticks; visual state by detection quality — stable
     (confirming color), searching (neutral), flagged (warning color) — colors defined in
     YOUR `colors_capture.xml` (`scan_framing_stable`, `scan_framing_searching`,
     `scan_framing_warning`; alpha-composited so the preview stays visible).
   - `fragment_scan.xml`: overlay layered above `scan_camera_preview` (id
     `scan_framing_overlay`, `contentDescription` from `strings_capture.xml`); guidance
     text (id `scan_detection_guidance`) below/above the capture row mapping quality
     flags to short user guidance strings (`scan_guidance_*`, prefixed, in
     `strings_capture.xml`).
   - ScanFragment: hosts DetectionAnalyzer; drives FramingOverlayView + guidance from
     StableDetection/flags. **Detection does NOT gate capture** — manual capture stays
     always-usable (feature matrix; auto-capture arrives only when reference behavior
     establishes it — do NOT implement auto-capture).
7. **JVM unit tests** (`app/src/test/java/org/payswap/camscan/capture/detect/`), pure
   JUnit 4, no Android classes:
   - **Synthetic frame builder** — deterministic programmatic frames: filled quad on a
     contrasting background with configurable corner positions, rotation/skew, noise
     level, brightness. No image files, no I/O, no randomness (fixed seeds if you use
     noise at all).
   - Detector: clean frame ⇒ corners within tolerance of ground truth + confidence
     range; skewed frame ⇒ detected; receipt-aspect ⇒ detected; blank/uniform ⇒ null or
     NO_PAGE; border-touching quad ⇒ PARTIAL_PAGE; low-contrast ⇒ LOW_CONTRAST;
     determinism: same frame twice ⇒ equal results (data-class equality).
   - Stabilizer: agreement ladder (2 agreeing ≠ stable, 3 = stable), disagreement reset,
     holdout countdown, MOTION_UNSTABLE surfacing, null-after-holdout, ctor param
     coverage.
   - QuadGeometry: ordering (all permutations of input corners ⇒ canonical TL/TR/BR/BL),
     convexity, area, IoU (identical=1, disjoint=0, known partial), corner distances.
   - These tests MUST compile and run under `./gradlew :app:testDebugUnitTest` at the
     integration station — keep every referenced class android-free.
8. **Instrumentation skeleton** (`app/src/androidTest/java/org/payswap/camscan/capture/detect/`):
   `ScanFragmentOverlayTest.kt` — FragmentScenario launch asserting `scan_framing_overlay`
   and `scan_detection_guidance` exist with the exact resource ids; comment that it runs
   at the lead's integration station (lab AVD), not in your sandbox.

**Stable semantic ids (exact, ADB-parity tests depend on them):**
`scan_framing_overlay`, `scan_detection_guidance` (new); all PROD-001 ids unchanged.
Every interactive view gets a `contentDescription` from `strings_capture.xml`.

**Discipline:**
- Kotlin 2.0, JVM target 17, minSdk 26 — no APIs above SDK 26 without fallback.
- No new dependencies beyond the §4 catalog (report needs instead). No Compose. No
  multi-module. No OpenCV/ML-Kit — pure Kotlin image math.
- Deterministic where pure: same input → same output; time only via TimeSource.
- Keep pure logic (6.1–6.4, 6.7) free of `android.*` imports so it stays JVM-testable;
  the glue (6.5–6.6, 6.8) stays thin.
- String/color resources only via `strings_capture.xml` / `colors_capture.xml`
  (`scan_` prefixed names).

# 7. VERIFICATION (honest, bounded)

1. Attempt, budget-boxed to ~20 minutes total: if a JDK exists you MAY try the Android
   cmdline-tools + `sdkmanager` route (licenses accepted) and
   `./gradlew :app:testDebugUnitTest`; your PROD-001 run proved the full Gradle gate CAN
   pass in your sandbox when the toolchain lands — retry that route; if anything fails
   or the budget expires: STOP, deliver statically, and say so plainly.
2. Static self-checks (always): `git status` clean-tree discipline; `git diff --stat`
   review; re-read every Kotlin file for syntax discipline (balanced braces, imports,
   no dangling TODOs without a report note).
3. NEVER fabricate test output. If you could not run Gradle, the report's verification
   section says exactly that plus the static checks you did perform.

# 8. DELIVERY (git, in-sandbox — you have NO push credentials)

```bash
cd CamScan
git add -A
git commit -m "CAMSCAN-PROD-002: document detection — quad detector, quality flags, temporal stabilizer, framing overlay on the PROD-001 capture base"
git bundle create camscan-prod-002.bundle f1356c986b09ea435baa30328db8e822464a327d..work/CAMSCAN-PROD-002
cp camscan-prod-002.bundle ../camscan-prod-002.bundle
sha256sum camscan-prod-002.bundle
git diff --stat f1356c986b09ea435baa30328db8e822464a327d..work/CAMSCAN-PROD-002
```

Also write `CamScan/DELIVERY-PROD-002.txt` listing every added/changed file path + the
bundle sha256. Your sandbox and its files ARE the delivery channel — the Tech Lead
harvests them directly. Do not delete your work. Do not push anywhere.

# 9. FINAL REPORT (your last chat message — report ONLY, no file dumps)

```
=== CAMSCAN-PROD-002 COMPLETION REPORT ===
task: document detection — quad detection, confidence, quality flags, temporal stabilization, framing overlay
environment: sandbox OS + toolchain actually available (state plainly if no Android SDK)
what was implemented/observed: …
verification: commands + outputs (verbatim — including failed attempts; never invented)
evidence: artifact list + sha256 (bundle path, DELIVERY-PROD-002.txt) — no raw bytes in chat
assumptions: …
open questions / handoffs: …
base sha: f1356c986b09ea435baa30328db8e822464a327d
```

Status vocabulary is binding: `implemented` ≠ `verified` ≠ `reconciled` ≠ `accepted`.
Report exactly what exists. A green self-review is not a pass.
