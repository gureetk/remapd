package ca.gureet.remapd;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import ca.gureet.remapd.adb.AdbIdentity;

/**
 * The app's adb key. It's kept in the app's private storage, encrypted with
 * an AES key that stays inside Android's keystore (in the TV's secure
 * hardware where it has some), so the file alone is useless elsewhere.
 */
final class AppKey {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String WRAPPING_KEY = "remapd-adb-key-wrap";
    private static final String FILE = "adbkey.enc";
    private static final byte[] AAD = "remapd adb key v1".getBytes(StandardCharsets.US_ASCII);
    private static final int IV_SIZE = 12;

    private AppKey() {
    }

    static boolean exists(Context context) {
        return file(context).exists();
    }

    /** The saved key, or null if there isn't one yet. */
    static AdbIdentity load(Context context) throws GeneralSecurityException, IOException {
        File f = file(context);
        if (!f.exists()) {
            return null;
        }
        byte[] stored = Files.readAllBytes(f.toPath());
        if (stored.length <= IV_SIZE) {
            throw new IOException("the saved adb key is damaged");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(false),
                new GCMParameterSpec(128, stored, 0, IV_SIZE));
        cipher.updateAAD(AAD);
        byte[] pkcs8 = cipher.doFinal(stored, IV_SIZE, stored.length - IV_SIZE);
        try {
            return AdbIdentity.fromEncodedPrivateKey(pkcs8, name());
        } finally {
            Arrays.fill(pkcs8, (byte) 0);
        }
    }

    /** Makes a new key and saves it, replacing any old one (which the TV then no longer knows). */
    static AdbIdentity create(Context context) throws GeneralSecurityException, IOException {
        KeyPair pair = AdbIdentity.generateKeyPair();
        AdbIdentity identity = new AdbIdentity(pair.getPrivate(), (RSAPublicKey) pair.getPublic(), name());
        byte[] pkcs8 = identity.encodedPrivateKey();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(true));   // the keystore picks the IV
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(pkcs8);
            byte[] iv = cipher.getIV();
            if (iv.length != IV_SIZE) {
                throw new GeneralSecurityException("unexpected IV size " + iv.length);
            }
            write(file(context), ByteBuffer.allocate(IV_SIZE + encrypted.length).put(iv).put(encrypted).array());
        } finally {
            Arrays.fill(pkcs8, (byte) 0);
        }
        return identity;
    }

    /** How the key is named on the TV (in Wireless debugging's list of paired devices). */
    private static String name() {
        return "remapd@" + Build.DEVICE.replaceAll("\\s", "_");
    }

    private static File file(Context context) {
        return new File(context.getNoBackupFilesDir(), FILE);
    }

    private static SecretKey wrappingKey(boolean create) throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(WRAPPING_KEY, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        if (!create) {
            throw new GeneralSecurityException("the keystore key for the saved adb key is gone");
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(WRAPPING_KEY,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static void write(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            throw new IOException("couldn't save the adb key");
        }
    }
}
