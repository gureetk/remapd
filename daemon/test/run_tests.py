"""End-to-end tests for remapd on a Linux PC, against a fake remote.

    python3 daemon/test/run_tests.py

Needs gcc and Python 3; no root, no Android device. remapd and an LD_PRELOAD
shim (fakeioctl.c) are built for the PC; FIFOs stand in for the remote's
input nodes and a plain file for /dev/uinput. REMAPD_TEST_ROOT makes remapd
use a sandbox folder (test/sandbox) instead of /dev, /system, /vendor and
/data/local/tmp, and fake dumpsys/settings/am/input/cmd tools are put on PATH.

The fake remote is an ACE666BT (0508:0110): three input nodes sharing one
keymap, read by Android with a minimal vendor key layout file, and Generic.kl
(from AOSP, see its header) is there for the virtual remote.
"""
import os, shutil, signal, struct, subprocess, sys, time

ROOT = os.path.dirname(os.path.abspath(__file__))
DAEMON = os.path.dirname(ROOT)
FAKE = os.path.join(ROOT, "fake")
BIN = os.path.join(ROOT, "fakebin")
SBX = os.path.join(ROOT, "sandbox")          # stands in for the Android filesystem
AOSP_GENERIC = os.path.join(ROOT, "Generic.kl")
GENERIC = "/system/usr/keylayout/Generic.kl"   # paths as Android (and remapd's output) see them
VENDOR_KL = "/vendor/usr/keylayout/Vendor_0508_Product_0110.kl"
REMAPD = os.path.join(ROOT, "remapd-test")
CONF = os.path.join(DAEMON, "examples", "ace666bt.conf")
SHIM = os.path.join(ROOT, "fakeioctl.so")
ENV = dict(os.environ, LD_PRELOAD=SHIM, FAKE_DIR=FAKE, REMAPD_TEST_ROOT=SBX,
           PATH=BIN + ":" + os.environ["PATH"])


def sbx(path):
    """Where an Android path lives in the sandbox."""
    return SBX + path


# Build remapd and the shim for this PC.
subprocess.run(["gcc", "-std=gnu99", "-Wall", "-Wextra", "-O2", "-o", REMAPD,
                os.path.join(DAEMON, "remapd.c")], check=True)
subprocess.run(["gcc", "-shared", "-fPIC", "-O2", "-o", SHIM, os.path.join(ROOT, "fakeioctl.c"), "-ldl"], check=True)
GLOBAL_OWNER = "com.google.android.tvlauncher/.GlobalKeyReceiver"
NAME = "ACE666BT Keyboard"
EV = struct.Struct("<QQHHi")   # input_event on x86_64
fails = 0

# The user's remote, codes exactly as captured with getevent.
REMOTE = {0x0c0069: 398, 0x0c006a: 399, 0x0c006c: 400, 0x0c006b: 401, 0x0c00cd: 164,
          0x0c00b2: 167, 0x0c0003: 240, 0x0c021f: 136, 0x0c0058: 240, 0x0c0008: 240,
          0x0c00ea: 114, 0x0c00e9: 115, 0x0c004e: 240, 0x0c004b: 240, 0x0c0223: 172,
          0x0c0224: 158, 0x0c0040: 139, 0x0c001c: 240, 0x0c0060: 358, 0x0c0041: 353,
          0x0c0043: 108, 0x0c0042: 103, 0x0c0044: 105, 0x0c0045: 106, 0x0c00e2: 113,
          0x0c0221: 217, 0x0c0004: 240, 0x0c0083: 405, 0x070066: 116}
SEND_USAGES = [0x0c0069, 0x0c006a, 0x0c006c, 0x0c006b, 0x0c00cd, 0x0c00b2, 0x0c00e2,
               0x0c004b, 0x0c004e, 0x0c001c, 0x0c0083, 0x0c0060, 0x0c021f]
# What the virtual remote should press for each send line (from Generic.kl).
VIRT_KEYS = {398, 399, 400, 401, 164, 167, 113, 402, 403, 362, 405, 217}

# Like the Chromecast's file for 0508:0110: only the basic keys.
VENDOR_KL_TEXT = """# basic keys only
key 2 1\nkey 3 2\nkey 4 3\nkey 5 4\nkey 6 5\nkey 7 6\nkey 8 7\nkey 9 8\nkey 10 9\nkey 11 0
key 103 DPAD_UP\nkey 108 DPAD_DOWN\nkey 105 DPAD_LEFT\nkey 106 DPAD_RIGHT\nkey 353 DPAD_CENTER
key 172 HOME\nkey 158 BACK\nkey 139 MENU\nkey 114 VOLUME_DOWN\nkey 115 VOLUME_UP
key 116 POWER\nkey 217 VOICE_ASSIST
"""

