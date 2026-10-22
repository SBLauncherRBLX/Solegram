/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;

/**
 * Telegram requires every third-party client to use its own api_id/api_hash
 * (https://core.telegram.org/api/obtaining_api_id). Solegram's come with the build
 * (SOLEGRAM_API_ID / SOLEGRAM_API_HASH in local.properties). Keys typed in by older versions
 * stay in use, so an existing login keeps working.
 */
public final class ApiCredentials {

    private static final String PREFS = "solegram_api";

    private ApiCredentials() {
    }

    private static SharedPreferences prefs() {
        Context ctx = ApplicationLoader.applicationContext;
        return ctx != null ? ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) : null;
    }

    /** Keys typed in by an older version, used as a pair only when both are valid. */
    private static boolean hasStoredKeys(SharedPreferences p) {
        return p != null && p.getInt("api_id", 0) > 0 && isValidHash(p.getString("api_hash", ""));
    }

    public static int getApiId() {
        final SharedPreferences p = prefs();
        return hasStoredKeys(p) ? p.getInt("api_id", 0) : BuildConfig.SOLEGRAM_API_ID;
    }

    public static String getApiHash() {
        final SharedPreferences p = prefs();
        return hasStoredKeys(p) ? p.getString("api_hash", "") : BuildConfig.SOLEGRAM_API_HASH;
    }

    public static boolean isConfigured() {
        return getApiId() > 0 && isValidHash(getApiHash());
    }

    public static boolean isValidHash(String hash) {
        return hash != null && hash.matches("[0-9a-fA-F]{32}");
    }
}
