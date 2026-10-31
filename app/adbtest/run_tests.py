"""Tests the app's adb client (app/src/main/java/io/github/remapd/adb)
against a fake adbd, on a computer.

    python3 app/adbtest/run_tests.py

Needs a JDK (javac, java) and Python 3 with pyOpenSSL, cryptography and
adb-shell (pip install pyopenssl cryptography adb-shell). Set CONSCRYPT_JAR to
the conscrypt-openjdk-uber jar to run everything a second time with
Conscrypt, the crypto and TLS provider Android uses, doing the RSA,
certificates and TLS; without it, only the JDK's are tested.

fake_adbd.py signs in the way adbd does, using OpenSSL and `cryptography`
rather than the Java code under test. The key format is also checked against
adb-shell's implementation, and the token signature and certificate against
`cryptography`.
"""
import base64
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time

from adb_shell.auth.keygen import encode_pubkey
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, utils
from cryptography.x509.oid import NameOID

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "src", "main", "java", "io", "github", "remapd", "adb")
BUILD = os.path.join(HERE, "build")
CLASSES = os.path.join(BUILD, "classes")
CONSCRYPT = os.environ.get("CONSCRYPT_JAR", "")
fails = 0


def check(cond, msg):
    global fails
    print(("  PASS " if cond else "  FAIL ") + msg)
    if not cond:
        fails += 1


def build():
    shutil.rmtree(CLASSES, ignore_errors=True)
    os.makedirs(CLASSES)
    sources = [os.path.join(SRC, f) for f in sorted(os.listdir(SRC)) if f.endswith(".java")]
    sources.append(os.path.join(HERE, "AdbTestMain.java"))
    subprocess.run(["javac", "-Xlint:all", "-Werror", "--release", "11", "-d", CLASSES] + sources,
                   check=True)


provider = "jdk"   # or "conscrypt": which provider AdbTestMain puts first


def java(*args, timeout=60):
    cp = CLASSES + (os.pathsep + CONSCRYPT if CONSCRYPT else "")
    flags = ["-Dadbtest.conscrypt=true"] if provider == "conscrypt" else []
    p = subprocess.run(["java", "-cp", cp] + flags + ["io.github.remapd.adb.AdbTestMain"] + list(args),
                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=timeout)
    return p.returncode, p.stdout


class Fake:
    """fake_adbd.py running in the background."""

    def __init__(self, keys, tls=False, prompt="ignore", extra=()):
        self.log = tempfile.mktemp(suffix=".jsonl")
        cmd = [sys.executable, os.path.join(HERE, "fake_adbd.py"), "--keys", keys,
               "--prompt", prompt, "--log", self.log] + (["--tls"] if tls else []) + list(extra)
        self.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, text=True)
        self.port = int(self.proc.stdout.readline())

    def events(self):
        time.sleep(0.3)   # let the fake finish writing
        if not os.path.exists(self.log):
            return []
        return [json.loads(line) for line in open(self.log)]

    def stop(self):
        self.proc.kill()
        self.proc.wait()
        if os.path.exists(self.log):
            os.unlink(self.log)


def outputs(text):
    """{n: decoded output} and {n: error} from AdbTestMain's lines."""
    out, err = {}, {}
    for line in text.splitlines():
        if line.startswith("OUT "):
            _, n, data = line.split(" ", 2)
            out[int(n)] = base64.b64decode(data).decode("utf-8")
        elif line.startswith("ERR "):
            _, n, msg = line.split(" ", 2)
            err[int(n)] = msg
    return out, err


def connect(fake, keydir, mode, prompt_ms=10000, services=()):
    rc, out = java("connect", str(fake.port), keydir, mode, str(prompt_ms), *services)
    if rc == 0 and provider == "conscrypt" and "PROVIDER Conscrypt" not in out:
        check(False, "Conscrypt was first")
    return rc, out


def errors(events):
    return [e for e in events if e["event"] == "error"]


def main():
    global provider
    build()
    providers = ["jdk"] + (["conscrypt"] if CONSCRYPT else [])
    for provider in providers:
        print("\n===== with the %s providers =====" % ("JDK's" if provider == "jdk" else "Conscrypt"))
        work = tempfile.mkdtemp()
        try:
            run(work)
        finally:
            shutil.rmtree(work, ignore_errors=True)
    if not CONSCRYPT:
        print("\n(CONSCRYPT_JAR isn't set: skipped the run with Android's provider, Conscrypt.)")
    print("\nFAILURES:", fails)
    return 1 if fails else 0


