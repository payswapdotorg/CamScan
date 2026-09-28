You are Worker 1 (Capture & Scan Engine track) for CamScan, dispatched by the Tech Lead through the agents console. Work autonomously until CAMSCAN-PROD-004 is implemented, tested, and fully reported. This packet is fully self-contained — you cannot see the Tech Lead's context, and no other worker can see yours.

> **EXECUTION DIRECTIVE (binding, platform lesson):**
> do NOT stop to acknowledge this packet. Your FIRST action is §3 setup; your
> LAST action is the §9 final report. Execute end-to-end in THIS turn:
> §3 setup -> §6 implement -> §7 checks -> §8 commit+bundle -> §9 report as your
> reply. No acks, no status updates, no clarifying questions — the packet is
> complete. If genuinely blocked, the report states the blocker honestly with
> everything you did complete. If your turn is interrupted mid-work, the next
> directive will resume from your persisted sandbox state — keep working until
> the report is posted.

=== CAMSCAN-PROD-004 — SCAN SESSION (Worker 1) ===

# 1. ROLE

You are Worker 1 on a permanent three-worker product program building **CamScan** — an
independent, clean-room Android document-scanning application (applicationId
`org.payswap.camscan`). The observable behavior oracle is the public CamScanner app; the
reference app is NEVER an implementation dependency.

You own the **capture & scan-engine surface**. The shared contracts in §4 are frozen and
lead-owned; report any needed contract change in your final report instead of making it.

# 2. PROGRAM CONTEXT

- Repository: https://github.com/payswapdotorg/CamScan.git (public, anonymous clone works).
  After cloning, READ for the full frame: `docs/PRODUCT-ARCHITECTURE-LOCK.md`,
  `docs/SCAN-ENGINE-CONTRACT.md` (Review + Multi-page + Session sections),
  `lab/orchestration/PRODUCT-WORK-BOARD.yaml`.
- **CAMSCAN-PROD-001/002/003 are DELIVERED and are your base.** Your capture foundation,
  document detection, and geometry/processing engine (193 green JVM tests) are the branch
  head. This work order is your track's capstone: the **scan session** — the
  end-to-end scanner journey: capture → review (accept/retake/crop/rotate/enhance) →
  multi-page → finish → document. The shell and persistence are OTHER workers' surfaces
  (PROD-005 delivered; PROD-006 persistence in flight) — you consume the CORE CONTRACTS
  read-only per §6.1 and keep the wiring note for the integration station.
- Priority rule: P0 "scan a document" — this IS the P0 completion. Do not build
  P1/P2/P3 features (no OCR, no export, no import).
- Offline-first: no network. No account. No cloud.

# 3. ENVIRONMENT SETUP (run exactly, in order)

```bash
git clone https://github.com/payswapdotorg/CamScan.git CamScan
cd CamScan
git fetch origin work/CAMSCAN-PROD-003
git checkout -b work/CAMSCAN-PROD-004 200f01e69694337c1b3c21a3256a80ed81ceab51
git config user.name "CamScan Worker 1"
git config user.email "worker1@camscan.invalid"
git rev-parse HEAD   # MUST print 200f01e69694337c1b3c21a3256a80ed81ceab51
ls app/src/main/java/org/payswap/camscan/capture/   # camera/ + detect/ + your processing tree
```

If your sandbox retains the prior CamScan clone + JDK + android-sdk + Gradle cache,
reuse them — your PROD-003 gate ran green there. Repeat the full gate this order.

# 4. SHARED CONTRACT LAYER (lead-owned, frozen — already on your base)

Read before writing code; consume READ-ONLY:
- `core/model/Documents.kt` — Document, Page, PageEnhancementMode, Corner, cropQuad,
  processedImageRef, sourceCaptureRef, rotationDegrees, ocrResultId
- `core/repository/DocumentRepository.kt` — observeDocuments/getDocument/getPages/
  upsertDocument/deleteDocument (suspend, Flow)
- `core/storage/ContentStore.kt` — put(key, bytes)->ref / open / delete / exists
- `core/time/TimeSource.kt`, `core/navigation/ScanEntry.kt` (ScanHost/ScanLauncher)
- `gradle/libs.versions.toml`, `app/build.gradle.kts`, `AndroidManifest.xml`

Do not modify any of them. Do not add sibling files under `core/` — report needed
core changes as open questions instead (the §6.1 dependency-injection design exists
precisely so no core change is needed).

# 5. YOUR OWNERSHIP BOUNDARY (binding)

