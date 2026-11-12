/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.wallet;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Base64;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * SoleWallet: the user's own Telegram Stars and gifts in a wallet of Solegram's design, behind a code of its own.
 * <p>
 * Nothing is kept outside the account and nothing goes to any server but Telegram: the balance, the history and the
 * gifts are Telegram's, and sending uses Telegram's own payments (a gift from the Stars balance, which the
 * receiver may turn into Stars, with the sender's name hidden if wanted). What is Solegram's is the wallet's code,
 * which only opens the wallet on this device, and the card: its number is the account's mark, not money.
 * <p>
 * The code is stored as a PBKDF2-HMAC-SHA256 hash with a random salt (never the code itself); after wrong tries
 * the wallet waits longer and longer before the next one.
 */
public final class SoleWallet {

    private static final int ITERATIONS = 60_000;
    private static final int MAX_FREE_TRIES = 5;

    private SoleWallet() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("solegram_wallet", Context.MODE_PRIVATE);
    }

    private static String k(int account, String name) {
        return name + "_" + UserConfig.getInstance(account).getClientUserId();
    }

    // ------------------------------------------------------------------ the tour

    public static boolean introSeen(int account) {
        return prefs().getBoolean(k(account, "intro"), false);
    }

    public static void setIntroSeen(int account) {
        prefs().edit().putBoolean(k(account, "intro"), true).apply();
    }

    // ------------------------------------------------------------------ the code

    public static boolean hasCode(int account) {
        return prefs().contains(k(account, "hash"));
    }

    public static void setCode(int account, String code) {
        final byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        final byte[] hash = derive(code, salt);
        prefs().edit()
            .putString(k(account, "salt"), Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(k(account, "hash"), Base64.encodeToString(hash, Base64.NO_WRAP))
            .putInt(k(account, "fails"), 0)
            .putLong(k(account, "until"), 0)
            .apply();
    }

    public static void removeCode(int account) {
        prefs().edit().remove(k(account, "salt")).remove(k(account, "hash")).remove(k(account, "fails")).remove(k(account, "until")).remove(k(account, "bio")).apply();
        unlockedAt = 0;
    }

    /** How long (ms) until the next try is allowed; 0 when it is now. */
    public static long waitLeft(int account) {
        final long until = prefs().getLong(k(account, "until"), 0);
        final long now = System.currentTimeMillis();
        return until > now ? until - now : 0;
    }

    /** Checks a code; a wrong one counts towards the wait. */
    public static boolean check(int account, String code) {
        if (waitLeft(account) > 0) {
            return false;
        }
        try {
            final byte[] salt = Base64.decode(prefs().getString(k(account, "salt"), ""), Base64.NO_WRAP);
            final byte[] want = Base64.decode(prefs().getString(k(account, "hash"), ""), Base64.NO_WRAP);
            final boolean ok = want.length > 0 && MessageDigest.isEqual(want, derive(code, salt));
            if (ok) {
                prefs().edit().putInt(k(account, "fails"), 0).putLong(k(account, "until"), 0).apply();
                unlockedAt = SystemClock.elapsedRealtime();
            } else {
                final int fails = prefs().getInt(k(account, "fails"), 0) + 1;
                long wait = 0;
                if (fails >= MAX_FREE_TRIES) {
                    // 30 s after the fifth wrong code, doubling with each next one, up to an hour
                    wait = Math.min(60L * 60_000L, 30_000L << Math.min(7, fails - MAX_FREE_TRIES));
                }
                prefs().edit().putInt(k(account, "fails"), fails).putLong(k(account, "until"), wait > 0 ? System.currentTimeMillis() + wait : 0).apply();
            }
            return ok;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    private static byte[] derive(String code, byte[] salt) {
        try {
            final PBEKeySpec spec = new PBEKeySpec(code.toCharArray(), salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The wallet stays open for a few minutes after the code, while the app is used. */
    private static long unlockedAt;
    private static final long UNLOCK_MS = 3 * 60_000L;

    public static boolean isUnlocked(int account) {
        return !hasCode(account) || unlockedAt != 0 && SystemClock.elapsedRealtime() - unlockedAt < UNLOCK_MS;
    }

    public static void lock() {
        unlockedAt = 0;
    }

    public static void touch() {
        if (unlockedAt != 0) {
            unlockedAt = SystemClock.elapsedRealtime();
        }
    }

    // ------------------------------------------------------------------ the card

    /**
     * The card's number: sixteen digits that stand for the account and nothing else - unique to it (two accounts
     * never share one), the same on every device of the user, and leading back to the account: a gift can be sent
     * by it. "7", the account's id through a keyed permutation (so the digits tell nothing about the id), and a
     * Luhn check digit as on bank cards (a mistyped digit is caught at once). A mark, not a payment card: nothing
     * can be paid with it.
     */
    public static String cardNumber(int account) {
        return cardNumberFor(UserConfig.getInstance(account).getClientUserId());
    }

    /** The 14 digits in the middle: ids below 10^14 (Telegram's are far below). */
    private static final long CARD_SPACE = 100_000_000_000_000L;
    private static final int CARD_HALF_BITS = 24;
    private static final long CARD_HALF_MASK = (1L << CARD_HALF_BITS) - 1;
    private static final int CARD_ROUNDS = 8;

    public static String cardNumberFor(long userId) {
        long x = Math.floorMod(userId, CARD_SPACE);
        // cycle walking: the permutation is of 48 bits, the digits hold a bit less; it is applied until the value fits
        do {
            x = feistel(x, false);
        } while (x >= CARD_SPACE);
        final String body = "7" + String.format(java.util.Locale.US, "%014d", x);
        return body + luhnDigit(body);
    }

    /** The account a card number stands for, or 0 when it is not a SoleWallet card number (wrong digits). */
    public static long userIdFromCard(String number) {
        if (number == null) {
            return 0;
        }
        final String digits = number.replaceAll("[^0-9]", "");
        if (digits.length() != 16 || digits.charAt(0) != '7' || luhnDigit(digits.substring(0, 15)) != digits.charAt(15) - '0') {
            return 0;
        }
        long x;
        try {
            x = Long.parseLong(digits.substring(1, 15));
        } catch (NumberFormatException e) {
            return 0;
        }
        do {
            x = feistel(x, true);
        } while (x >= CARD_SPACE);
        return x;
    }

    /** A balanced Feistel network over 48 bits (its inverse with {@code inverse}): a fixed, keyed permutation. */
    private static long feistel(long value, boolean inverse) {
        long left = (value >>> CARD_HALF_BITS) & CARD_HALF_MASK, right = value & CARD_HALF_MASK;
        if (!inverse) {
            for (int round = 0; round < CARD_ROUNDS; round++) {
                final long next = left ^ roundFunction(right, round);
                left = right;
                right = next;
            }
        } else {
            for (int round = CARD_ROUNDS - 1; round >= 0; round--) {
                final long previous = right ^ roundFunction(left, round);
                right = left;
                left = previous;
            }
        }
        return (left << CARD_HALF_BITS) | right;
    }

    private static long roundFunction(long half, int round) {
        long h = half * 0x9E3779B97F4A7C15L + (round + 1) * 0xC2B2AE3D27D4EB4FL + 0x5017A11E7L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return h & CARD_HALF_MASK;
    }

    /** The Luhn check digit of a string of digits. */
    private static int luhnDigit(String digits) {
        int sum = 0;
        boolean twice = true; // the rightmost digit of the body is doubled (the check digit goes after it)
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (twice) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            twice = !twice;
        }
        return (10 - sum % 10) % 10;
    }

    public static String cardNumberGrouped(int account, boolean masked) {
        final String n = cardNumber(account);
        return masked ? n.substring(0, 4) + "  ••••  ••••  " + n.substring(12) : n.substring(0, 4) + "  " + n.substring(4, 8) + "  " + n.substring(8, 12) + "  " + n.substring(12);
    }

    public static int cardStyle(int account) {
        return prefs().getInt(k(account, "style"), 0);
    }

    public static void setCardStyle(int account, int style) {
        prefs().edit().putInt(k(account, "style"), style).apply();
    }

    /** The collectible gift on the card (its slug), or null. */
    public static String cardGift(int account) {
        return prefs().getString(k(account, "gift"), null);
    }

    public static void setCardGift(int account, String slug) {
        if (slug == null) {
            prefs().edit().remove(k(account, "gift")).apply();
        } else {
            prefs().edit().putString(k(account, "gift"), slug).apply();
        }
    }

    /** Whether the card takes the gift's backdrop colours. */
    public static boolean cardGiftColor(int account) {
        return prefs().getBoolean(k(account, "giftColor"), true);
    }

    public static void setCardGiftColor(int account, boolean on) {
        prefs().edit().putBoolean(k(account, "giftColor"), on).apply();
    }

    // ------------------------------------------------------------------ unlocking with a fingerprint

    public static boolean biometric(int account) {
        return hasCode(account) && prefs().getBoolean(k(account, "bio"), false);
    }

    public static void setBiometric(int account, boolean on) {
        prefs().edit().putBoolean(k(account, "bio"), on).apply();
    }

    /** The fingerprint was accepted by the system's prompt: the wallet opens as with the code. */
    public static void unlockByBiometric() {
        unlockedAt = SystemClock.elapsedRealtime();
    }

    public static boolean biometricAvailable(Context context) {
        try {
            return androidx.biometric.BiometricManager.from(context).canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
                == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Throwable e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ savings goals

    /**
     * A goal: a name, a target in Stars and how much is put aside for it. Putting aside is the wallet's own
     * bookkeeping: the Stars stay on the balance, the wallet only shows them as kept for the goal.
     */
    public static final class Goal {
        public long id;
        public String name;
        public long target;
        public long saved;
    }

    public static java.util.ArrayList<Goal> goals(int account) {
        final java.util.ArrayList<Goal> list = new java.util.ArrayList<>();
        try {
            final org.json.JSONArray a = new org.json.JSONArray(prefs().getString(k(account, "goals"), "[]"));
            for (int i = 0; i < a.length(); i++) {
                final org.json.JSONObject o = a.getJSONObject(i);
                final Goal g = new Goal();
                g.id = o.optLong("id");
                g.name = o.optString("name");
                g.target = Math.max(1, o.optLong("target", 1));
                g.saved = Math.max(0, o.optLong("saved"));
                list.add(g);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return list;
    }

    public static void saveGoals(int account, java.util.List<Goal> list) {
        try {
            final org.json.JSONArray a = new org.json.JSONArray();
            for (Goal g : list) {
                final org.json.JSONObject o = new org.json.JSONObject();
                o.put("id", g.id);
                o.put("name", g.name);
                o.put("target", g.target);
                o.put("saved", g.saved);
                a.put(o);
            }
            prefs().edit().putString(k(account, "goals"), a.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** All Stars put aside, never more than the balance (if Stars were spent, the goals give back from the last). */
    public static long putAside(int account, long balance) {
        final java.util.ArrayList<Goal> list = goals(account);
        long total = 0;
        for (Goal g : list) {
            total += g.saved;
        }
        if (total > balance && balance >= 0) {
            long over = total - balance;
            for (int i = list.size() - 1; i >= 0 && over > 0; i--) {
                final long take = Math.min(over, list.get(i).saved);
                list.get(i).saved -= take;
                over -= take;
            }
            saveGoals(account, list);
            total = balance;
        }
        return total;
    }
}
