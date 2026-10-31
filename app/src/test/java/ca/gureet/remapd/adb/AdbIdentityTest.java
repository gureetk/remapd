package ca.gureet.remapd.adb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;

import org.junit.Test;

public class AdbIdentityTest {
    /** A throwaway test key (PKCS#8), and what adb-shell's encoder, a separate implementation, makes of it. */
    private static final String PRIVATE_KEY =
            "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCWOkYZ1cq1t+NzeW3iz4lkURmY0UwEbD7kemiZImZiyi2R"
            + "93E4RnC/+w9e4vO8Halab6mkz5HlSVJHWmPYu82BTPxsO6TUkD4DO2mfo1KlKOBmg05T85guBw2bJX5AUU8ytMpIQBvflB6n"
            + "u67+6JMhaCNhJoRhpBUv8WCT7j3bpUHraL7o+kMPmQqaisX1KFfATNjnMJJBfWfwLFv0Dta8AefdfkYg9LecNWsy8sCMRyP3"
            + "ziDhg2P2b8ADAXdLGcbd4bUFLhMkZ+bqWQaDxvDbqWQ5C0QZ/XUevWKJ+D314aHVGrnKMwb0GmN5QBrrxnC78cNEzLhytXvQ"
            + "E8Toc6arAgMBAAECggEAKV6X0mKBBaTDopeUbqrHQ5Rfz6kHXYpvkVyyNEV4W4kgnns9NwmS4BNjqrYl965VZj2swmGU5Eba"
            + "PSMDrvocwBgyxR1xqUWlhh9U2WzRBAFrNl+/cLf6tuYjVY+wIkch361QrS/WXS39eSRirvjeF4uFfqnsN7VHTmISvfoUF8mR"
            + "BqVGw7zI10hCY5HPFFKXo+HPbFiJS2ruV8Ru9ljmJnP6bqHAC7MQ4PAMsj1xaFS6xC+nijexzmTT9WJdfb+BmqWIdFn2Qh+Q"
            + "OTdWueua7+wSWbI3AVFZt2FDBBpXBk8ZkNUJ9VNesbHZnZ3PSawaAqDqYGqsRSPOVTv1OA8TTQKBgQDGgyVOkpYgDDdwjDZ+"
            + "BF9gNV72sWg1a7DuP8t27IOmi7+tNPOYQgQFnCufYPZO1qu5Z6UBC9WD36rCav7MrrKpLE/HJUEeqlb8E7YsjxWRpLSnW3M4"
            + "5O3/GeuXEWw8tmkym0OnnAIQ3IrbV9785lOgsWgZ7+19Hh8I8K4SdhDyPQKBgQDBu4J6MEh0O/+YvT0zQ62yfdK8mM6ooAkB"
            + "V6fi4jJ2YH/V1qHyYlUUeI2igpISAgEiTpey3KIt/ZUeeJuYLRvlac6nITSHpl/zN7kZ3msEc/GBwPfUsAQl7aCwRDFgXngz"
            + "w6alqV/ZTZcEVTf/NlEQgCxalHgDc7yFZby+ILSTBwKBgFeVdzlSfYAIQ9GZW1WAMmBtS8fGADdJm1eSt/9xXKh3kgsmiK9N"
            + "uR6Mh8aa7jFcj4mOzANJKFw1q855jI/cKCFx1oUDz9Zqy1QOklrYimim5bnNJGoev07FLeeKHVowFPvp+xCRSWwNEgqym0Sz"
            + "joQgodCHxh1k08aLAhJNGy71AoGAJXfhdUZPTQOpnF3FL1wAhQvmrQkUtvI0q6jLVIsQ3WoubyoSqjEwaUQjiHGeVJNEquDu"
            + "X+5iA2kO5RTIavcWdRkwmYxHf5DC7+QwDXvB0NunssOiauUb+EXsFQsrTYsXzlMhsNqAKiPieDkPGnHj3cAcU+xxBaHlrRsP"
            + "FOp4YhMCgYEAvEjL84aPyUNB7cx3H4FUnwKaCJN+BmAzEQL/Dq2LyCjXFgCtgMWffJYRP3sKQiokIV3CabkRJx5OS8Cacvab"
            + "rBUHDbPVx7TmPkHjAlR9+Djuo1qclp7HZ0HAEvkvWLeLAG2/dsUxUSEIJvzODFFcnph0ahMSIhjGDR7rJUezCqY=";
    private static final String EXPECTED_PUBLIC_KEY =
            "QAAAAP3bYIKrpnPoxBPQe7VyuMxEw/G7cMbrGkB5Yxr0BjPKuRrVoeH1PfiJYr0edf0ZRAs5ZKnb8MaDBlnq5mckEy4FteHd"
            + "xhlLdwEDwG/2Y4PhIM73I0eMwPIyazWct/QgRn7d5wG81g70WyzwZ31BkjDn2EzAVyj1xYqaCpkPQ/rovmjrQaXbPe6TYPEv"
            + "FaRhhCZhI2ghk+j+rrunHpTfG0BIyrQyT1FAfiWbDQcumPNTToNm4CilUqOfaTsDPpDUpDts/EyBzbvYY1pHUknlkc+kqW9a"
            + "qR288+JeD/u/cEY4cfeRLcpiZiKZaHrkPmwETNGYGVFkic/ibXlz47e1ytUZRjqWnBr002i9SnKspMrtOeffXB0eIetKgM3Z"
            + "7yoY1FUrD2F2ko8/NGXExXOzkUrm38BFKePz8cOSVTydFkx7Riluws9BBpXyiBwh1QJvYt/9u773IJAe8XDFwvD3RUDcsEa4"
            + "an4etHUV0sfoNl9q8KMt1rbamzoTruY0i29v5Twku6zoccF2jT5aFptRKYkxHhcMG7UL3FEvkc/kYigtHMBUtndMLAOwquSn"
            + "eu2BtHxohEZqauJ6GgU4wGYlPxfitcd5zjosPVgByEGbJOevMLBWKFgGwwbfMXSDSvVvRRYa5KngAsgczbxEE8iODvSv+NFv"
            + "/0IN4SRe+AcBWBqhnkkSiwEAAQA=";
    private static final String EXPECTED_FINGERPRINT = "BC:8B:90:96:41:5C:1A:3A:25:11:57:B1:6B:6A:53:2E";

