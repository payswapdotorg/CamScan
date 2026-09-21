"""Baked TCG bootstrap recipe for the E2B `desktop` template.

This is NOT rediscovered experimentally per run — it is the empirically gated
recipe from `lab/substrate/VALIDATION-2026-09-21-TCG.md` (2026-09-21 hard
gate: Android 11 x86_64 boot to sys.boot_completed=1 in 410 s), encoded with
its seven gotchas fixed:

  1. Java 17 required (template ships Java 11): openjdk-17-jre-headless +
     JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64.
  2. Headless JDK truststore is a 32-byte placeholder -> SSLException
     trustAnchors -> sdkmanager 'Failed to find package'. Fix: remove cacerts
     + update-ca-certificates -f; if that fails, rebuild the keystore from
     the split PEM bundle via keytool. HARD GATE: HTTPS for sdkmanager is
     verified (curl to dl.google.com) before SDK install; no silent
     continue after truststore failure.
  3. sdkmanager unpacks binaries WITHOUT exec bits -> chmod a+x emulator
     tree explicitly.
  4. <sdk>/emulator is a DIRECTORY; the binary is <sdk>/emulator/emulator.
     Executing the directory yields a misleading 'Permission denied'.
  5. Emulation must be TCG (-accel off); there is no /dev/kvm in E2B
     (Firecracker microVMs). The capability report says
     emulator_acceleration: none — never kvm.
  6. The launcher execs into qemu-system-x86_64-headless (process name
     differs from 'emulator'); liveness checks must match the qemu pattern.
  7. e2b commands.run raises CommandExitException on non-zero exit (the
     exception carries exit_code/stdout/stderr) — sh() normalizes it.

Every step is marker-file idempotent (/root/.camscan-bootstrap/<step>.done)
so re-entry after stop/start does not repeat work. All durations are
recorded into the environment handle's timings dict.
"""
from __future__ import annotations

import time
from typing import Any, Callable, Optional

JAVA_HOME = "/usr/lib/jvm/java-17-openjdk-amd64"
SDK = "/opt/android-sdk"
ADB = f"{SDK}/platform-tools/adb"
EMULATOR_BIN = f"{SDK}/emulator/emulator"  # gotcha 4: <sdk>/emulator is a dir
AVDMANAGER = f"{SDK}/cmdline-tools/latest/bin/avdmanager"
MARKER_DIR = "/root/.camscan-bootstrap"

CMDLINE_TOOLS_URL = (
    "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
)

#: Known-good base SDK package set (handoff: exact versions may be
#: parameterized, but the default must be known-good).
BASE_SDK_PACKAGES: tuple[str, ...] = (
    "platform-tools",
    "emulator",
    "system-images;android-30;default;x86_64",
    "platforms;android-30",
    "build-tools;33.0.2",
)

BOOT_FATAL_STRINGS = (
    "requires hardware acceleration",
    "x86_64 emulation currently requires",
    "panicked",
    "command not found",
    "permission denied",
    "nohup: failed",
)

# ------------------------------------------------------------------ helpers

def _sh(sb: Any, cmd: str, timeout: float = 300, user: str = "root") -> tuple[int, str]:
    """Run cmd in the sandbox; normalize CommandExitException (gotcha 7)."""
    try:
        r = sb.commands.run(cmd, timeout=timeout, user=user)
        return r.exit_code, ((r.stdout or "") + (r.stderr or "")).strip()
    except Exception as e:  # noqa: BLE001 — carries exit_code/stdout/stderr
        ec = getattr(e, "exit_code", -1)
        out = ((getattr(e, "stdout", "") or "") + (getattr(e, "stderr", "") or "")).strip()
        if ec == -1 and not out:
            return -1, f"RUN_ERROR: {e}"
        return ec, out or f"RUN_ERROR: {e}"


def _extend(sb: Any, secs: int = 3600) -> None:
    """Renew sandbox lifetime (per-call cap 1 h; call repeatedly)."""
    try:
        sb.set_timeout(secs)
    except Exception:  # noqa: BLE001 — best-effort renewal, next op retries
        pass


