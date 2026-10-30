/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.updater;

import android.text.TextUtils;
import android.util.Base64;

import com.solegram.messenger.SolegramConfig;
import com.solegram.messenger.SolegramSecrets;

import org.json.JSONObject;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Beta versions: early builds for testing new features before their release, open only to those who have the
 * access code. They are published next to the releases, in {@code beta/update.json}, but sealed: the APK and its
 * changelog are encrypted with a key made from the code (PBKDF2-HMAC-SHA256, then AES-256-CTR with an
 * HMAC-SHA256 over the iv and the encrypted bytes), so the files in the public repository are of no use without it.
 * <pre>
 * {"version": "3.0.0-beta1", "build": 70, "apk": "beta/Solegram-3.0.0-beta1-b70.sealed",
 *  "size": …, "sha256": "…"               (the APK itself, checked again before it is installed)
 *  "sealedSize": …, "sealedSha256": "…",  (the file as downloaded)
 *  "salt": "hex", "iter": 120000, "iv": "hex", "mac": "hex",
 *  "check": "hex",                         (HMAC of a fixed text: tells a right code from a wrong one)
 *  "changelog": {"iv": "hex", "data": "base64", "mac": "hex"}}
 * </pre>
 * A newer beta is offered over the release only while it is newer; the next release (a higher build) replaces it.
 */
public final class BetaChannel {

    public static final int STATUS_UNKNOWN = 0;
    public static final int STATUS_OK = 1;
    public static final int STATUS_WRONG_CODE = 2;
    public static final int STATUS_NONE = 3;
    public static final int STATUS_ERROR = 4;

    private static final String SECRET = "betaCode";
    private static final byte[] CHECK_TEXT = "solegram-beta-check".getBytes(StandardCharsets.UTF_8);
    private static final int MIN_ITER = 10_000, MAX_ITER = 2_000_000;
    private static final Pattern HEX = Pattern.compile("([0-9a-f]{2})+");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** What the last look at the beta channel found (the settings page shows it). */
    public static volatile int status = STATUS_UNKNOWN;
    /** The newest beta's version when there is one. */
    public static volatile String latestVersion;

    private static String keysFor;
    private static byte[][] keys;

    private BetaChannel() {
    }

