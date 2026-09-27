You are Worker 1 (Capture & Scan Engine track) for CamScan, dispatched by the Tech Lead through the agents console. Work autonomously until CAMSCAN-PROD-001 is implemented, tested, and fully reported. This packet is fully self-contained — you cannot see the Tech Lead's context, and no other worker can see yours.

> **PACKET REVISION 2** (2026-09-27, lead): rebased onto the lead-owned
> PRODUCT-FOUNDATION commit `69ee49256ca647c3a1f0ec5d8689204f07624f9a`. Two changes from revision 1 (as
> originally dispatched): (a) the §3 branch base is `69ee49256ca6…`, not
> `9a674bc…`; (b) the §4 contract layer is ALREADY on your base — consume it
> read-only, never recreate or modify it. Revision-1 text is preserved in git
> history (34f7d2c) and in the live session transcript. Everything else is
> unchanged.

=== CAMSCAN-PROD-001 — CAPTURE FOUNDATION (Worker 1) ===

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
  - `docs/SCAN-ENGINE-CONTRACT.md` (the scanner capability contract)
  - `lab/orchestration/PRODUCT-WORK-BOARD.yaml` (your path ownership)
- CAMSCAN-PROD-001 is the **capture foundation** of the P0 scanner vertical slice:
  CameraX preview, camera permission flow, capture lifecycle, stable camera state.
  Document detection, quad stabilization, geometry/perspective, enhancement and the scan
  session arrive in LATER work orders (PROD-002/003/004) owned by other sessions — do NOT
  implement them now, but keep your seams clean so they can plug in.
- Priority rule: P0 "scan a document" outranks everything. Do not build P1/P2/P3 features.
- Offline-first: no network in the scan path. No account. No cloud.

# 3. ENVIRONMENT SETUP (run exactly, in order)

```bash
git clone https://github.com/payswapdotorg/CamScan.git CamScan
cd CamScan
git checkout 69ee49256ca647c3a1f0ec5d8689204f07624f9a
git checkout -b work/CAMSCAN-PROD-001
git config user.name "CamScan Worker 1"
git config user.email "worker1@camscan.invalid"
git rev-parse HEAD   # MUST print 69ee49256ca647c3a1f0ec5d8689204f07624f9a
```

Your sandbox very likely has NO Android SDK and no Gradle cache. That is expected and
acceptable — the Tech Lead re-runs the full Gradle gate (build + tests + lint) at the
integration station on the lab substrate and NEVER trusts reported numbers. Your duty is
correct, complete, disciplined code plus honest reporting. See §7 for the optional
best-effort toolchain attempt and the honesty rules.

# 4. SHARED CONTRACT LAYER (lead-owned, frozen — already on your base commit)

The wave-0 contracts are NOT something you create. They are landed on `main` as
the single lead-owned PRODUCT-FOUNDATION commit
`69ee49256ca647c3a1f0ec5d8689204f07624f9a`, which is your branch base. All
three concurrent workers build on the exact same bytes — that is why nobody
may modify them. Read them in your clone before writing code; your task
section references their types:

- `gradle/libs.versions.toml` — version catalog (CameraX 1.4.1 set, lifecycle,
  fragment, recyclerview, coroutines, JVM + instrumentation test deps)
- `app/build.gradle.kts` — module build (SDK levels, dependency wiring,
  AndroidJUnitRunner)
- `app/src/main/AndroidManifest.xml` — CAMERA permission + optional camera
  feature (required=false)
- `app/src/main/java/org/payswap/camscan/core/model/Documents.kt` — Document /
  Page domain model (Corner, cropQuad, enhancement modes, content refs)
- `app/src/main/java/org/payswap/camscan/core/repository/DocumentRepository.kt`
  — domain persistence contract (Flow-based observe, upsert, delete)
- `app/src/main/java/org/payswap/camscan/core/storage/ContentStore.kt` —
  opaque-ref binary asset contract (put/open/delete/exists)
- `app/src/main/java/org/payswap/camscan/core/time/TimeSource.kt` —
  deterministic time seam (SYSTEM default; inject fakes in tests)
- `app/src/main/java/org/payswap/camscan/core/navigation/ScanEntry.kt` —
  shell ⇄ scan-engine seam (ScanHost / ScanLauncher)

Do not modify any of them. Do not add sibling files under `core/` — if you need
a core change, report it as an open question in your final report and implement
nothing core-side.

# 5. YOUR OWNERSHIP BOUNDARY (binding)