def check(cond, msg):
    global fails
    print(("  PASS " if cond else "  FAIL ") + msg)
    if not cond:
        fails += 1

def make_dev(node, name, vid, pid, usages):
    # Build it under a temp name, then rename: the real node only appears once
    # the kernel device is complete.
    path, tmp = sbx("/dev/input/" + node), sbx("/dev/input/tmp-" + node)
    if os.path.exists(path):
        os.unlink(path)
    os.mkfifo(tmp, 0o660)
    ino = os.stat(tmp).st_ino
    with open(f"{FAKE}/{ino}.dev", "w") as f:
        f.write(f"name {name}\nid {vid:04x} {pid:04x}\n")
        for u, c in usages.items():
            f.write(f"usage {u:08x} {c}\n")
    os.rename(tmp, path)
    return path

def make_remote(first=6):
    """The ACE666BT: three nodes sharing one keymap (hard links = one inode)."""
    path = make_dev(f"event{first}", NAME, 0x0508, 0x0110, REMOTE)
    for i, n in ((1, "Mouse"), (2, "plain")):
        os.link(path, sbx(f"/dev/input/event{first + i}"))
    return path

def unplug_remote(first=6):
    for i in range(3):
        try: os.unlink(sbx(f"/dev/input/event{first + i}"))
        except FileNotFoundError: pass

def wait_for(cond, secs=5.0):
    """Gives remapd up to secs to get something done; whether cond() came true."""
    end = time.time() + secs
    while not cond():
        if time.time() >= end:
            return False
        time.sleep(0.05)
    return True

def keycode(path, usage):
    ino = os.stat(path).st_ino
    for line in open(f"{FAKE}/{ino}.dev"):
        if line.startswith("usage ") and int(line.split()[1], 16) == usage:
            return int(line.split()[2])
    raise KeyError(hex(usage))

def press(path, usage, down=True, up=True):
    """What the kernel sends for a press/release, using the device's current keymap."""
    code = keycode(path, usage)
    data = b""
    if down:
        data += EV.pack(0, 0, 4, 4, usage) + EV.pack(0, 0, 1, code, 1) + EV.pack(0, 0, 0, 0, 0)
    if up:
        data += EV.pack(0, 0, 4, 4, usage) + EV.pack(0, 0, 1, code, 0) + EV.pack(0, 0, 0, 0, 0)
    fd = os.open(path, os.O_WRONLY | os.O_NONBLOCK)
    os.write(fd, data)
    os.close(fd)

def virt_events():
    """(code, value) of EV_KEY events the virtual remote sent, checking SYN after each."""
    raw = open(sbx("/dev/uinput"), "rb").read() if os.path.exists(sbx("/dev/uinput")) else b""
    evs = [EV.unpack_from(raw, i) for i in range(0, len(raw) - len(raw) % EV.size, EV.size)]
    out = []
    for i, (_, _, t, c, v) in enumerate(evs):
        if t == 1:
            nxt = evs[i + 1] if i + 1 < len(evs) else None
            out.append((c, v) if nxt and nxt[2] == 0 and nxt[3] == 0 else ("no SYN", c, v))
    return out

def readf(name):
    p = f"{FAKE}/{name}"
    return open(p).read() if os.path.exists(p) else ""

def rd(cmd, env=None):
    r = subprocess.run([REMAPD] + cmd, env=env or ENV, capture_output=True, text=True, timeout=20)
    return r.returncode, r.stdout + r.stderr

def reset(uinput=True):
    shutil.rmtree(FAKE, ignore_errors=True); os.makedirs(FAKE)
    shutil.rmtree(sbx("/dev/input"), ignore_errors=True); os.makedirs(sbx("/dev/input"))
    if os.path.exists(sbx("/dev/uinput")):
        os.unlink(sbx("/dev/uinput"))
    if uinput:
        open(sbx("/dev/uinput"), "w").close()
    os.makedirs(sbx("/data/local/tmp"), exist_ok=True)
    for f in ("remapd.pid", "remapd.log"):
        try: os.unlink(sbx("/data/local/tmp/" + f))
        except FileNotFoundError: pass

