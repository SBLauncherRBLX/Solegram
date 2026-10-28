/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.Base64;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Secrets the user types in (the key of their own AI service): kept encrypted with an AES key that lives in the
 * Android Keystore and never leaves it, so the preferences file alone (a backup, a copied data folder) does not
 * give them away. Values typed in by older versions are moved here on first read. Decrypted values are kept in
 * memory, so only the first read of each costs a Keystore call.
 */
public final class SolegramSecrets {

    private static final String PREFS = "solegram_secrets";
    private static final String KEY_ALIAS = "solegram_secrets_v1";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;

    private static final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    private SolegramSecrets() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static synchronized SecretKey key() throws Exception {
        final KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        final KeyStore.Entry entry = store.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        final KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        return generator.generateKey();
    }

    /** The secret, or "" when there is none (or it cannot be read any more: the Keystore key is gone). */
    public static String get(String name) {
        final String cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        final String stored = prefs().getString(name, null);
        String value = "";
        if (stored != null) {
            try {
                final byte[] data = Base64.decode(stored, Base64.NO_WRAP);
                final Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, data, 0, IV_LENGTH));
                value = new String(cipher.doFinal(data, IV_LENGTH, data.length - IV_LENGTH), StandardCharsets.UTF_8);
            } catch (Throwable e) {
                // a lost Keystore key (the app data moved to another phone) cannot be helped: the user types it again
                FileLog.e(e);
                prefs().edit().remove(name).apply();
            }
        }
        cache.put(name, value);
        return value;
    }

    /**
     * Stores the secret ("" or null removes it).
     *
     * @return false when it could not be encrypted (a broken Keystore): then nothing was stored
     */
    public static boolean put(String name, String value) {
        if (TextUtils.isEmpty(value)) {
            cache.put(name, "");
            prefs().edit().remove(name).commit();
            return true;
        }
        try {
            final Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key());
            final byte[] iv = cipher.getIV();
            final byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            if (iv == null || iv.length != IV_LENGTH) {
                throw new IllegalStateException("iv");
            }
            final byte[] data = new byte[IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, data, 0, IV_LENGTH);
            System.arraycopy(encrypted, 0, data, IV_LENGTH, encrypted.length);
            prefs().edit().putString(name, Base64.encodeToString(data, Base64.NO_WRAP)).commit();
            cache.put(name, value);
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /**
     * The secret {@code key} of {@code legacy}, where older versions kept it as plain text: moved here (and
     * removed there) once it is safely encrypted. Without a plain copy it is read from here.
     */
    public static String migrate(SharedPreferences legacy, String key) {
        final String plain = legacy.getString(key, null);
        if (plain == null) {
            return get(key);
        }
        if (plain.isEmpty() || put(key, plain)) {
            legacy.edit().remove(key).commit();
            return plain;
        }
        // the Keystore does not work on this phone: the key stays where it was rather than being lost
        return plain;
    }
}