You MAY create/modify:
- `app/src/main/java/org/payswap/camscan/capture/**` (your tree)
- `app/src/test/java/org/payswap/camscan/capture/**` (your JVM tests)
- `app/src/androidTest/java/org/payswap/camscan/capture/**` (your instrumentation skeletons)
- `app/src/main/res/layout/fragment_scan.xml`, `app/src/main/res/values/strings_capture.xml`
  (your resources ONLY — file names are worker-disjoint by design)
- nothing in the §4 contract layer — it is already on your base commit and is read-only

You MUST NOT touch:
- `MainActivity.kt`, `activity_main.xml`, `res/values/strings.xml`, any other res file,
  or any file under `document/`, `library/`, `export/`, `settings/`, `ocr/`, `tools/`,
  `search/` (other workers' surfaces / the shell)
- anything outside `app/` (lab/, tools/, docs/ are lead-owned)

# 6. TASK — CAPTURE FOUNDATION

Implement, in `app/src/main/java/org/payswap/camscan/capture/camera/` (plus your res
files and tests):

1. **CameraPermissionGate** — permission state machine, pure Kotlin:
   states `NOT_REQUESTED, REQUESTED, GRANTED, DENIED_SOFT, DENIED_PERMANENT`;
   events `onPermissionResult(granted: Boolean)`, plus a rationale probe injected as
   `fun interface RationaleChecker { fun shouldShowRationale(): Boolean }` so the machine
   is JVM-testable. `DENIED_PERMANENT` is reached only when denied AND rationale is false.
   Include `val canRequest: Boolean` semantics (re-request allowed from NOT_REQUESTED and
   DENIED_SOFT only).
2. **CameraStateMachine** — capture lifecycle state machine, pure Kotlin:
   states `IDLE, OPENING, READY, CAPTURING, ERROR, CLOSED`; events `bindStarted()`,
   `cameraReady()`, `captureStarted()`, `captureComplete()`, `captureFailed()`,
   `cameraError(recoverable: Boolean)`, `close()`. Legal transitions only; an illegal
   event is rejected (returns false and records a diagnostic string) — never throws.
   A recoverable camera error returns to OPENING (rebind); a permanent one goes to ERROR
   (terminal until close()).
3. **StableCameraState** — immutable camera settings + reducer, pure Kotlin:
   fields `lensFacing (BACK|FRONT)`, `flash (AUTO|ON|OFF)`, `available: Boolean`,
   plus an event reducer: `ToggleFlash`, `SwitchLens`, `SetAvailable(Boolean)`.
   Switching to an unavailable lens is rejected. Flash AUTO is the default (matches the
   reference scanner behavior of auto-flash in dim conditions).
4. **CameraController** — the CameraX glue (thin; all decisions delegated to 1–3):
   - binds `Preview` + `ImageCapture` to a `LifecycleOwner` via
     `ProcessCameraProvider.getInstance(ctx)` (ListenableFuture listener pattern, no
     blocking gets), selector from StableCameraState.lensFacing;
   - rebinds on lens switch and on recoverable error;
   - `takeStill(target: java.io.File, callback: (CaptureOutcome) -> Unit)` where
     `CaptureOutcome` is your own sealed type (Saved(file)/Failed(reason)) with a reason
     taxonomy (IO, CAPTURE_FAILED, CAMERA_UNAVAILABLE, NOT_READY, NO_PERMISSION);
   - surface flash mode onto ImageCapture flash-mode mapping;
   - exposes camera state via a small listener interface; no Activity/Fragment imports
     beyond LifecycleOwner/Context.
5. **ScanFragment** — the live scan surface (`fragment_scan.xml`):
   - `PreviewView` (id `scan_camera_preview`) with compat mode;
   - permission overlay when not GRANTED: rationale text (id
     `scan_permission_rationale`) + request button (id `scan_permission_request_button`)
     calling `registerForActivityResult(RequestPermission())`;
   - capture button (id `scan_capture_button`, enabled only in READY);
   - flash toggle (id `scan_flash_toggle`) and camera switch (id `scan_switch_camera`);
   - graceful no-camera / permanent-error state (id `scan_unavailable_state`);
   - hosts CameraController; drives CameraStateMachine; on capture-complete keeps the
     file reference in memory (a simple `CapturedShot` value) and re-arms for the next
     capture — document persistence is NOT your concern in this work order;
   - exposes `var onCaptureResult: ((List<CapturedShot>) -> Unit)? = null` invoked when
     the user finishes (a leave-scan affordance, id `scan_done_button`) — the shell and
     session flow consume this in later work orders.
6. **CameraScanLauncher** — implements `core.navigation.ScanLauncher`: starts
   `ScanFragment` into `host.containerViewId` via the host's FragmentManager (addToBackStack
   "scan"), returns true. `ScanHost.onScanFinished` is called with null when the user exits
   the scan surface without captures (for now; the real document id arrives with the
   session work order).
