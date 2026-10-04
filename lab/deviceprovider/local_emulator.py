"""LocalEmulatorProvider — the on-box Android emulator via adb.

The deterministic DEVICE surface of the acceptance campaign (the station's own
level): androidTest instrumentation, UI dumps, logcat, screenshots — free,
reproducible, no external quota. Justification recorded in provider.py's
module docstring (board open-item (a) needs an emulator-backed station; the
operator's Device Streaming minutes stay reserved for REAL-device evidence).

CAPABILITY RECORD (2026-10-04, empirically proven on the 9.9GB station box):
this box class CANNOT host an API-35 emulator alongside the replay stack —
(a) no /dev/kvm → TCG software emulation (recorded ~50-min crawl);
(b) the emulator's launcher REWRITES config.ini (device-profile defaults
restore disk.dataPartition.size=6GiB and re-enable firstboot snapshot flags
regardless of edits, -partition-size, -no-snapshot, or -no-snapstorage);
(c) the pre-flight check then requires 7.4GB free for the raw (non-qcow2)
userdata pre-allocation — 2.9GB free with SDK+stack resident. Three failure
modes, three honest attempts. On this box the device evidence route is the
GoogleDeviceProvider (the handoff's primary external route); this provider
serves KVM-capable hosts (where it boots in ~1 min) unchanged.

Booting recipe (the recorded TCG lessons): audio ON, virtualscene camera for
a semi-deterministic camera scene, -qemu -m cap on the 4GB box, low_ram prop.
CAMERA FIXTURE HONESTY (handoff §9): a booted virtual camera does NOT flip
camera_fixture — only real-device camera evidence or an empirically proven
deterministic scene injection does. This provider reports
camera_fixture=False in DeviceFacts until such proof lands.
"""

from __future__ import annotations

import json
import re
import time
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

SDK = Path("/home/z/android-sdk")
ADB = SDK / "platform-tools" / "adb"
EMULATOR = SDK / "emulator" / "emulator"
AVD_NAME = "camscan-e2e"


