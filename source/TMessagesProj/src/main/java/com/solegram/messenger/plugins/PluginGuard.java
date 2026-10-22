/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.plugins;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.solegram.messenger.SolegramConfig;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plugin verification around {@link PluginSecurity}: reports cached by SHA-256, the public
 * blocklist of known malicious plugins, and the "reviewed" marks of plugins whose report the user
 * has seen. Everything that touches files or the network runs off the UI thread.
 */
public final class PluginGuard {

    public static final String BLOCKLIST_URL = "https://raw.githubusercontent.com/SBLauncherRBLX/Solegram/main/plugins_blocklist.txt";

    /** Install errors understood by the UI. */
    public static final String ERROR_BLOCKLISTED = "sg_security_blocklisted";
    public static final String ERROR_DANGEROUS = "sg_security_dangerous";

    public static final int BLOCK_NONE = 0;
    public static final int BLOCK_LIST = 1;
    public static final int BLOCK_DANGEROUS = 2;

    // the list changes rarely: a few hours between downloads, and a failed one is not retried at once
    private static final long BLOCKLIST_TTL = 6 * 60 * 60 * 1000L;
    private static final long BLOCKLIST_RETRY = 30 * 60 * 1000L;
    private static final int TIMEOUT = 5000;
    private static final int MAX_CACHED_REPORTS = 64;
    private static final int MAX_PLUGIN_SIZE = 8 * 1024 * 1024;

    /** Scans for the UI run here, not on pluginsQueue: that one may be busy starting Python. */
    private static final DispatchQueue queue = new DispatchQueue("pluginGuardQueue");