def _marked(sb: Any, step: str) -> bool:
    c, o = _sh(sb, f"test -f {MARKER_DIR}/{step}.done && echo YES || echo NO", timeout=30)
    return o.strip().endswith("YES")


def _mark(sb: Any, step: str) -> None:
    _sh(sb, f"mkdir -p {MARKER_DIR} && touch {MARKER_DIR}/{step}.done", timeout=30)


def _run_step(sb: Any, env_id: str, step: str, timings: dict[str, float],
              fn: Callable[[], tuple[bool, str]]) -> tuple[bool, str]:
    """Run one idempotent bootstrap step with timing + marker."""
    if _marked(sb, step):
        timings[f"bootstrap_{step}_s"] = 0.0
        return True, "already-done"
    t0 = time.time()
    ok, detail = fn()
    timings[f"bootstrap_{step}_s"] = round(time.time() - t0, 1)
    if ok:
        _mark(sb, step)
    return ok, detail


# -------------------------------------------------------------------- steps

def step_java(sb: Any) -> tuple[bool, str]:
    """Java 17 + real truststore (gotchas 1+2). Hard-gated, no silent pass."""
    script = f"""
export JAVA_HOME={JAVA_HOME}
SZ=$(stat -c %s /etc/ssl/certs/java/cacerts 2>/dev/null || echo 0)
if [ ! -x $JAVA_HOME/bin/java ] || [ "$SZ" -lt 10000 ]; then
  (apt-get update -qq || true) >/dev/null 2>&1
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-17-jre-headless \\
    unzip ca-certificates-java >/dev/null 2>&1 || true
  rm -f /etc/ssl/certs/java/cacerts
  update-ca-certificates -f >/dev/null 2>&1 || true
fi
SZ=$(stat -c %s /etc/ssl/certs/java/cacerts 2>/dev/null || echo 0)
if [ "$SZ" -lt 10000 ]; then
  rm -rf /tmp/certs && mkdir -p /tmp/certs
  csplit -z -f /tmp/certs/c- /etc/ssl/certs/ca-certificates.crt \\
    '/-----BEGIN CERTIFICATE-----/' '{{*}}' >/dev/null 2>&1
  rm -f /etc/ssl/certs/java/cacerts
  n=0
  for f in /tmp/certs/c-*; do
    n=$((n+1))
    $JAVA_HOME/bin/keytool -noprompt -importcert -alias "ca$n" -file "$f" \\
      -keystore /etc/ssl/certs/java/cacerts -storepass changeit >/dev/null 2>&1
  done
fi
SZ=$(stat -c %s /etc/ssl/certs/java/cacerts 2>/dev/null || echo 0)
echo "CACERTS_SIZE=$SZ"
$JAVA_HOME/bin/java -version 2>&1 | head -1
"""
    c, o = _sh(sb, script, timeout=900)
    if 'version "17' not in o:
        return False, f"java 17 unavailable: {o[:300]}"
    try:
        size = int(o.split("CACERTS_SIZE=")[1].split()[0])
    except (IndexError, ValueError):
        return False, f"cacerts size unparseable: {o[:200]}"
    if size < 10000:
        return False, f"truststore still a placeholder ({size} bytes) — refusing to continue"
    return True, o.strip().splitlines()[-1]


def step_https_check(sb: Any) -> tuple[bool, str]:
    """Verify sdkmanager HTTPS reachability BEFORE SDK install (hard gate)."""
    c, o = _sh(sb, "curl -fsSI --max-time 60 "
                   "https://dl.google.com/android/repository/repository2-1.xml 2>&1 | head -1",
               timeout=120)
    first = o.strip().splitlines()[0].strip() if o.strip() else ""
    # status line forms: "HTTP/1.1 200" / "HTTP/2 200" (no trailing space!)
    parts = first.split()
    if len(parts) < 2 or not parts[0].startswith("HTTP/") or parts[1] != "200":
        return False, f"https check failed: {o[:200]}"
    return True, first


