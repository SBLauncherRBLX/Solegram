/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.plugins;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.solegram.messenger.SolegramConfig;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Plugin manager compatible with the exteraGram / AyuGram plugin format (single .plugin / .py file
 * with top-level metadata and a BasePlugin subclass). The code execution itself is delegated to a
 * {@link PluginEngine}; this class owns state, persistence, the hook registry and dispatching.
 */
public class PluginsController {

    public static final String APP_EVENT_START = "START";
    public static final String APP_EVENT_STOP = "STOP";
    public static final String APP_EVENT_PAUSE = "PAUSE";
    public static final String APP_EVENT_RESUME = "RESUME";

    public static final DispatchQueue pluginsQueue = new DispatchQueue("pluginsQueue");

    private static volatile PluginsController instance;
    private static volatile PluginEngine engine;

    private static volatile boolean hasHooks;
    private static volatile boolean hasSendHooks;
    private static volatile boolean hasUpdatesHooks;

    private final Map<String, PluginMeta> plugins = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Object hooksLock = new Object();
    /** pluginId -> hook registrations */
    private final Map<String, List<HookEntry>> hooksByPlugin = new HashMap<>();
    /** Immutable view of the request/update hooks; swapped atomically so readers never mix old and new state. */
    private static final class HookSnapshot {
        final Map<String, List<HookEntry>> exact;
        final List<HookEntry> substring;
        final ConcurrentHashMap<String, List<HookEntry>> resolved = new ConcurrentHashMap<>();

        HookSnapshot(Map<String, List<HookEntry>> exact, List<HookEntry> substring) {
            this.exact = exact;
            this.substring = substring;
        }
    }

    private volatile HookSnapshot hookSnapshot = new HookSnapshot(Collections.emptyMap(), Collections.emptyList());
    /** Send-message hooks sorted by priority; replaced as a whole, never mutated in place. */
    private volatile List<HookEntry> sendHooks = Collections.emptyList();
    private final CopyOnWriteArrayList<String> updatesHookPlugins = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PluginMenuItem> menuItems = new CopyOnWriteArrayList<>();

    private volatile boolean started;
    private volatile boolean engineFailed;
    private volatile String engineError;

    private static final class HookEntry {
        final String pluginId;
        final String name;
        final boolean substring;
        final int priority;

        HookEntry(String pluginId, String name, boolean substring, int priority) {
            this.pluginId = pluginId;
            this.name = name;
            this.substring = substring;
            this.priority = priority;
        }
    }

    public static PluginsController getInstance() {
        if (instance == null) {
            synchronized (PluginsController.class) {
                if (instance == null) {
                    instance = new PluginsController();
                }
            }
        }
        return instance;
    }

    public static void setEngine(PluginEngine pluginEngine) {
        engine = pluginEngine;
    }

    public static PluginEngine getEngine() {
        return engine;
    }

    public static boolean isEngineAvailable() {
        return engine != null;
    }

    public static boolean hasRequestHooks() {
        return hasHooks;
    }

    public static boolean hasUpdateHooks() {
        return hasHooks || hasUpdatesHooks;
    }

    public static boolean hasSendMessageHooks() {
        return hasSendHooks;
    }

    // region Files & preferences

