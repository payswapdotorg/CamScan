You are Worker 2 (Document Workspace & Export track) for CamScan, dispatched by the Tech Lead through the agents console. Work autonomously until CAMSCAN-PROD-006 is implemented, tested, and fully reported. This packet is fully self-contained — you cannot see the Tech Lead's context, and no other worker can see yours.

> **EXECUTION DIRECTIVE (binding, platform lesson):**
> do NOT stop to acknowledge this packet. Your FIRST action is §3 setup; your
> LAST action is the §9 final report. Execute end-to-end in THIS turn:
> §3 setup -> §6 implement -> §7 checks -> §8 commit+bundle -> §9 report as your
> reply. No acks, no status updates, no clarifying questions — the packet is
> complete. If genuinely blocked, the report states the blocker honestly with
> everything you did complete. If your turn is interrupted mid-work, the next
> directive will resume from your persisted sandbox state.

=== CAMSCAN-PROD-006 — PERSISTENCE & DOCUMENT VIEWER (Worker 2) ===

# 1. ROLE

You are Worker 2 on a permanent three-worker product program building **CamScan** — an
independent, clean-room Android document-scanning application (applicationId
`org.payswap.camscan`). The observable behavior oracle is the public CamScanner app; the
reference app is NEVER an implementation dependency.

You own the **document workspace surface**: persistence, viewer, and (later) export. The
shared contracts in §4 are frozen and lead-owned; report any needed contract change in
your final report instead of making it yourself.

# 2. PROGRAM CONTEXT

- Repository: https://github.com/payswapdotorg/CamScan (public, anonymous clone works).
  After cloning, READ these files in your clone:
  - `docs/PRODUCT-ARCHITECTURE-LOCK.md` (architecture authority)
  - `docs/SCAN-ENGINE-CONTRACT.md` (Multi-page + Review sections)
  - `lab/orchestration/PRODUCT-WORK-BOARD.yaml` (your path ownership)
- **CAMSCAN-PROD-005 is DELIVERED and is your base.** Your own product shell
  (MainActivity + HomeFragment + LibraryFragment + DocumentDetailStubFragment +
  PlaceholderScanLauncher + InMemoryDocumentRepository + 19 JVM/Espresso tests) is the
  branch head you build on. This work order replaces the "stub" viewer with the real
  **document/page persistence and viewer**: a durable repository backed by on-device
  storage through the lead's ContentStore contract, a page-detail viewer with
  reorder/delete, and honest empty/loading states. Import/merge/split/compress and
  PDF/JPG export arrive in PROD-007/008 — do NOT implement them now.
- Priority rule: P0 "scan a document" (save → reopen → library) outranks everything.
- Offline-first: no network. No account. No cloud.

# 3. ENVIRONMENT SETUP (run exactly, in order)

```bash
git clone https://github.com/payswapdotorg/CamScan.git CamScan
cd CamScan
git fetch origin work/CAMSCAN-PROD-005
git checkout -b work/CAMSCAN-PROD-006 01406f4e3bcb6f7fc957fbbab1e2ab2e98c1c86b
git config user.name "CamScan Worker 2"
git config user.email "worker2@camscan.invalid"
git rev-parse HEAD   # MUST print 01406f4e3bcb6f7fc957fbbab1e2ab2e98c1c86b
ls app/src/main/java/org/payswap/camscan/document/   # your PROD-005 files
```

(The lead's reassembly of your PROD-005 delivery is the pushed head; your prior inline
delivery content is byte-equivalent — your PROD-005 files are all present.)

# 4. SHARED CONTRACT LAYER (lead-owned, frozen — already on your base)

The wave-0 contracts landed on main as PRODUCT-FOUNDATION (69ee492) and are ancestors of
your base. Nobody may modify them. Your §6 uses them HEAVILY:

- `app/src/main/java/org/payswap/camscan/core/model/Documents.kt` — Document, Page,
  PageEnhancementMode, Corner, cropQuad, processedImageRef, ocrResultId
- `app/src/main/java/org/payswap/camscan/core/repository/DocumentRepository.kt` — the
  domain persistence contract (Flow-based observe, upsert, delete, getPages…)
