#!/usr/bin/env python3
"""A fake adbd, for testing the app's adb client without a device.

It speaks the adb protocol the way adbd does (AOSP adb/protocol.txt, adb.cpp
and daemon/auth.cpp), with real cryptography from other implementations than
the app's: OpenSSL (through pyOpenSSL) for TLS 1.3, and `cryptography` for the
RSA checks. Plain mode signs in like a port opened with `adb tcpip`: a token
to sign, then the public key and a prompt. TLS mode is like the wireless
debugging port: STLS, then a TLS handshake that only accepts known keys.

    fake_adbd.py --keys FILE [--tls] [--prompt always|once|ignore] --log FILE

Prints the port it listens on (127.0.0.1), then serves connections until it's
killed. Everything it sees goes to --log as JSON lines for the test to check.
"""
import argparse
import base64
import datetime
import hashlib
import json
import os
import socket
import struct
import subprocess
import sys
import threading
import warnings

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa, utils
from cryptography.x509.oid import NameOID
from OpenSSL import SSL, crypto

A_CNXN, A_AUTH, A_OPEN, A_OKAY = 0x4E584E43, 0x48545541, 0x4E45504F, 0x59414B4F
A_CLSE, A_WRTE, A_STLS = 0x45534C43, 0x45545257, 0x534C5453
A_VERSION = 0x01000001
A_STLS_VERSION = 0x01000000
MAX_PAYLOAD = 1024 * 1024
AUTH_TOKEN, AUTH_SIGNATURE, AUTH_RSAPUBLICKEY = 1, 2, 3
PUBKEY_SIZE = 3 * 4 + 2 * 256
CHUNK = 4096   # small writes, so long output takes many WRTE/OKAY rounds

log_lock = threading.Lock()


class Log:
    def __init__(self, path):
        self.path = path

    def __call__(self, event, **fields):
        fields["event"] = event
        with log_lock, open(self.path, "a") as f:
            f.write(json.dumps(fields) + "\n")


def decode_adb_pubkey(text):
    """android_pubkey_decode, with every field checked, from "base64 name"."""
    raw = base64.b64decode(text.split()[0], validate=True)
    if len(raw) != PUBKEY_SIZE:
        raise ValueError("encoded key is %d bytes, not %d" % (len(raw), PUBKEY_SIZE))
    words, n0inv = struct.unpack_from("<II", raw, 0)
    n = int.from_bytes(raw[8:264], "little")
    rr = int.from_bytes(raw[264:520], "little")
    (e,) = struct.unpack_from("<I", raw, 520)
    if words != 64:
        raise ValueError("modulus_size_words is %d" % words)
    if n.bit_length() != 2048:
        raise ValueError("modulus isn't 2048 bits")
    if (n0inv * n) % 2**32 != 2**32 - 1:
        raise ValueError("n0inv is wrong")
    if rr != pow(2, 4096, n):
        raise ValueError("rr is wrong")
    if e != 65537:
        raise ValueError("exponent is %d" % e)
    return rsa.RSAPublicNumbers(e, n).public_key()


def known_keys(path):
    """The keys in an adb_keys-style file (one "base64 name" per line)."""
    keys = []
    if os.path.exists(path):
        for line in open(path):
            if line.strip():
                keys.append(decode_adb_pubkey(line))
    return keys


def server_identity():
    """adbd makes a random RSA key and self-signed certificate for its TLS side."""
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Adb")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
            .public_key(key.public_key()).serial_number(1)
            .not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=365))
            .sign(key, hashes.SHA256()))
    return key, cert


