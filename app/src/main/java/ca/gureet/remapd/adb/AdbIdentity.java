package ca.gureet.remapd.adb;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * The app's adb key: a 2048-bit RSA key, like the one adb keeps in
 * ~/.android/adbkey on a computer. Holds everything adbd needs to recognise it.
 */
public final class AdbIdentity {
    /** Size of the key struct from AOSP's libcrypto_utils/android_pubkey.c. */
    static final int ENCODED_PUBLIC_KEY_SIZE = 3 * 4 + 2 * 256;

    /** DER prefix for a SHA-1 DigestInfo; adb signs its token as if it were a SHA-1 hash. */
    private static final byte[] SHA1_DIGEST_INFO = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
    private static final int TOKEN_SIZE = 20;

    final PrivateKey privateKey;
    final RSAPublicKey publicKey;
    final X509Certificate certificate;
    final String name;

    /** @param name shown on the TV next to the key, like user@host for a computer; no spaces. */
    public AdbIdentity(PrivateKey privateKey, RSAPublicKey publicKey, String name)
            throws GeneralSecurityException {
        if (publicKey.getModulus().bitLength() != 2048) {
            throw new InvalidKeyException("adb needs a 2048-bit RSA key");
        }
        if (name.isEmpty() || !name.matches("\\S+")) {
            throw new IllegalArgumentException("the key name can't be empty or contain spaces");
        }
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.name = name;
        this.certificate = SelfSignedCertificate.create(privateKey, publicKey, "remapd");
    }

    /** A new 2048-bit RSA key pair with exponent 65537, as adb uses. */
    public static KeyPair generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4));
        return generator.generateKeyPair();
    }

    /** Loads a private key saved with {@link #encodedPrivateKey()}. */
    public static AdbIdentity fromEncodedPrivateKey(byte[] pkcs8, String name) throws GeneralSecurityException {
        return fromPrivateKey(KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8)), name);
    }

    /**
     * Rebuilds the public key from the private one. Conscrypt (Android's
     * provider) loads saved keys without their CRT parts, so the public
     * exponent isn't always there; keys made here always use 65537, and a
     * test signature checks that the two halves belong together.
     */
    static AdbIdentity fromPrivateKey(PrivateKey key, String name) throws GeneralSecurityException {
        if (!(key instanceof RSAPrivateKey)) {
            throw new InvalidKeyException("not an RSA private key");
        }
        BigInteger exponent = key instanceof RSAPrivateCrtKey
                ? ((RSAPrivateCrtKey) key).getPublicExponent() : RSAKeyGenParameterSpec.F4;
        RSAPublicKey pub = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(((RSAPrivateKey) key).getModulus(), exponent));
        byte[] test = "remapd key check".getBytes(StandardCharsets.US_ASCII);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update(test);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(pub);
        verifier.update(test);
        if (!verifier.verify(signature)) {
            throw new InvalidKeyException("the saved key doesn't match its public key");
        }
        return new AdbIdentity(key, pub, name);
    }

    /** The private key in PKCS#8 form, for saving. */
    public byte[] encodedPrivateKey() {
        return privateKey.getEncoded();
    }

    /**
     * The public key in adb's own format (android_pubkey_encode): modulus size
     * in words, n0inv = -1/n mod 2^32, the modulus, R^2 mod n, and the exponent,
     * all little-endian.
     */
    byte[] adbPublicKey() {
        BigInteger n = publicKey.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(2 * 2048).mod(n);
        ByteBuffer b = ByteBuffer.allocate(ENCODED_PUBLIC_KEY_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(2048 / 32);
        b.putInt(n0inv.intValue());
        b.put(littleEndian(n));
        b.put(littleEndian(rr));
        b.putInt(publicKey.getPublicExponent().intValue());
        return b.array();
    }

    /** The public key as a line of ~/.android/adbkey.pub: base64, a space, the name. */
    public String publicKeyString() {
        return Base64.getEncoder().encodeToString(adbPublicKey()) + " " + name;
    }

    /**
     * The fingerprint the TV shows in its "Allow USB debugging?" prompt: MD5 of
     * the key in adb's format, as AdbDebuggingManager formats it.
     */
    public String fingerprint() {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(adbPublicKey());
            StringBuilder sb = new StringBuilder();
            for (byte x : digest) {
                if (sb.length() > 0) {
                    sb.append(':');
                }
                sb.append(String.format("%02X", x & 0xff));
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            return "";
        }
    }

    /**
     * Whether adbd's list of keys it knows (sent during the TLS handshake as
     * names "O=AdbKey-0, CN=<SHA-256 of the key, in hex>") includes this key.
     * Null if there's no such list.
     */
    Boolean isListedIn(Principal[] issuers) {
        if (issuers == null) {
            return null;
        }
        String wanted;
        try {
            StringBuilder sb = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded())) {
                sb.append(String.format("%02X", x & 0xff));
            }
            wanted = sb.toString();
        } catch (GeneralSecurityException e) {
            return null;
        }
        boolean anyAdbKeys = false;
        for (Principal issuer : issuers) {
            if (issuer == null) {
                continue;
            }
            String cn = null;
            String o = null;
            for (String part : issuer.getName().split(",")) {
                String p = part.trim();
                if (p.startsWith("CN=")) {
                    cn = p.substring(3);
                } else if (p.startsWith("O=")) {
                    o = p.substring(2);
                }
            }
            if ("AdbKey-0".equals(o) && cn != null) {
                anyAdbKeys = true;
                if (cn.equalsIgnoreCase(wanted)) {
                    return true;
                }
            }
        }
        return anyAdbKeys ? Boolean.FALSE : null;
    }

    /** Signs adbd's 20-byte token the way adb does: RSA_sign(NID_sha1, token). */
    byte[] signToken(byte[] token) throws GeneralSecurityException {
        if (token.length != TOKEN_SIZE) {
            throw new GeneralSecurityException("unexpected adb token size: " + token.length);
        }
        Signature signer = Signature.getInstance("NONEwithRSA");
        signer.initSign(privateKey);
        signer.update(SHA1_DIGEST_INFO);
        signer.update(token);
        return signer.sign();
    }

    private static byte[] littleEndian(BigInteger value) {
        byte[] big = value.toByteArray();   // big-endian, maybe with a leading 0
        byte[] out = new byte[256];
        for (int i = 0; i < out.length && i < big.length; i++) {
            out[i] = big[big.length - 1 - i];
        }
        return out;
    }
}
