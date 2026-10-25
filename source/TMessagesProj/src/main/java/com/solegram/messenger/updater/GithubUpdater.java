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
            try {
                // repositories use either main or master
                String branch = SolegramConfig.getUpdateBranch();
                byte[] body;
                try {
                    body = readAll(rawUrl(branch, "update.json") + "?t=" + System.currentTimeMillis());
                } catch (java.io.FileNotFoundException | NotFound e) {
                    branch = "master";
                    body = readAll(rawUrl(branch, "update.json") + "?t=" + System.currentTimeMillis());
                }
                String json = new String(body, "UTF-8");
                JSONObject o = new JSONObject(json);
                UpdateController.UpdateInfo info = new UpdateController.UpdateInfo();
                info.build = o.optInt("build", 0);
                info.versionName = o.optString("version", String.valueOf(info.build));
                info.changelog = o.optString("changelog", "");
                info.size = o.optLong("size", 0);
                info.sha256 = o.optString("sha256", "").toLowerCase(Locale.ROOT);
                String apk = o.optString("apk", "");
                if (info.build <= 0 || TextUtils.isEmpty(apk)) {
                    callback.onResult(null, "update.json: build/apk missing");
                    return;
                }
                info.url = apk.startsWith("https://") ? apk : rawUrl(branch, apk);
                // optional link that always points at the newest APK (for the browser)
                final String latest = o.optString("latest", "");
                if (!TextUtils.isEmpty(latest)) {
                    info.latestUrl = latest.startsWith("https://") ? latest : rawUrl(branch, latest);
                }
                callback.onResult(info, null);
            } catch (Throwable e) {
                FileLog.e(e);
                callback.onResult(null, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });
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
                if (info.size > 0 && have > info.size) {
                    part.delete();
                    have = 0;
                }
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                if (have > 0) {
                    have = hashInto(part, have, digest);
                }
                long done = have;
                if (info.size <= 0 || have < info.size) {
                    connection = open(info.url, have);
                    final boolean append = have > 0 && connection.getResponseCode() == HttpURLConnection.HTTP_PARTIAL
                        && String.valueOf(connection.getHeaderField("Content-Range")).startsWith("bytes " + have + "-");
                    if (!append && have > 0) {
                        // the server sent the whole file: start over
                        have = 0;
                        done = 0;
                        digest.reset();
                    }
                    long total = info.size;
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
                if (info.size > 0 && done != info.size) {
                    dropPart = true;
                    throw new SecurityException("size " + done + " != " + info.size);
                }
                String hash = toHex(digest.digest());
                if (!TextUtils.isEmpty(info.sha256) && !info.sha256.equals(hash)) {
                    dropPart = true;
                    throw new SecurityException("sha256 mismatch");
                }
                target.delete();
                ok = part.renameTo(target);
                if (!ok) {
                    error = "rename failed";
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

    /** @param from resume from this byte (Range request); the server may still answer with the whole file */
    private static HttpURLConnection open(String url, long from) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT);
        connection.setReadTimeout(TIMEOUT);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Solegram-Updater");
        if (from > 0) {
            connection.setRequestProperty("Range", "bytes=" + from + "-");
            // a transparently gzipped body has no byte ranges
            connection.setRequestProperty("Accept-Encoding", "identity");
        }
        int code = connection.getResponseCode();
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

    private static byte[] readAll(String url) throws Exception {
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
