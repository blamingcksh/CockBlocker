package com.cockblocker.app.data;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Installation-scoped, non-exportable HMAC key held by Android Keystore. */
public final class JournalKey {
    public static final String ALIAS = "cockblocker_journal_hmac_v1";
    private static final String STORE = "AndroidKeyStore";
    private static final String HMAC = "HmacSHA256";

    private final SecretKey key;

    private JournalKey(SecretKey key) {
        this.key = key;
    }

    public static JournalKey acquire(Context context) {
        if (context == null) {
            return new JournalKey(null);
        }
        try {
            KeyStore store = KeyStore.getInstance(STORE);
            store.load(null);
            Key existing = store.getKey(ALIAS, null);
            if (existing instanceof SecretKey) {
                return new JournalKey((SecretKey) existing);
            }
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_HMAC_SHA256, STORE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                KeyGenParameterSpec strongBoxSpec = new KeyGenParameterSpec.Builder(
                        ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(false)
                        .setIsStrongBoxBacked(true)
                        .build();
                try {
                    generator.init(strongBoxSpec);
                    return keepIfUsable(generator.generateKey());
                } catch (Exception strongBoxFailure) {
                    generator = KeyGenerator.getInstance(
                            KeyProperties.KEY_ALGORITHM_HMAC_SHA256, STORE);
                    KeyGenParameterSpec fallbackSpec = new KeyGenParameterSpec.Builder(
                            ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                            .setDigests(KeyProperties.DIGEST_SHA256)
                            .setUserAuthenticationRequired(false)
                            .setIsStrongBoxBacked(false)
                            .build();
                    generator.init(fallbackSpec);
                }
            } else {
                KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                        ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(false)
                        .build();
                generator.init(spec);
            }
            return keepIfUsable(generator.generateKey());
        } catch (Exception failure) {
            return new JournalKey(null);
        }
    }

    /**
     * Test-only factory over an HMAC key the test itself generated. Production MUST
     * go through {@link #acquire(Context)}: keys never leave Android Keystore, and a
     * software-backed HMAC key would break the Keystore-resident invariant.
     */
    static JournalKey forTest(SecretKey key) {
        if (key == null) {
            return new JournalKey(null);
        }
        if (!"HmacSHA256".equals(key.getAlgorithm())) {
            return new JournalKey(null);
        }
        return new JournalKey(key);
    }

    private static JournalKey keepIfUsable(SecretKey key) {
        JournalKey journalKey = new JournalKey(key);
        if (!journalKey.isUsable()) {
            throw new IllegalStateException("generated journal key is not usable");
        }
        return journalKey;
    }

    public boolean isUsable() {
        if (key == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            return true;
        } catch (Exception failure) {
            return false;
        }
    }

    public byte[] hmac(String domain, byte[] bytes) {
        if (!isUsable()) {
            throw new IllegalStateException("journal HMAC key is unavailable");
        }
        if (domain == null || bytes == null) {
            throw new IllegalArgumentException("domain and bytes are required");
        }
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            byte[] domainBytes = domain.getBytes(StandardCharsets.UTF_8);
            byte[] input = new byte[domainBytes.length + 1 + bytes.length];
            System.arraycopy(domainBytes, 0, input, 0, domainBytes.length);
            input[domainBytes.length] = 0;
            System.arraycopy(bytes, 0, input, domainBytes.length + 1, bytes.length);
            return mac.doFinal(input);
        } catch (Exception failure) {
            throw new IllegalStateException("journal HMAC failed", failure);
        }
    }

    public String getAlias() {
        return ALIAS;
    }

    static boolean equal(byte[] left, byte[] right) {
        return left != null && right != null && MessageDigestCompat.constantTimeEquals(left, right);
    }

    private static final class MessageDigestCompat {
        static boolean constantTimeEquals(byte[] a, byte[] b) {
            if (a.length != b.length) {
                return false;
            }
            int difference = 0;
            for (int i = 0; i < a.length; i++) {
                difference |= a[i] ^ b[i];
            }
            return difference == 0;
        }
    }
}
