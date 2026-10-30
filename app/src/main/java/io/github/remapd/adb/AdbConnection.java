package io.github.remapd.adb;

import static io.github.remapd.adb.AdbMessage.A_AUTH;
import static io.github.remapd.adb.AdbMessage.A_CLSE;
import static io.github.remapd.adb.AdbMessage.A_CNXN;
import static io.github.remapd.adb.AdbMessage.A_OKAY;
import static io.github.remapd.adb.AdbMessage.A_OPEN;
import static io.github.remapd.adb.AdbMessage.A_STLS;
import static io.github.remapd.adb.AdbMessage.A_WRTE;
import static io.github.remapd.adb.AdbMessage.AUTH_RSAPUBLICKEY;
import static io.github.remapd.adb.AdbMessage.AUTH_SIGNATURE;
import static io.github.remapd.adb.AdbMessage.AUTH_TOKEN;
import static io.github.remapd.adb.AdbMessage.EMPTY;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.function.Consumer;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * A connection to adbd, the adb daemon on the device, from the app itself.
 * It does what `adb connect` and `adb shell` do on a computer, and nothing
 * else: no pairing, no file transfer.
 *
 * <p>adbd decides how the connection signs in. On the wireless debugging port
 * it switches to TLS, and checks the app's key from the TLS certificate. On a
 * plain TCP port (after `adb tcpip`), it sends a token to sign; if it doesn't
 * know the key, the client can send the public key, and the TV asks the user
 * whether to allow it ("Allow USB debugging?"). Either way, adbd only accepts
 * keys listed in /data/misc/adb/adb_keys, where "Always allow" puts them.
 */
public final class AdbConnection implements Closeable {
    public enum Mode {
        /** Wireless debugging: the connection has to switch to TLS. Never signs a token. */
        TLS,
        /** Plain TCP: signs adbd's token, and gives up if adbd doesn't know the key. */
        SIGNATURE_ONLY,
        /** Plain TCP: if adbd doesn't know the key, sends it so the TV asks the user. */
        ALLOW_PROMPT,
    }

    public interface PromptListener {
        /** The TV is showing "Allow USB debugging?" now. */
        void waitingForApproval();
    }

    static final int CONNECT_TIMEOUT_MS = 5000;
    static final int READ_TIMEOUT_MS = 15000;
    /** More output than any of the app's commands print; stops a misbehaving server filling memory. */
    static final int MAX_OUTPUT = 1024 * 1024;

    private final Socket plain;
    private Socket socket;   // the plain socket, or the TLS socket on top of it
    private InputStream in;
    private OutputStream out;
    private int nextLocalId = 1;
    private String banner = "";
    /**
     * Whether adbd's TLS handshake listed the app's key among the keys it
     * knows (adbd sends that list to the client), or null if it sent no list.
     */
    private volatile Boolean keyListed;

    private AdbConnection(Socket plain) throws IOException {
        this.plain = plain;
        this.socket = plain;
        this.in = plain.getInputStream();   // unbuffered, so nothing is read past the switch to TLS
        this.out = plain.getOutputStream();
    }

