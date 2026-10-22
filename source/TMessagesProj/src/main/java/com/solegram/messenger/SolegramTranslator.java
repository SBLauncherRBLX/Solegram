/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.net.Uri;
import android.text.TextUtils;
import android.util.LruCache;

import org.json.JSONArray;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.Bulletin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Chat translation without Telegram Premium: messages go to the public Google Translate endpoint,
 * two at a time, with a small cache and one retry when Google asks to slow down.
 * Only plain text is translated; formatting of the original message is not carried over.
 */
public final class SolegramTranslator {

    private static final int MAX_CHUNK = 1500;
    private static final ExecutorService pool = Executors.newFixedThreadPool(2);
    private static final LruCache<String, String> cache = new LruCache<>(500);
    private static long lastErrorShown;
    /** After Google says "too many requests" nothing is sent for a minute. */
    private static volatile long blockedUntil;
    private static final long COOLDOWN_MS = 60_000;

    private SolegramTranslator() {
    }

    /** Google refused recently ("too many requests"); nothing is sent until the pause is over. */
    public static boolean isRateLimited() {
        return System.currentTimeMillis() < blockedUntil;
    }

    /** Whether chat translation of this account goes through Google instead of Telegram servers. */
    public static boolean shouldUse(int account) {
        return SolegramConfig.freeTranslate && !UserConfig.getInstance(account).isPremium();
    }

    /** Result arrives on the UI thread; null on failure. */
    public static void translate(String text, String toLanguage, Utilities.Callback<String> done) {
        if (TextUtils.isEmpty(text) || TextUtils.isEmpty(toLanguage)) {
            AndroidUtilities.runOnUIThread(() -> done.run(null));
            return;
        }
        final String to = googleLanguage(toLanguage);
        final String key = to + '\u0000' + text;
        final String cached = cache.get(key);
        if (cached != null) {
            AndroidUtilities.runOnUIThread(() -> done.run(cached));
            return;
        }
        if (System.currentTimeMillis() < blockedUntil) {
            AndroidUtilities.runOnUIThread(() -> done.run(null));
            return;
        }
        pool.execute(() -> {
            // the same text may have been translated (or Google may have refused) while this waited in the queue
            final String ready = cache.get(key);
            if (ready != null || System.currentTimeMillis() < blockedUntil) {
                AndroidUtilities.runOnUIThread(() -> done.run(ready));
                return;
            }
            String result = null;
            boolean limited = false;
            try {
                final StringBuilder out = new StringBuilder();
                for (String part : split(text)) {
                    String translated = request(part, to);
                    if (translated == null) {
                        // Google answered 429: wait a moment and try once more
                        Thread.sleep(1500);
                        translated = request(part, to);
                    }
                    if (translated == null) {
                        limited = true;
                        blockedUntil = System.currentTimeMillis() + COOLDOWN_MS;
                        out.setLength(0);
                        break;
                    }
                    out.append(translated);
                }
                if (out.length() > 0) {
                    result = out.toString();
                    cache.put(key, result);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            final String finalResult = result;
            final boolean finalLimited = limited;
            AndroidUtilities.runOnUIThread(() -> {
                if (finalResult == null) {
                    showError(finalLimited);
                }
                done.run(finalResult);
            });
        });
    }

    /** Returns null when Google rate-limits the request; throws on other failures. */
    private static String request(String text, String to) throws Exception {
        final String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=" + Uri.encode(to)
            + "&dt=t&ie=UTF-8&oe=UTF-8&q=" + Uri.encode(text);
        final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36");
            final int code = connection.getResponseCode();
            if (code == 429) {
                return null;
            }
            if (code != 200) {
                throw new IllegalStateException("translate http " + code);
            }
            final StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                final char[] buf = new char[4096];
                int n;
                while ((n = reader.read(buf)) != -1) {
                    sb.append(buf, 0, n);
                }
            }
            final JSONArray sentences = new JSONArray(sb.toString()).getJSONArray(0);
            final StringBuilder result = new StringBuilder();
            for (int i = 0; i < sentences.length(); i++) {
                final JSONArray s = sentences.optJSONArray(i);
                if (s != null && !s.isNull(0)) {
                    result.append(s.getString(0));
                }
            }
            if (text.startsWith("\n") && result.length() > 0 && result.charAt(0) != '\n') {
                result.insert(0, '\n');
            }
            return result.toString();
        } finally {
            connection.disconnect();
        }
    }

    /** Splits long texts at line breaks (or spaces) so each request stays short. */
    private static ArrayList<String> split(String text) {
        final ArrayList<String> parts = new ArrayList<>();
        int start = 0;
        while (text.length() - start > MAX_CHUNK) {
            int end = text.lastIndexOf('\n', start + MAX_CHUNK);
            if (end <= start) {
                end = text.lastIndexOf(' ', start + MAX_CHUNK);
            }
            if (end <= start) {
                end = start + MAX_CHUNK;
            }
            parts.add(text.substring(start, end));
            start = end;
        }
        parts.add(text.substring(start));
        return parts;
    }

    private static String googleLanguage(String lang) {
        switch (lang) {
            case "zh":
            case "zh-hans":
                return "zh-CN";
            case "zh-hant":
                return "zh-TW";
            case "nb":
                return "no";
            default:
                return lang;
        }
    }

    private static void showError(boolean rateLimit) {
        final long now = System.currentTimeMillis();
        if (now - lastErrorShown < 60_000) {
            return;
        }
        lastErrorShown = now;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.showBulletin, Bulletin.TYPE_ERROR,
            LocaleController.getString(rateLimit ? R.string.TranslationFailedAlert1 : R.string.TranslationFailedAlert2));
    }
}