def start_fg(conf=CONF, env=None):
    p = subprocess.Popen([REMAPD, "run", conf], env=env or ENV, stdout=subprocess.PIPE,
                         stderr=subprocess.STDOUT, text=True)
    time.sleep(0.8)
    return p

def stop_fg(p):
    p.send_signal(signal.SIGINT)
    return p.communicate(timeout=5)[0]

# Android files and fake Android tools.
shutil.rmtree(SBX, ignore_errors=True)
os.makedirs(os.path.dirname(sbx(GENERIC)), exist_ok=True)
shutil.copy(AOSP_GENERIC, sbx(GENERIC))
os.makedirs(os.path.dirname(sbx(VENDOR_KL)), exist_ok=True)
open(sbx(VENDOR_KL), "w").write(VENDOR_KL_TEXT)
os.makedirs(BIN, exist_ok=True)
DUMPSYS = r'''case "$1" in
input) cat <<EOF
INPUT MANAGER (dumpsys input)

Event Hub State:
  BuiltInKeyboardId: -2
  Devices:
    -1: Virtual
      Classes: KEYBOARD | ALPHAKEY | VIRTUAL
      Path: <virtual>
      KeyLayoutFile: /system/usr/keylayout/Generic.kl
    3: Chromecast Remote
      Classes: KEYBOARD | DPAD | EXTERNAL
      Path: /dev/input/event2
      KeyLayoutFile: /vendor/usr/keylayout/Vendor_0957_Product_0001.kl
EOF
for n in 6 7 8; do cat <<EOF
    1$n: ACE666BT Keyboard
      Classes: KEYBOARD | DPAD | EXTERNAL
      Path: /dev/input/event$n
      Identifier: bus=0x0005, vendor=0x0508, product=0x0110, version=0x0001
      KeyLayoutFile: /vendor/usr/keylayout/Vendor_0508_Product_0110.kl
      KeyCharacterMapFile: /system/usr/keychars/Generic.kcm
EOF
done
cat <<EOF

Input Reader State (Nums of external devices: 2)
  Device 16: ACE666BT Keyboard
    KeyLayoutFile: /should/not/be/used.kl
EOF
;;
window) cat <<EOF
WINDOW MANAGER POLICY STATE (dumpsys window policy)
    mSafeMode=false mSystemReady=true mSystemBooted=true
    mKeyMapping={
      KEYCODE_GUIDE=com.google.android.tvlauncher/.GlobalKeyReceiver,dispatchWhenNonInteractive=false
      KEYCODE_TV=com.google.android.tv/.GlobalKeyReceiver,dispatchWhenNonInteractive=true
    }
    mKeyCombinationManager:
EOF
;;
esac'''
for tool, body in {
    "cmd": 'if [ "$1 $2" = "package resolve-activity" ]; then\n'
           '  case "$*" in *LEANBACK_LAUNCHER*com.google.android.youtube.tv*) '
           'echo "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false"; '
           'echo "com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity";;\n'
           '  *) echo "No activity found";; esac\nfi',
    "am": 'echo "am $*" >> "$FAKE_DIR/actions.log"',
    "input": 'echo "input $*" >> "$FAKE_DIR/actions.log"',
    "dumpsys": DUMPSYS,
    "settings": '[ "$*" = "get secure enabled_accessibility_services" ] && '
                'echo "${FAKE_A11Y:-flar2.homebutton/flar2.homebutton.MyAccessibilityService}"',
}.items():
    with open(f"{BIN}/{tool}", "w") as f:
        f.write("#!/bin/sh\n" + body + "\n")
    os.chmod(f"{BIN}/{tool}", 0o755)

# ---------------------------------------------------------------------------
print("A. list / check")
reset()
remote = make_remote()
make_dev("event2", "Chromecast Remote", 0x0957, 0x0001, {0x0c0221: 217, 0x0c0223: 172})
rc, out = rd(["list"]); print(out)
check(rc == 0 and "event6    0508:0110  ACE666BT Keyboard" in out and "event2" in out, "list shows all devices")
rc, out = rd(["check", CONF]); print(out)
check(rc == 0 and f"send keys are pressed on a virtual remote that Android reads with {GENERIC}" in out,
      "check: virtual remote uses Generic.kl")