7. **JVM unit tests** (`app/src/test/java/org/payswap/camscan/capture/camera/`):
   exhaustive transition tables for 1–3: every legal transition asserted, every illegal
   event rejected without throw; permission gate paths incl. permanent-denial; reducer
   defaults + rejections. These tests are pure JUnit 4, no Android classes — they MUST
   compile and run under `./gradlew :app:testDebugUnitTest` at the integration station.
8. **Instrumentation skeleton** (`app/src/androidTest/java/org/payswap/camscan/capture/camera/ScanFragmentLaunchTest.kt`):
   FragmentScenario launch of ScanFragment asserting the preview + capture button exist
   with the exact resource ids; marked with a clear comment that it runs at the lead's
   integration station (lab AVD), not in your sandbox.

**Stable semantic ids (exact, ADB-parity tests depend on them):**
`scan_camera_preview`, `scan_capture_button`, `scan_flash_toggle`,
`scan_switch_camera`, `scan_permission_request_button`, `scan_permission_rationale`,
`scan_unavailable_state`, `scan_done_button`.
Every interactive view also gets a `contentDescription` from `strings_capture.xml`
(except purely decorative ones).

**Discipline:**
- Kotlin 2.0, JVM target 17, minSdk 26 — no APIs above SDK 26 without fallback.
- No new dependencies beyond §4 (report needs instead). No Compose. No multi-module.
- Deterministic where pure: same input → same output; time only via TimeSource.
- Keep pure logic (1–3) free of `android.*` imports so it stays JVM-testable; the glue
  (4–6) stays thin.
- String resources only via `strings_capture.xml` (prefixed `scan_`).

# 7. VERIFICATION (honest, bounded)

1. Attempt, budget-boxed to ~20 minutes total: check `java -version`; if a JDK exists you
   MAY try to fetch Android cmdline-tools + `sdkmanager "platforms;android-35"
   "build-tools;35.0.0"` (accept licenses), then `./gradlew :app:testDebugUnitTest
   --offline` will not work without SDK — use the online build. If anything fails or the
   budget expires: STOP, deliver statically, and say so plainly.
2. Static self-checks (always): `git status` clean-tree discipline; `git diff --stat`
   review; re-read every Kotlin file for syntax discipline (balanced braces, imports,
   no dangling TODOs without a report note).
3. NEVER fabricate test output. If you could not run Gradle, the report's verification
   section says exactly: "no Android toolchain in sandbox; compile/test deferred to lead
   integration" plus the static checks you did perform.

# 8. DELIVERY (git, in-sandbox — you have NO push credentials)

```bash
cd CamScan
git add -A
git commit -m "CAMSCAN-PROD-001: capture foundation — CameraX preview, permission gate, capture lifecycle, stable camera state on the lead PRODUCT-FOUNDATION base"
git bundle create camscan-prod-001.bundle 69ee49256ca647c3a1f0ec5d8689204f07624f9a..work/CAMSCAN-PROD-001
cp camscan-prod-001.bundle ../camscan-prod-001.bundle
sha256sum camscan-prod-001.bundle
git diff --stat 69ee49256ca647c3a1f0ec5d8689204f07624f9a..work/CAMSCAN-PROD-001
```

Also write `CamScan/DELIVERY-PROD-001.txt` listing every added/changed file path + the
bundle sha256. Your sandbox and its files ARE the delivery channel — the Tech Lead
harvests them directly. Do not delete your work. Do not push anywhere.

# 9. FINAL REPORT (your last chat message — report ONLY, no file dumps)

```
=== CAMSCAN-PROD-001 COMPLETION REPORT ===
task: capture foundation — CameraX preview, permission, capture lifecycle, stable camera state
environment: sandbox OS + toolchain actually available (state plainly if no Android SDK)
what was implemented/observed: …
verification: commands + outputs (verbatim — including failed attempts; never invented)
evidence: artifact list + sha256 (bundle path, DELIVERY-PROD-001.txt) — no raw bytes in chat
assumptions: …
open questions / handoffs: …
base sha: 69ee49256ca647c3a1f0ec5d8689204f07624f9a
```

Status vocabulary is binding: `implemented` ≠ `verified` ≠ `reconciled` ≠ `accepted`.
Report exactly what exists. A green self-review is not a pass.
