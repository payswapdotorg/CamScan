#!/usr/bin/env python3
"""CAMSCAN-004 — generate the per-scenario observation protocol records.

One markdown file per S001–S018 scenario area under
docs/reference-observations/. Each file records: the observation workflow to
perform (exact UI flow), what to capture, state changes / outputs / edge cases
to check, the session status (NOT-OBSERVED + blocker), and static-APK-informed
expectations (clearly marked UNVERIFIED). Re-runnable: content is derived from
this table so regeneration is deterministic.
"""
from __future__ import annotations

from pathlib import Path

OUT = Path(__file__).resolve().parents[2] / "docs" / "reference-observations"

BLOCKER = (
    "**Status: NOT-OBSERVED — blocked by the reference-substrate credential** "
    "(no `E2B_API_KEY` in this worker sandbox; see "
    "[REPORT.md §3](REPORT.md#3-blocker--reference-substrate-credential-absent-from-this-worker-sandbox) "
    "for the verbatim attempt record). Static manifest facts below are "
    "OBSERVED-and-verified where marked."
)

# per-scenario customization: (file, title, flow, ui, state, outputs, edge, static, corrections)
S = {
    "S001-launch": dict(
        title="S001 — Cold launch of the application",
        flow=[
            "precondition via `provider.reset(ResetSpec(wipe_data=True, reinstall_apk=<official bundle splits>))` — fresh install state",
            "`am start`/`monkey -p com.intsig.camscanner -c android.intent.category.LAUNCHER 1` (launchable activity verified statically)",
            "screenshots + ui_hierarchy at 0 / 15 / 30 s; `dumpsys window | grep mCurrentFocus` per capture",
            "logcat (-d) for the first-run process: crash stack vs clean start",
            "`ps -A | grep com.intsig.camscanner` — process alive check",
        ],
        ui=["first foreground activity (onboarding? login? main library?)", "root controls visible"],
        state=["app foregrounded", "first-run flags in app storage (inaccessible — observe via next-run behavior instead)"],
        outputs=["none expected at launch (verify: no files appear under /sdcard/Android/data/com.intsig.camscanner/)"],
        edge=["launch after force-stop (warm start)", "launch offline (svc wifi/data disable) — splash/error states"],
        static="Launchable activity `com.intsig.camscanner.mainmenu.mainactivity.MainActivity` (OBSERVED, manifest-verified). Application class `CsApplication`, `largeHeap=true`, `hardwareAccelerated=true`.",
        corrections=[
            "assertion `cold-start-under-threshold` needs a concrete threshold (suggest ≤ 30 s to first frame under TCG — provider measured ~30–35 s per adb action; lead to pin the number)",
            "split the unknown: first-run foreground = onboarding (S002) — S001 should assert only process-up + no-crash + a visible root screen",
        ],
    ),
    "S002-onboarding": dict(
        title="S002 — First-run onboarding flow",
        flow=[
            "reset to fresh install (wipe_data)",
            "launch; capture every screen until a stable state (screenshot + ui dump per screen, back-driven where needed)",
            "record the onboarding decision tree verbatim: pages, buttons (Skip/Next/Agree), privacy-policy gate, login prompt position",
            "complete OR skip onboarding; then force-stop + relaunch — does onboarding reappear? (persistent flag)",
            "record account/session requirements: does first-run DEMAND login? which paths proceed anonymous? (work-order question)",
        ],
        ui=["onboarding pager steps + skip control", "login/signup screen presence + dismissability", "privacy-consent dialog text"],
        state=["onboarding-completed-flag observable via non-reappearance after relaunch"],
        outputs=["none expected"],
        edge=["back-press during onboarding (exit vs previous page)", "onboarding offline (privacy webview may fail offline)"],
        static="No static signal for onboarding structure. Flutter module + sentry present — first-run may show feature carousel / crash-report consent (UNVERIFIED).",
        corrections=[
            "assertion `onboarding-shown-on-first-run` may be FALSE for the official app (modern CamScanner versions land on the camera/library screen directly with EULA on first scan) — must be observed, not assumed",
            "add assertion: `login-not-required-for-core-scan-flow` (or its negation) — the work order explicitly asks which features gate on accounts",
        ],
    ),
    "S003-camera-permission": dict(
        title="S003 — Camera permission request and grant",
        flow=[
            "fresh install (camera permission baseline: `pm list permissions -g` / `dumpsys package` requested vs granted)",
            "launch; navigate to the scan/camera entry WITHOUT pre-granting",
            "capture the system permission dialog (screenshot + ui dump: `com.android.packageinstaller` / `com.google.android.permissioncontroller`)",
            "tap Allow; verify `dumpsys package com.intsig.camscanner` granted=true for CAMERA",
            "repeat fresh-install → Deny path: capture the app's denied-state UI (retry banner? settings deep-link?)",
            "repeat fresh-install → Allow-only-once (Android 11 one-time grant) if offered",
        ],
        ui=["system dialog text (While using the app / Only this time / Deny)", "app's rationale UI before/after denial"],
        state=["CAMERA granted=true/false/one-time", "possible bundled LOCATION prompt — record whether it appears at camera time"],
        outputs=["none"],
        edge=["deny → retry loop", "deny + don't-ask-again → settings deep-link", "permission revoked post-grant (pm revoke) then camera re-entry"],
        static="OBSERVED (manifest): `CAMERA` is requested and is a runtime dangerous permission on Android 11 — a system dialog WILL appear before first camera use. ALSO runtime: `READ_EXTERNAL_STORAGE`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` — expect possible additional prompts (EXIF geotagging). `WRITE_EXTERNAL_STORAGE` is auto-granted-but-ineffective (targetSdk 36).",
        corrections=[
            "steps assume a single camera prompt; add optional `grant-permission: location` step or an assertion capturing a possible bundled location prompt",
            "assertion `permission-requested-before-camera-use` is correct-by-manifest; the dialog is the OS's, not the app's UI — keep the UI assertion on the app's pre-permission rationale screen",
        ],
    ),
    "S004-single-document-capture": dict(
        title="S004 — Single-document capture end-to-end",
        flow=[
            "prereq: camera permission granted (S003 path) + camera fixture `clean-a4` (REQUIRES a working camera-fixture mechanism — provider `camera_fixture: false` today)",
            "launch → tap scan → observe live preview (virtualscene content) → capture (shutter)",
            "observe post-capture stage: crop/edit screen, detected edges",
            "accept/save → observe library with the new document",
            "hash every produced file (`find /sdcard/Android/data/com.intsig.camscanner -type f` + ls of legacy dirs /sdcard/CamScanner, DCIM, Pictures)",
        ],
        ui=["camera screen: shutter, torch, mode selector", "post-capture: crop corners, confirm/rotate/enhance controls", "library card for the saved doc"],
        state=["document-added-to-library"],
        outputs=["pdf or jpg per capture (names/formats/locations) — `adb shell ls` + pull + sha256 (work-order requirement)"],
        edge=["capture with no document in frame (empty scene)", "cancel from post-capture (discard path)", "capture offline"],
        static="Storage is scoped on Android 11 for targetSdk 36 → outputs expected under `/sdcard/Android/data/com.intsig.camscanner/files/...` or via MediaStore (UNVERIFIED prediction; `requestLegacyExternalStorage=true` is ignored for fresh installs on Android 11).",
        corrections=[
            "`output: pdf-created` is an assumption — modern CamScanner saves in-app pages (jpg) by default; PDF may be an explicit export (S016). Verify default save format empirically",
            "blocked until camera_fixture exists: `meta.requires.camera_fixture: true` cannot be satisfied by the current e2b capability report",
        ],
    ),
    "S005-automatic-document-detection": dict(
        title="S005 — Automatic document edge detection",
        flow=[
            "camera permission granted + `clean-a4` fixture in view",
            "observe the live preview overlay: edge rectangle updates as the 'document' appears in the virtualscene",
            "screenshot pairs (with/without document in frame) at t and t+3 s — overlay difference",
            "capture with document detected vs not — compare post-capture crops",
        ],
        ui=["live edge overlay (animated rectangle)", "shutter enabled state possibly gated on detection confidence"],
        state=[],
        outputs=["none (stage-level behavior)"],
        edge=["scene with no document-like content (does capture still fire?)", "partially-visible document (clipped edges)"],
        static="Scan engine is native (arm64 split) — S005 depends on the ABI-resolution open question (REPORT §6.2).",
        corrections=[
            "assertion `detection-overlay-shown-live` assumes continuous overlay — CamScanner historically shows a 'detect' state + hint text; record the actual affordance",
            "requires camera_fixture — currently unsatisfiable on e2b (capability report)",
        ],
    ),
    "S006-crop-confirmation": dict(
        title="S006 — Crop confirmation stage after capture",
        flow=[
            "capture with `clean-a4` (S004 path up to post-capture)",
            "screenshot + ui dump of the crop stage: corner handles, magnifier lobes, confirm button",
            "confirm WITHOUT adjustment → next stage",
            "record which controls the stage offers (adjust / rotate / enhance / more)",
        ],
        ui=["crop UI: 4 corner handles + edges", "confirm control label", "secondary controls"],
        state=[],
        outputs=["the stage's intermediate output naming (page added to edit buffer)"],
        edge=["confirm immediately vs after 10 s idle", "back from crop stage (discard capture?)"],
        static="—",
        corrections=["assertions are UI-level and fine; add the actual control labels once observed (target registry for CAMSCAN-002 needs them)"],
    ),
    "S007-manual-crop": dict(
        title="S007 — Manual crop adjustment",
        flow=[
            "capture with `skewed-document` fixture",
            "drag each corner handle in turn (provider `interact` swipe gestures on handle coordinates from the ui dump)",
            "observe preview re-render after each drag (screenshot per step)",
            "confirm → save → pull output; verify the crop reflects the dragged geometry (visual + output hash)",
        ],
        ui=["handle hit-targets (coordinates from ui_hierarchy)", "preview update latency"],
        state=[],
        outputs=["cropped-output-reflects-adjustment (compare against unadjusted capture of same fixture)"],
        edge=["degenerate crop (handles collapsed to a line)", "crop then cancel — original preserved?"],
        static="—",
        corrections=["step `adjust-crop` needs concrete gesture parameters once handle coordinates are known — capture them at first run"],
    ),
    "S008-perspective-correction": dict(
        title="S008 — Perspective correction after capture",
        flow=[
            "capture with `skewed-document` (off-angle view)",
            "at crop stage accept the detected quad (not manual) → save",
            "reopen the document → inspect rectification: page geometry vs original skew",
            "export PDF (S016 path) → verify rectangular page (pdfinfo/PyPDF page box) — semantic output assertion",
        ],
        ui=["post-correction preview"],
        state=["document-added-to-library"],
        outputs=["pdf-pages-rectangular (page aspect/box check, not pixel diff)"],
        edge=["extreme skew (near-90°) — does correction fail/degenerate?"],
        static="Perspective correction is native-engine work (arm64 split) — ABI open question applies.",
        corrections=["assertion `rectified-page-geometry` needs a measurable tolerance (e.g., corner angles 90°±3°) — pixel-perfect not required per LAB.md"],
    ),
    "S009-enhancement": dict(
        title="S009 — Enhancement modes",
        flow=[
            "capture with `low-light` fixture",
            "at the edit stage cycle through enhancement modes (labels + order from ui dump: e.g. Original / Magic / Grayscale / B&W / Lighten)",
            "screenshot per mode; record default-selected mode",
            "accept with one mode → save → pull output; verify enhancement visible in the output file",
        ],
        ui=["mode selector labels + order", "preview update per mode"],
        state=[],
        outputs=["enhanced-output-artifact (hash differs per mode)"],
        edge=["mode + rotate combined", "mode switch on a multi-page doc (per-page or all-pages?)"],
        static="—",
        corrections=["mode names are unknown until observed — capture verbatim labels for the target registry; assertions stay semantic"],
    ),
    "S010-rotate": dict(
        title="S010 — Page rotation",
        flow=[
            "capture with `clean-a4` → edit stage",
            "tap rotate (record control label + direction) — screenshot per tap (90° steps)",
            "save → pull PDF → verify page rotation metadata (pdfinfo 'Page rot' or render check)",
        ],
        ui=["rotate control (label, 90°-step behavior, long-press variants?)"],
        state=[],
        outputs=["pdf-page-rotated (page rotation attribute or rendered orientation)"],
        edge=["rotate 4× (full circle) — returns to original?", "rotate + crop order interplay"],
        static="—",
        corrections=["assertion `rotation-persists-in-output` should specify the check: PDF `/Rotate` attribute vs rendered pixels — choose the semantic check"],
    ),
    "S011-save": dict(
        title="S011 — Save document to library",
        flow=[
            "capture `clean-a4` → accept → save (record the save flow: filename prompt? tags? folder choice?)",
            "library list state before/after (screenshots)",
            "`adb shell ls -laR` of the app's external storage before/after — exact files, names, formats",
            "pull + sha256 every new file; identify default output format",
            "relaunch — document still listed (persistence)",
        ],
        ui=["save controls + any name/tag prompt", "library entry (thumbnail, page count, date)"],
        state=["document-added-to-library"],
        outputs=["pdf-created (VERIFY — may be jpg in-app page by default)", "exact output locations + names"],
        edge=["save offline (cloud-sync features may queue)", "duplicate names — auto-rename behavior"],
        static="OBSERVED: targetSdk 36 → scoped storage on Android 11; `WRITE_EXTERNAL_STORAGE` ineffective. UNVERIFIED prediction: outputs under `/sdcard/Android/data/com.intsig.camscanner/files/` (or MediaStore collections for shared items), NOT legacy `/sdcard/CamScanner`.",
        corrections=[
            "replace output-location assumption with: 'output files enumerated under the app-scoped external dir (exact subpaths recorded at first run)'",
            "default-format question (pdf vs in-app jpg) must be answered by observation before locking the assertion",
        ],
    ),
    "S012-reopen-saved-document": dict(
        title="S012 — Reopen a saved document",
        flow=[
            "prereq: a saved document exists (S011 product)",
            "launch → library → tap the document (coordinates from ui dump)",
            "viewer state: pages rendered as saved; page navigation",
            "screenshot + ui dump of the viewer; note available actions (OCR/export/share/delete)",
        ],
        ui=["library → viewer transition", "viewer controls (page list, edit, more)"],
        state=["document-open-in-viewer"],
        outputs=["none (viewer-level)"],
        edge=["reopen after force-stop (state restore)", "reopen offline (cloud thumbnails may differ)"],
        static="—",
        corrections=["minor: add an assertion for viewer action inventory (feeds S015–S018 target discovery)"],
    ),
    "S013-multi-page-scan": dict(
        title="S013 — Multi-page document scan",
        flow=[
            "permission granted + `multi-page` fixture sequence",
            "scan → capture → capture → capture (record the add-page control between captures)",
            "done → save → library entry page count == captures",
            "pull output: page count of produced artifact (pdfinfo) == 3",
        ],
        ui=["add-page control (label + position)", "page counter during capture", "page list at review"],
        state=["document-added-to-library"],
        outputs=["pdf-page-count-equals-captures"],
        edge=["zero additional pages (single-page doc via multi-page flow)", "delete a page mid-flow", "camera permission revoked mid-sequence"],
        static="—",
        corrections=["requires camera_fixture (currently unsatisfiable); page-count check should use the produced artifact, not the UI badge alone"],
    ),
    "S014-document-deletion": dict(
        title="S014 — Delete a document from library",
        flow=[
            "prereq: saved document (S011)",
            "library → long-press or overflow menu → delete (capture exact affordance)",
            "confirmation dialog (capture text) → confirm",
            "library after: document gone; `ls` of storage after: orphaned files? (cleanup vs retained)",
        ],
        ui=["delete affordance (long-press menu vs overflow vs swipe)", "confirm dialog"],
        state=["document-removed-from-library"],
        outputs=["storage delta after delete (files removed?)"],
        edge=["cancel at confirm dialog", "delete the currently-open document from its viewer", "delete with pending cloud sync"],
        static="—",
        corrections=["add storage-level assertion: deletion also removes or marks the backing files (observable via ls delta)"],
    ),
    "S015-ocr": dict(
        title="S015 — OCR text extraction",
        flow=[
            "prereq: saved document with legible text (clean-a4 with text)",
            "viewer → run OCR (record the control label — often 'Recognize'/'OCR')",
            "capture progress + result (text layer / recognized-text panel)",
            "pull any OCR output files (txt/pdf with text layer); sample recognized strings vs fixture ground truth",
            "offline OCR run — cloud dependency check (error vs degraded local recognition)",
        ],
        ui=["OCR entry control + progress UI", "recognized-text presentation"],
        state=[],
        outputs=["ocr-text-present", "ocr-text-recognizable (semantic match vs fixture ground truth)"],
        edge=["OCR offline (cloud OCR failure path)", "OCR on handwriting fixture (future)", "OCR on rotated page"],
        static="UNVERIFIED hint: app allows cleartext traffic + heavy network stack — cloud-assisted OCR likely; a login-gated OCR quota is plausible (work-order question: which features gate on account).",
        corrections=[
            "assertion `ocr-runs-on-demand` should note possible cloud dependency + account gate; record whether recognition works anonymously & offline",
            "ground-truth comparison needs CAMSCAN-003 fixture text content committed",
        ],
    ),
    "S016-pdf-export": dict(
        title="S016 — Export document as PDF",
        flow=[
            "prereq: saved document",
            "viewer → share/export → PDF (capture exact menu path + labels)",
            "destination: save-to-device (capture the file-picker flow if any) vs share-sheet",
            "pull the exported PDF: sha256, page count, page size; open check (pdfinfo/readable)",
            "options recorded: page size (A4/letter/fit), margins, quality if offered",
        ],
        ui=["export menu structure", "options (page size/quality)", "destination picker"],
        state=[],
        outputs=["pdf-created", "pdf-readable (pdfinfo page count + sizes match document)"],
        edge=["export offline", "export multi-page doc (page order)", "export to a full /sdcard (write failure path — hard to stage; note only)"],
        static="—",
        corrections=["assertions fine; add export-options record (page size default matters for parity of outputs)"],
    ),
    "S017-jpg-export": dict(
        title="S017 — Export page as JPG",
        flow=[
            "prereq: saved document",
            "viewer → export → JPG/image (record labels; single page vs all pages)",
            "pull exported jpg: sha256, dimensions, EXIF presence (app may strip or add geotags — location permission interplay)",
            "viewable check (PIL open + dimensions)",
        ],
        ui=["export path for image format", "per-page vs whole-doc selection"],
        state=[],
        outputs=["jpg-created", "jpg-readable"],
        edge=["jpg export offline", "EXIF/geotag presence (ties to ACCESS_FINE_LOCATION — privacy behavior worth recording)"],
        static="Location permissions present in manifest — EXIF geotagging behavior is a real parity-relevant observable (UNVERIFIED).",
        corrections=["add optional assertion: `jpg-exif-geotag-{present,absent}` — record the app's actual behavior"],
    ),
    "S018-sharing": dict(
        title="S018 — Share a document",
        flow=[
            "prereq: saved document",
            "viewer → share → capture the system share sheet (`com.android.internal.app.ChooserActivity` in window focus)",
            "record offered targets (emulator has few apps; Drive/Files if present)",
            "complete a share to an available target (e.g. Files save) if possible → pulled artifact hash",
        ],
        ui=["system chooser sheet", "app-side share origin menu (format choice pdf/jpg?)"],
        state=[],
        outputs=["shared-artifact-hash-stable (same bytes as the S016/S017 export of the same doc)"],
        edge=["share offline (targets may be network apps)", "share from library long-press vs viewer"],
        static="—",
        corrections=["assertion is good; add: share format options (pdf vs jpg) recorded for parity with S016/S017"],
    ),
}

