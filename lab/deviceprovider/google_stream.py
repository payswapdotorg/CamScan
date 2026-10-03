"""GoogleDeviceProvider — Android Device Streaming through the replay browser.

The PRIMARY external real-device route (final TL handoff §2): the operator
authenticates Google interactively through the replay image (§3 — the lead
NEVER touches credentials), opens a Device Streaming session in the Firebase
console, and this provider drives the STREAMED DEVICE through the page via
CDP (the same browser the operator watches). Not hard-wired to Firebase Test
Lab APIs (Test Lab shuts down 2027-09-30; Device Streaming continues as part
of the Developer Device Platform); the seam stays replaceable (§17).

Honesty: every capability the streaming page does not expose raises
ProviderCapability naming the EXACT missing capability — never a generic
"infrastructure blocked" (§16). Automated CLI/API routes (gcloud ADC) are the
documented escalation, NOT a silent fallback.
"""

from __future__ import annotations

import json
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Optional

from .provider import (
    ActionResult,
    DeviceFacts,
    DeviceProvider,
    ProviderCapability,
    run,
    sha256_of,
)

CDP_PORT = 9222  # the replay browser's Chrome DevTools port


def _http_json(path: str, method: str = "GET", body: Optional[dict] = None) -> dict:
    url = f"http://127.0.0.1:{CDP_PORT}{path}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read().decode())


class _Cdp:
    """Minimal CDP caller against the replay browser (tab-scoped)."""

    def __init__(self, tab_id: str):
        import websocket  # websocket-client, present on this box

        self.ws = websocket.create_connection(
            f"ws://127.0.0.1:{CDP_PORT}/devtools/page/{tab_id}", timeout=30
        )
        self._id = 0

    def call(self, method: str, params: Optional[dict] = None, timeout_s: int = 30) -> dict:
        self._id += 1
        self.ws.send(json.dumps({"id": self._id, "method": method, "params": params or {}}))
        self.ws.settimeout(timeout_s)
        while True:
            msg = json.loads(self.ws.recv())
            if msg.get("id") == self._id:
                if "error" in msg:
                    raise RuntimeError(f"CDP {method}: {msg['error']}")
                return msg.get("result", {})

    def eval(self, expr: str, timeout_s: int = 30) -> Any:
        r = self.call("Runtime.evaluate", {
            "expression": expr, "returnByValue": True, "awaitPromise": True,
        }, timeout_s=timeout_s)
        if r.get("exceptionDetails"):
            raise RuntimeError(str(r["exceptionDetails"])[:400])
        return r.get("result", {}).get("value")

    def close(self) -> None:
        try:
            self.ws.close()
        except Exception:
            pass