check(f"Android reads its keys with {VENDOR_KL}" in out, "check: remote uses the vendor key layout")
check("000c0069  KEY_RED -> own key off; sends Android PROG_RED (virtual remote key KEY_RED)" in out, "check: Red line")
check("000c004b  KEY_UNKNOWN -> own key off; sends Android CHANNEL_UP (virtual remote key KEY_CHANNELUP)" in out, "check: Pg/Ch Up line")
check("000c0221  KEY_SEARCH -> KEY_UNKNOWN = Android UNKNOWN (apps ignore it)" in out, "check: Assistant button turned off")
check("000c0008  KEY_UNKNOWN -> own key off; on press: launch org.jellyfin.androidtv" in out, "check: IPTV button opens Jellyfin")
check("000c00e2  KEY_MUTE -> own key off; sends Android VOLUME_MUTE (virtual remote key KEY_MUTE)" in out, "check: Mute line")
check("000c0060  KEY_INFO -> own key off; sends Android INFO (with the slower input keyevent: Generic.kl has no line for it)" in out,
      "check: INFO falls back to input keyevent")
check(f"000c001c  KEY_UNKNOWN -> own key off; sends Android GUIDE, but the system sends it to {GLOBAL_OWNER}, never to the app on screen (virtual remote key KEY_PROGRAM)" in out,
      "check: Guide flagged as a system global key")
check("000c0058  KEY_UNKNOWN -> own key off; on press: launch com.google.android.youtube.tv" in out, "check: YouTube launch")
check('event7 "ACE666BT Keyboard": another part of the same remote as event6 (same buttons)' in out
      and out.count("Android reads its keys") == 1, "check: the remote's other nodes collapse to one line")
check('"Chromecast Remote"' not in out, "device line keeps other remotes out")
check("flar2.homebutton/flar2.homebutton.MyAccessibilityService" in out, "check lists accessibility services")
check(readf("ioctl.log") == "" and readf("uinput.log") == "", "check changed nothing and made no virtual remote")

print("\nB. run in foreground: virtual remote, presses, held key, reconnect, Ctrl+C")
p = start_fg()
ul = readf("uinput.log")
check("setup name=remapd virtual remote bus=6 vendor=0 product=0" in ul and "create" in ul, "virtual remote created, no vendor ID")
keybits = {int(l.split()[1]) for l in ul.splitlines() if l.startswith("keybit")}
check(keybits == VIRT_KEYS, f"virtual remote declares exactly the send keys ({sorted(keybits)})")
check(all(keycode(remote, u) == 240 for u in SEND_USAGES), "every send button's own key is off")
check(keycode(remote, 0x0c00e9) == 115 and keycode(remote, 0x0c0221) == 240, "volume untouched, Assistant button turned off")
il = readf("ioctl.log")
check(sum(1 for l in il.splitlines() if "000c0069 240 -> 240" in l) == 2, "keymap set again through the remote's other two nodes")
for u in (0x0c0069, 0x0c004b, 0x0c00cd, 0x0c0060, 0x0c001c, 0x0c0058, 0x0c00e9, 0x0c0003):
    press(remote, u); time.sleep(0.15)
time.sleep(0.5)
check(virt_events() == [(398, 1), (398, 0), (402, 1), (402, 0), (164, 1), (164, 0), (362, 1), (362, 0)],
      f"virtual remote pressed and released PROG_RED, CHANNEL_UP, PLAY_PAUSE, GUIDE ({virt_events()})")
a = readf("actions.log"); print("  actions:", a.strip().replace("\n", " | "))
check("input keyevent KEYCODE_INFO" in a, "INFO sent with input keyevent")
check("am start -f 0x10200000 -n com.google.android.youtube.tv/" in a, "YouTube still launches")
check(a.count("\n") == 2, "nothing else ran a command")

print("  ...hold Red, then the remote disconnects while it's held")
open(sbx("/dev/uinput"), "wb").close()
press(remote, 0x0c0069, up=False); time.sleep(0.4)
check(virt_events() == [(398, 1)], "holding the button holds the key")
press(remote, 0x0c0069, up=False); time.sleep(0.3)
check(virt_events() == [(398, 1)], "a second down while held isn't sent twice")
time.sleep(0.1)
unplug_remote(); time.sleep(0.6)
check(virt_events() == [(398, 1), (398, 0)], "key released when the remote disconnects")
remote = make_remote()
check(wait_for(lambda: keycode(remote, 0x0c0069) == 240 and keycode(remote, 0x0c00b2) == 240),
      "own keys off again after reconnect")