    private static final Map<String, PluginSecurity.Report> memory = Collections.synchronizedMap(new LinkedHashMap<String, PluginSecurity.Report>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, PluginSecurity.Report> eldest) {
            return size() > 32;
        }
    });

    private static volatile Set<String> blocklist;
    private static final AtomicBoolean fetching = new AtomicBoolean();
    private static volatile long lastAttempt;

    private PluginGuard() {
    }

    private static File dir() {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "plugin_security");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("solegram_plugin_security", Context.MODE_PRIVATE);
    }

    // region Scanning

    public interface Callback {
        void onResult(PluginSecurity.Report report, boolean blocklisted, String error);
    }

    /** Reads, hashes and analyzes a plugin file; reports are cached by hash. Background threads only. */
    public static PluginSecurity.Report scan(File file, boolean force) throws Exception {
        byte[] data = read(file);
        String sha = PluginSecurity.sha256(data);
        if (!force) {
            PluginSecurity.Report cached = memory.get(sha);
            if (cached == null) {
                cached = readCache(sha);
                if (cached != null) {
                    memory.put(sha, cached);
                }
            }
            if (cached != null) {
                return cached;
            }
        }
        PluginSecurity.Report report = PluginSecurity.analyze(data);
        memory.put(sha, report);
        writeCache(report);
        return report;
    }

    /** Scans in the background; the callback runs on the UI thread. */
    public static void scanAsync(File file, boolean force, Callback callback) {
        queue.postRunnable(() -> {
            PluginSecurity.Report report = null;
            String error = null;
            try {
                report = scan(file, force);
            } catch (Throwable e) {
                FileLog.e(e);
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            final PluginSecurity.Report r = report;
            final String err = error;
            final boolean blocked = r != null && isBlocklisted(r.sha256);
            AndroidUtilities.runOnUIThread(() -> callback.onResult(r, blocked, err));
        });
        refreshBlocklist(false, null);
    }

    public static void runInBackground(Runnable runnable) {
        queue.postRunnable(runnable);
    }

    private static byte[] read(File file) throws Exception {
        if (file.length() > MAX_PLUGIN_SIZE) {
            throw new IllegalArgumentException("Plugin file is too large");
        }
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(1024, file.length()));
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
            return out.toByteArray();
        }
    }

    /** Why a plugin may not run: blocklisted, dangerous while dangerous plugins are blocked, or not at all. */
    public static int blockReason(PluginSecurity.Report report) {
        if (report == null) {
            return BLOCK_NONE;
        }
        if (isBlocklisted(report.sha256)) {
            return BLOCK_LIST;
        }
        if (SolegramConfig.pluginsBlockDangerous && report.verdict == PluginSecurity.VERDICT_DANGER) {
            return BLOCK_DANGEROUS;
        }
        return BLOCK_NONE;
    }
    // endregion

    // region Report cache

    private static File cacheFile(String sha) {
        return new File(new File(dir(), "reports"), sha + ".json");
    }

    private static PluginSecurity.Report readCache(String sha) {
        File f = cacheFile(sha);
        if (!f.exists()) {
            return null;
        }
        try {
            return fromJson(new JSONObject(new String(read(f), StandardCharsets.UTF_8)));
        } catch (Throwable e) {
            FileLog.e(e);
            f.delete();
            return null;
        }
    }

    private static void writeCache(PluginSecurity.Report report) {
        try {
            File f = cacheFile(report.sha256);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            writeAtomically(f, toJson(report).toString().getBytes(StandardCharsets.UTF_8));
            File[] all = parent != null ? parent.listFiles() : null;
            if (all != null && all.length > MAX_CACHED_REPORTS) {
                java.util.Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                for (int i = 0; i < all.length - MAX_CACHED_REPORTS; i++) {
                    all[i].delete();
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void writeAtomically(File target, byte[] data) throws Exception {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        }
        if (!tmp.renameTo(target)) {
            target.delete();
            if (!tmp.renameTo(target)) {
                throw new IllegalStateException("rename failed");
            }
        }
    }

    static JSONObject toJson(PluginSecurity.Report r) throws Exception {
        JSONObject o = new JSONObject();
        o.put("v", r.version);
        o.put("sha", r.sha256);
        o.put("verdict", r.verdict);
        o.put("size", r.size);
        o.put("lines", r.lines);
        o.put("reason", r.reason);
        o.put("hosts", new JSONArray(r.hosts));
        JSONArray findings = new JSONArray();
        for (PluginSecurity.Finding f : r.findings) {
            JSONObject fo = new JSONObject();
            fo.put("r", f.rule);
            fo.put("n", f.count);
            fo.put("d", new JSONArray(f.details));
            JSONArray ev = new JSONArray();
            for (PluginSecurity.Evidence e : f.evidence) {
                ev.put(new JSONArray().put(e.line).put(e.snippet));
            }
            fo.put("e", ev);
            findings.put(fo);
        }
        o.put("f", findings);
        return o;
    }

    static PluginSecurity.Report fromJson(JSONObject o) throws Exception {
        if (o.optInt("v") != PluginSecurity.VERSION) {
            // written by an older analyzer: its rules may have missed something
            return null;
        }
        PluginSecurity.Report r = new PluginSecurity.Report();
        r.sha256 = o.getString("sha");
        r.verdict = o.getInt("verdict");
        r.size = o.optLong("size");
        r.lines = o.optInt("lines");
        r.reason = o.isNull("reason") ? null : o.optString("reason", null);
        JSONArray hosts = o.optJSONArray("hosts");
        for (int i = 0; hosts != null && i < hosts.length(); i++) {
            r.hosts.add(hosts.getString(i));
        }
        JSONArray findings = o.getJSONArray("f");
        for (int i = 0; i < findings.length(); i++) {
            JSONObject fo = findings.getJSONObject(i);
            String rule = fo.getString("r");
            if (!PluginSecurity.isKnownRule(rule)) {
                return null;
            }
            PluginSecurity.Finding f = new PluginSecurity.Finding(rule, PluginSecurity.ruleCategory(rule), PluginSecurity.ruleSeverity(rule));
            f.count = fo.optInt("n");
            JSONArray d = fo.optJSONArray("d");
            for (int k = 0; d != null && k < d.length(); k++) {
                f.details.add(d.getString(k));
            }
            JSONArray ev = fo.optJSONArray("e");
            for (int k = 0; ev != null && k < ev.length(); k++) {
                JSONArray e = ev.getJSONArray(k);
                f.evidence.add(new PluginSecurity.Evidence(e.getInt(0), e.getString(1)));
            }
            r.findings.add(f);
        }
        return r;
    }
    // endregion

    // region Blocklist

    public static boolean isBlocklisted(String sha) {
        if (sha == null) {
            return false;
        }
        Set<String> set = blocklist;
        if (set == null) {
            set = loadCachedBlocklist();
        }
        return set.contains(sha.toLowerCase(java.util.Locale.ROOT));
    }

    private static synchronized Set<String> loadCachedBlocklist() {
        if (blocklist != null) {
            return blocklist;
        }
        Set<String> set = Collections.emptySet();
        File f = new File(dir(), "blocklist.txt");
        if (f.exists()) {
            try {
                set = parseBlocklist(new String(read(f), StandardCharsets.UTF_8));
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        blocklist = set;
        return set;
    }

    /** One SHA-256 per line; "#" starts a comment, anywhere on a line. */
    static Set<String> parseBlocklist(String text) {
        HashSet<String> set = new HashSet<>();
        for (String line : text.split("\n")) {
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            line = line.trim().toLowerCase(java.util.Locale.ROOT);
            if (line.length() == 64 && PluginSecurity.isHex(line)) {
                set.add(line);
            }
        }
        return set;
    }

    /**
     * Downloads the blocklist in the background when the cached copy is older than a few hours
     * ({@code force}: now). A missing list (404) is an empty one. {@code done} runs on the UI thread.
     */
    public static void refreshBlocklist(boolean force, Runnable done) {
        // the freshness check reads preferences: never on the UI thread
        queue.postRunnable(() -> refreshBlocklistInternal(force, done));
    }

    private static void refreshBlocklistInternal(boolean force, Runnable done) {
        long now = System.currentTimeMillis();
        boolean fresh = now - prefs().getLong("blocklist_fetched", 0) < BLOCKLIST_TTL;
        if (!force && (fresh || now - lastAttempt < BLOCKLIST_RETRY) || !fetching.compareAndSet(false, true)) {
            if (done != null) {
                AndroidUtilities.runOnUIThread(done);
            }
            return;
        }
        lastAttempt = now;
        Thread thread = new Thread(() -> {
            try {
                fetchBlocklist();
            } finally {
                fetching.set(false);
                if (done != null) {
                    AndroidUtilities.runOnUIThread(done);
                }
            }
        }, "PluginBlocklist");
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void fetchBlocklist() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(BLOCKLIST_URL).openConnection();
            connection.setConnectTimeout(TIMEOUT);
            connection.setReadTimeout(TIMEOUT);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "Solegram-Plugins");
            int code = connection.getResponseCode();
            String text;
            if (code == HttpURLConnection.HTTP_OK) {
                try (InputStream in = connection.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) > 0 && out.size() < 4 * 1024 * 1024) {
                        out.write(buf, 0, r);
                    }
                    text = new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            } else if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                // the list has not been published yet: nothing is blocked
                text = "";
            } else {
                FileLog.d("plugin blocklist: HTTP " + code);
                return;
            }
            Set<String> set = parseBlocklist(text);
            writeAtomically(new File(dir(), "blocklist.txt"), text.getBytes(StandardCharsets.UTF_8));
            prefs().edit().putLong("blocklist_fetched", System.currentTimeMillis()).apply();
            Set<String> old = blocklist;
            blocklist = set;
            if (old == null || !old.equals(set)) {
                PluginsController.getInstance().onBlocklistUpdated();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
    // endregion

    // region Reviewed reports

    /** The user has seen the report of exactly this file (by hash) and accepted it. */
    public static boolean isReviewed(String pluginId, String sha) {
        return sha != null && sha.equals(prefs().getString("reviewed_" + pluginId, null));
    }

    public static void markReviewed(String pluginId, String sha) {
        if (pluginId != null && sha != null) {
            prefs().edit().putString("reviewed_" + pluginId, sha).apply();
        }
    }

    public static void forget(String pluginId) {
        prefs().edit().remove("reviewed_" + pluginId).apply();
    }
    // endregion

    // region Crash detection

    /** Whether the app process last ended with a crash or ANR after {@code since} (Android 11+). */
    static boolean lastExitWasCrash(long since) {
        if (Build.VERSION.SDK_INT < 30 || since <= 0) {
            return false;
        }
        try {
            ActivityManager am = (ActivityManager) ApplicationLoader.applicationContext.getSystemService(Context.ACTIVITY_SERVICE);
            List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(null, 0, 1);
            if (exits == null || exits.isEmpty()) {
                return false;
            }
            ApplicationExitInfo info = exits.get(0);
            int reason = info.getReason();
            return info.getTimestamp() >= since && (reason == ApplicationExitInfo.REASON_CRASH
                || reason == ApplicationExitInfo.REASON_CRASH_NATIVE || reason == ApplicationExitInfo.REASON_ANR);
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }
    // endregion
}