def run(work):
    me, stranger = os.path.join(work, "me"), os.path.join(work, "stranger")
    for d in (me, stranger):
        rc, out = java("keygen", d)
        check(rc == 0, "keygen " + os.path.basename(d) + ("" if rc == 0 else ": " + out))

    print("\nThe key, signature and certificate, checked by other implementations:")
    pub = open(os.path.join(me, "key.pub")).read()
    b64, name = pub.split(" ")
    theirs = base64.b64encode(encode_pubkey(os.path.join(me, "key.pem"))).decode()
    check(b64 == theirs, "public key matches adb-shell's encoding of the same key")
    check(name == "test@adbtest", "public key line ends with the name")
    raw = base64.b64decode(b64)
    fp = ":".join("%02X" % x for x in hashlib.md5(raw).digest())
    check(open(os.path.join(me, "fingerprint.txt")).read() == fp,
          "fingerprint is the MD5 the TV shows (" + fp[:11] + "...)")

    private = serialization.load_der_private_key(open(os.path.join(me, "key.pk8"), "rb").read(), None)
    token = bytes(i * 7 + 1 & 0xFF for i in range(20))
    sig = open(os.path.join(me, "token.sig"), "rb").read()
    try:   # what adbd_auth_verify does: RSA_verify(NID_sha1, token, ...)
        private.public_key().verify(sig, token, padding.PKCS1v15(), utils.Prehashed(hashes.SHA1()))
        sig_ok = True
    except Exception:
        sig_ok = False
    check(sig_ok, "token signature verifies as RSA_verify(NID_sha1) would")

    cert = x509.load_der_x509_certificate(open(os.path.join(me, "cert.der"), "rb").read())
    try:
        cert.public_key().verify(cert.signature, cert.tbs_certificate_bytes, padding.PKCS1v15(),
                                 cert.signature_hash_algorithm)
        cert_ok = True
    except Exception:
        cert_ok = False
    check(cert_ok and cert.version == x509.Version.v3, "certificate is a valid self-signed X.509 v3")
    check(cert.public_key().public_numbers() == private.public_key().public_numbers(),
          "certificate holds the app's key")
    check(cert.subject.get_attributes_for_oid(NameOID.COMMON_NAME)[0].value == "remapd",
          "certificate name")

    keys = os.path.join(work, "adb_keys")

    print("\nPlain TCP (adb tcpip), like the one-time setup:")
    open(keys, "w").close()
    fake = Fake(keys)
    rc, out = connect(fake, me, "SIGNATURE_ONLY")
    ev = fake.events()
    check(rc == 2 and "AUTH KEY_REJECTED" in out, "unknown key without a prompt is rejected")
    check([e["valid"] for e in ev if e["event"] == "signature"] == [False]
          and not any(e["event"] == "pubkey" for e in ev), "it signed once and didn't send the key")
    cnxn = [e for e in ev if e["event"] == "cnxn"]
    check(cnxn and cnxn[0]["version"] == 0x01000001 and cnxn[0]["maxdata"] == 1024 * 1024
          and cnxn[0]["banner"] == "host::\0", "CNXN version, max payload and banner")
    check(not errors(ev), "no protocol errors " + str(errors(ev)))
    fake.stop()

    fake = Fake(keys, prompt="always")
    rc, out = connect(fake, me, "ALLOW_PROMPT", services=["shell:echo hello"])
    ev = fake.events()
    o, e = outputs(out)
    check(rc == 0 and "PROMPT" in out, "unknown key: sends the key, the TV asks, \"Always allow\" connects")
    check(o.get(0) == "hello\n", "shell command output")
    pk = [x for x in ev if x["event"] == "pubkey"]
    check(pk and pk[0]["valid"] and pk[0]["name"] == "test@adbtest",
          "public key arrives NUL-terminated and well-formed")
    check(open(keys).read().strip() == pub.strip(), "\"Always allow\" stored the key")
    check(not errors(ev), "no protocol errors " + str(errors(ev)))
    fake.stop()

    fake = Fake(keys)   # nobody answers prompts now
    rc, out = connect(fake, me, "SIGNATURE_ONLY", services=[
        "shell:__big__ 300000", "shell:printf 'a\\nb'; exit 3", "nonsense:", "!usb:"])
    ev = fake.events()
    o, e = outputs(out)
    check(rc == 0 and "PROMPT" not in out, "known key signs in with its signature alone")
    check([x["valid"] for x in ev if x["event"] == "signature"] == [True], "one valid signature")
    check(o.get(0) == "".join(chr(32 + i % 95) for i in range(300000)),
          "300 KB of output over many WRTE/OKAY rounds")
    check(o.get(1) == "a\nb", "output without a trailing newline")
    check("couldn't open" in e.get(2, ""), "unknown service: open fails cleanly")
    check(o.get(3) == "restarting in USB mode\n", "\"usb:\" output, then the connection drops")
    check(not errors(ev), "no protocol errors " + str(errors(ev)))
    fake.stop()

    fake = Fake(keys)
    rc, out = connect(fake, me, "SIGNATURE_ONLY", services=["shell:__big__ 2000000", "shell:echo after"])
    ev = fake.events()
    o, e = outputs(out)
    check("more than 1024 KB" in e.get(0, ""), "output is capped at 1 MB")
    check(any(x["event"] == "client_closed_stream" for x in ev), "...and the client closes that stream")
    check(o.get(1) == "after\n", "the connection still works afterwards")
    check(not errors(ev), "no protocol errors " + str(errors(ev)))
    fake.stop()

    open(keys, "w").close()
    fake = Fake(keys, prompt="ignore")
    t0 = time.time()
    rc, out = connect(fake, me, "ALLOW_PROMPT", prompt_ms=2000)
    took = time.time() - t0
    check(rc == 3 and "PROMPT" in out, "prompt not answered: gives up as NOT_ALLOWED")
    check(took < 8, "...after the prompt timeout (%.1f s)" % took)
    fake.stop()

    fake = Fake(keys, prompt="once")
    rc, out = connect(fake, me, "ALLOW_PROMPT", services=["shell:echo once"])
    check(rc == 0 and outputs(out)[0].get(0) == "once\n", "\"Allow\" without \"Always\" connects")
    check(open(keys).read() == "", "...and doesn't store the key")
    fake.stop()

    fake = Fake(keys)
    rc, out = connect(fake, me, "TLS")
    ev = fake.events()
    check(rc == 4 and "AUTH REFUSED" in out, "TLS mode refuses to sign for a plain port")
    check(not any(x["event"] == "signature" for x in ev), "...and sent no signature")
    fake.stop()

    fake = Fake(keys, extra=["--no-auth"])
    rc, out = connect(fake, me, "TLS", services=["shell:echo hi"])
    check(rc == 4 and "AUTH REFUSED" in out and "OUT 0" not in out,
          "TLS mode refuses a server that skips TLS, and runs nothing")
    fake.stop()

    with open(keys, "w") as f:
        f.write(pub + "\n")
    print("\nWireless debugging (TLS 1.3):")
    fake = Fake(keys, tls=True)
    rc, out = connect(fake, me, "TLS", services=["shell:echo hi", "shell:__big__ 70000"])
    ev = fake.events()
    o, e = outputs(out)
    check(rc == 0, "known key connects over TLS" + ("" if rc == 0 else ": " + out))
    check(any(x["event"] == "tls_ok" and x["version"] == "TLSv1.3" for x in ev), "TLS 1.3 was used")
    check([x["accepted"] for x in ev if x["event"] == "tls_verify"][-1:] == [True],
          "adbd-style check accepted the certificate's key")
    check(o.get(0) == "hi\n" and len(o.get(1, "")) == 70000, "commands run over TLS")
    check(not any(x["event"] == "signature" for x in ev), "no token signing over TLS")
    check(not errors(ev), "no protocol errors " + str(errors(ev)))
    fake.stop()

    fake = Fake(keys, tls=True)
    rc, out = connect(fake, stranger, "TLS")
    ev = fake.events()
    check(rc == 2 and "AUTH KEY_REJECTED" in out, "unknown key is rejected over TLS" +
          ("" if rc == 2 else ": " + out))
    check(any(x["event"] == "tls_failed" for x in ev), "...by the TLS handshake")
    fake.stop()

    fake = Fake(keys, tls=True)
    rc, out = connect(fake, stranger, "ALLOW_PROMPT")
    check(rc == 2 and "PROMPT" not in out, "no prompt is possible over TLS: rejected")
    fake.stop()

    fake = Fake(keys, tls=True, extra=["--drop-after-tls"])
    rc, out = connect(fake, me, "TLS")
    check(rc == 5 and "dropped" in out,
          "known key, connection dropped: a failure to retry, not a rejected key" +
          ("" if rc == 5 else ": " + out))
    fake.stop()


if __name__ == "__main__":
    sys.exit(main())