You MAY create/modify:
- `app/src/main/java/org/payswap/camscan/capture/session/**` (new — this work order's tree)
- `app/src/test/java/org/payswap/camscan/capture/session/**` (your JVM tests)
- `app/src/androidTest/java/org/payswap/camscan/capture/session/**` (instrumentation)
- your own prior trees: `capture/camera/**`, `capture/detect/**`, `processing/**` and
  their tests — extend ONLY where §6.6 requires wiring; no opportunistic refactors
- `app/src/main/res/layout/fragment_*.xml` for YOUR fragments (fragment_review.xml,
  fragment_session_pages.xml — new names), `values/strings_capture.xml`,
  `values/colors_capture.xml` (your resources ONLY)

You MUST NOT touch:
- the §4 contract layer; `MainActivity.kt`, `activity_main.xml`, `res/values/strings.xml`,
  `res/values/themes.xml`, `AndroidManifest.xml`
- anything under `document/`, `library/`, `export/`, `settings/`, `ocr/`, `tools/`,
  `search/`, or outside `app/`

# 6. TASK — SCAN SESSION

Implement, in `app/src/main/java/org/payswap/camscan/capture/session/` (plus wiring in
your own camera tree + tests). Seam names are contractual
(`docs/SCAN-ENGINE-CONTRACT.md`: `ScanSession` with addPage / retake / remove /
reorder / finish — exact class names may vary, the seams may not disappear).

1. **SessionPersistence** (YOUR interface, in your tree — consumes core contracts):
   ```kotlin
   interface SessionPersistence {
     val repository: DocumentRepository   // core contract, read-only use
     val contentStore: ContentStore        // core contract, read-only use
     val timeSource: TimeSource
     val idGenerator: () -> String
   }
   ```
   Plus a `SessionPersistence.UNAVAILABLE` singleton (or null-object) used when the
   shell has not wired persistence yet — sessions then finish with
   `onScanFinished(null)` (the current placeholder behavior, honestly preserved).
2. **SessionPage** (in-memory page under review): the captured source ImageBuffer, the
   detected quad (nullable), the ProcessedImage (warped + enhanced via your
   PROD-003 ProcessingPipeline), current enhancement mode, current rotation (0/90/180/270),
   page order index, retake lineage (supersedes id — stable final ids per the contract's
   "retake/delete state" rule: a retake REPLACES the page at the same index; ids stay
   stable once finished).
3. **ScanSession** (pure Kotlin state machine — the contract seam):
   - `addPage(page: SessionPage)` (append at end), `retake(index: Int, newPage)` (atomic
     replace at index; old page's refs swept on finish), `remove(index)`,
   `reorder(from: Int, to: Int)` (list-move semantics, documented),
   `setEnhancement(index, mode)` + `setRotation(index, degrees)` (re-process lazily or
   eagerly — document which; deterministic either way),
   `finish(): SessionResult?` — builds the durable payload: for each page, encode the
     processed image as PNG bytes (your own encoder? NO — see §6.4 note: use
     android.graphics.Bitmap compress in the ADAPTER; the pure layer emits
     ImageBuffer + metadata and the adapter persists), normalized cropQuad
     (QuadF.toNormalizedCorners), rotationDegrees, enhancement mode.
   - Illegal transitions rejected without throwing (your established machine discipline);
     a session with zero pages finishes null (abort semantics).
4. **Session image persistence adapter** (thin, android-side):
   `SessionPersistAdapter` — takes SessionResult + SessionPersistence: PNG-encodes each
   processed ImageBuffer (Bitmap.compress PNG, deterministic quality settings only),
   `contentStore.put("pages/<pageId>.png", bytes)` → refs, builds core `Page` list +
   one `Document` (title default "Scan <yyyy-MM-dd HH:mm>" from TimeSource — formatted
   via java.time with an injected zone, NOT device default), `repository.upsertDocument`,
   returns the document id. Runs on a caller executor; suspend functions all the way.
5. **ReviewFragment** (the post-capture review UI — `fragment_review.xml`):
   - shows the current page's processed image (rotation applied) in `review_page_image`;
   - accept → `review_accept_button` (keeps page, proceeds);
   - retake → `review_retake_button` (back to the scan surface, atomic replace on next
     capture);
   - crop-adjust → `review_crop_button` opening a crop-adjust sheet (`review_crop_sheet`):
     draggable corner handles over the processed image (your own touch logic; no new
     dependencies) emitting an adjusted quad → re-run the pipeline correction from the
     SOURCE capture (non-destructive per the contract);
   - rotate → `review_rotate_button` (cycles 0/90/180/270);
   - enhancement selector → `review_enhancement_group` (radio-like row of the 6 modes,
     `review_enhancement_original/…/_low_light` ids) re-processing from the source;
   - quality banner `review_quality_banner` (from QualityGates flags: BLURRED/GLARE/...).
6. **Multi-page capture loop + wiring in YOUR camera tree**:
   - ScanFragment: after a capture completes (your PROD-001 seam), route into
     ReviewFragment (fragment transaction in the SAME container, back-stack entry);
     accept returns to the scan surface with the page added; a session tray
     (`session_page_count_badge`) shows "N pages"; `scan_done_button` now finishes the
     SESSION (not just exits): ScanSession.finish() → adapter persists (when
     SessionPersistence wired) → `ScanHost.onScanFinished(documentId)` (or null when
     persistence unavailable or zero pages).
   - CameraScanLauncher: gains an OPTIONAL constructor parameter
     `sessionPersistence: SessionPersistence? = null` (default null = today's behavior);
     passes it into ScanFragment. The no-arg construction the shell uses today keeps
     compiling unchanged — the integration station later swaps in the wired
     construction (a one-line change in the SHELL's file, not yours — record this in
     your report's handoffs).
7. **JVM unit tests** (`app/src/test/java/org/payswap/camscan/capture/session/`):
   - ScanSession machine: add/retake/remove/reorder semantics (order, indices, atomic
     replace, retake lineage), enhancement/rotation set + re-process determinism,
     finish payload correctness (quads normalized, rotation/mode per page), zero-page
     finish → null, illegal-transition rejections.
   - SessionResult/adapter logic (android-free parts): page metadata assembly, title
     formatting under a fixed TimeSource + zone, id stability across retakes.
   - Use your PROD-002 SyntheticFrames + PROD-003 pipeline fakes as fixtures.
8. **Instrumentation skeletons** (`app/src/androidTest/.../session/`):
   `ReviewFlowTest.kt` — launch scan → simulated capture → review visible with the
   stable ids; annotated lead-station-only.

**Stable semantic ids (exact):**
`review_page_image`, `review_accept_button`, `review_retake_button`, `review_crop_button`,
`review_crop_sheet`, `review_rotate_button`, `review_enhancement_group`,
`review_quality_banner`, `session_page_count_badge`.
All PROD-001/002 ids unchanged. Every interactive view gets a `contentDescription`
from `strings_capture.xml`.

**Discipline:**
- Kotlin 2.0, JVM target 17, minSdk 26. No new dependencies. No Compose.
- Deterministic where pure; time only via TimeSource; ids only via idGenerator.
- Keep 6.1–6.3 + 6.7 android-free; adapters/UI thin.
- The full Gradle gate if the toolchain is warm: testDebugUnitTest + lintDebug +
  assembleDebug + compileDebugAndroidTestKotlin.

# 7. VERIFICATION (honest, bounded)

Same rules as your prior orders: full Gradle gate if possible (verbatim summaries);
static self-checks always; NEVER fabricate output; budget-box toolchain repair to
~20 minutes.

# 8. DELIVERY (git, in-sandbox — you have NO push credentials)

```bash
cd CamScan
git add -A
git commit -m "CAMSCAN-PROD-004: scan session — review (accept/retake/crop/rotate/enhance), multi-page loop, session persistence adapter on the PROD-003 processing base"
git bundle create camscan-prod-004.bundle 200f01e69694337c1b3c21a3256a80ed81ceab51..work/CAMSCAN-PROD-004
cp camscan-prod-004.bundle ../camscan-prod-004.bundle
sha256sum camscan-prod-004.bundle
git diff --stat 200f01e69694337c1b3c21a3256a80ed81ceab51..work/CAMSCAN-PROD-004
```

Also write `CamScan/DELIVERY-PROD-004.txt` (all paths + bundle sha256) and copy it + the
bundle to the workspace root next to your prior artifacts. Your sandbox files ARE the
delivery channel — the Tech Lead harvests them (pod files-API or an inline-transit
order). Do not delete your work. Do not push anywhere.

# 9. FINAL REPORT (your last chat message — report ONLY, no file dumps)

```
=== CAMSCAN-PROD-004 COMPLETION REPORT ===
task: scan session — review, crop, rotate, retake, multi-page capture
environment: sandbox OS + toolchain actually available
what was implemented/observed: …
verification: commands + outputs (verbatim — never invented)
evidence: artifact list + sha256 (bundle path, DELIVERY-PROD-004.txt) — no raw bytes
assumptions: …
open questions / handoffs: … (include the SessionPersistence wiring note for the shell)
base sha: 200f01e69694337c1b3c21a3256a80ed81ceab51
```

Status vocabulary is binding: `implemented` ≠ `verified` ≠ `reconciled` ≠ `accepted`.
Report exactly what exists. A green self-review is not a pass.