class LocalEmulatorProvider(DeviceProvider):
    name = "local-emulator"

    def __init__(self, avd: str = AVD_NAME, boot_timeout_s: int = 420):
        self.avd = avd
        self.boot_timeout_s = boot_timeout_s
        self.serial: Optional[str] = None
        self._video_proc: Any = None

    # -- lifecycle ---------------------------------------------------------
    def provisionDevice(self, *, timeout_s: int = 300) -> DeviceFacts:
        # attach to a booted device first
        rc, out = run([str(ADB), "devices"])
        for line in out.splitlines()[1:]:
            if re.match(r"^emulator-\d+\s+device$", line.strip()):
                self.serial = line.split()[0]
                break
        if self.serial is None:
            if not EMULATOR.exists():
                raise ProviderCapability(
                    "emulator-binary", self.name,
                    "install emulator + system-image via sdkmanager",
                )
            import subprocess

            # TCG honesty: this box has NO /dev/kvm — x86_64 images run under
            # software emulation (-no-accel). Boot crawls (~50 min recorded);
            # the deterministic surface is worth the wait when the memory
            # window allows it. KVM-capable hosts simply boot faster.
            subprocess.Popen(
                [
                    str(EMULATOR), "-avd", self.avd,
                    "-no-window", "-no-audio", "-no-boot-anim",
                    "-no-accel",
                    "-gpu", "swiftshader_indirect",
                    "-camera-back", "virtualscene",
                    "-qemu", "-m", "1792",
                ],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                start_new_session=True,
            )
            deadline = time.time() + self.boot_timeout_s
            while time.time() < deadline:
                rc, out = run([str(ADB), "devices"])
                m = re.search(r"^(emulator-\d+)\s+device$", out, re.M)
                if m:
                    self.serial = m.group(1)
                    break
                time.sleep(5)
        if self.serial is None:
            raise ProviderCapability("device-boot", self.name, "emulator did not boot in time")
        # wait for full boot
        deadline = time.time() + self.boot_timeout_s
        while time.time() < deadline:
            rc, out = run([str(ADB), "-s", self.serial, "shell", "getprop", "sys.boot_completed"])
            if out.strip() == "1":
                break
            time.sleep(5)
        return self._facts()

    def _adb(self, *args: str, timeout_s: int = 120) -> tuple[int, str]:
        assert self.serial, "provisionDevice() first"
        return run([str(ADB), "-s", self.serial, *args], timeout_s=timeout_s)

    def _facts(self) -> DeviceFacts:
        props = {}
        for p in ("ro.product.model", "ro.build.version.release", "ro.build.version.sdk",
                  "ro.product.cpu.abilist", "ro.build.display.id"):
            rc, out = self._adb("shell", "getprop", p)
            props[p] = out.strip()
        rc, size = self._adb("shell", "wm", "size")
        rc, dpi = self._adb("shell", "wm", "density")
        return DeviceFacts(
            provider=self.name,
            device_model=props.get("ro.product.model", "unknown"),
            android_version=props.get("ro.build.version.release", "?"),
            api_level=int(props.get("ro.build.version.sdk", "0") or 0),
            screen=f"{size.strip()}@{dpi.strip()}",
            abis=[a for a in props.get("ro.product.cpu.abilist", "").split(",") if a],
            extra={"display_id": props.get("ro.build.display.id", ""),
                   "camera_fixture": False,  # §9 honesty — virtual camera ≠ fixture proof
                   },
        )

    def releaseDevice(self) -> None:
        if self._video_proc:
            try:
                self._video_proc.terminate()
            except Exception:
                pass
            self._video_proc = None
        if self.serial:
            self._adb("emu", "kill")
            self.serial = None

    # -- app surface -------------------------------------------------------
    def installApk(self, apk_path: Path) -> ActionResult:
        apk = Path(apk_path)
        if not apk.exists():
            return ActionResult(False, "install", f"apk missing: {apk}")
        rc, out = self._adb("install", "-r", "-t", str(apk), timeout_s=300)
        return ActionResult(rc == 0, "install", out.strip()[-400:])

    def launchApp(self, activity: str = "org.payswap.camscan/.MainActivity") -> ActionResult:
        rc, out = self._adb(
            "shell", "am", "start", "-W", "-n", activity, timeout_s=60
        )
        ok = rc == 0 and "Status: ok" in out
        return ActionResult(ok, "launch", out.strip()[-300:])

    def executeAction(self, action: str, **params: Any) -> ActionResult:
        if action == "tap":
            rc, out = self._adb("shell", "input", "tap", str(params["x"]), str(params["y"]))
        elif action == "swipe":
            rc, out = self._adb("shell", "input", "swipe",
                                str(params["x1"]), str(params["y1"]),
                                str(params["x2"]), str(params["y2"]),
                                str(params.get("dur_ms", 300)))
        elif action == "type":
            rc, out = self._adb("shell", "input", "text", str(params["text"]))
        elif action == "key":
            rc, out = self._adb("shell", "input", "keyevent", str(params["code"]))
        elif action == "back":
            rc, out = self._adb("shell", "input", "keyevent", "4")
        elif action == "wait":
            time.sleep(float(params.get("s", 1)))
            return ActionResult(True, "wait", f"{params.get('s', 1)}s")
        elif action == "grant":
            rc, out = self._adb("shell", "pm", "grant", "org.payswap.camscan",
                                str(params["permission"]))
        else:
            return ActionResult(False, action, f"unknown action: {action}")
        return ActionResult(rc == 0, action, out.strip()[-200:])

    # -- verification surface ----------------------------------------------
    def executeInstrumentation(self, test_filter: str = "") -> ActionResult:
        args = ["shell", "am", "instrument", "-w", "-r",
                "org.payswap.camscan.test/androidx.test.runner.AndroidJUnitRunner"]
        if test_filter:
            args += ["-e", "class", test_filter]
        rc, out = self._adb(*args, timeout_s=900)
        ok = rc == 0 and "FAILURES" not in out and "Error" not in out
        return ActionResult(ok, "instrumentation", out.strip()[-2000:])

    # -- evidence collectors -------------------------------------------------
    def collectScreenshot(self, out_path: Path) -> ActionResult:
        remote = "/sdcard/camscan_shot.png"
        self._adb("shell", "screencap", "-p", remote)
        rc, out = self._adb("pull", remote, str(out_path))
        self._adb("shell", "rm", remote)
        return ActionResult(rc == 0 and Path(out_path).exists(), "screenshot", str(out_path))

    def collectVideo(self, out_path: Path) -> ActionResult:
        # screenrecord capped at 180s per file (platform limit) — one segment
        import subprocess

        remote = "/sdcard/camscan_video.mp4"
        self._video_proc = subprocess.Popen(
            [str(ADB), "-s", self.serial, "shell", "screenrecord", "--time-limit",
             "180", remote],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        return ActionResult(True, "video-start",
                            f"recording (stop+pull via collectArtifacts) -> {out_path}")

    def collectUiDump(self, out_path: Path) -> ActionResult:
        remote = "/sdcard/camscan_uidump.xml"
        self._adb("shell", "uiautomator", "dump", remote)
        rc, out = self._adb("pull", remote, str(out_path))
        self._adb("shell", "rm", remote)
        return ActionResult(rc == 0 and Path(out_path).exists(), "uidump", str(out_path))

    def collectLogcat(self, out_path: Path, *, since_ms: Optional[int] = None) -> ActionResult:
        args = ["logcat", "-d", "-v", "time"]
        if since_ms:
            args += ["-T", f"{since_ms}"]
        rc, out = self._adb(*args, timeout_s=180)
        Path(out_path).write_text(out, encoding="utf-8")
        return ActionResult(rc == 0, "logcat", f"{len(out)} bytes -> {out_path}")

    def collectArtifacts(self, out_dir: Path) -> list[dict[str, str]]:
        out_dir.mkdir(parents=True, exist_ok=True)
        arts: list[dict[str, str]] = []
        if self._video_proc:
            self._video_proc.wait(timeout=200)
            rc, _ = self._adb("pull", "/sdcard/camscan_video.mp4", str(out_dir / "video.mp4"))
            self._adb("shell", "rm", "/sdcard/camscan_video.mp4")
            if rc == 0:
                p = out_dir / "video.mp4"
                arts.append({"kind": "video", "path": str(p), "sha256": sha256_of(p)})
        # ANRs + tombstones when present
        for remote, name in (
            ("/data/anr/", "anr/"),
            ("/data/tombstones/", "tombstones/"),
        ):
            rc, out = self._adb("shell", f"ls {remote} 2>/dev/null")
            for f in [l.strip() for l in out.splitlines() if l.strip()][:5]:
                dest = out_dir / (name.replace("/", "_") + f)
                rc2, _ = self._adb("pull", remote + f, str(dest))
                if rc2 == 0:
                    arts.append({"kind": name.strip("/"), "path": str(dest), "sha256": sha256_of(dest)})
        return arts
