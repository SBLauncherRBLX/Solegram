/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.updater;

import android.text.TextUtils;

import com.solegram.messenger.SolegramConfig;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Updates published in a GitHub repository. The repository holds {@code update.json} next to
 * the APK:
 * <pre>
 * {"version": "1.2.0", "build": 3, "apk": "apk/Solegram-1.2.0-b3-release.apk",
 *  "size": 78000000, "sha256": "…", "changelog": "…"}
 * </pre>
 * Files are fetched from raw.githubusercontent.com, so no GitHub account is needed.
 */
final class GithubUpdater {

    interface CheckCallback {
        void onResult(UpdateController.UpdateInfo info, String error);
    }

    interface DownloadListener {
        void onProgress(float progress);

        /** @param error null on success: the file is in place and matched its size and SHA-256 */
        void onDone(boolean success, String error);
    }

    private static final int TIMEOUT = 20_000;

    /**
     * Where update files may come from, every redirect included: GitHub only (raw files, the repository and its
     * release assets), https only. The APK is still checked for our signature before it is installed.
     */
    private static final String[] ALLOWED_HOSTS = {"raw.githubusercontent.com", "github.com", "objects.githubusercontent.com",
        "release-assets.githubusercontent.com"};
    private static final int MAX_REDIRECTS = 5;
    /** Far above any real Solegram APK; a server that keeps sending must not fill the storage. */
    private static final long MAX_APK_SIZE = 400L * 1024 * 1024;
    private static final int MAX_CHANGELOG = 8000;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    /** A file of the repository, named in update.json: plain path segments, no way out of the repository. */
    private static final Pattern REPO_PATH = Pattern.compile("[A-Za-z0-9._+-]+(/[A-Za-z0-9._+-]+)*");

    // own threads: a 78 MB download must never hold Telegram's shared background queues
    private static DispatchQueue checkQueue, downloadQueue;

    private static synchronized DispatchQueue checkQueue() {
        if (checkQueue == null) {
            checkQueue = new DispatchQueue("solegramUpdateCheck");
        }
        return checkQueue;
    }

    private static synchronized DispatchQueue downloadQueue() {
        if (downloadQueue == null) {
            downloadQueue = new DispatchQueue("solegramUpdateDownload");
        }
        return downloadQueue;
    }

    static String rawUrl(String branch, String path) {
        String repo = SolegramConfig.getUpdateRepo();
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return "https://raw.githubusercontent.com/" + repo + "/" + branch + "/" + path;
    }