    private static AdbIdentity identity() throws Exception {
        return AdbIdentity.fromEncodedPrivateKey(Base64.getDecoder().decode(PRIVATE_KEY), "remapd@test");
    }

    @Test
    public void publicKeyMatchesAnotherImplementation() throws Exception {
        assertEquals(EXPECTED_PUBLIC_KEY + " remapd@test", identity().publicKeyString());
    }

    @Test
    public void fingerprintIsWhatTheTvShows() throws Exception {
        assertEquals(EXPECTED_FINGERPRINT, identity().fingerprint());
    }

    /** Like Conscrypt on Android, which loads saved keys without their CRT parts. */
    @Test
    public void loadsKeysWithoutCrtParts() throws Exception {
        AdbIdentity id = identity();
        java.security.interfaces.RSAPrivateCrtKey crt = (java.security.interfaces.RSAPrivateCrtKey) id.privateKey;
        java.security.PrivateKey bare = java.security.KeyFactory.getInstance("RSA").generatePrivate(
                new java.security.spec.RSAPrivateKeySpec(crt.getModulus(), crt.getPrivateExponent()));
        assertTrue(!(bare instanceof java.security.interfaces.RSAPrivateCrtKey));
        assertEquals(id.publicKeyString(), AdbIdentity.fromPrivateKey(bare, "remapd@test").publicKeyString());
    }

    @Test
    public void privateKeyRoundTrips() throws Exception {
        AdbIdentity id = identity();
        AdbIdentity again = AdbIdentity.fromEncodedPrivateKey(id.encodedPrivateKey(), "remapd@test");
        assertEquals(id.publicKeyString(), again.publicKeyString());
    }

    /** Undoes the signature with plain RSA maths and checks the PKCS#1 v1.5 block adbd expects. */
    @Test
    public void tokenSignatureIsPkcs1Sha1() throws Exception {
        AdbIdentity id = identity();
        byte[] token = new byte[20];
        for (int i = 0; i < token.length; i++) {
            token[i] = (byte) (0xa0 + i);
        }
        byte[] sig = id.signToken(token);
        assertEquals(256, sig.length);
        RSAPublicKey pub = id.publicKey;
        byte[] block = new BigInteger(1, sig).modPow(pub.getPublicExponent(), pub.getModulus()).toByteArray();
        // toByteArray drops the leading 00, so the block starts at 01.
        assertEquals(255, block.length);
        assertEquals(1, block[0]);
        byte[] digestInfo = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
        int tail = digestInfo.length + token.length;
        for (int i = 1; i < block.length - tail - 1; i++) {
            assertEquals((byte) 0xff, block[i]);
        }
        assertEquals(0, block[block.length - tail - 1]);
        assertArrayEquals(digestInfo, Arrays.copyOfRange(block, block.length - tail, block.length - token.length));
        assertArrayEquals(token, Arrays.copyOfRange(block, block.length - token.length, block.length));
    }

    @Test
    public void onlySignsAdbSizedTokens() throws Exception {
        try {
            identity().signToken(new byte[32]);
            fail("signed a 32-byte token");
        } catch (java.security.GeneralSecurityException expected) {
        }
    }

    @Test
    public void certificateHoldsTheKey() throws Exception {
        AdbIdentity id = identity();
        id.certificate.checkValidity(new java.util.Date());
        id.certificate.verify(id.publicKey);   // self-signed
        assertEquals(3, id.certificate.getVersion());
        assertEquals("CN=remapd", id.certificate.getSubjectX500Principal().getName());
        assertEquals(id.publicKey, id.certificate.getPublicKey());
    }

    @Test
    public void newKeysWork() throws Exception {
        KeyPair pair = AdbIdentity.generateKeyPair();
        AdbIdentity id = new AdbIdentity(pair.getPrivate(), (RSAPublicKey) pair.getPublic(), "a@b");
        assertEquals(65537, id.publicKey.getPublicExponent().intValue());
        assertEquals(AdbIdentity.ENCODED_PUBLIC_KEY_SIZE, id.adbPublicKey().length);
        assertTrue(id.publicKeyString().endsWith(" a@b"));
    }

    @Test
    public void rejectsOtherKeySizesAndBadNames() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(new RSAKeyGenParameterSpec(1024, RSAKeyGenParameterSpec.F4));
        KeyPair small = g.generateKeyPair();
        try {
            new AdbIdentity(small.getPrivate(), (RSAPublicKey) small.getPublic(), "a@b");
            fail("accepted a 1024-bit key");
        } catch (java.security.InvalidKeyException expected) {
        }
        KeyPair pair = AdbIdentity.generateKeyPair();
        try {
            new AdbIdentity(pair.getPrivate(), (RSAPublicKey) pair.getPublic(), "has space");
            fail("accepted a name with a space");
        } catch (IllegalArgumentException expected) {
        }
    }
}