def step_sdk(sb: Any, packages: tuple[str, ...]) -> tuple[bool, str]:
    """cmdline-tools + known-good package set."""
    pkgs = " ".join(f'"{p}"' for p in packages)
    script = f"""
set -e
export JAVA_HOME={JAVA_HOME}
mkdir -p {SDK}/cmdline-tools
cd {SDK}
if [ ! -x cmdline-tools/latest/bin/sdkmanager ]; then
  curl -fsSLo cmdtools.zip {CMDLINE_TOOLS_URL}
  unzip -q cmdtools.zip -d cmdline-tools
  mv cmdline-tools/cmdline-tools cmdline-tools/latest
  rm -f cmdtools.zip
fi
export ANDROID_HOME={SDK}
yes | cmdline-tools/latest/bin/sdkmanager --licenses >/tmp/lic.log 2>&1 || true
ec=0
cmdline-tools/latest/bin/sdkmanager {pkgs} >/tmp/sdk.log 2>&1 || ec=$?
if [ "$ec" -ne 0 ]; then
  echo "SDKMANAGER_EXIT=$ec"
  tail -30 /tmp/sdk.log
  exit 1
fi
test -x {ADB}
test -d {SDK}/emulator
echo SDK_DONE
"""
    c, o = _sh(sb, script, timeout=2400)
    if "SDK_DONE" not in o:
        # head AND tail: progress bars flood the head; the reason is at the end
        return False, f"sdk install failed (exit={c}): {o[:300]} ... {o[-500:]}"
    return True, "SDK_DONE"


def step_exec_bits(sb: Any) -> tuple[bool, str]:
    """gotcha 3: sdkmanager unpacks without exec bits."""
    script = (f"chmod a+x {SDK}/emulator/emulator {SDK}/emulator/crashpad-handler "
              f"{SDK}/emulator/qemu/linux-x86_64/* 2>/dev/null || true; "
              f"test -x {EMULATOR_BIN} && echo EMU_EXEC_OK")
    c, o = _sh(sb, script, timeout=120)
    if "EMU_EXEC_OK" not in o:
        return False, f"emulator binary not executable at {EMULATOR_BIN}: {o[:200]}"
    return True, "EMU_EXEC_OK"


def step_avd(sb: Any, avd_name: str, system_image: str, device_profile: str,
              sdcard_mb: int = 2048) -> tuple[bool, str]:
    """Create the AVD. sdcard: camera/storage-hungry apps (AOSP camera2,
    document scanners like CamScanner) crash with
    ExceptionInInitializerError in Storage singletons when the AVD has no
    sdcard volume — always create one (camera-fixture probe round 7, 2026-09-21).
    """
    script = (f"export JAVA_HOME={JAVA_HOME} ANDROID_HOME={SDK} && "
              f"echo no | {AVDMANAGER} create avd -n {avd_name} -k '{system_image}' "
              f"-d {device_profile} -c {sdcard_mb}M --force >/dev/null 2>&1; "
              f"{AVDMANAGER} list avd 2>/dev/null | grep -c 'Name: {avd_name}'")
    c, o = _sh(sb, script, timeout=300)
    if o.strip() != "1":
        return False, f"avd create failed: {o[:300]}"
    return True, avd_name


# ------------------------------------------------------------------- runner

def bootstrap(sb: Any, env_id: str, timings: dict[str, float],
              packages: tuple[str, ...] = BASE_SDK_PACKAGES,
              avd_name: str = "camscan", system_image: str = "system-images;android-30;default;x86_64",
              device_profile: str = "pixel_4",
              on_progress: Callable[[str], None] | None = None) -> tuple[bool, str]:
    """Run the full baked bootstrap; returns (ok, failure_detail).

    Steps: java17+truststore -> HTTPS gate -> SDK -> exec bits -> AVD.
    Idempotent per sandbox via marker files.
    """
    def say(msg: str) -> None:
        if on_progress:
            on_progress(msg)

    _extend(sb)
    say("java 17 + truststore")
    ok, detail = _run_step(sb, env_id, "java", timings, lambda: step_java(sb))
    if not ok:
        return False, f"java: {detail}"

    _extend(sb)
    say("https gate")
    ok, detail = _run_step(sb, env_id, "https", timings, lambda: step_https_check(sb))
    if not ok:
        return False, f"https: {detail}"

    _extend(sb)
    say(f"sdk install ({len(packages)} packages)")
    ok, detail = _run_step(sb, env_id, "sdk", timings, lambda: step_sdk(sb, packages))
    if not ok:
        return False, f"sdk: {detail}"

    _extend(sb)
    say("exec bits")
    ok, detail = _run_step(sb, env_id, "execbits", timings, lambda: step_exec_bits(sb))
    if not ok:
        return False, f"execbits: {detail}"

    _extend(sb)
    say("avd create")
    ok, detail = _run_step(
        sb, env_id, "avd", timings,
        lambda: step_avd(sb, avd_name, system_image, device_profile))
    if not ok:
        return False, f"avd: {detail}"

    return True, "bootstrapped"