- `app/src/main/java/org/payswap/camscan/core/storage/ContentStore.kt` — opaque-ref
  binary asset contract: put(bytes, refHint?) -> ref, open(ref) -> bytes?, delete(ref),
  exists(ref). THIS is your persistence substrate — a file-backed implementation lives
  on YOUR side of the seam (see §6.1).
- `core/time/TimeSource.kt` (deterministic time), `core/navigation/ScanEntry.kt`

Do not modify any of them. Do not add sibling files under `core/`.

# 5. YOUR OWNERSHIP BOUNDARY (binding)

You MAY create/modify:
- `app/src/main/java/org/payswap/camscan/document/**` (your tree — extend freely)
- `app/src/main/java/org/payswap/camscan/library/**` (your tree)
- `app/src/test/java/org/payswap/camscan/document/**`, `library/**` (JVM tests)
- `app/src/androidTest/java/org/payswap/camscan/document/**` (instrumentation)
- `app/src/main/res/layout/fragment_*.xml`, `item_*.xml` (your layouts),
  `app/src/main/res/values/strings_workspace.xml` (+ new `values/arrays_workspace.xml`
  if needed) — your resources ONLY

You MUST NOT touch:
- the §4 contract layer (read-only)
- `MainActivity.kt` wiring beyond the §6.4-listed additions, `activity_main.xml` ids,
  `res/values/strings.xml`, `res/values/themes.xml`, `AndroidManifest.xml`
- anything under `capture/`, `ocr/`, `tools/`, `search/`, or outside `app/`

# 6. TASK — PERSISTENCE & VIEWER

1. **FileContentStore** (`document/persistence/FileContentStore.kt`): implements the
   lead's `core.storage.ContentStore` over a single app-private directory
   (`context.filesDir/content`): put = atomic write (tmp file + rename) with a
   ref vocabulary you define + document (e.g. `<uuid>.bin`), open = full read,
   delete = best-effort unlink, exists = stat. Constructor-injected root dir
   (JVM tests use a temp dir — java.io only, no android.* in the pure logic;
   an android.Context factory function is the only android-importing line).
2. **PersistentDocumentRepository** (`document/persistence/`): implements the lead's
   `core.repository.DocumentRepository` contract. Design:
   - A single JSON index file (`content/index.json`) — your schema, versioned
     (`schemaVersion: 1`), containing Documents + Pages (ids, titles, timestamps,
     refs, cropQuads, enhancement, rotation, ordering). Deterministic serialization
     (sorted keys or fixed field order — byte-stable rewrites).
   - Page images and future blobs go through ContentStore refs recorded in the index
     (`processedImageRef`, `sourceCaptureRef` semantics per the Documents model).
   - Write path: mutate in-memory snapshot → serialize → atomic rewrite of index +
     ContentStore puts → THEN delete now-unreferenced refs (orphan sweep order —
     crash between index-write and sweep must never lose live refs: sweep deletes only
     refs unreferenced by the CURRENT index).
   - Read path: load + parse on first access; corrupted index ⇒ start empty + flag
     (never throw to the UI); a `.bak` of the previous index is kept and restored if
     the primary is corrupt.
   - All timestamps via TimeSource. All ids via an injectable `IdGenerator`
     (`fun interface` returning String) — deterministic in tests.