press(remote, 0x0c00b2); time.sleep(0.4)
check(virt_events()[-2:] == [(167, 1), (167, 0)], "send works after reconnect (MEDIA_RECORD)")
out = stop_fg(p); print(out)
check(p.returncode == 0, "exits cleanly on Ctrl+C")
check(readf("uinput.log").rstrip().endswith("destroy") and not os.path.exists(sbx("/dev/input/event50")), "virtual remote removed on exit")
check(keycode(remote, 0x0c0069) == 398 and keycode(remote, 0x0c004b) == 240 and keycode(remote, 0x0c0060) == 358,
      "original codes restored on exit")
check(f"virtual remote ready for 12 key(s); Android reads it with {GENERIC}" in out, "log: virtual remote ready")
check(f"000c0060: INFO has no line in {GENERIC}, so it's sent with the slower `input keyevent`" in out, "log: INFO fallback explained")
check(f"event50: this is the virtual remote; Android reads it with {GENERIC}" in out, "own virtual remote recognised and left alone")
check(f"event6: Android reads its keys with {VENDOR_KL}" in out, "log: remote's key layout")
check("000c0069  KEY_RED -> KEY_UNKNOWN (own key off; sends Android PROG_RED instead)" in out, "apply log for send lines")
check('event7: "ACE666BT Keyboard" is another part of the same remote as event6' in out
      and "nothing to change" not in out and out.count("Android reads its keys with") == 2,
      "the remote's other nodes: one line each, one key layout lookup per connection")
check("000c0069 pressed -> Android PROG_RED\n" in out.replace("\r", ""), "press log: PROG_RED")
check(out.count("000c0069 pressed -> Android PROG_RED") == 2, "held button logged once, not per extra down")
check("000c0060 pressed -> Android INFO (with input keyevent)" in out, "press log: INFO via input keyevent")
check(f"000c001c pressed -> Android GUIDE, but the system sends it to {GLOBAL_OWNER}, never to the app on screen" in out,
      "press log: GUIDE flagged")
check("000c00e9 pressed: KEY_VOLUMEUP = Android VOLUME_UP" in out, "unconfigured button: Android key from the vendor file")
check("000c0003 pressed: KEY_UNKNOWN = Android UNKNOWN (apps ignore it)" in out, "unconfigured unknown button")
check("/should/not/be/used.kl" not in out, "only the Event Hub block's KeyLayoutFile is used")

print("\nC. background start / status / stop")
reset()
remote = make_remote()
rc, out = rd(["start", CONF]); print(out)
check(rc == 0 and "running in the background" in out and "virtual remote ready for 12 key(s)" in out, "start creates the virtual remote")
check("Android reads its keys" not in out, "background mode skips the dumpsys lookups")
rc, out = rd(["status"])
check(rc == 0 and "is running" in out, "status sees it")
press(remote, 0x0c006a); time.sleep(0.5)
check(virt_events() == [(399, 1), (399, 0)], "background copy sends PROG_GREEN")
rc, out = rd(["start", CONF])
check(rc == 0 and readf("uinput.log").count("create") == 2 and readf("uinput.log").count("destroy") == 1,
      "start again replaces the old copy cleanly")
press(remote, 0x0c0004); time.sleep(0.5)
check("android.settings.SETTINGS" in readf("actions.log"), "background copy runs actions")
rc, out = rd(["stop"]); print(out)
check("Stopped" in out and keycode(remote, 0x0c0069) == 398 and readf("uinput.log").count("destroy") == 2,
      "stop restores buttons and removes the virtual remote")
rc, out = rd(["status"])
check(rc == 1 and "not running" in out, "status after stop")
log = open(sbx("/data/local/tmp/remapd.log")).read()
check("000c0004 pressed -> run am start -a android.settings.SETTINGS" in log and "pressed -> Android" not in log,
      "log keeps actions, not every key press")