def emulator_launch_cmd(avd_name: str, memory_mb: int, cores: int,
                        locale: str, timezone: str, camera_back: str,
                        wipe_data: bool,
                        camera_poster: Optional[str] = None) -> str:
    """Detached TCG emulator launch line (gotchas 4+5+6).

    Proven configuration: Android 11 x86_64 / pixel_4 / -accel off /
    -no-window / -gpu swiftshader_indirect / -memory 2048 / -cores 4.
    Deterministic locale/timezone via -prop persist.sys.*.
    camera_poster: absolute sandbox path to an image injected into the
    virtualscene back camera as poster1 — discovered by binary mining at
    the 2026-09-21 camera-fixture probe (emulator 37.1.11 supports
    `-virtualscene-poster <name>=<filename>`; the console `virtualscene`
    subcommand does NOT exist in this build — use the launch flag).
    """
    wipe = " -wipe-data" if wipe_data else ""
    poster = (f" -virtualscene-poster poster1={camera_poster}"
              if camera_poster else "")
    # audio stays ENABLED (no -no-audio): apps initializing audio services
    # (AOSP camera2 shutter player — camera-fixture probe round 5) crash with
    # the flag; qemu falls back to a null backend headlessly. Boot measured
    # 390-434s with audio ON — no TCG penalty.
    return (
        f"cd {SDK}/emulator && "
        f"(nohup ./emulator -avd {avd_name} -accel off -no-window "
        f"-no-boot-anim -gpu swiftshader_indirect -memory {memory_mb} -cores {cores} "
        f"-no-snapshot{wipe}{poster} "
        f"-prop persist.sys.locale={locale} -prop persist.sys.timezone={timezone} "
        f"-camera-back {camera_back} "
        f"> /tmp/emulator.log 2>&1 < /dev/null &) ; sleep 3 ; "
        f"pgrep -c -f 'qemu-syste[m].*-avd {avd_name}'"
    )


def emulator_kill_cmd(avd_name: str) -> str:
    """Stop the emulator process tree (sandbox stays alive): SIGTERM, then
    SIGKILL escalation. Gotcha 8: pgrep/pkill -f SELF-MATCH — the E2B
    command wrapper shell's own cmdline contains the pattern text, so a
    plain pattern counts/kills the wrapper itself (inflated counts; the
    kill sequence shooting its own shell mid-sequence). The [m] bracket
    trick makes the literal pattern text non-self-matching."""
    return (
        f"pkill -f 'qemu-syste[m].*-avd {avd_name}' 2>/dev/null; sleep 3; "
        f"pkill -9 -f 'qemu-syste[m].*-avd {avd_name}' 2>/dev/null; sleep 2; "
        f"pgrep -c -f 'qemu-syste[m].*-avd {avd_name}' || true"
    )


def emulator_alive_cmd(avd_name: str) -> str:
    """gotcha 6: the process is qemu-system-x86_64-headless, not 'emulator'.
    gotcha 8: [m] bracket trick prevents pgrep -f self-match on the E2B
    command wrapper shell (empirically verified: plain pattern counts 1
    even with a bogus avd name)."""
    return f"pgrep -c -f 'qemu-syste[m].*-avd {avd_name}' || true"
