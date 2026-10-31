package ca.gureet.remapd.adb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * A minimal self-signed X.509 certificate for the app's adb key, which TLS
 * needs to present the key. adbd only compares the key inside it with the
 * keys it trusts, so the rest (name, dates) doesn't matter; the dates are
 * fixed so the certificate is the same every time.
 */
final class SelfSignedCertificate {
    // DER-encoded object identifiers.
    private static final byte[] SHA256_WITH_RSA = {0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
            (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b};   // 1.2.840.113549.1.1.11
    private static final byte[] COMMON_NAME = {0x06, 0x03, 0x55, 0x04, 0x03};   // 2.5.4.3
    private static final byte[] NULL = {0x05, 0x00};

    private SelfSignedCertificate() {
    }

    static X509Certificate create(PrivateKey privateKey, PublicKey publicKey, String commonName)
            throws GeneralSecurityException {
        byte[] algorithm = der(0x30, SHA256_WITH_RSA, NULL);
        byte[] name = der(0x30, der(0x31, der(0x30, COMMON_NAME,
                der(0x0c, commonName.getBytes(StandardCharsets.UTF_8)))));
        byte[] validity = der(0x30, utcTime("200101000000Z"), utcTime("491231235959Z"));
        byte[] tbs = der(0x30,
                der(0xa0, der(0x02, new byte[]{2})),   // version 3
                der(0x02, new byte[]{1}),              // serial number
                algorithm, name, validity, name,
                publicKey.getEncoded());               // SubjectPublicKeyInfo

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(tbs);
        byte[] signature = signer.sign();
        byte[] bits = new byte[signature.length + 1];   // a leading 0: no unused bits
        System.arraycopy(signature, 0, bits, 1, signature.length);

        byte[] certificate = der(0x30, tbs, algorithm, der(0x03, bits));
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certificate));
    }

    private static byte[] utcTime(String time) {
        return der(0x17, time.getBytes(StandardCharsets.US_ASCII));
    }

    /** A DER tag-length-value with the given parts concatenated as the value. */
    static byte[] der(int tag, byte[]... parts) {
        ByteArrayOutputStream value = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            value.write(part, 0, part.length);
        }
        int length = value.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (length < 0x80) {
            out.write(length);
        } else {
            int bytes = length > 0xffff ? 3 : length > 0xff ? 2 : 1;
            out.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) {
                out.write(length >>> (8 * i));
            }
        }
        byte[] v = value.toByteArray();
        out.write(v, 0, v.length);
        return out.toByteArray();
    }
}