print("\nD. device filter, map + run")
reset()
make_remote()
make_dev("event2", "Chromecast Remote", 0x0957, 0x0001, {0x0c0221: 217})
conf2 = f"{FAKE}/t.conf"
open(conf2, "w").write("run 000c0221 input keyevent KEYCODE_TV\n")
rc, out = rd(["check", conf2]); print(out)
check("event2" in out and "event6" in out, "without device line, both remotes would change")
check("(Couldn't find or read the key layout file Android uses for it.)" in out, "unreadable key layout file is reported, not fatal")
open(conf2, "w").write("device 0508:0110\nrun 000c0221 input keyevent KEYCODE_TV\n")
rc, out = rd(["check", conf2])
check("event6" in out and "event2" not in out, "device vendor:product filter")
open(conf2, "w").write("device ace666   # name match\nrun 000c0221 input keyevent KEYCODE_TV\n")
rc, out = rd(["check", conf2])
check("event6" in out and "event2" not in out, "device name filter (case-insensitive, comment stripped)")
open(conf2, "w").write("device ace666\nmap 000c0221 KEY_TV\nrun 000c0221 input keyevent KEYCODE_HOME\n")
rc, out = rd(["check", conf2]); print(out)
check("000c0221  KEY_SEARCH -> KEY_TV = Android UNKNOWN (apps ignore it); on press also: run input keyevent KEYCODE_HOME" in out,
      "map + run on one button; map can't help with this vendor file")

print("\nE. config errors")
open(conf2, "w").write("map 000c001c KEY_NOPE\nmap zz KEY_PROGRAM\nlaunch 000c0058 bad;pkg\nfrobnicate 1 2\n"
                       "map 000c004b KEY_CHANNELUP extra\nrun 000c0004\nsend 000c0069 PROG_REED\n"
                       "map 000c006a KEY_GREEN\nsend 000c006a PROG_GREEN\nsend 000c006b PROG_BLUE\n"
                       "map 000c006b KEY_BLUE\nrun 000c006b echo hi\nsend 000c006c\n")
rc, out = rd(["check", conf2]); print(out)
check(rc == 1 and out.count(" line ") == 11, "every bad line (11 of 13) is reported with its line number")
check("'PROG_REED' is not an Android key name" in out and "use either map or send" in out
      and "already has an action (send, run or launch)" in out, "send errors are specific")
open(conf2, "w").write("﻿map 0x000C001C program\r\nmap 000c004b 402\r\nsend 000c006c keycode_prog_yellow\r\n")
rc, out = rd(["check", conf2])
check(rc == 0 and "report as KEY_PROGRAM" in out and "report as KEY_CHANNELUP" in out
      and "own key off, sends Android PROG_YELLOW" in out, "BOM, CRLF, 0x prefix, short/numeric key names, KEYCODE_ prefix")

print("\nF. kernel refuses the change")
reset()
remote = make_remote()
p = start_fg(env=dict(ENV, FAKE_DENY_SET="1"))
press(remote, 0x0c0069); time.sleep(0.4)
out = stop_fg(p); print(out)
check("FAILED: Operation not permitted" in out and "Android refused to change" in out
      and "send, run and launch lines still work" in out, "a refused change is reported clearly")
check(virt_events() == [(398, 1), (398, 0)], "send still works when the own key can't be turned off")
check("could not put" not in out, "nothing to undo on exit when nothing was changed")

print("\nG. remote connects after remapd starts")
reset()
p = start_fg()
remote = make_remote(first=9)
check(wait_for(lambda: keycode(remote, 0x0c0069) == 240 and keycode(remote, 0x0c006b) == 240),
      "picked up the new remote")
press(remote, 0x0c006b); time.sleep(0.4)
out = stop_fg(p); print(out)
check("waiting for the remote to connect" in out, "said it was waiting")
check(f"event9: Android reads its keys with {VENDOR_KL}" in out,
      "not in dumpsys yet: found Android's key layout with Android's own search")
check(virt_events() == [(401, 1), (401, 0)], "PROG_BLUE sent")

print("\nH. map lines and Android's search order (other remote, vendor file, usage lines win)")
reset()
os.makedirs(sbx("/odm/usr/keylayout"), exist_ok=True)
with open(sbx("/odm/usr/keylayout/Vendor_1d5a_Product_c081.kl"), "w") as f:
    f.write("key 398   PROG_GREEN\nkey usage 0x0c004b MEDIA_NEXT   # usage beats key code\n"
            "key 104   PAGE_UP\naxis 0x00 X\nled 0x00 NUM_LOCK\n")
make_dev("event4", "BLE Remote Pro", 0x1d5a, 0xc081, REMOTE)
conf3 = f"{FAKE}/map.conf"
open(conf3, "w").write("map 000c004b KEY_PAGEUP\nmap 000c004e KEY_PAGEDOWN\n")
p = start_fg(conf3)
for u in (0x0c0069, 0x0c004b, 0x0c004e):
    press(sbx("/dev/input/event4"), u); time.sleep(0.15)