    /**
     * Connects and signs in.
     *
     * @param tlsProvider the TLS implementation to use, or null for the default
     *                    (tests use this to try Conscrypt, Android's own)
     * @throws AdbAuthException if adbd doesn't accept the key
     */
    public static AdbConnection open(String host, int port, AdbIdentity identity, Mode mode,
                                     PromptListener listener, int promptTimeoutMs,
                                     Provider tlsProvider) throws IOException {
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(READ_TIMEOUT_MS);
            AdbConnection c = new AdbConnection(s);
            c.signIn(host, port, identity, mode, listener, promptTimeoutMs, tlsProvider);
            return c;
        } catch (IOException | RuntimeException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    /** What adbd said about itself, like "device::ro.product.name=...;features=...". */
    public String banner() {
        return banner;
    }

    private void signIn(String host, int port, AdbIdentity id, Mode mode, PromptListener listener,
                        int promptTimeoutMs, Provider tlsProvider) throws IOException {
        send(A_CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD,
                "host::\0".getBytes(StandardCharsets.US_ASCII));
        boolean tls = false;
        boolean signed = false;
        boolean sentKey = false;
        while (true) {
            AdbMessage m;
            try {
                m = AdbMessage.read(in);
            } catch (SSLException | EOFException | SocketException e) {
                if (!tls) {
                    throw e;
                }
                // In TLS 1.3, the client's side of the handshake is over before adbd
                // checks the certificate, so a rejected key shows up here.
                throw afterTlsFailure(e);
            } catch (SocketTimeoutException e) {
                if (sentKey) {
                    throw new AdbAuthException(AdbAuthException.Reason.NOT_ALLOWED,
                            "the connection wasn't allowed on the TV in time", e);
                }
                throw e;
            }

            switch (m.command) {
                case A_STLS:
                    if (tls) {
                        throw new IOException("adbd asked to switch to TLS twice");
                    }
                    send(A_STLS, AdbMessage.STLS_VERSION, 0, EMPTY);
                    startTls(host, port, id, tlsProvider);
                    tls = true;
                    break;

                case A_AUTH:
                    if (tls || mode == Mode.TLS) {
                        // A wireless debugging port never does this; don't sign for whoever it is.
                        throw new AdbAuthException(AdbAuthException.Reason.REFUSED,
                                "expected a TLS connection, but the other side asked for a signature", null);
                    }
                    if (m.arg0 != AUTH_TOKEN) {
                        throw new IOException("unexpected adb AUTH packet, type " + m.arg0);
                    }
                    if (!signed) {
                        send(A_AUTH, AUTH_SIGNATURE, 0, sign(id, m.payload));
                        signed = true;
                    } else if (mode == Mode.ALLOW_PROMPT && !sentKey) {
                        // adbd didn't recognise the signature: send the key, and the TV asks.
                        send(A_AUTH, AUTH_RSAPUBLICKEY, 0,
                                (id.publicKeyString() + "\0").getBytes(StandardCharsets.US_ASCII));
                        sentKey = true;
                        socket.setSoTimeout(promptTimeoutMs);
                        if (listener != null) {
                            listener.waitingForApproval();
                        }
                    } else if (sentKey) {
                        throw new AdbAuthException(AdbAuthException.Reason.NOT_ALLOWED,
                                "the connection wasn't allowed on the TV", null);
                    } else {
                        throw new AdbAuthException(AdbAuthException.Reason.KEY_REJECTED,
                                "adbd doesn't know the app's key", null);
                    }
                    break;

                case A_CNXN:
                    if (mode == Mode.TLS && !tls) {
                        // Wireless debugging always switches to TLS first; this is something else.
                        throw new AdbAuthException(AdbAuthException.Reason.REFUSED,
                                "expected a TLS connection, but the other side didn't ask for one", null);
                    }
                    socket.setSoTimeout(READ_TIMEOUT_MS);
                    banner = new String(m.payload, StandardCharsets.UTF_8).replace("\0", "");
                    return;

                default:
                    throw new IOException("unexpected adb packet while connecting: " + m);
            }
        }
    }

    private void sawKeyList(Boolean listed) {
        keyListed = listed;
    }

    /**
     * Tells a rejected key from a dropped connection, using the list of known
     * keys adbd sent during the handshake: a key on that list is accepted, so
     * the connection must have dropped (worth trying again later).
     */
    private IOException afterTlsFailure(IOException e) {
        Boolean listed = keyListed;
        if (Boolean.TRUE.equals(listed)) {
            return new IOException("the connection to adbd dropped (" + e.getMessage() + ")", e);
        }
        return new AdbAuthException(AdbAuthException.Reason.KEY_REJECTED, listed == null
                ? "adbd ended the connection; it doesn't seem to accept the app's key (" + e.getMessage() + ")"
                : "adbd doesn't know the app's key (" + e.getMessage() + ")", e);
    }

    private static byte[] sign(AdbIdentity id, byte[] token) throws IOException {
        try {
            return id.signToken(token);
        } catch (GeneralSecurityException e) {
            throw new IOException("couldn't sign adbd's token: " + e.getMessage(), e);
        }
    }

    private void startTls(String host, int port, AdbIdentity id, Provider provider) throws IOException {
        SSLSocket ssl;
        try {
            SSLContext context = provider == null
                    ? SSLContext.getInstance("TLS") : SSLContext.getInstance("TLS", provider);
            context.init(new KeyManager[]{new IdentityKeyManager(id, this::sawKeyList)},
                    new TrustManager[]{new AnyServerTrustManager()}, null);
            ssl = (SSLSocket) context.getSocketFactory().createSocket(plain, host, port, true);
        } catch (GeneralSecurityException e) {
            throw new IOException("couldn't set up TLS: " + e.getMessage(), e);
        }
        ssl.setUseClientMode(true);
        ssl.setEnabledProtocols(new String[]{"TLSv1.3"});   // all adbd accepts
        ssl.setSoTimeout(READ_TIMEOUT_MS);
        try {
            ssl.startHandshake();
        } catch (SocketTimeoutException e) {
            throw e;
        } catch (IOException e) {
            // adbd ends the handshake when it doesn't know the key; depending on
            // timing, that shows up here (an alert, or the connection dropping
            // while the client is still sending) or on the first read.
            throw afterTlsFailure(e);
        }
        socket = ssl;
        in = ssl.getInputStream();
        out = ssl.getOutputStream();
    }

    /**
     * Opens an adb service, like "shell:ls", and returns everything it prints
     * until it closes.
     *
     * @param endsConnection true for services that restart adbd (like "usb:"),
     *                       where the connection may drop instead of closing the stream
     */
    public String run(String service, int timeoutMs, boolean endsConnection) throws IOException {
        int localId = nextLocalId++;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int remoteId = 0;
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        try {
            send(A_OPEN, localId, 0, (service + "\0").getBytes(StandardCharsets.UTF_8));
            while (true) {
                long left = (deadline - System.nanoTime()) / 1_000_000L;
                AdbMessage m;
                try {
                    if (left <= 0) {
                        throw new SocketTimeoutException();
                    }
                    socket.setSoTimeout((int) Math.min(left, Integer.MAX_VALUE));
                    m = AdbMessage.read(in);
                } catch (SocketTimeoutException e) {
                    if (remoteId != 0) {
                        send(A_CLSE, localId, remoteId, EMPTY);
                    }
                    throw new SocketTimeoutException(describe(service) + " didn't finish in time");
                }
                if (m.arg1 != localId) {
                    continue;   // not for this stream
                }
                switch (m.command) {
                    case A_OKAY:
                        if (remoteId == 0) {
                            remoteId = m.arg0;
                        }
                        break;
                    case A_WRTE:
                        if (remoteId == 0) {
                            remoteId = m.arg0;
                        }
                        if (output.size() + m.payload.length > MAX_OUTPUT) {
                            send(A_CLSE, localId, remoteId, EMPTY);
                            throw new IOException(describe(service) + " printed more than "
                                    + MAX_OUTPUT / 1024 + " KB");
                        }
                        output.write(m.payload, 0, m.payload.length);
                        send(A_OKAY, localId, m.arg0, EMPTY);   // ready for more
                        break;
                    case A_CLSE:
                        if (remoteId == 0 && m.arg0 == 0) {
                            throw new IOException("adbd couldn't open " + describe(service));
                        }
                        return text(output);   // per protocol.txt, a CLOSE gets no reply
                    default:
                        break;
                }
            }
        } catch (EOFException | SocketException | SSLException e) {
            if (endsConnection && remoteId != 0) {
                return text(output);   // adbd restarted, as expected
            }
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
        plain.close();
    }

    private void send(int command, int arg0, int arg1, byte[] payload) throws IOException {
        out.write(new AdbMessage(command, arg0, arg1, payload).encode());
        out.flush();
    }

    private static String text(ByteArrayOutputStream output) {
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String describe(String service) {
        int colon = service.indexOf(':');
        return "adb service \"" + (colon >= 0 ? service.substring(0, colon + 1) : service) + "\"";
    }

    /**
     * Presents the app's key and certificate whatever adbd asks for, and notes
     * whether adbd's list of keys it knows includes this one.
     */
    private static final class IdentityKeyManager extends X509ExtendedKeyManager {
        private static final String ALIAS = "adb";
        private final AdbIdentity id;
        private final Consumer<Boolean> keyListed;

        IdentityKeyManager(AdbIdentity id, Consumer<Boolean> keyListed) {
            this.id = id;
            this.keyListed = keyListed;
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            keyListed.accept(id.isListedIn(issuers));
            return ALIAS;
        }

        @Override
        public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
            keyListed.accept(id.isListedIn(issuers));
            return ALIAS;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return new String[]{ALIAS};
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return ALIAS.equals(alias) ? new X509Certificate[]{id.certificate} : null;
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return ALIAS.equals(alias) ? id.privateKey : null;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return null;
        }
    }

    /**
     * Accepts adbd's certificate without checking it, as adb on a computer
     * does: adbd makes a new self-signed one each time it starts, so there's
     * nothing to check it against. What protects the connection is adbd
     * checking the app's key; someone else's server can't use that, since a
     * TLS 1.3 client signature only covers its own handshake.
     */
    @SuppressWarnings({"TrustAllX509TrustManager", "CustomX509TrustManager"})
    private static final class AnyServerTrustManager extends X509ExtendedTrustManager {
        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("this is a client");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            throw new CertificateException("this is a client");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            throw new CertificateException("this is a client");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