    static void check(CheckCallback callback) {
        checkQueue().postRunnable(() -> {
            UpdateController.UpdateInfo stable = null;
            String error = null;
            // repositories use either main or master
            String branch = SolegramConfig.getUpdateBranch();
            try {
                JSONObject o = readJson(branch, "update.json");
                if (o == null) {
                    branch = "master";
                    o = readJson(branch, "update.json");
                }
                if (o == null) {
                    throw new java.io.FileNotFoundException("update.json");
                }
                stable = parseInfo(o, branch);
            } catch (Throwable e) {
                FileLog.e(e);
                error = e instanceof IllegalArgumentException ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            // Solegram betas: a newer beta is offered over the release (those with the access code only)
            if (BetaChannel.enabled()) {
                final UpdateController.UpdateInfo beta = BetaChannel.check(branch);
                if (beta != null && (stable == null || beta.build > stable.build)) {
                    callback.onResult(beta, null);
                    return;
                }
            }
            callback.onResult(stable, stable == null ? error : null);
        });
    }

    /** Checks a typed beta access code (BetaChannel.STATUS_*), on the update thread. */
    static void verifyBetaCode(String code, BetaChannel.CodeCallback callback) {
        checkQueue().postRunnable(() -> BetaChannel.verify(code, SolegramConfig.getUpdateBranch(), callback));
    }

    /** A JSON file of the repository, null when it is not there. */
    static JSONObject readJson(String branch, String path) throws Exception {
        try {
            return new JSONObject(new String(readAll(rawUrl(branch, path) + "?t=" + System.currentTimeMillis()), "UTF-8"));
        } catch (java.io.FileNotFoundException | NotFound e) {
            return null;
        }
    }

    /** An update.json; what does not fit is refused (IllegalArgumentException with the reason), never "fixed". */
    static UpdateController.UpdateInfo parseInfo(JSONObject o, String branch) {
        UpdateController.UpdateInfo info = new UpdateController.UpdateInfo();
        info.build = o.optInt("build", 0);
        info.versionName = clip(o.optString("version", String.valueOf(info.build)), 32);
        info.changelog = clip(o.optString("changelog", ""), MAX_CHANGELOG);
        info.size = o.optLong("size", 0);
        info.sha256 = o.optString("sha256", "").trim().toLowerCase(Locale.ROOT);
        String apk = o.optString("apk", "");
        if (info.build <= 0 || info.build > 10_000_000 || TextUtils.isEmpty(apk)) {
            throw new IllegalArgumentException("update.json: build/apk missing");
        }
        if (!TextUtils.isEmpty(info.sha256) && !SHA256.matcher(info.sha256).matches()) {
            throw new IllegalArgumentException("update.json: bad sha256");
        }
        if (info.size < 0 || info.size > MAX_APK_SIZE) {
            throw new IllegalArgumentException("update.json: bad size");
        }
        info.url = fileUrl(branch, apk);
        if (info.url == null) {
            throw new IllegalArgumentException("update.json: apk link not allowed");
        }
        // optional link that always points at the newest APK (for the browser); a bad one is ignored
        final String latest = o.optString("latest", "");
        if (!TextUtils.isEmpty(latest)) {
            info.latestUrl = fileUrl(branch, latest);
        }
        return info;
    }

    /** Text at most {@code max} long, without control characters (it is shown as it is). */
    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(Math.min(s.length(), max));
        for (int i = 0; i < s.length() && sb.length() < max; i++) {
            final char c = s.charAt(i);
            if (c >= ' ' || max > 32 && (c == '\n' || c == '\t')) { // the version is one line, the changelog has lines
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** An https link to one of the GitHub hosts, on the standard port and without user info. */
    static boolean isAllowedUrl(String url) {
        if (url == null) {
            return false;
        }
        try {
            final URL u = new URL(url);
            if (!"https".equalsIgnoreCase(u.getProtocol()) || u.getUserInfo() != null || u.getPort() != -1 && u.getPort() != 443) {
                return false;
            }
            final String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            for (String allowed : ALLOWED_HOSTS) {
                if (allowed.equals(host)) {
                    return true;
                }
            }
        } catch (Exception ignore) {
        }
        return false;
    }

    /** A file named in update.json: a full GitHub link, or a path inside the repository. Null when neither. */
    private static String fileUrl(String branch, String value) {
        final String v = value == null ? "" : value.trim();
        if (v.regionMatches(true, 0, "https://", 0, 8)) {
            return isAllowedUrl(v) ? v : null;
        }
        if (!REPO_PATH.matcher(v).matches()) {
            return null; // a scheme, a query, a backslash, an encoded character...
        }
        for (String segment : v.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                return null;
            }
        }
        return rawUrl(branch, v);
    }

    static File updateDir() {
        return new File(ApplicationLoader.applicationContext.getCacheDir(), "solegram/update");
    }

    static File apkFile(UpdateController.UpdateInfo info) {
        // the hash is part of the name, so a re-published build is never mistaken for the cached one
        String version = info.versionName == null ? "" : info.versionName.replaceAll("[^0-9A-Za-z._-]", "_");
        String hash = TextUtils.isEmpty(info.sha256) ? "" : "-" + info.sha256.substring(0, Math.min(8, info.sha256.length())).replaceAll("[^0-9a-f]", "");
        return new File(updateDir(), "Solegram-" + version + "-b" + info.build + hash + ".apk");
    }

    /** Each download gets a generation; cancelling or starting a new one retires the old task. */
    private static final java.util.concurrent.atomic.AtomicInteger generation = new java.util.concurrent.atomic.AtomicInteger();

    static void cancel() {
        generation.incrementAndGet();
    }

    static void download(UpdateController.UpdateInfo info, DownloadListener listener) {
        final int gen = generation.incrementAndGet();
        downloadQueue().postRunnable(() -> {
            if (gen != generation.get()) {
                return;
            }
            File target = apkFile(info);
            File part = new File(target.getPath() + ".part");
            // a beta comes sealed: the part holds the sealed file, opened into the APK once it is complete
            final boolean sealed = info.beta;
            final long expectedSize = sealed ? info.sealedSize : info.size;
            final String expectedSha256 = sealed ? info.sealedSha256 : info.sha256;
            boolean ok = false;
            // a lost connection or a stopped background job keeps the part: the next try resumes it.
            // A file that failed its check is never resumed
            boolean dropPart = false;
            String error = null;
            HttpURLConnection connection = null;
            try {
                File dir = updateDir();
                dir.mkdirs();
                // drop APKs of older updates (the part of this one stays)
                File[] old = dir.listFiles();
                if (old != null) {
                    for (File f : old) {
                        if (!f.getName().equals(target.getName()) && !f.getName().equals(part.getName())) {
                            f.delete();
                        }
                    }
                }
                long have = part.isFile() ? part.length() : 0;
                if (expectedSize > 0 && have > expectedSize) {
                    part.delete();
                    have = 0;
                }
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                if (have > 0) {
                    have = hashInto(part, have, digest);
                }
                long done = have;
                if (expectedSize <= 0 || have < expectedSize) {
                    connection = open(info.url, have);
                    final boolean append = have > 0 && connection.getResponseCode() == HttpURLConnection.HTTP_PARTIAL
                        && String.valueOf(connection.getHeaderField("Content-Range")).startsWith("bytes " + have + "-");
                    if (!append && have > 0) {
                        // the server sent the whole file: start over
                        have = 0;
                        done = 0;
                        digest.reset();
                    }
                    long total = expectedSize;
                    if (total <= 0) {
                        final long length = connection.getContentLengthLong();
                        total = length > 0 ? have + length : 0;
                    }
                    try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(part, append)) {
                        byte[] buffer = new byte[64 * 1024];
                        long lastReport = 0;
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            if (gen != generation.get()) {
                                throw new InterruptedException("cancelled");
                            }
                            out.write(buffer, 0, read);
                            digest.update(buffer, 0, read);
                            done += read;
                            if (done > MAX_APK_SIZE) {
                                dropPart = true;
                                throw new java.io.IOException("too large");
                            }
                            if (total > 0 && done > total) {
                                dropPart = true;
                                throw new java.io.IOException("larger than expected");
                            }
                            long now = System.currentTimeMillis();
                            if (total > 0 && now - lastReport > 150) {
                                lastReport = now;
                                listener.onProgress(Math.min(1f, done / (float) total));
                            }
                        }
                        out.getFD().sync();
                    }
                    if (total > 0 && done != total) {
                        throw new java.io.IOException("incomplete download: " + done + "/" + total);
                    }
                }
                if (expectedSize > 0 && done != expectedSize) {
                    dropPart = true;
                    throw new SecurityException("size " + done + " != " + expectedSize);
                }
                String hash = toHex(digest.digest());
                if (!TextUtils.isEmpty(expectedSha256) && !expectedSha256.equals(hash)) {
                    dropPart = true;
                    throw new SecurityException("sha256 mismatch");
                }
                if (sealed) {
                    BetaChannel.unseal(info, part, target);
                    part.delete();
                    ok = true;
                } else {
                    target.delete();
                    ok = part.renameTo(target);
                    if (!ok) {
                        error = "rename failed";
                    }
                }
            } catch (RangeNotSatisfiable e) {
                dropPart = true;
                error = e.getMessage();
            } catch (Throwable e) {
                FileLog.e(e);
                error = e instanceof SecurityException ? "verification: " + e.getMessage() : e.getClass().getSimpleName() + ": " + e.getMessage();
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                if (!ok && dropPart) {
                    part.delete();
                }
            }
            if (gen == generation.get()) {
                listener.onDone(ok, ok ? null : error);
            }
        });
    }

