package ca.gureet.remapd.adb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.Provider;
import java.security.Security;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/**
 * Drives the app's adb client from the command line, for run_tests.py.
 *
 *   keygen DIR
 *       Makes a key and writes key.pk8, key.pem, key.pub (adb format),
 *       cert.der and fingerprint.txt to DIR.
 *   connect PORT DIR MODE PROMPT_MS SERVICE...
 *       Connects to 127.0.0.1:PORT with DIR's key. MODE is TLS,
 *       SIGNATURE_ONLY or ALLOW_PROMPT. Runs each SERVICE ("!" in front: it
 *       may drop the connection) and prints "OUT <n> <base64>" or
 *       "ERR <n> <message>" for each. With "~" in front, another thread
 *       aborts the connection a second in, and it prints
 *       "ABORTED <n> <milliseconds until the service failed> <message>".
 *
 * With -Dadbtest.conscrypt=true, Conscrypt (Android's crypto and TLS
 * provider) goes first, so it does the RSA, certificates and TLS, as on a TV.
 *
 * Exit status: 0 connected, 2 key rejected, 3 not allowed, 4 refused, 5 other error.
 */
public final class AdbTestMain {
    public static void main(String[] args) throws Exception {
        if (Boolean.getBoolean("adbtest.conscrypt")) {
            Provider conscrypt = (Provider) Class.forName("org.conscrypt.Conscrypt")
                    .getMethod("newProvider").invoke(null);
            Security.insertProviderAt(conscrypt, 1);
        }
        if (args[0].equals("keygen")) {
            keygen(Paths.get(args[1]));
            return;
        }
        int port = Integer.parseInt(args[1]);
        AdbIdentity id = AdbIdentity.fromEncodedPrivateKey(
                Files.readAllBytes(Paths.get(args[2], "key.pk8")), "test@adbtest");
        AdbConnection.Mode mode = AdbConnection.Mode.valueOf(args[3]);
        int promptMs = Integer.parseInt(args[4]);

        AdbConnection c;
        try {
            c = AdbConnection.open("127.0.0.1", port, id, mode,
                    () -> System.out.println("PROMPT"), promptMs, null);
        } catch (AdbAuthException e) {
            System.out.println("AUTH " + e.reason + " " + e.getMessage());
            System.exit(e.reason == AdbAuthException.Reason.KEY_REJECTED ? 2
                    : e.reason == AdbAuthException.Reason.NOT_ALLOWED ? 3 : 4);
            return;
        } catch (IOException e) {
            System.out.println("FAIL " + e);
            System.exit(5);
            return;
        }
        System.out.println("BANNER " + c.banner());
        System.out.println("PROVIDER " + Security.getProviders()[0].getName());
        AdbConnection conn = c;
        try {
            for (int i = 5; i < args.length; i++) {
                boolean ends = args[i].startsWith("!");
                boolean abort = args[i].startsWith("~");
                String service = ends || abort ? args[i].substring(1) : args[i];
                if (abort) {
                    new Thread(() -> {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {
                        }
                        conn.abort();
                    }).start();
                }
                long start = System.nanoTime();
                try {
                    String out = c.run(service, 20000, ends);
                    System.out.println("OUT " + (i - 5) + " " + Base64.getEncoder()
                            .encodeToString(out.getBytes(StandardCharsets.UTF_8)));
                } catch (IOException e) {
                    System.out.println((abort ? "ABORTED " : "ERR ") + (i - 5) + " "
                            + (abort ? (System.nanoTime() - start) / 1_000_000 + " " : "") + e.getMessage());
                }
            }
        } finally {
            try {
                c.close();
            } catch (IOException ignored) {   // after an abort
            }
        }
    }

    private static void keygen(Path dir) throws Exception {
        Files.createDirectories(dir);
        KeyPair pair = AdbIdentity.generateKeyPair();
        AdbIdentity id = new AdbIdentity(pair.getPrivate(), (RSAPublicKey) pair.getPublic(), "test@adbtest");
        byte[] pk8 = id.encodedPrivateKey();
        Files.write(dir.resolve("key.pk8"), pk8);
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pk8)
                + "\n-----END PRIVATE KEY-----\n";
        Files.write(dir.resolve("key.pem"), pem.getBytes(StandardCharsets.US_ASCII));
        Files.write(dir.resolve("key.pub"), id.publicKeyString().getBytes(StandardCharsets.US_ASCII));
        Files.write(dir.resolve("cert.der"), id.certificate.getEncoded());
        Files.write(dir.resolve("fingerprint.txt"), id.fingerprint().getBytes(StandardCharsets.US_ASCII));
        // A token signature for the test to check with another RSA implementation.
        byte[] token = new byte[20];
        for (int i = 0; i < token.length; i++) {
            token[i] = (byte) (i * 7 + 1);
        }
        Files.write(dir.resolve("token.sig"), id.signToken(token));
    }
}