    public static File getPluginsDir() {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "plugins");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static SharedPreferences statePrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("solegram_plugins", Context.MODE_PRIVATE);
    }

    private static SharedPreferences settingsPrefs(String pluginId) {
        return ApplicationLoader.applicationContext.getSharedPreferences("solegram_plugin_" + pluginId, Context.MODE_PRIVATE);
    }

    public boolean isEnabledInPrefs(String pluginId) {
        return statePrefs().getBoolean("enabled_" + pluginId, true);
    }

    /** Raw JSON-encoded setting value, or null. Used by the Python SDK (plugin_settings). */
    public static String getSettingJson(String pluginId, String key) {
        return settingsPrefs(pluginId).getString(key, null);
    }

    public static void setSettingJson(String pluginId, String key, String json) {
        SharedPreferences.Editor e = settingsPrefs(pluginId).edit();
        if (json == null) {
            e.remove(key);
        } else {
            e.putString(key, json);
        }
        e.apply();
    }

    /** All settings of a plugin as a JSON object {key: json-value-string}. */
    public static String getAllSettingsJson(String pluginId) {
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, ?> entry : settingsPrefs(pluginId).getAll().entrySet()) {
                obj.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
            return obj.toString();
        } catch (Exception e) {
            FileLog.e(e);
            return "{}";
        }
    }

    public static void clearSettings(String pluginId) {
        settingsPrefs(pluginId).edit().clear().apply();
    }

    public static void notifySettingsReload(String pluginId) {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.solegramPluginsChanged, pluginId, "settings"));
    }
    // endregion

    // region Lifecycle

    /** Boots the engine and loads enabled plugins in the background. */
    public void init() {
        if (started) {
            return;
        }
        started = true;
        pluginsQueue.postRunnable(() -> {
            scanPlugins();
            if (!SolegramConfig.pluginsEngine || engine == null) {
                return;
            }
            SharedPreferences prefs = statePrefs();
            checkCrashLoop(prefs);
            PluginGuard.refreshBlocklist(false, null);
            if (SolegramConfig.pluginsSafeMode) {
                return;
            }
            boolean anyEnabled = false;
            for (PluginMeta meta : getPlugins()) {
                if (meta.enabled) {
                    anyEnabled = true;
                    break;
                }
            }
            if (!anyEnabled && !SolegramConfig.pluginsDevMode) {
                // The Python runtime is started lazily when a plugin is installed or enabled.
                return;
            }
            if (!startEngine()) {
                return;
            }
            prefs.edit().putBoolean("loading_in_progress", true).commit();
            String lastLoaded = null;
            for (PluginMeta meta : getPlugins()) {
                if (meta.enabled) {
                    // committed before the plugin runs: a crash inside it names the culprit next time
                    prefs.edit().putString("loading_plugin", meta.id).commit();
                    loadInternal(meta);
                    if (meta.loaded) {
                        lastLoaded = meta.id;
                    }
                }
            }
            prefs.edit()
                .putBoolean("loading_in_progress", false)
                .putString("last_loaded_plugin", lastLoaded)
                .putBoolean("boot_window", lastLoaded != null)
                .putLong("boot_time", System.currentTimeMillis())
                .putBoolean("crashed", false)
                .commit();
            if (lastLoaded != null) {
                watchStartup();
            }
            dispatchAppEvent(APP_EVENT_START);
        });
    }

    public boolean wasSafeModeEnabledAutomatically() {
        return statePrefs().getBoolean("safe_mode_auto", false);
    }

    public void clearAutoSafeModeFlag() {
        statePrefs().edit().remove("safe_mode_auto").remove("safe_mode_plugin").remove("safe_mode_reason").apply();
    }

    public static final int SAFE_MODE_CRASH_WHILE_LOADING = 1;
    public static final int SAFE_MODE_CRASH_LOOP = 2;

    /** Plugin that was loading (or loaded last) when the crash that turned safe mode on happened, or null. */
    public String getSafeModePlugin() {
        return statePrefs().getString("safe_mode_plugin", null);
    }

    /** SAFE_MODE_CRASH_*, or 0 when safe mode was not turned on automatically. */
    public int getSafeModeReason() {
        return statePrefs().getInt("safe_mode_reason", 0);
    }

    // region Crash-loop protection
    // A start that crashes while loading a plugin turns safe mode on at once. A start that crashes within
    // STABLE_AFTER of loading plugins is a strike; two strikes in a row turn safe mode on as well.

    private static final long STABLE_AFTER = 20_000;
    private static final int STRIKES_FOR_SAFE_MODE = 2;
    private static volatile boolean crashHandlerInstalled;

    private void checkCrashLoop(SharedPreferences prefs) {
        if (prefs.getBoolean("loading_in_progress", false)) {
            FileLog.e("Solegram: previous plugin load did not finish, enabling safe mode");
            enterSafeMode(prefs, prefs.getString("loading_plugin", null), SAFE_MODE_CRASH_WHILE_LOADING);
            return;
        }
        if (!prefs.getBoolean("boot_window", false)) {
            return;
        }
        // the previous start loaded plugins and ended before it was stable: count it only if it really crashed
        boolean crashed = prefs.getBoolean("crashed", false) || PluginGuard.lastExitWasCrash(prefs.getLong("boot_time", 0));
        int strikes = crashed ? prefs.getInt("crash_strikes", 0) + 1 : 0;
        prefs.edit().putInt("crash_strikes", strikes).putBoolean("boot_window", false).putBoolean("crashed", false).commit();
        if (strikes >= STRIKES_FOR_SAFE_MODE) {
            FileLog.e("Solegram: " + strikes + " crashes in a row after loading plugins, enabling safe mode");
            enterSafeMode(prefs, prefs.getString("last_loaded_plugin", null), SAFE_MODE_CRASH_LOOP);
        }
    }

    private void enterSafeMode(SharedPreferences prefs, String pluginId, int reason) {
        SolegramConfig.putBoolean("pluginsSafeMode", true);
        SharedPreferences.Editor e = prefs.edit()
            .putBoolean("loading_in_progress", false)
            .putBoolean("boot_window", false)
            .putInt("crash_strikes", 0)
            .putBoolean("safe_mode_auto", true)
            .putInt("safe_mode_reason", reason);
        if (pluginId != null) {
            e.putString("safe_mode_plugin", pluginId);
        } else {
            e.remove("safe_mode_plugin");
        }
        e.commit();
        // the plugins page explains which plugin and offers to turn it off
        AndroidUtilities.runOnUIThread(com.solegram.messenger.ui.PluginSecuritySheet::showSafeModeNotice, 2500);
    }

    /** Marks crashes during the first seconds after plugins loaded, then declares the start stable. */
    private void watchStartup() {
        if (!crashHandlerInstalled) {
            crashHandlerInstalled = true;
            final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                try {
                    SharedPreferences prefs = statePrefs();
                    if (prefs.getBoolean("boot_window", false)) {
                        prefs.edit().putBoolean("crashed", true).commit();
                    }
                } catch (Throwable ignore) {
                }
                if (previous != null) {
                    previous.uncaughtException(thread, error);
                }
            });
        }
        pluginsQueue.postRunnable(() -> statePrefs().edit().putBoolean("boot_window", false).putInt("crash_strikes", 0).apply(), STABLE_AFTER);
    }
    // endregion

    private boolean startEngine() {
        if (engineFailed) {
            return false;
        }
        try {
            engine.start();
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            engineFailed = true;
            engineError = e.getMessage();
            return false;
        }
    }

    public String getEngineError() {
        return engineError;
    }

    public boolean isEngineRunning() {
        return engine != null && !engineFailed && SolegramConfig.pluginsEngine && !SolegramConfig.pluginsSafeMode;
    }

    private void scanPlugins() {
        File[] files = getPluginsDir().listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            String name = f.getName();
            if (!f.isFile() || !(name.endsWith(".py") || name.endsWith(".plugin"))) {
                continue;
            }
            try {
                PluginMeta meta = readMetadata(f);
                if (meta == null || !meta.isIdValid()) {
                    continue;
                }
                meta.enabled = isEnabledInPrefs(meta.id);
                updateReport(meta, false);
                meta.blocked = PluginGuard.blockReason(meta.report);
                plugins.put(meta.id, meta);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        postChanged(null);
    }

    private PluginMeta readMetadata(File f) throws Exception {
        PluginMeta meta = PluginMetaParser.parse(f);
        if ((meta == null || meta.id == null) && engine != null) {
            meta = engine.readMetadata(f);
        }
        if (meta != null) {
            meta.file = f;
        }
        return meta;
    }

    public List<PluginMeta> getPlugins() {
        synchronized (plugins) {
            ArrayList<PluginMeta> list = new ArrayList<>(plugins.values());
            Collections.sort(list, (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name != null ? a.name : a.id, b.name != null ? b.name : b.id));
            return list;
        }
    }

    public PluginMeta getPlugin(String id) {
        return plugins.get(id);
    }

    private void loadInternal(PluginMeta meta) {
        if (meta.loaded || engine == null) {
            return;
        }
        if (!passesSecurity(meta)) {
            return;
        }
        try {
            meta.error = null;
            if (meta.requirements != null && !meta.requirements.isEmpty()) {
                String err = engine.installRequirements(meta);
                if (err != null) {
                    meta.error = err;
                    postChanged(meta.id);
                    return;
                }
            }
            meta.handle = engine.load(meta);
            meta.loaded = true;
        } catch (Throwable e) {
            FileLog.e(e);
            meta.error = describeError(e);
            meta.loaded = false;
            meta.handle = null;
            removeAllFor(meta.id);
        }
        postChanged(meta.id);
    }

    // region Security

    /** (Re)computes the security report of the plugin's current file; cheap when the file did not change. */
    private void updateReport(PluginMeta meta, boolean force) {
        if (meta.file == null || !meta.file.exists()) {
            return;
        }
        try {
            PluginSecurity.Report report = PluginGuard.scan(meta.file, force);
            meta.report = report;
            meta.sha256 = report.sha256;
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Checked right before a plugin's code would run: refuses blocklisted and (if so set) dangerous plugins. */
    private boolean passesSecurity(PluginMeta meta) {
        updateReport(meta, false);
        int block = PluginGuard.blockReason(meta.report);
        meta.blocked = block;
        if (block == PluginGuard.BLOCK_NONE) {
            return true;
        }
        // refused plugins stay off until the user decides otherwise
        boolean wasEnabled = meta.enabled;
        meta.enabled = false;
        statePrefs().edit().putBoolean("enabled_" + meta.id, false).apply();
        postChanged(meta.id);
        if (wasEnabled) {
            String name = meta.name != null ? meta.name : meta.id;
            AndroidUtilities.runOnUIThread(() -> com.solegram.messenger.ui.PluginSecuritySheet.showRefusedNotice(name, block));
        }
        return false;
    }

    /** The blocklist changed: installed plugins that are now on it are stopped and turned off. */
    void onBlocklistUpdated() {
        pluginsQueue.postRunnable(() -> {
            for (PluginMeta meta : getPlugins()) {
                if (meta.sha256 == null || !PluginGuard.isBlocklisted(meta.sha256)) {
                    if (meta.blocked == PluginGuard.BLOCK_LIST) {
                        meta.blocked = PluginGuard.blockReason(meta.report);
                        postChanged(meta.id);
                    }
                    continue;
                }
                meta.blocked = PluginGuard.BLOCK_LIST;
                boolean wasEnabled = meta.enabled;
                unloadInternal(meta);
                meta.enabled = false;
                statePrefs().edit().putBoolean("enabled_" + meta.id, false).apply();
                postChanged(meta.id);
                if (wasEnabled) {
                    String name = meta.name != null ? meta.name : meta.id;
                    AndroidUtilities.runOnUIThread(() -> com.solegram.messenger.ui.PluginSecuritySheet.showRefusedNotice(name, PluginGuard.BLOCK_LIST));
                }
            }
        });
    }

    /** "Block dangerous plugins" changed: running plugins that are now refused are stopped and turned off. */
    public void applySecurityPolicy() {
        pluginsQueue.postRunnable(() -> {
            for (PluginMeta meta : getPlugins()) {
                meta.blocked = PluginGuard.blockReason(meta.report);
                if (meta.blocked != PluginGuard.BLOCK_NONE && meta.enabled) {
                    unloadInternal(meta);
                    passesSecurity(meta);
                }
            }
            postChanged(null);
        });
    }

    public interface RescanCallback {
        void onResult(PluginMeta meta);
    }

    /** "Check again": analyzes the file anew and downloads the blocklist now. Callback on the UI thread. */
    public void rescan(String id, RescanCallback callback) {
        PluginMeta meta = plugins.get(id);
        if (meta == null) {
            return;
        }
        PluginGuard.runInBackground(() -> {
            updateReport(meta, true);
            // loads the cached list here, so the check below on the UI thread reads no file
            PluginGuard.blockReason(meta.report);
            PluginGuard.refreshBlocklist(true, () -> {
                meta.blocked = PluginGuard.blockReason(meta.report);
                if (meta.blocked == PluginGuard.BLOCK_LIST && meta.enabled) {
                    setEnabled(meta.id, false);
                }
                postChanged(meta.id);
                callback.onResult(meta);
            });
        });
    }
    // endregion

    private void unloadInternal(PluginMeta meta) {
        if (!meta.loaded) {
            return;
        }
        // Detach first so network/update threads stop calling into the plugin while it unloads.
        PluginHandle handle = meta.handle;
        removeAllFor(meta.id);
        meta.loaded = false;
        meta.handle = null;
        try {
            if (engine != null) {
                engine.unload(meta, handle);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        postChanged(meta.id);
    }

    public void setEnabled(String id, boolean enabled) {
        PluginMeta meta = plugins.get(id);
        if (meta == null) {
            return;
        }
        meta.enabled = enabled;
        statePrefs().edit().putBoolean("enabled_" + id, enabled).apply();
        pluginsQueue.postRunnable(() -> {
            if (enabled) {
                if (isEngineRunningOrStart()) {
                    loadInternal(meta);
                }
            } else {
                unloadInternal(meta);
            }
        });
    }

    private boolean isEngineRunningOrStart() {
        if (engine == null || !SolegramConfig.pluginsEngine || SolegramConfig.pluginsSafeMode) {
            return false;
        }
        return startEngine();
    }

    public void reloadPlugin(String id) {
        PluginMeta meta = plugins.get(id);
        if (meta == null) {
            return;
        }
        pluginsQueue.postRunnable(() -> {
            unloadInternal(meta);
            try {
                PluginMeta fresh = readMetadata(meta.file);
                if (fresh != null) {
                    copyMeta(fresh, meta);
                }
                updateReport(meta, false);
            } catch (Throwable e) {
                meta.error = describeError(e);
            }
            if (meta.enabled && isEngineRunningOrStart()) {
                loadInternal(meta);
            }
        });
    }

    /** Applies the engine on/off and safe mode switches. */
    public void applyEngineState() {
        pluginsQueue.postRunnable(() -> {
            boolean shouldRun = SolegramConfig.pluginsEngine && !SolegramConfig.pluginsSafeMode;
            for (PluginMeta meta : getPlugins()) {
                if (shouldRun && meta.enabled) {
                    if (isEngineRunningOrStart()) {
                        loadInternal(meta);
                    }
                } else {
                    unloadInternal(meta);
                }
            }
        });
    }

    public interface InstallCallback {
        void onResult(PluginMeta meta, String error);
    }

    /** Reads metadata of a candidate file without installing it. */
    public void inspect(File source, InstallCallback callback) {
        pluginsQueue.postRunnable(() -> {
            PluginMeta meta = null;
            String error = null;
            try {
                meta = readMetadata(source);
                if (meta == null || !meta.isIdValid()) {
                    error = "invalid_id";
                }
            } catch (Throwable e) {
                FileLog.e(e);
                error = describeError(e);
            }
            final PluginMeta m = meta;
            final String err = error;
            AndroidUtilities.runOnUIThread(() -> callback.onResult(m, err));
        });
    }

    /** Copies the plugin into the plugins directory and enables it. */
    public void install(File source, InstallCallback callback) {
        install(source, false, callback);
    }

    /**
     * @param reviewed the user has seen and accepted the security report of this file, so enabling it
     *                 later does not ask again
     */
    public void install(File source, boolean reviewed, InstallCallback callback) {
        pluginsQueue.postRunnable(() -> {
            String error = null;
            PluginMeta meta = null;
            try {
                PluginMeta parsed = readMetadata(source);
                if (parsed == null || !parsed.isIdValid()) {
                    throw new IllegalArgumentException("invalid_id");
                }
                // Solegram: verified before the installed version is touched, whoever asks (UI, dev server, another plugin)
                PluginSecurity.Report report = PluginGuard.scan(source, false);
                if (PluginGuard.isBlocklisted(report.sha256)) {
                    throw new SecurityException(PluginGuard.ERROR_BLOCKLISTED);
                }
                if (SolegramConfig.pluginsBlockDangerous && report.verdict == PluginSecurity.VERDICT_DANGER) {
                    throw new SecurityException(PluginGuard.ERROR_DANGEROUS);
                }
                PluginMeta existing = plugins.get(parsed.id);
                if (existing != null) {
                    unloadInternal(existing);
                    if (existing.file != null && existing.file.exists()) {
                        existing.file.delete();
                    }
                }
                File target = new File(getPluginsDir(), parsed.id + ".py");
                copyFile(source, target);
                meta = readMetadata(target);
                meta.enabled = true;
                meta.report = report;
                meta.sha256 = report.sha256;
                if (reviewed) {
                    PluginGuard.markReviewed(meta.id, report.sha256);
                }
                statePrefs().edit().putBoolean("enabled_" + meta.id, true).apply();
                plugins.put(meta.id, meta);
                if (isEngineRunningOrStart()) {
                    loadInternal(meta);
                }
                error = meta.error;
            } catch (Throwable e) {
                FileLog.e(e);
                error = describeError(e);
            }
            final PluginMeta m = meta;
            final String err = error;
            postChanged(m != null ? m.id : null);
            AndroidUtilities.runOnUIThread(() -> callback.onResult(m, err));
        });
    }

    public void delete(String id, Runnable done) {
        PluginMeta meta = plugins.get(id);
        if (meta == null) {
            return;
        }
        pluginsQueue.postRunnable(() -> {
            unloadInternal(meta);
            if (meta.file != null) {
                meta.file.delete();
            }
            plugins.remove(id);
            clearSettings(id);
            if (engine != null && !engineFailed) {
                try {
                    engine.onPluginDeleted(id);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            statePrefs().edit().remove("enabled_" + id).apply();
            PluginGuard.forget(id);
            postChanged(id);
            if (done != null) {
                AndroidUtilities.runOnUIThread(done);
            }
        });
    }

    public static boolean isPluginFileName(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase();
        return lower.endsWith(".plugin");
    }

    private static void copyFile(File from, File to) throws Exception {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
        }
    }

    private static void copyMeta(PluginMeta from, PluginMeta to) {
        to.name = from.name;
        to.description = from.description;
        to.author = from.author;
        to.version = from.version;
        to.icon = from.icon;
        to.appVersion = from.appVersion;
        to.sdkVersion = from.sdkVersion;
        to.requirements = from.requirements;
    }

    private static String describeError(Throwable e) {
        String msg = e.getMessage();
        if (TextUtils.isEmpty(msg)) {
            msg = e.getClass().getSimpleName();
        }
        // Python tracebacks are long: keep the last meaningful line for the UI.
        String[] lines = msg.trim().split("\n");
        return lines[lines.length - 1].trim();
    }

    private void postChanged(String pluginId) {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.solegramPluginsChanged, pluginId, "state"));
    }

    public void applyDevMode() {
        boolean enabled = SolegramConfig.pluginsDevMode;
        pluginsQueue.postRunnable(() -> {
            if (engine == null || engineFailed) {
                return;
            }
            if (enabled && isEngineRunningOrStart()) {
                // a fresh start already picks the flag up; setDevMode is idempotent
                engine.setDevMode(true);
            } else if (!enabled) {
                engine.setDevMode(false);
            }
        });
    }

    public void dispatchAppEvent(String event) {
        if (!hasLoadedPlugins()) {
            return;
        }
        pluginsQueue.postRunnable(() -> {
            for (PluginMeta meta : getPlugins()) {
                PluginHandle h = meta.handle;
                if (meta.loaded && h != null) {
                    try {
                        h.onAppEvent(event);
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                }
            }
        });
    }

    public boolean hasLoadedPlugins() {
        synchronized (plugins) {
            for (PluginMeta meta : plugins.values()) {
                if (meta.loaded) {
                    return true;
                }
            }
        }
        return false;
    }

    public int getLoadedCount() {
        int c = 0;
        synchronized (plugins) {
            for (PluginMeta meta : plugins.values()) {
                if (meta.loaded) {
                    c++;
                }
            }
        }
        return c;
    }
    // endregion

    // region Hook registry (called from the plugin SDK)

    public static void registerHook(String pluginId, String name, boolean matchSubstring, int priority) {
        PluginsController c = getInstance();
        synchronized (c.hooksLock) {
            List<HookEntry> list = c.hooksByPlugin.get(pluginId);
            if (list == null) {
                list = new ArrayList<>();
                c.hooksByPlugin.put(pluginId, list);
            }
            for (Iterator<HookEntry> it = list.iterator(); it.hasNext(); ) {
                if (it.next().name.equals(name)) {
                    it.remove();
                }
            }
            list.add(new HookEntry(pluginId, name, matchSubstring, priority));
            c.rebuildHooks();
        }
    }

    public static void unregisterHook(String pluginId, String name) {
        PluginsController c = getInstance();
        synchronized (c.hooksLock) {
            List<HookEntry> list = c.hooksByPlugin.get(pluginId);
            if (list != null) {
                for (Iterator<HookEntry> it = list.iterator(); it.hasNext(); ) {
                    if (it.next().name.equals(name)) {
                        it.remove();
                    }
                }
            }
            c.rebuildHooks();
        }
    }

    public static void registerSendMessageHook(String pluginId, int priority) {
        PluginsController c = getInstance();
        synchronized (c.hooksLock) {
            ArrayList<HookEntry> list = new ArrayList<>();
            for (HookEntry e : c.sendHooks) {
                if (!e.pluginId.equals(pluginId)) {
                    list.add(e);
                }
            }
            list.add(new HookEntry(pluginId, "send_message", false, priority));
            Collections.sort(list, (a, b) -> Integer.compare(b.priority, a.priority));
            c.sendHooks = Collections.unmodifiableList(list);
            hasSendHooks = !list.isEmpty();
        }
    }

    public static void unregisterSendMessageHook(String pluginId) {
        PluginsController c = getInstance();
        synchronized (c.hooksLock) {
            ArrayList<HookEntry> list = new ArrayList<>();
            for (HookEntry e : c.sendHooks) {
                if (!e.pluginId.equals(pluginId)) {
                    list.add(e);
                }
            }
            c.sendHooks = Collections.unmodifiableList(list);
            hasSendHooks = !list.isEmpty();
        }
    }

    /** Plugins overriding on_updates_hook receive every update container. */
    public static void setUpdatesHook(String pluginId, boolean enabled) {
        PluginsController c = getInstance();
        c.updatesHookPlugins.remove(pluginId);
        if (enabled) {
            c.updatesHookPlugins.add(pluginId);
        }
        hasUpdatesHooks = !c.updatesHookPlugins.isEmpty();
    }

    private void rebuildHooks() {
        HashMap<String, List<HookEntry>> exact = new HashMap<>();
        ArrayList<HookEntry> substr = new ArrayList<>();
        for (List<HookEntry> list : hooksByPlugin.values()) {
            for (HookEntry e : list) {
                if (e.substring) {
                    substr.add(e);
                } else {
                    List<HookEntry> l = exact.get(e.name);
                    if (l == null) {
                        l = new ArrayList<>();
                        exact.put(e.name, l);
                    }
                    l.add(e);
                }
            }
        }
        hookSnapshot = new HookSnapshot(exact, substr);
        hasHooks = !exact.isEmpty() || !substr.isEmpty();
    }

    private void removeAllFor(String pluginId) {
        synchronized (hooksLock) {
            hooksByPlugin.remove(pluginId);
            rebuildHooks();
        }
        unregisterSendMessageHook(pluginId);
        setUpdatesHook(pluginId, false);
        for (PluginMenuItem item : menuItems) {
            if (pluginId.equals(item.pluginId)) {
                menuItems.remove(item);
            }
        }
    }

    private static final List<HookEntry> NO_HOOKS = Collections.emptyList();

    private List<HookEntry> hooksFor(String name) {
        HookSnapshot snap = hookSnapshot;
        List<HookEntry> cached = snap.resolved.get(name);
        if (cached != null) {
            return cached;
        }
        ArrayList<HookEntry> result = new ArrayList<>();
        List<HookEntry> exact = snap.exact.get(name);
        if (exact != null) {
            result.addAll(exact);
        }
        for (HookEntry e : snap.substring) {
            if (name.contains(e.name)) {
                result.add(e);
            }
        }
        Collections.sort(result, (a, b) -> Integer.compare(b.priority, a.priority));
        List<HookEntry> value = result.isEmpty() ? NO_HOOKS : result;
        snap.resolved.put(name, value);
        return value;
    }

    private PluginHandle handleOf(String pluginId) {
        PluginMeta meta = plugins.get(pluginId);
        return meta != null && meta.loaded ? meta.handle : null;
    }
    // endregion

    // region Dispatch

    private static final ConcurrentHashMap<Class<?>, String> tlNames = new ConcurrentHashMap<>();

    /**
     * Canonical exteraGram-style TL name: TLRPC.TL_messages_sendMessage -> "TL_messages_sendMessage",
     * TL_account.updateStatus -> "TL_account_updateStatus".
     */
    public static String tlName(Object obj) {
        if (obj == null) {
            return "null";
        }
        Class<?> cls = obj.getClass();
        String name = tlNames.get(cls);
        if (name != null) {
            return name;
        }
        String simple = cls.getSimpleName();
        if (simple.startsWith("TL_")) {
            name = simple;
        } else {
            Class<?> outer = cls.getEnclosingClass();
            if (outer != null && outer.getSimpleName().startsWith("TL_")) {
                name = "TL_" + outer.getSimpleName().substring(3) + "_" + simple;
            } else {
                name = simple;
            }
        }
        tlNames.put(cls, name);
        return name;
    }

    public HookResult dispatchPreRequest(int account, Object request) {
        String name = tlName(request);
        List<HookEntry> hooks = hooksFor(name);
        if (hooks.isEmpty()) {
            return null;
        }
        Object current = request;
        HookResult acc = null;
        for (HookEntry e : hooks) {
            PluginHandle h = handleOf(e.pluginId);
            if (h == null) {
                continue;
            }
            HookResult r;
            try {
                r = h.preRequestHook(name, account, current);
            } catch (Throwable t) {
                FileLog.e(t);
                continue;
            }
            if (r == null || r.isDefault()) {
                continue;
            }
            if (r.isCancel()) {
                return r;
            }
            if (r.request != null) {
                current = r.request;
            }
            acc = new HookResult(HookResult.MODIFY);
            acc.request = current;
            if (r.strategy == HookResult.MODIFY_FINAL) {
                break;
            }
        }
        return acc;
    }

    public HookResult dispatchPostRequest(int account, Object request, Object response, Object error) {
        String name = tlName(request);
        List<HookEntry> hooks = hooksFor(name);
        if (hooks.isEmpty()) {
            return null;
        }
        Object curResponse = response;
        Object curError = error;
        HookResult acc = null;
        for (HookEntry e : hooks) {
            PluginHandle h = handleOf(e.pluginId);
            if (h == null) {
                continue;
            }
            HookResult r;
            try {
                r = h.postRequestHook(name, account, curResponse, curError);
            } catch (Throwable t) {
                FileLog.e(t);
                continue;
            }
            if (r == null || r.isDefault()) {
                continue;
            }
            if (r.isCancel()) {
                return r;
            }
            if (r.response != null) {
                curResponse = r.response;
            }
            if (r.error != null) {
                curError = r.error;
            }
            acc = new HookResult(HookResult.MODIFY);
            acc.response = curResponse;
            acc.error = curError;
            if (r.strategy == HookResult.MODIFY_FINAL) {
                break;
            }
        }
        return acc;
    }

    public HookResult dispatchUpdate(int account, Object update) {
        if (!hasHooks) {
            return null;
        }
        String name = tlName(update);
        List<HookEntry> hooks = hooksFor(name);
        if (hooks.isEmpty()) {
            return null;
        }
        Object current = update;
        HookResult acc = null;
        for (HookEntry e : hooks) {
            PluginHandle h = handleOf(e.pluginId);
            if (h == null) {
                continue;
            }
            HookResult r;
            try {
                r = h.onUpdateHook(name, account, current);
            } catch (Throwable t) {
                FileLog.e(t);
                continue;
            }
            if (r == null || r.isDefault()) {
                continue;
            }
            if (r.isCancel()) {
                return r;
            }
            if (r.update != null) {
                current = r.update;
            }
            acc = new HookResult(HookResult.MODIFY);
            acc.update = current;
            if (r.strategy == HookResult.MODIFY_FINAL) {
                break;
            }
        }
        return acc;
    }

    public HookResult dispatchUpdates(int account, Object updates) {
        String name = tlName(updates);
        ArrayList<String> targets = new ArrayList<>(updatesHookPlugins);
        for (HookEntry e : hooksFor(name)) {
            if (!targets.contains(e.pluginId)) {
                targets.add(e.pluginId);
            }
        }
        if (targets.isEmpty()) {
            return null;
        }
        Object current = updates;
        HookResult acc = null;
        for (String pluginId : targets) {
            PluginHandle h = handleOf(pluginId);
            if (h == null) {
                continue;
            }
            HookResult r;
            try {
                r = h.onUpdatesHook(name, account, current);
            } catch (Throwable t) {
                FileLog.e(t);
                continue;
            }
            if (r == null || r.isDefault()) {
                continue;
            }
            if (r.isCancel()) {
                return r;
            }
            if (r.updates != null) {
                current = r.updates;
            }
            acc = new HookResult(HookResult.MODIFY);
            acc.updates = current;
            if (r.strategy == HookResult.MODIFY_FINAL) {
                break;
            }
        }
        return acc;
    }

    public HookResult dispatchSendMessage(int account, Object params) {
        Object current = params;
        HookResult acc = null;
        for (HookEntry e : sendHooks) {
            PluginHandle h = handleOf(e.pluginId);
            if (h == null) {
                continue;
            }
            HookResult r;
            try {
                r = h.onSendMessageHook(account, current);
            } catch (Throwable t) {
                FileLog.e(t);
                continue;
            }
            if (r == null || r.isDefault()) {
                continue;
            }
            if (r.isCancel()) {
                return r;
            }
            if (r.params != null) {
                current = r.params;
            }
            acc = new HookResult(HookResult.MODIFY);
            acc.params = current;
            if (r.strategy == HookResult.MODIFY_FINAL) {
                break;
            }
        }
        return acc;
    }
    // endregion

    // region Menu items

    public static String addMenuItem(String pluginId, String itemId, String menuType, String text, String subtext, String icon, String condition, int priority) {
        PluginsController c = getInstance();
        PluginMenuItem item = new PluginMenuItem();
        item.pluginId = pluginId;
        item.itemId = !TextUtils.isEmpty(itemId) ? itemId : pluginId + "_" + Utilities.random.nextInt(Integer.MAX_VALUE);
        item.menuType = menuType;
        item.text = text;
        item.subtext = subtext;
        item.icon = icon;
        item.condition = condition;
        item.priority = priority;
        for (PluginMenuItem existing : c.menuItems) {
            if (existing.pluginId.equals(pluginId) && existing.itemId.equals(item.itemId)) {
                c.menuItems.remove(existing);
            }
        }
        c.menuItems.add(item);
        return item.itemId;
    }

    public static boolean removeMenuItem(String pluginId, String itemId) {
        PluginsController c = getInstance();
        for (PluginMenuItem existing : c.menuItems) {
            if (existing.pluginId.equals(pluginId) && existing.itemId.equals(itemId)) {
                c.menuItems.remove(existing);
                return true;
            }
        }
        return false;
    }

    /** Visible menu items of a type for the given click context, highest priority first. */
    public List<PluginMenuItem> getMenuItems(String menuType, Map<String, Object> context) {
        ArrayList<PluginMenuItem> result = new ArrayList<>();
        if (menuItems.isEmpty()) {
            return result;
        }
        for (PluginMenuItem item : menuItems) {
            if (!menuType.equals(item.menuType) || handleOf(item.pluginId) == null) {
                continue;
            }
            if (!TextUtils.isEmpty(item.condition) && engine != null) {
                try {
                    if (!engine.evalCondition(item.condition, context)) {
                        continue;
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            result.add(item);
        }
        Collections.sort(result, (a, b) -> Integer.compare(b.priority, a.priority));
        return result;
    }

    public void onMenuItemClick(PluginMenuItem item, Map<String, Object> context) {
        PluginHandle h = handleOf(item.pluginId);
        if (h == null) {
            return;
        }
        try {
            h.onMenuItemClick(item.itemId, context);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
    // endregion

    // region Helpers

    public static int resolveDrawable(String name) {
        if (TextUtils.isEmpty(name)) {
            return 0;
        }
        try {
            Context ctx = ApplicationLoader.applicationContext;
            return ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
        } catch (Throwable e) {
            return 0;
        }
    }

    /** Exports the list of installed plugins with their enabled flags and settings. */
    public String exportState() {
        try {
            JSONArray arr = new JSONArray();
            for (PluginMeta meta : getPlugins()) {
                JSONObject o = new JSONObject();
                o.put("id", meta.id);
                o.put("enabled", meta.enabled);
                o.put("settings", new JSONObject(getAllSettingsJson(meta.id)));
                arr.put(o);
            }
            return arr.toString();
        } catch (Exception e) {
            FileLog.e(e);
            return "[]";
        }
    }
    // endregion
}