    /** The access code, the way it is typed: case, spaces and dashes do not matter. */
    public static String normalize(String code) {
        return code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    public static boolean hasAccess() {
        return !TextUtils.isEmpty(SolegramSecrets.get(SECRET));
    }

    /** Betas are looked for: there is a code and they are switched on. */
    public static boolean enabled() {
        return SolegramConfig.betaUpdates && hasAccess();
    }

    /** Saves a checked code (and turns betas on), or removes access with "". */
    public static void setCode(String code) {
        final String c = normalize(code);
        SolegramSecrets.put(SECRET, c);
        SolegramConfig.putBoolean("betaUpdates", !c.isEmpty());
        status = STATUS_UNKNOWN;
        latestVersion = null;
    }

    public interface CodeCallback {
        void onResult(int status);
    }

    /** Checks a typed code (the result comes on a background thread). */
    public static void verifyCode(String code, CodeCallback callback) {
        GithubUpdater.verifyBetaCode(code, callback);
    }

    /** Checks a typed code against the beta channel (in the background; the result on the update thread). */
    static void verify(String code, String branch, CodeCallback callback) {
        try {
            final JSONObject o = GithubUpdater.readJson(branch, "beta/update.json");
            callback.onResult(o == null ? STATUS_NONE : codeMatches(o, normalize(code)) ? STATUS_OK : STATUS_WRONG_CODE);
        } catch (Throwable e) {
            FileLog.e(e);
            callback.onResult(STATUS_ERROR);
        }
    }

    /** The newest beta, when the code fits and one is published; null otherwise (the status says why). */
    static UpdateController.UpdateInfo check(String branch) {
        try {
            final JSONObject o = GithubUpdater.readJson(branch, "beta/update.json");
            if (o == null) {
                status = STATUS_NONE;
                latestVersion = null;
                return null;
            }
            final String code = SolegramSecrets.get(SECRET);
            if (!codeMatches(o, code)) {
                status = STATUS_WRONG_CODE;
                latestVersion = null;
                return null;
            }
            if (o.optInt("build", 0) <= 0) {
                status = STATUS_NONE; // the channel is open, no beta yet
                latestVersion = null;
                return null;
            }
            final UpdateController.UpdateInfo info = GithubUpdater.parseInfo(o, branch);
            info.beta = true;
            info.betaSalt = hex(o.optString("salt"));
            info.betaIter = o.optInt("iter");
            info.betaIv = hex(o.optString("iv"));
            info.betaMac = hex(o.optString("mac"));
            info.sealedSize = o.optLong("sealedSize");
            info.sealedSha256 = o.optString("sealedSha256", "").toLowerCase(Locale.ROOT);
            if (info.betaIv == null || info.betaIv.length != 16 || info.betaMac == null || info.betaMac.length != 32
                || info.sealedSize <= 0 || !SHA256.matcher(info.sealedSha256).matches() || TextUtils.isEmpty(info.sha256)) {
                throw new IllegalArgumentException("beta/update.json: sealed fields");
            }
            final JSONObject changelog = o.optJSONObject("changelog");
            info.changelog = changelog == null ? "" : openText(keys(code, info.betaSalt, info.betaIter), changelog);
            status = STATUS_OK;
            latestVersion = info.versionName;
            return info;
        } catch (Throwable e) {
            FileLog.e(e);
            status = STATUS_ERROR;
            return null;
        }
    }

    private static boolean codeMatches(JSONObject o, String code) throws Exception {
        final byte[] salt = hex(o.optString("salt"));
        final byte[] check = hex(o.optString("check"));
        final int iter = o.optInt("iter");
        if (TextUtils.isEmpty(code) || salt == null || salt.length < 16 || check == null || iter < MIN_ITER || iter > MAX_ITER) {
            return false;
        }
        return MessageDigest.isEqual(hmac(keys(code, salt, iter)[1], CHECK_TEXT), check);
    }

    /**
     * The downloaded sealed file into the APK: its HMAC is checked first, then it is decrypted; the APK must be
     * the one beta/update.json names (size and SHA-256).
     */
    static void unseal(UpdateController.UpdateInfo info, File sealed, File target) throws Exception {
        final byte[][] k = keys(SolegramSecrets.get(SECRET), info.betaSalt, info.betaIter);
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(k[1], "HmacSHA256"));
        mac.update(info.betaIv);
        final byte[] buffer = new byte[64 * 1024];
        try (InputStream in = new FileInputStream(sealed)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                mac.update(buffer, 0, n);
            }
        }
        if (!MessageDigest.isEqual(mac.doFinal(), info.betaMac)) {
            throw new SecurityException("beta access code");
        }
        final Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k[0], "AES"), new IvParameterSpec(info.betaIv));
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        final File tmp = new File(target.getPath() + ".open");
        long size = 0;
        try (InputStream in = new FileInputStream(sealed); FileOutputStream out = new FileOutputStream(tmp)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                final byte[] plain = cipher.update(buffer, 0, n);
                if (plain != null) {
                    out.write(plain);
                    digest.update(plain);
                    size += plain.length;
                }
            }
            final byte[] rest = cipher.doFinal();
            if (rest != null && rest.length > 0) {
                out.write(rest);
                digest.update(rest);
                size += rest.length;
            }
            out.getFD().sync();
        }
        final StringBuilder hash = new StringBuilder();
        for (byte b : digest.digest()) {
            hash.append(String.format(Locale.ROOT, "%02x", b));
        }
        if (info.size > 0 && size != info.size || !hash.toString().equals(info.sha256)) {
            tmp.delete();
            throw new SecurityException("beta apk");
        }
        target.delete();
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new java.io.IOException("rename failed");
        }
    }

    /** The sealed fields of a beta, kept with a saved update (AutoUpdater). */
    static void save(UpdateController.UpdateInfo info, JSONObject o) throws Exception {
        if (!info.beta || info.betaSalt == null || info.betaIv == null || info.betaMac == null) {
            return;
        }
        final JSONObject b = new JSONObject();
        b.put("salt", hexString(info.betaSalt));
        b.put("iv", hexString(info.betaIv));
        b.put("mac", hexString(info.betaMac));
        b.put("iter", info.betaIter);
        b.put("sealedSize", info.sealedSize);
        b.put("sealedSha256", info.sealedSha256);
        o.put("beta", b);
    }

    static void load(JSONObject o, UpdateController.UpdateInfo info) {
        final JSONObject b = o.optJSONObject("beta");
        if (b == null) {
            return;
        }
        info.betaSalt = hex(b.optString("salt"));
        info.betaIv = hex(b.optString("iv"));
        info.betaMac = hex(b.optString("mac"));
        info.betaIter = b.optInt("iter");
        info.sealedSize = b.optLong("sealedSize");
        info.sealedSha256 = b.optString("sealedSha256", "");
        info.beta = info.betaSalt != null && info.betaIv != null && info.betaMac != null;
    }

    private static String openText(byte[][] k, JSONObject sealed) throws Exception {
        final byte[] iv = hex(sealed.optString("iv"));
        final byte[] data = Base64.decode(sealed.optString("data", ""), Base64.DEFAULT);
        final byte[] tag = hex(sealed.optString("mac"));
        if (iv == null || iv.length != 16 || tag == null) {
            return "";
        }
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(k[1], "HmacSHA256"));
        mac.update(iv);
        if (!MessageDigest.isEqual(mac.doFinal(data), tag)) {
            return "";
        }
        final Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k[0], "AES"), new IvParameterSpec(iv));
        final String text = new String(cipher.doFinal(data), StandardCharsets.UTF_8);
        return text.length() > 8000 ? text.substring(0, 8000) : text;
    }

    /** {encryption key, MAC key}: the 64 bytes PBKDF2 makes from the code (kept for the code and salt last used). */
    private static synchronized byte[][] keys(String code, byte[] salt, int iter) throws Exception {
        final String id = code + "|" + hexString(salt) + "|" + iter;
        if (id.equals(keysFor)) {
            return keys;
        }
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(code.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        final byte[][] out = new byte[2][];
        for (int block = 1; block <= 2; block++) {
            mac.update(salt);
            mac.update(new byte[]{(byte) (block >>> 24), (byte) (block >>> 16), (byte) (block >>> 8), (byte) block});
            byte[] u = mac.doFinal();
            final byte[] t = u.clone();
            for (int i = 1; i < iter; i++) {
                u = mac.doFinal(u);
                for (int j = 0; j < t.length; j++) {
                    t[j] ^= u[j];
                }
            }
            out[block - 1] = t;
        }
        keysFor = id;
        keys = out;
        return out;
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] hex(String s) {
        if (s == null || !HEX.matcher(s = s.toLowerCase(Locale.ROOT)).matches() || s.length() > 256) {
            return null;
        }
        final byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hexString(byte[] b) {
        final StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format(Locale.ROOT, "%02x", x));
        }
        return sb.toString();
    }
}
