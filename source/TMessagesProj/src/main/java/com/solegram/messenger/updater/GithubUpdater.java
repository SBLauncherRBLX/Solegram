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

        void onDone(boolean success);
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

    private static File updateDir() {
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
            HttpURLConnection connection = null;
            try {
                File dir = updateDir();
                dir.mkdirs();
                // drop APKs of older updates
                File[] old = dir.listFiles();
                if (old != null) {
                    for (File f : old) {
                        if (!f.getName().equals(target.getName())) {
                            f.delete();
                        }
                    }
                }
                connection = open(info.url);
                long total = connection.getContentLengthLong();
                if (total <= 0) {
                    total = info.size;
                }
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                    byte[] buffer = new byte[64 * 1024];
                    long done = 0;
                    long lastReport = 0;
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        if (gen != generation.get()) {
                            throw new InterruptedException("cancelled");
                        }
                        out.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                        done += read;
                        long now = System.currentTimeMillis();
                        if (total > 0 && now - lastReport > 150) {
                            lastReport = now;
                            listener.onProgress(Math.min(1f, done / (float) total));
                        }
                    }
                    if (total > 0 && done != total) {
                        throw new java.io.IOException("incomplete download: " + done + "/" + total);
                    }
                }
                String hash = toHex(digest.digest());
                if (!TextUtils.isEmpty(info.sha256) && !info.sha256.equals(hash)) {
                    throw new SecurityException("sha256 mismatch");
                }
                target.delete();
                ok = part.renameTo(target);
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                if (!ok) {
                    part.delete();
                }
            }
            if (gen == generation.get()) {
                listener.onDone(ok);
            }
        });
    }

    private static final class NotFound extends java.io.IOException {
        NotFound() {
            super("HTTP 404");
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT);
        connection.setReadTimeout(TIMEOUT);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Solegram-Updater");
        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new NotFound();
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