class Transport:
    """One connection: plain socket at first, TLS after STLS."""

    def __init__(self, sock, log):
        self.sock = sock
        self.conn = sock
        self.log = log

    def recv_exact(self, n):
        buf = b""
        while len(buf) < n:
            try:
                chunk = self.conn.recv(n - len(buf))
            except (SSL.ZeroReturnError, SSL.SysCallError):
                chunk = b""
            if not chunk:
                raise EOFError
            buf += chunk
        return buf

    def recv(self):
        header = self.recv_exact(24)
        cmd, arg0, arg1, length, check, magic = struct.unpack("<6I", header)
        if magic != cmd ^ 0xFFFFFFFF:
            raise ValueError("bad magic")
        if length > MAX_PAYLOAD:
            raise ValueError("payload too large")
        payload = self.recv_exact(length)
        if check != sum(payload) & 0xFFFFFFFF:
            raise ValueError("bad checksum")
        return cmd, arg0, arg1, payload

    def send(self, cmd, arg0, arg1, payload=b""):
        header = struct.pack("<6I", cmd, arg0, arg1, len(payload), sum(payload) & 0xFFFFFFFF,
                             cmd ^ 0xFFFFFFFF)
        self.conn.sendall(header + payload)


class Session:
    def __init__(self, sock, args, log):
        self.t = Transport(sock, log)
        self.args = args
        self.log = log
        self.next_id = 100

    def run(self):
        cmd, version, maxdata, payload = self.t.recv()
        if cmd != A_CNXN:
            self.log("error", message="first packet wasn't CNXN")
            return
        self.log("cnxn", version=version, maxdata=maxdata,
                 banner=payload.decode("latin-1"))
        if self.args.tls:
            self.tls_sign_in()
        else:
            self.plain_sign_in()
        self.serve()

    def device_banner(self):
        return b"device::ro.product.name=fake;ro.product.model=fake;features=cmd\0"

    def tls_sign_in(self):
        self.t.send(A_STLS, A_STLS_VERSION, 0)
        cmd, arg0, _, _ = self.t.recv()
        if cmd != A_STLS:
            self.log("error", message="expected STLS back")
            raise EOFError
        self.log("stls", version=arg0)
        key, cert = server_identity()
        ctx = SSL.Context(SSL.TLS_METHOD)
        ctx.set_min_proto_version(SSL.TLS1_3_VERSION)
        ctx.set_max_proto_version(SSL.TLS1_3_VERSION)
        ctx.use_certificate(cert)
        ctx.use_privatekey(key)
        keys = known_keys(self.args.keys)

        # Like adbd_tls_client_ca_list: "O=AdbKey-0, CN=<SHA-256 of each known key>".
        # The client has to send its certificate even though its name isn't listed.
        try:
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", DeprecationWarning)
                names = []
                for k in keys:
                    der = k.public_bytes(serialization.Encoding.DER,
                                         serialization.PublicFormat.SubjectPublicKeyInfo)
                    name = crypto.X509().get_subject()
                    name.O = "AdbKey-0"
                    name.CN = hashlib.sha256(der).hexdigest().upper()
                    names.append(name)
                ctx.set_client_ca_list(names)
        except AttributeError:   # a pyOpenSSL without X509Name support
            self.log("note", message="no client CA list")

        def verify(conn, x509cert, errnum, depth, ok):
            # Like adbd_tls_verify_cert: only the key matters.
            pub = x509cert.to_cryptography().public_key()
            match = any(isinstance(pub, rsa.RSAPublicKey)
                        and pub.public_numbers() == k.public_numbers() for k in keys)
            self.log("tls_verify", accepted=match)
            return match

        ctx.set_verify(SSL.VERIFY_PEER | SSL.VERIFY_FAIL_IF_NO_PEER_CERT, verify)
        conn = SSL.Connection(ctx, self.t.sock)
        conn.set_accept_state()
        try:
            conn.do_handshake()
        except SSL.Error as e:
            self.log("tls_failed", message=str(e))
            raise EOFError
        self.log("tls_ok", version=conn.get_protocol_version_name())
        self.t.conn = conn
        self.t.send(A_CNXN, A_VERSION, MAX_PAYLOAD, self.device_banner())

    def plain_sign_in(self):
        token = os.urandom(20)
        self.t.send(A_AUTH, AUTH_TOKEN, 0, token)
        while True:
            cmd, arg0, _, payload = self.t.recv()
            if cmd != A_AUTH:
                self.log("error", message="expected AUTH, got %08x" % cmd)
                raise EOFError
            if arg0 == AUTH_SIGNATURE:
                ok = False
                for k in known_keys(self.args.keys):
                    try:   # RSA_verify(NID_sha1, token, ...), as adbd_auth_verify does
                        k.verify(payload, token, padding.PKCS1v15(), utils.Prehashed(hashes.SHA1()))
                        ok = True
                    except Exception:
                        pass
                self.log("signature", valid=ok, size=len(payload))
                if ok:
                    self.t.send(A_CNXN, A_VERSION, MAX_PAYLOAD, self.device_banner())
                    return
                token = os.urandom(20)
                self.t.send(A_AUTH, AUTH_TOKEN, 0, token)
            elif arg0 == AUTH_RSAPUBLICKEY:
                if not payload.endswith(b"\0"):
                    self.log("error", message="public key isn't NUL-terminated")
                    raise EOFError
                text = payload[:-1].decode("ascii")
                try:
                    decode_adb_pubkey(text)
                    valid = True
                except Exception as e:
                    self.log("pubkey_invalid", message=str(e))
                    valid = False
                self.log("pubkey", valid=valid, name=text.split(" ", 1)[1] if " " in text else "")
                if not valid:
                    raise EOFError
                if self.args.prompt in ("always", "once"):
                    if self.args.prompt == "always":
                        with open(self.args.keys, "a") as f:
                            f.write(text + "\n")
                    self.t.send(A_CNXN, A_VERSION, MAX_PAYLOAD, self.device_banner())
                    return
                # "ignore": nobody answers the prompt; adbd just waits.
            else:
                self.log("error", message="unexpected AUTH type %d" % arg0)
                raise EOFError

    def serve(self):
        while True:
            cmd, arg0, arg1, payload = self.t.recv()
            if cmd == A_OPEN:
                self.open(arg0, payload.rstrip(b"\0").decode("utf-8"))
            elif cmd == A_CLSE:
                self.log("client_close", local=arg0, remote=arg1)
            elif cmd == A_OKAY:
                pass
            else:
                self.log("error", message="unexpected packet %08x" % cmd)

    def open(self, remote, service):
        self.log("open", service=service)
        local = self.next_id
        self.next_id += 1
        if service.startswith("shell:"):
            command = service[len("shell:"):]
            if command.startswith("__big__ "):
                output = bytes(32 + i % 95 for i in range(int(command.split()[1])))
            else:
                output = subprocess.run(["sh", "-c", command], stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, timeout=10).stdout
            self.t.send(A_OKAY, local, remote)
            for i in range(0, len(output), CHUNK):
                self.t.send(A_WRTE, local, remote, output[i:i + CHUNK])
                self.wait_okay(local, remote)
            self.t.send(A_CLSE, local, remote)
        elif service == "usb:":
            # adbd answers, then restarts, which drops the connection.
            self.t.send(A_OKAY, local, remote)
            self.t.send(A_WRTE, local, remote, b"restarting in USB mode\n")
            self.log("usb")
            raise EOFError
        else:
            self.t.send(A_CLSE, 0, remote)   # failed OPEN

    def wait_okay(self, local, remote):
        while True:
            cmd, arg0, arg1, _ = self.t.recv()
            if cmd == A_OKAY and arg1 == local:
                if arg0 != remote:
                    self.log("error", message="OKAY from the wrong stream")
                return
            self.log("error", message="expected OKAY, got %08x" % cmd)


def handle(sock, args, log):
    try:
        Session(sock, args, log).run()
    except (EOFError, ConnectionError, OSError, SSL.Error) as e:
        log("closed", message=type(e).__name__)
    except Exception as e:   # report, don't hang the test
        log("error", message="%s: %s" % (type(e).__name__, e))
    finally:
        try:
            sock.close()
        except OSError:
            pass


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--keys", required=True)
    p.add_argument("--tls", action="store_true")
    p.add_argument("--prompt", choices=["always", "once", "ignore"], default="ignore")
    p.add_argument("--log", required=True)
    args = p.parse_args()
    log = Log(args.log)
    server = socket.socket()
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", 0))
    server.listen(4)
    print(server.getsockname()[1], flush=True)
    while True:
        sock, _ = server.accept()
        threading.Thread(target=handle, args=(sock, args, log), daemon=True).start()


if __name__ == "__main__":
    sys.exit(main())
