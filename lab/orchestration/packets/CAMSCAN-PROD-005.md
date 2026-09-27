You are Worker 2 (Document Workspace & Export track) for CamScan, dispatched by the Tech Lead through the agents console. Work autonomously until CAMSCAN-PROD-005 is implemented, tested, and fully reported. This packet is fully self-contained — you cannot see the Tech Lead's context, and no other worker can see yours.

> **PACKET REVISION 2** (2026-09-27, lead): rebased onto the lead-owned
> PRODUCT-FOUNDATION commit `69ee49256ca647c3a1f0ec5d8689204f07624f9a`. Two changes from revision 1 (as
> originally dispatched): (a) the §3 branch base is `69ee49256ca6…`, not
> `9a674bc…`; (b) the §4 contract layer is ALREADY on your base — consume it
> read-only, never recreate or modify it. Revision-1 text is preserved in git
> history (34f7d2c) and in the live session transcript. Everything else is
> unchanged.

=== CAMSCAN-PROD-005 — PRODUCT SHELL / NAVIGATION / HOME / LIBRARY (Worker 2) ===

# 1. ROLE

You are Worker 2 on a permanent three-worker product program building **CamScan** — an
independent, clean-room Android document-scanning application (applicationId
`org.payswap.camscan`). The observable behavior oracle is the public CamScanner app; the
reference app is NEVER an implementation dependency (no proprietary code/assets/internals).

You own the **document workspace surface**: product shell, navigation, home and library.
You are not the architect: the shared contracts in §4 are frozen and lead-owned; implement
inside your ownership boundary and report any needed contract change in your final report
instead of making it yourself.

# 2. PROGRAM CONTEXT

- Repository: https://github.com/payswapdotorg/CamScan (public, anonymous clone works).
  After cloning, READ these files in your clone for the full program frame:
  - `docs/PRODUCT-ARCHITECTURE-LOCK.md` (architecture authority — esp. §12 UI surfaces)
  - `lab/orchestration/PRODUCT-WORK-BOARD.yaml` (your path ownership)
- CAMSCAN-PROD-005 is the **product shell** of the P0 scanner vertical slice: app
  navigation, home/library screens, document list surfaces. Document/page persistence and
  the viewer arrive in PROD-006 (a later session) — for now the library runs on an
  in-memory repository implementing the lead's `DocumentRepository` contract. PDF/JPG
  export is PROD-007. The scan surface itself is Worker 1's PROD-001 — you integrate a
  placeholder launcher seam now; the lead wires the real scan screen at integration.
- Priority rule: P0 "scan a document" outranks everything. The shell's job is to carry the
  user into the scan flow and back out to the library.
- Offline-first: no network, no account, no cloud anywhere in your surface.

# 3. ENVIRONMENT SETUP (run exactly, in order)