class GoogleDeviceProvider(DeviceProvider):
    """Drives the operator's interactive Device Streaming session.

    provisionDevice(): find (or open) the Firebase console tab in the replay
    browser and verify a streaming session is live. The OPERATOR performs the
    Google sign-in, project selection (preferred: CamScan-device-validation),
    device pick, and permission grants interactively through the replay image
    — this provider only attaches and drives the already-streamed device.
    """

    name = "google-device-streaming"
    CONSOLE_URL = "https://console.firebase.google.com/"
    STREAM_HINT = "device streaming"  # title/url substring of a live session

    def __init__(self, cdp_port: int = CDP_PORT):
        self.cdp_port = cdp_port
        self.tab_id: Optional[str] = None
        self._cdp: Optional[_Cdp] = None
        self._facts: Optional[DeviceFacts] = None
        self._login_probe_ms: Optional[int] = None

    # -- internals ----------------------------------------------------------
    def _tabs(self) -> list[dict]:
        return _http_json("/json").get("data") or _http_json("/json/list") or []

    def _find_stream_tab(self) -> Optional[dict]:
        for t in self._tabs():
            blob = (t.get("title", "") + " " + t.get("url", "")).lower()
            if "firebase" in blob or "device" in blob or "android" in blob:
                if self.STREAM_HINT in blob or "console.firebase" in blob:
                    return t
        return None

    def _attach(self, tab_id: str) -> None:
        if self._cdp:
            self._cdp.close()
        self._cdp = _Cdp(tab_id)
        self.tab_id = tab_id

    # -- lifecycle ---------------------------------------------------------
    def provisionDevice(self, *, timeout_s: int = 300) -> DeviceFacts:
        tab = self._find_stream_tab()
        if tab is None:
            # open the console tab; the OPERATOR signs in + starts the stream
            created = _http_json("/json/new?" + urllib.parse.urlencode(
                {"url": self.CONSOLE_URL}), method="PUT")
            tab = created
        self._attach(tab["id"])
        # wait for the operator's streaming session to go live (their action)
        deadline = time.time() + timeout_s
        last_err = "no streaming session detected in the Firebase console tab"
        while time.time() < deadline:
            try:
                state = self._cdp.eval(
                    "document.readyState + '|' + location.href + '|' + document.title", 10)
                if self.STREAM_HINT in str(state).lower():
                    return self._device_facts_from_page()
                last_err = f"page state: {str(state)[:160]}"
            except Exception as e:  # tab navigating / renderer swap
                last_err = str(e)[:160]
            time.sleep(10)
        raise ProviderCapability(
            "device-streaming-session", self.name,
            f"operator sign-in/stream start pending ({last_err}); "
            "the operator drives Google auth interactively through the replay",
        )

    def _device_facts_from_page(self) -> DeviceFacts:
        # best-effort facts from the streaming page DOM; honest '?' where absent
        try:
            txt = str(self._cdp.eval("document.body.innerText.slice(0, 4000)", 10))
        except Exception:
            txt = ""
        model = next((l.strip() for l in txt.splitlines()
                      if any(k in l.lower() for k in ("pixel", "galaxy", "oneplus", "xiaomi"))), "?")
        ver = next((l.strip() for l in txt.splitlines() if "api" in l.lower() or "android" in l.lower()), "?")
        self._facts = DeviceFacts(
            provider=self.name, device_model=model or "?", android_version=ver or "?",
            extra={"camera_fixture": None,  # real device: camera evidence path is OPEN
                   "quota_note": "30 no-cost streaming minutes/project/month"},
        )
        return self._facts

    def releaseDevice(self) -> None:
        if self._cdp:
            self._cdp.close()
            self._cdp = None
        # the operator closes the streaming session in the console (or it expires)

    # -- app surface -------------------------------------------------------
    def installApk(self, apk_path: Path) -> ActionResult:
        apk = Path(apk_path)
        if not apk.exists():
            return ActionResult(False, "install", f"apk missing: {apk}")
        # The streaming page's own install/upload affordance (file input or
        # drag-drop) is driven through the page; exact selector recorded at
        # first live use. Capability named precisely when the page lacks it.
        try:
            found = self._cdp.eval(
                "!!document.querySelector('input[type=file]')", 10)
        except Exception as e:
            raise ProviderCapability("apk-install-surface", self.name,
                                     f"streaming page not attached ({e})") from e
        if not found:
            raise ProviderCapability(
                "apk-install-surface", self.name,
                "no file-install affordance detected on the streaming page; "
                "use the page's Run/install flow manually through the replay, "
                "or escalate to the adb-over-web route when the page exposes it",
            )
        # DOM-set the file input is not possible via pure CDP eval; the
        # operator drops the APK through the replay image (drag streams live).
        return ActionResult(False, "install",
                            "file affordance present — operator drop required via replay "
                            "(provider does not synthesize credential-bearing paths)")

    def launchApp(self, activity: str = "org.payswap.camscan/.MainActivity") -> ActionResult:
        # On a streamed device the app launches by tapping its icon in the
        # streamed home screen — a screen-coordinate action on the page.
        raise ProviderCapability(
            "app-launch-api", self.name,
            "launch via executeAction('tap', ...) on the streamed device image",
        )

    def executeAction(self, action: str, **params: Any) -> ActionResult:
        """Tap/swipe/type on the STREAMED device image inside the page.

        The streamed device renders in a canvas/element region; actions are
        mapped onto it as page-relative coordinates (fx/fy 0..1 of the device
        frame) — the same coordinate convention the replay console itself
        uses. The frame selector is recorded at first live session attach.
        """
        if not self._cdp:
            return ActionResult(False, action, "not attached — provisionDevice() first")
        if action in ("tap", "swipe", "type", "key", "back"):
            try:
                frame_js = (
                    "(()=>{const el=document.querySelector('canvas')||"
                    "document.querySelector('[data-device-frame]');"
                    "if(!el)return null;const r=el.getBoundingClientRect();"
                    "return JSON.stringify({x:r.x,y:r.y,w:r.width,h:r.height});})()"
                )
                fr = self._cdp.eval(frame_js, 10)
                if not fr:
                    raise ProviderCapability("device-frame-element", self.name,
                                             "streamed device frame element not found")
                r = json.loads(fr)
                if action == "tap":
                    x, y = r["x"] + float(params["fx"]) * r["w"], r["y"] + float(params["fy"]) * r["h"]
                    self._cdp.call("Input.dispatchMouseEvent", {
                        "type": "mousePressed", "x": x, "y": y,
                        "button": "left", "clickCount": 1})
                    self._cdp.call("Input.dispatchMouseEvent", {
                        "type": "mouseReleased", "x": x, "y": y,
                        "button": "left", "clickCount": 1})
                    return ActionResult(True, "tap", f"fx={params['fx']} fy={params['fy']}")
                if action == "type":
                    self._cdp.call("Input.insertText", {"text": str(params["text"])})
                    return ActionResult(True, "type", str(params["text"])[:80])
                if action == "key" or action == "back":
                    code = 4 if action == "back" else int(params["code"])
                    self._cdp.call("Input.dispatchKeyEvent", {
                        "type": "keyDown", "windowsVirtualKeyCode": code,
                        "nativeVirtualKeyCode": code, "key": ""})
                    self._cdp.call("Input.dispatchKeyEvent", {
                        "type": "keyUp", "windowsVirtualKeyCode": code,
                        "nativeVirtualKeyCode": code, "key": ""})
                    return ActionResult(True, action, f"keycode {code}")
                if action == "swipe":
                    x1 = r["x"] + float(params["fx1"]) * r["w"]
                    y1 = r["y"] + float(params["fy1"]) * r["h"]
                    x2 = r["x"] + float(params["fx2"]) * r["w"]
                    y2 = r["y"] + float(params["fy2"]) * r["h"]
                    for t, x, y in self._swipe_path(x1, y1, x2, y2, int(params.get("steps", 12))):
                        self._cdp.call("Input.dispatchMouseEvent", {
                            "type": t, "x": x, "y": y, "button": "left",
                            "clickCount": 1 if t == "mousePressed" else 0})
                    return ActionResult(True, "swipe", f"({params['fx1']},{params['fy1']})->({params['fx2']},{params['fy2']})")
            except ProviderCapability:
                raise
            except Exception as e:
                return ActionResult(False, action, str(e)[:200])
        if action == "wait":
            time.sleep(float(params.get("s", 1)))
            return ActionResult(True, "wait", f"{params.get('s', 1)}s")
        return ActionResult(False, action, f"unknown action: {action}")

    @staticmethod
    def _swipe_path(x1: float, y1: float, x2: float, y2: float, steps: int):
        yield ("mousePressed", x1, y1)
        for i in range(1, steps):
            t = i / steps
            yield ("mouseMoved", x1 + (x2 - x1) * t, y1 + (y2 - y1) * t)
        yield ("mouseReleased", x2, y2)

    # -- verification surface ----------------------------------------------
    def executeInstrumentation(self, test_filter: str = "") -> ActionResult:
        raise ProviderCapability(
            "on-device-instrumentation-api", self.name,
            "Device Streaming is interactive; automated instrumentation goes "
            "through the LocalEmulatorProvider (deterministic station) or the "
            "gcloud/Test-Lab route — the latter needs operator billing review "
            "per the handoff STOP rule",
        )

    # -- evidence collectors -------------------------------------------------
    def collectScreenshot(self, out_path: Path) -> ActionResult:
        if not self._cdp:
            return ActionResult(False, "screenshot", "not attached")
        try:
            r = self._cdp.call("Page.captureScreenshot", {"format": "png"}, timeout_s=30)
            import base64

            Path(out_path).write_bytes(base64.b64decode(r["data"]))
            return ActionResult(True, "screenshot", str(out_path))
        except Exception as e:
            return ActionResult(False, "screenshot", str(e)[:200])

    def collectVideo(self, out_path: Path) -> ActionResult:
        # The console operator can record the replay itself; the provider does
        # not synthesize video from stills.
        raise ProviderCapability("session-video-api", self.name,
                                  "record via the replay console's recording "
                                  "or the streaming page's own capture")

    def collectUiDump(self, out_path: Path) -> ActionResult:
        if not self._cdp:
            return ActionResult(False, "uidump", "not attached")
        try:
            # streamed-device accessibility tree is NOT exposed to the page;
            # capture the visible device-frame text + frame geometry honestly
            blob = str(self._cdp.eval(
                "document.body.innerText.slice(0, 20000)", 10))
            Path(out_path).write_text(
                f"<!-- google-device-streaming visible-page dump (no a11y API) -->\n{blob}",
                encoding="utf-8")
            return ActionResult(True, "uidump", "page-text dump (a11y not exposed)")
        except Exception as e:
            return ActionResult(False, "uidump", str(e)[:200])

    def collectLogcat(self, out_path: Path, *, since_ms: Optional[int] = None) -> ActionResult:
        raise ProviderCapability("logcat-api", self.name,
                                  "Device Streaming exposes no logcat API to the page; "
                                  "use the page's logcat panel via the replay (manual) or "
                                  "LocalEmulatorProvider for automated logcat")

    def collectArtifacts(self, out_dir: Path) -> list[dict[str, str]]:
        # screenshots pulled by the harness are the artifacts; nothing else is
        # exposed. Kept honest and empty.
        return []