    /** Feeds the first {@code length} bytes of a partial download into the digest; returns how many it read. */
    private static long hashInto(File file, long length, MessageDigest digest) throws java.io.IOException {
        long read = 0;
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while (read < length && (n = in.read(buffer, 0, (int) Math.min(buffer.length, length - read))) > 0) {
                digest.update(buffer, 0, n);
                read += n;
            }
        }
        return read;
    }

    /** SHA-256 of a whole file, lowercase hex. */
    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        hashInto(file, file.length(), digest);
        return toHex(digest.digest());
    }

    private static final class NotFound extends java.io.IOException {
        NotFound() {
            super("HTTP 404");
        }
    }

    /** The server has no bytes after the part we hold (the file changed or the part is complete garbage). */
    private static final class RangeNotSatisfiable extends java.io.IOException {
        RangeNotSatisfiable() {
            super("HTTP 416");
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        return open(url, 0);
    }

    /**
     * @param from resume from this byte (Range request); the server may still answer with the whole file.
     * Redirects are followed here, one by one, so each hop has to be an allowed GitHub host too (release assets
     * move to a storage host).
     */
    private static HttpURLConnection open(String url, long from) throws Exception {
        String current = url;
        HttpURLConnection connection;
        int code;
        for (int hop = 0; ; hop++) {
            if (!isAllowedUrl(current)) {
                throw new SecurityException("host not allowed");
            }
            connection = (HttpURLConnection) new URL(current).openConnection();
            connection.setConnectTimeout(TIMEOUT);
            connection.setReadTimeout(TIMEOUT);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Solegram-Updater");
            if (from > 0) {
                connection.setRequestProperty("Range", "bytes=" + from + "-");
                // a transparently gzipped body has no byte ranges
                connection.setRequestProperty("Accept-Encoding", "identity");
            }
            code = connection.getResponseCode();
            if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308) {
                break;
            }
            final String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (TextUtils.isEmpty(location) || hop >= MAX_REDIRECTS) {
                throw new java.io.IOException("HTTP " + code);
            }
            current = new URL(new URL(current), location).toString();
        }
        if (code != HttpURLConnection.HTTP_OK && !(from > 0 && code == HttpURLConnection.HTTP_PARTIAL)) {
            connection.disconnect();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new NotFound();
            }
            if (code == 416) {
                throw new RangeNotSatisfiable();
            }
            throw new java.io.IOException("HTTP " + code);
        }
        return connection;
    }

    static byte[] readAll(String url) throws Exception {
        HttpURLConnection connection = open(url);
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (out.size() > 512 * 1024) {
                    throw new java.io.IOException("update.json too large");
                }
            }
            return out.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    private GithubUpdater() {
    }
}