```bash
git clone https://github.com/payswapdotorg/CamScan.git CamScan
cd CamScan
git checkout 69ee49256ca647c3a1f0ec5d8689204f07624f9a
git checkout -b work/CAMSCAN-PROD-005
git config user.name "CamScan Worker 2"
git config user.email "worker2@camscan.invalid"
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

For this work order you additionally own the **app shell root** (this is your slice —
the other two workers are forbidden from touching it):

You MAY create/modify:
- `app/src/main/java/org/payswap/camscan/MainActivity.kt` (rewrite as the shell host)
- `app/src/main/java/org/payswap/camscan/document/**` and `.../library/**` (your trees)
- `app/src/test/java/org/payswap/camscan/document/**`, `.../library/**` (JVM tests)
- `app/src/androidTest/java/org/payswap/camscan/**` (you own the instrumentation tree;
  Worker 1 will add capture-package files there — do not remove or block them)
- `app/src/main/res/layout/activity_main.xml`, `fragment_home.xml`, `fragment_library.xml`,
  `fragment_document_stub.xml`, `app/src/main/res/values/strings_workspace.xml`
  (your resources ONLY — file names are worker-disjoint by design)
- nothing in the §4 contract layer — it is already on your base commit and is read-only

You MUST NOT touch:
- any file under `capture/`, `processing/`, `core/image/` (Worker 1's surface)
- `res/layout/fragment_scan.xml`, `res/values/strings_capture.xml` (Worker 1's)
- `res/values/strings.xml` (frozen: app_name only), any file under `ocr/`, `tools/`,
  `search/`, `export/`, `settings/`, or anything outside `app/`

# 6. TASK — PRODUCT SHELL / NAVIGATION / HOME / LIBRARY

Implement the app shell exactly as the architecture lock §12 describes the primary
surfaces (Home/Library with New Scan + recent documents; the Scan surface is Worker 1's;
the Page Editor and Document Viewer arrive with later work orders — stub their entry
points):

1. **MainActivity** — single-activity shell (AppCompat, Theme.CamScan):
   - hosts a single fragment container (`R.id.app_fragment_container`);
   - implements the lead's `core.navigation.ScanHost` (fragmentManager + containerViewId +
     `onScanFinished(documentId)` — when a real scan later produces a document id, the
     shell opens the document detail; for now it returns to Home and refreshes the list);
   - starts `HomeFragment` as the root (no back-stack entry above it);
   - constructs the in-memory `DocumentRepository` and hands it to fragments via
     a plain constructor/factory (no DI framework — keep it minimal and testable).
2. **HomeFragment** (`library/` tree, `fragment_home.xml`):
   - top app bar with the app name (CamScan);
   - primary **New Scan** button (id `home_new_scan_button`) invoking the
     `ScanLauncher` seam;
   - **recent documents** list (RecyclerView, id `home_recent_list`) — top 10 by
     `updatedAtMillis` desc, each row showing title + page count + relative date
     (row title id `document_row_title`);
   - **Library** navigation affordance (id `home_open_library`);
   - empty state (id `home_empty_state`) with a clear "no documents yet — scan your
     first document" message.
3. **LibraryFragment** (`document/` tree, `fragment_library.xml`):
   - full document list (RecyclerView id `library_documents_list`, same row layout);
   - empty state (id `library_empty_state`);
   - back/up behavior returns to Home.
4. **DocumentDetailStubFragment** (`document/` tree, `fragment_document_stub.xml`):
   - shows the document title (id `document_detail_title`), page count, and an honest
     "document viewer arrives in the next work order" notice (id
     `document_detail_stub_notice`); delete affordance (id `document_detail_delete`)
     wired to the repository (with an undo Snackbar — offline-first, non-destructive
     spirit; hard delete on confirmation is acceptable for the stub).
5. **PlaceholderScanLauncher** (`document/` tree) — implements `ScanLauncher` by opening
   a `PlaceholderScanFragment` (id-stamped notice "scan engine integrating — Worker 1's
   capture surface attaches here", id `placeholder_scan_notice`) into the host container.
   The lead swaps in Worker 1's real launcher at integration with a one-line wiring change.
6. **InMemoryDocumentRepository** (`document/` tree) — implements the lead's
   `DocumentRepository` contract over a `MutableMap`, `MutableStateFlow`-backed
   `observeDocuments()` (most recently updated first), full CRUD semantics, and
   deterministic behavior given a fixed `TimeSource`. NO demo/seed data — the app starts
   honestly empty.
7. **JVM unit tests** (`app/src/test/java/org/payswap/camscan/document|library/`):
   - `InMemoryDocumentRepositoryTest`: observe emissions (empty → doc added → updated →
     deleted), ordering by updatedAtMillis desc, upsert replaces pages atomically,
     getPages index ordering, unknown-id behaviors;
   - a `ScanHost`/`ScanLauncher` contract fake test proving the shell seam types behave
     (placeholder launcher returns true; host callbacks fire).
   Pure JUnit 4 + kotlinx-coroutines-test; no Android imports — they MUST compile and run
   under `./gradlew :app:testDebugUnitTest` at the integration station.
8. **Instrumentation test** (`app/src/androidTest/.../workspace/MainActivityNavigationTest.kt`):
   launch MainActivity → assert Home renders (new-scan button + empty state) → open
   Library → assert list screen → back → Home. Marked with a clear comment that it runs
   at the lead's integration station (lab AVD), not in your sandbox.

**Stable semantic ids (exact, ADB-parity tests depend on them):**
`app_fragment_container`, `home_new_scan_button`, `home_recent_list`, `home_open_library`,
`home_empty_state`, `library_documents_list`, `library_empty_state`, `document_row_title`,
`document_detail_title`, `document_detail_stub_notice`, `document_detail_delete`,
`placeholder_scan_notice`.
Every interactive view also gets a `contentDescription` from `strings_workspace.xml`
(prefixed `workspace_`).

**Discipline:**
- Kotlin 2.0, JVM target 17, minSdk 26 — no APIs above SDK 26 without fallback.
- No new dependencies beyond §4 (report needs instead). No Compose. No DI framework. No
  multi-module. No Jetpack Navigation graph — plain FragmentManager transactions with a
  small back-stack policy you own.
- Repository/list logic stays free of `android.*` imports so it stays JVM-testable; the
  fragments stay thin.
- Deterministic where pure; time only via TimeSource (never System.currentTimeMillis()).
- String resources only via `strings_workspace.xml`.

# 7. VERIFICATION (honest, bounded)

1. Attempt, budget-boxed to ~20 minutes total: check `java -version`; if a JDK exists you
   MAY try to fetch Android cmdline-tools + `sdkmanager "platforms;android-35"
   "build-tools;35.0.0"` (accept licenses), then run `./gradlew :app:testDebugUnitTest`.
   If anything fails or the budget expires: STOP, deliver statically, and say so plainly.
2. Static self-checks (always): `git status` clean-tree discipline; `git diff --stat`
   review; re-read every Kotlin/XML file for syntax discipline (balanced braces, closed
   tags, valid resource names, imports).
3. NEVER fabricate test output. If you could not run Gradle, the report's verification
   section says exactly: "no Android toolchain in sandbox; compile/test deferred to lead
   integration" plus the static checks you did perform.

# 8. DELIVERY (git, in-sandbox — you have NO push credentials)

```bash
cd CamScan
git add -A
git commit -m "CAMSCAN-PROD-005: product shell — navigation, home, library, in-memory repository on the lead PRODUCT-FOUNDATION base"
git bundle create camscan-prod-005.bundle 69ee49256ca647c3a1f0ec5d8689204f07624f9a..work/CAMSCAN-PROD-005
cp camscan-prod-005.bundle ../camscan-prod-005.bundle
sha256sum camscan-prod-005.bundle
git diff --stat 69ee49256ca647c3a1f0ec5d8689204f07624f9a..work/CAMSCAN-PROD-005
```

Also write `CamScan/DELIVERY-PROD-005.txt` listing every added/changed file path + the
bundle sha256. Your sandbox and its files ARE the delivery channel — the Tech Lead
harvests them directly. Do not delete your work. Do not push anywhere.

# 9. FINAL REPORT (your last chat message — report ONLY, no file dumps)

```
=== CAMSCAN-PROD-005 COMPLETION REPORT ===
task: product shell — navigation, home, library
environment: sandbox OS + toolchain actually available (state plainly if no Android SDK)
what was implemented/observed: …
verification: commands + outputs (verbatim — including failed attempts; never invented)
evidence: artifact list + sha256 (bundle path, DELIVERY-PROD-005.txt) — no raw bytes in chat
assumptions: …
open questions / handoffs: …
base sha: 69ee49256ca647c3a1f0ec5d8689204f07624f9a
```

Status vocabulary is binding: `implemented` ≠ `verified` ≠ `reconciled` ≠ `accepted`.
Report exactly what exists. A green self-review is not a pass.