TEMPLATE = """# {title}

> Scenario record for the CAMSCAN-004 reference capability discovery session
> (`camscan004-reference-20260921T224152Z`). Provisional spec:
> `lab/scenarios/{yaml}` (lead-owned; corrections proposed here, not applied).

{blocker}

## Observation workflow (to perform when unblocked)

{flow}

## UI structure to capture

{ui}

## State changes to check

{state}

## Outputs to record (files: names, formats, locations, hashes)

{outputs}

## Errors / edge cases to probe

{edge}

## Static-informed expectations (UNVERIFIED unless marked OBSERVED)

{static}

## Proposed corrections to the provisional spec (for the lead)

{corrections}
"""


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for name, d in S.items():
        num = name.split("-")[0]
        yaml_name = {
            "S001": "S001-application-launch.yaml", "S002": "S002-onboarding.yaml",
            "S003": "S003-camera-permission.yaml", "S004": "S004-single-document-capture.yaml",
            "S005": "S005-automatic-document-detection.yaml", "S006": "S006-crop-confirmation.yaml",
            "S007": "S007-manual-crop.yaml", "S008": "S008-perspective-correction.yaml",
            "S009": "S009-enhancement.yaml", "S010": "S010-rotate.yaml",
            "S011": "S011-save.yaml", "S012": "S012-reopen-saved-document.yaml",
            "S013": "S013-multi-page-scan.yaml", "S014": "S014-document-deletion.yaml",
            "S015": "S015-ocr.yaml", "S016": "S016-pdf-export.yaml",
            "S017": "S017-jpg-export.yaml", "S018": "S018-sharing.yaml",
        }[num]
        text = TEMPLATE.format(
            title=d["title"], yaml=yaml_name, blocker=BLOCKER,
            flow="\n".join(f"{i+1}. {s}" for i, s in enumerate(d["flow"])),
            ui="\n".join(f"- {s}" for s in d["ui"]),
            state="\n".join(f"- {s}" for s in d["state"]),
            outputs="\n".join(f"- {s}" for s in d["outputs"]),
            edge="\n".join(f"- {s}" for s in d["edge"]),
            static=d["static"],
            corrections="\n".join(f"- {s}" for s in d["corrections"]),
        )
        (OUT / f"{name}.md").write_text(text)
        print("wrote", f"{name}.md")
    print(f"\n{len(S)} scenario records generated in {OUT}")


if __name__ == "__main__":
    main()