3. **Viewer UI** (`document/`):
   - Replace DocumentDetailStubFragment's stub notice with a real
     **DocumentViewerFragment**: toolbar with document title (editable via dialog —
     updateTitle through the repository), page count, and:
     - `document_pages_pager` (ViewPager2 or RecyclerView-based pager — NO new
       dependencies: use androidx.viewpager2 ONLY if already in the §4 version
       catalog; otherwise a RecyclerView with a snap helper) showing each page's
       processed image (loaded from ContentStore via ref, decoded on a background
       executor, placeholder while loading);
     - page indicator `document_page_indicator` ("3 / 7" style);
     - delete-page affordance `document_page_delete` (confirm dialog; repository
       remove + orphan sweep; empty-document state → honest empty view + delete
       document);
     - reorder affordance `document_reorder_pages` entering an order-editing mode:
       `document_reorder_list` with up/down moves per page (deterministic indices,
       no drag-and-drop library) — persist reordered indices atomically;
     - rotation display only (rotationDegrees applied at render) — actual re-edit UI
       belongs to the scan-session review (W1's PROD-004), not here.
   - Library/Home: replace InMemoryDocumentRepository wiring in MainActivity with the
     PersistentDocumentRepository (the §6.4 wiring change). Keep the InMemory impl in
     the tree (tests + PROD-005 history use it).
   - PROD-005's stable ids all remain; new ids: `document_pages_pager`,
     `document_page_indicator`, `document_page_delete`, `document_reorder_pages`,
     `document_reorder_list`, plus `document_empty_state` (viewer-level).
     All interactive views get `contentDescription`s from `strings_workspace.xml`.
4. **Wiring changes in MainActivity** (the ONLY root-file edits): construct
   FileContentStore + PersistentDocumentRepository + IdGenerator + TimeSource and
   pass them into the fragment factory (replacing the InMemory construction). No
   other MainActivity changes.
5. **JVM unit tests** (`app/src/test/java/org/payswap/camscan/document/`): pure JUnit 4
   with temp dirs + fake TimeSource/IdGenerator:
   - FileContentStore: put/open roundtrip (bytes identical), exists/delete, atomic
     overwrite, unknown-ref open → null.
   - PersistentDocumentRepository: contract-complete behavior (all 7 methods):
     observe emissions on upsert/delete/updateTitle/reorder; persisted-across-reload
     (new instance on same dir sees identical documents — THE save/reopen test);
     page ordering + reorder persistence; delete-page orphan sweep (file gone after
     index rewrite; crash-simulation: orphan left behind is cleaned on next write);
     corrupt index → empty + backup restored; byte-stable index rewrite (same logical
     state → identical bytes); concurrent-observers get consistent snapshots.
   - Viewer logic: pure view-model style tests for page list/indicator/reorder state
     transitions (android-free logic classes).
6. **Instrumentation tests** (`app/src/androidTest/.../document/`): Espresso —
   Library→viewer open (page count + indicator visible), page delete flow with
   confirm, reorder mode completes; annotated lead-station-only (no emulator in your
   sandbox).

# 7. VERIFICATION (honest, bounded)

1. If your sandbox has JDK + Android SDK + Gradle cache, run
   `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` and report
   verbatim summaries. Budget-box toolchain setup to ~20 minutes; otherwise deliver
   statically and say so plainly.
2. Static self-checks (always): clean tree; `git diff --stat` review; XML parse
   validation; brace balance; stable-id grep audit; ownership audit
   (`git diff --name-only` against your §5 boundary).
3. NEVER fabricate test output.

# 8. DELIVERY (git, in-sandbox — you have NO push credentials)

```bash
cd CamScan
git add -A
git commit -m "CAMSCAN-PROD-006: persistence & viewer — FileContentStore, PersistentDocumentRepository, page viewer with reorder/delete on the PROD-005 shell base"
git bundle create camscan-prod-006.bundle 01406f4e3bcb6f7fc957fbbab1e2ab2e98c1c86b..work/CAMSCAN-PROD-006
cp camscan-prod-006.bundle ../camscan-prod-006.bundle
sha256sum camscan-prod-006.bundle
git diff --stat 01406f4e3bcb6f7fc957fbbab1e2ab2e98c1c86b..work/CAMSCAN-PROD-006
```

Also write `CamScan/DELIVERY-PROD-006.txt` (all paths + bundle sha256) and copy it +
the bundle to the workspace root. Your sandbox files ARE the delivery channel — the
Tech Lead harvests via the pod files-API or (if unreachable) an inline-transit order.
Do not delete your work. Do not push anywhere.

# 9. FINAL REPORT (your last chat message — report ONLY, no file dumps)

```
=== CAMSCAN-PROD-006 COMPLETION REPORT ===
task: document/page persistence and viewer
environment: sandbox OS + toolchain actually available
what was implemented/observed: …
verification: commands + outputs (verbatim — including failed attempts; never invented)
evidence: artifact list + sha256 (bundle path, DELIVERY-PROD-006.txt) — no raw bytes in chat
assumptions: …
open questions / handoffs: …
base sha: 01406f4e3bcb6f7fc957fbbab1e2ab2e98c1c86b
```

Status vocabulary is binding: `implemented` ≠ `verified` ≠ `reconciled` ≠ `accepted`.
Report exactly what exists. A green self-review is not a pass.