time.sleep(0.4)
out = stop_fg(p); print(out)
check("Android reads its keys with /odm/usr/keylayout/Vendor_1d5a_Product_c081.kl" in out,
      "vendor file in /odm found before /vendor and /system")
check("000c0069 pressed: KEY_RED = Android PROG_GREEN" in out, "vendor file mapping used")
check("000c004b pressed: KEY_PAGEUP = Android MEDIA_NEXT" in out, "HID usage line wins over the key code, like Android")
check("000c004e pressed: KEY_PAGEDOWN = Android UNKNOWN (apps ignore it)" in out, "key missing from the vendor file = UNKNOWN")
check(readf("uinput.log") == "", "no virtual remote without send lines")
shutil.rmtree(sbx("/odm"))

print("\nI. virtual remote can't be made")
reset(uinput=False)
remote = make_remote()
p = start_fg()
press(remote, 0x0c0069); time.sleep(0.4)
out = stop_fg(p); print(out)
check("can't create the virtual remote (/dev/uinput: No such file or directory); sending keys with the slower `input keyevent` instead" in out,
      "missing uinput reported")
check("input keyevent KEYCODE_PROG_RED" in readf("actions.log") and "000c0069 pressed -> Android PROG_RED (with input keyevent)" in out,
      "falls back to input keyevent")
reset()
remote = make_remote()
p = start_fg(env=dict(ENV, FAKE_UINPUT_FAIL="1"))
press(remote, 0x0c00cd); time.sleep(0.4)
out = stop_fg(p)
check("can't create the virtual remote (Operation not permitted)" in out and "input keyevent KEYCODE_MEDIA_PLAY_PAUSE" in readf("actions.log"),
      "refused uinput falls back too")

print("\nJ. no Android tools and no key layout files")
reset()
make_remote()
shutil.move(sbx("/system"), sbx("/system.off")); shutil.move(sbx("/vendor"), sbx("/vendor.off"))
try:
    rc, out = rd(["check", CONF], env=dict(ENV, PATH="/usr/bin:/bin")); print(out)
finally:
    shutil.move(sbx("/system.off"), sbx("/system")); shutil.move(sbx("/vendor.off"), sbx("/vendor"))
check(rc == 0 and "(Can't find the key layout file Android would use for a virtual remote; send lines will use the slower input keyevent.)" in out
      and "(Couldn't find or read the key layout file Android uses for it.)" in out and "accessibility" not in out,
      "check still works without Android tools or files")
check("sends Android PROG_RED (with the slower input keyevent: no key layout file has no line for it)" not in out
      and "sends Android PROG_RED (with the slower input keyevent" in out, "fallback wording without a key layout file")

print("\nK. learn: one press, with the devices grabbed")
reset()
remote = make_remote()
cc = make_dev("event2", "Chromecast Remote", 0x0957, 0x0001, {0x0c008d: 365})
os.makedirs(sbx("/vendor/usr/keylayout"), exist_ok=True)
open(sbx(VENDOR_KL), "w").write(VENDOR_KL_TEXT)
open(sbx("/vendor/usr/keylayout/Vendor_0957_Product_0001.kl"), "w").write("key 365 GUIDE\n")
make_dev("event50", "remapd virtual remote", 0, 0, {})

def learn(secs, then=None):
    p = subprocess.Popen([REMAPD, "learn", str(secs)], env=ENV, stdout=subprocess.PIPE,
                         stderr=subprocess.STDOUT, text=True)
    time.sleep(0.6)
    t0 = time.time()
    if then:
        then()
    rc = p.wait(timeout=secs + 10)
    return rc, p.stdout.read(), time.time() - t0

rc, out, took = learn(10, lambda: press(remote, 0x0c0069)); print(out)
check(rc == 0 and "learned 000c0069 KEY_RED 0508:0110 UNKNOWN - ACE666BT Keyboard\n" in out,
      "learn: Red, which Android doesn't recognise")
check(took < 3, "learn: done as soon as the button is let go (%.1f s)" % took)
grabs = [l for l in readf("ioctl.log").splitlines() if l.startswith("GRAB")]
ino = os.stat(remote).st_ino
check(f"GRAB ino={ino} 1" in grabs and f"GRAB ino={ino} 0" in grabs, "learn: grabs the remote, then lets go")
check(grabs.count(f"GRAB ino={ino} 1") == grabs.count(f"GRAB ino={ino} 0"), "learn: lets go of every node it grabbed")
vino = os.stat(sbx("/dev/input/event50")).st_ino
check(not any(f"ino={vino} " in l for l in grabs), "learn: leaves the virtual remote alone")

rc, out, _ = learn(10, lambda: press(remote, 0x0c00ea)); print(out)
check(rc == 0 and "learned 000c00ea KEY_VOLUMEDOWN 0508:0110 VOLUME_DOWN - ACE666BT Keyboard\n" in out,
      "learn: a key Android knows")
rc, out, _ = learn(10, lambda: press(cc, 0x0c008d)); print(out)
check(rc == 0 and "learned 000c008d KEY_EPG 0957:0001 GUIDE com.google.android.tvlauncher/.GlobalKeyReceiver Chromecast Remote\n" in out,
      "learn: another remote, and a key the system keeps for itself")

def press_without_scan():
    fd = os.open(remote, os.O_WRONLY | os.O_NONBLOCK)
    os.write(fd, EV.pack(0, 0, 1, 108, 1) + EV.pack(0, 0, 0, 0, 0) + EV.pack(0, 0, 1, 108, 0) + EV.pack(0, 0, 0, 0, 0))
    os.close(fd)
rc, out, _ = learn(10, press_without_scan); print(out)
check(rc == 0 and "learned none KEY_DOWN 0508:0110 DPAD_DOWN - ACE666BT Keyboard\n" in out,
      "learn: a press without a scan code says so")
def combo():
    """A button that sends a key combination (Meta+E), like some app buttons do."""
    fd = os.open(remote, os.O_WRONLY | os.O_NONBLOCK)
    os.write(fd, EV.pack(0, 0, 4, 4, 0x700e3) + EV.pack(0, 0, 1, 125, 1) + EV.pack(0, 0, 4, 4, 0x70008)
             + EV.pack(0, 0, 1, 18, 1) + EV.pack(0, 0, 0, 0, 0)
             + EV.pack(0, 0, 4, 4, 0x70008) + EV.pack(0, 0, 1, 18, 0) + EV.pack(0, 0, 4, 4, 0x700e3)
             + EV.pack(0, 0, 1, 125, 0) + EV.pack(0, 0, 0, 0, 0))
    os.close(fd)
rc, out, _ = learn(10, combo); print(out)
check(rc == 0 and "learned 00070008 KEY_E 0508:0110 " in out, "learn: a key combination is learned as its key, not the modifier")

def new_remote():
    """A remote that was asleep reconnects as a new node, then the press arrives."""
    other = make_dev("event12", "Sleepy Remote", 0x1234, 0x5678, {0x0c0030: 116})
    time.sleep(0.5)
    press(other, 0x0c0030)
rc, out, _ = learn(10, new_remote); print(out)
check(rc == 0 and "learned 000c0030 KEY_POWER 1234:5678 POWER - Sleepy Remote" in out,
      "learn: listens to a remote that connects while it waits")
sino = os.stat(sbx("/dev/input/event12")).st_ino
grabs = [l for l in readf("ioctl.log").splitlines() if l.startswith("GRAB")]
check(f"GRAB ino={sino} 1" in grabs and f"GRAB ino={sino} 0" in grabs, "learn: grabs it too, and lets go")

open(f"{FAKE}/ioctl.log", "w").close()
p = subprocess.Popen([REMAPD, "learn", "10"], env=ENV, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
time.sleep(0.6)
p.stdout.close()   # the app went away
try:
    rc = p.wait(timeout=4)
except subprocess.TimeoutExpired:
    p.kill(); rc = None
grabs = [l for l in readf("ioctl.log").splitlines() if l.startswith("GRAB")]
check(rc == 1 and f"GRAB ino={ino} 0" in grabs, "learn: stops and lets go when whoever started it goes away")

rc, out, took = learn(1); print(out)
check(rc == 3 and out.strip().endswith("timeout"), "learn: nothing pressed -> timeout")
rc, out = rd(["learn", "0"])
check(rc == 2 and "remapd learn" in out, "learn: refuses a silly time")

shutil.rmtree(SBX, ignore_errors=True)
shutil.rmtree(FAKE, ignore_errors=True)
print("\nFAILURES:", fails)
sys.exit(1 if fails else 0)
