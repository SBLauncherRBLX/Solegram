/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.plugins;

import android.content.Context;
import android.os.SystemClock;

import com.solegram.messenger.SolegramConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a running plugin may do, enforced while it runs (the second line after {@link PluginSecurity}).
 * <p>
 * Two parts:
 * <ul>
 * <li><b>Permissions from the report.</b> A plugin may do at run time only the kinds of things the static analysis
 * found in its code and the user accepted: network, a shell, native code, loading dex, running code built from
 * strings, unpickling, installing apps. Code that hides such a call from the analysis (built from strings,
 * decoded at run time) is stopped when it makes it. The Python side (solegram_guard.py) asks {@link #permits}.</li>
 * <li><b>The account shield.</b> Taking over the account (logging in another device by QR, exporting the login,
 * changing the password or the phone, ending sessions, deleting the account) is never done by a plugin, and paying
 * (Stars, gifts, ownership) only when the user allowed plugins to pay. Checked in Java, in
 * {@code ConnectionsManager}, right where the request is sent: the plugin's Python frames are on the stack there
 * whatever helper it used.</li>
 * </ul>
 * Plugins run in Solegram's process, so a plugin written to attack Solegram itself can still get around in-process
 * checks with Java reflection. These checks stop what the analysis could not see and what generic malicious plugins
 * do; the analysis, the blocklist and the user's review stay the first line. See SECURITY.md.
 */
public final class PluginRuntimeGuard {

    public static final String PERM_NETWORK = "network";
    public static final String PERM_SHELL = "shell";
    public static final String PERM_NATIVE = "native";
    public static final String PERM_DEX = "dex";
    public static final String PERM_EXEC = "exec";
    public static final String PERM_DESERIALIZE = "deserialize";
    public static final String PERM_INSTALL = "install";

    /** Error text a blocked request gets back (the plugin's callback still runs). */
    public static final String BLOCKED_ERROR = "SOLEGRAM_PLUGIN_BLOCKED";

    /** Taking over the account: never from a plugin. */
    private static final Set<String> ACCOUNT_TAKEOVER = new HashSet<>(java.util.Arrays.asList(
        "TL_auth_acceptLoginToken", "TL_auth_exportLoginToken", "TL_auth_importLoginToken",
        "TL_auth_exportAuthorization", "TL_auth_importAuthorization", "TL_auth_importBotAuthorization",
        "TL_auth_resetAuthorizations", "TL_auth_logOut", "TL_auth_checkPassword", "TL_auth_recoverPassword",
        "TL_auth_requestPasswordRecovery", "TL_auth_cancelCode", "TL_auth_resetLoginEmail",
        "TL_account_resetAuthorization", "TL_account_setAuthorizationTTL", "TL_account_changeAuthorizationSettings",
        "TL_account_getPasswordSettings", "TL_account_updatePasswordSettings", "TL_account_getTmpPassword",
        "TL_account_confirmPasswordEmail", "TL_account_resendPasswordEmail", "TL_account_cancelPasswordEmail",
        "TL_account_resetPassword", "TL_account_declinePasswordReset",
        "TL_account_deleteAccount", "TL_account_setAccountTTL", "TL_account_sendChangePhoneCode", "TL_account_changePhone",
        "TL_account_resetWebAuthorization", "TL_account_resetWebAuthorizations",
        "TL_account_sendVerifyEmailCode", "TL_account_verifyEmail",
        "TL_channels_editCreator"));

    private static final ConcurrentHashMap<String, Set<String>> permissions = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> lastNotice = new ConcurrentHashMap<>();
    private static final long NOTICE_INTERVAL = 30_000;
    private static final int MAX_EVENTS = 64;
    private static final List<Event> events = Collections.synchronizedList(new ArrayList<>());

    private PluginRuntimeGuard() {
    }

    // region Permissions from the report

    /** The plugin is about to run: its permissions are what its accepted report shows. */
    static void register(PluginMeta meta) {
        if (meta == null || meta.id == null) {
            return;
        }
        permissions.put(meta.id, Collections.unmodifiableSet(permissionsOf(meta.report)));
    }

    static void unregister(String pluginId) {
        if (pluginId != null) {
            permissions.remove(pluginId);
        }
    }

    static Set<String> permissionsOf(PluginSecurity.Report report) {
        HashSet<String> set = new HashSet<>();
        if (report == null) {
            return set;
        }
        if (report.categorySeverity(PluginSecurity.CAT_NETWORK) > 0) {
            set.add(PERM_NETWORK);
        }
        if (report.has("sys_shell")) {
            set.add(PERM_SHELL);
        }
        if (report.has("code_native") || report.has("sys_native_lib")) {
            set.add(PERM_NATIVE);
        }
        if (report.has("sys_dex")) {
            set.add(PERM_DEX);
        }
        if (report.has("code_exec") || report.has("code_exec_static")) {
            set.add(PERM_EXEC);
        }
        if (report.has("code_deserialize")) {
            set.add(PERM_DESERIALIZE);
        }
        if (report.has("sys_install")) {
            set.add(PERM_INSTALL);
        }
        return set;
    }

    /** Asked by solegram_guard.py when code of the plugin is about to do something of this kind. */
    public static boolean permits(String pluginId, String permission) {
        if (pluginId == null || permission == null) {
            return false;
        }
        final Set<String> set = permissions.get(pluginId);
        return set != null && set.contains(permission);
    }

    /** The app's private directories the Python guard protects (canonical paths), in a fixed order. */
    public static String[] protectedRoots() {
        final Context context = ApplicationLoader.applicationContext;
        final File files = context.getFilesDir();
        final File data = files.getParentFile();
        return new String[]{
            canonical(data),                                           // 0: the app's data dir
            canonical(files),                                          // 1: files
            canonical(PluginsController.getPluginsDir()),              // 2: plugins
            canonical(new File(files, "plugin_libs")),                 // 3: plugin packages
            canonical(new File(files, "plugin_security")),             // 4: reports, blocklist
            canonical(new File(files, "chaquopy")),                    // 5: Python itself and the SDK
            canonical(new File(context.getCacheDir(), "plugin_dex")),  // 6: generated classes the runtime loads
            canonical(context.getCodeCacheDir()),                      // 7: code cache
            data != null ? canonical(new File(data, "shared_prefs")) : "",  // 8: preferences (keys, sessions, settings)
            data != null ? canonical(new File(data, "databases")) : "",     // 9: databases
        };
    }

    private static String canonical(File f) {
        if (f == null) {
            return "";
        }
        try {
            return f.getCanonicalPath();
        } catch (Throwable e) {
            return f.getAbsolutePath();
        }
    }
    // endregion

    // region The account shield

    /** Paying, giving away gifts, changing who owns a chat: only when the user allowed plugins to. */
    static boolean isPayment(String name) {
        if (!name.startsWith("TL_payments_")) {
            return false;
        }
        final String m = name.substring("TL_payments_".length());
        // reading (forms, prices, gifts, status) stays allowed
        return !(m.startsWith("get") || m.startsWith("validate") || m.startsWith("check") || m.startsWith("can"));
    }

    static boolean isCritical(Object request) {
        if (!(request instanceof TLObject)) {
            return false;
        }
        final String name = request.getClass().getSimpleName();
        return ACCOUNT_TAKEOVER.contains(name) || isPayment(name);
    }

    private static boolean blockedKind(String name) {
        return ACCOUNT_TAKEOVER.contains(name) || isPayment(name) && !SolegramConfig.pluginsAllowPayments;
    }

    /**
     * Called by ConnectionsManager on the caller's thread before a request is queued: true when a plugin sent a
     * request it may not (it is then not sent).
     */
    public static boolean blockRequest(int account, TLObject request) {
        if (request == null || !PluginsController.isEngineAvailable()) {
            return false;
        }
        final String name = request.getClass().getSimpleName();
        if (!blockedKind(name)) {
            return false;
        }
        if (!PluginsController.getInstance().hasLoadedPlugins()) {
            return false;
        }
        final String plugin = callingPlugin();
        if (plugin == null) {
            return false;
        }
        onBlocked(plugin, "account", name);
        return true;
    }

    /** A plugin's pre_request hook swapped a request for another: not for one it could not send itself. */
    static boolean blockSwap(String pluginId, Object original, Object replacement) {
        if (replacement == null || original == null || replacement.getClass() == original.getClass()) {
            return false;
        }
        if (!(replacement instanceof TLObject) || !blockedKind(replacement.getClass().getSimpleName())) {
            return false;
        }
        onBlocked(pluginId, "account", replacement.getClass().getSimpleName());
        return true;
    }

    /**
     * The plugin whose code made this call: by the Python frames of this thread (when the call came from Python),
     * else null. Python frames on the Java stack alone (no plugin named) still count: only plugins send from Python.
     */
    static String callingPlugin() {
        boolean fromPython = false;
        final StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement e : stack) {
            if (e.getClassName().startsWith("com.chaquo.python.")) {
                fromPython = true;
                break;
            }
        }
        // a thread Python started has no Java frames under the call (no Thread.run, no main loop): it may be a
        // plugin's thread even without the bridge's frames; any Java thread of the app is not, and Python is not
        // asked then (no waiting for the interpreter on the UI thread)
        if (!fromPython && stack.length > 0 && startedByJava(stack[stack.length - 1])) {
            return null;
        }
        String plugin = null;
        final PluginEngine engine = PluginsController.getEngine();
        if (engine != null) {
            try {
                plugin = engine.currentPlugin();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        if (plugin != null) {
            return plugin;
        }
        return fromPython ? "" : null;
    }
    /**
     * Whether the thread was started by Java (its first frame is a thread's run(), the main loop): a thread
     * Python attached has the first Java method Python called there instead.
     */
    private static boolean startedByJava(StackTraceElement bottom) {
        final String cls = bottom.getClassName();
        if (cls.startsWith("com.android.internal.os.") || cls.startsWith("android.app.ActivityThread")) {
            return true;
        }
        if (!"run".equals(bottom.getMethodName())) {
            return false;
        }
        if ("java.lang.Thread".equals(cls)) {
            return true;
        }
        try {
            return Thread.class.isAssignableFrom(Class.forName(cls, false, PluginRuntimeGuard.class.getClassLoader()));
        } catch (Throwable e) {
            return false;
        }
    }
    // endregion

    // region What was stopped

    public static final class Event {
        public final String pluginId;
        public final String kind;
        public final String detail;
        public final long time;

        Event(String pluginId, String kind, String detail) {
            this.pluginId = pluginId;
            this.kind = kind;
            this.detail = detail;
            this.time = System.currentTimeMillis();
        }
    }

    /** Called by the Java shield and by solegram_guard.py when something was stopped. */
    public static void onBlocked(String pluginId, String kind, String detail) {
        final String id = pluginId == null ? "" : pluginId;
        FileLog.d("plugin guard: blocked " + kind + " (" + detail + ") of plugin " + (id.isEmpty() ? "?" : id));
        synchronized (events) {
            events.add(new Event(id, kind, detail));
            while (events.size() > MAX_EVENTS) {
                events.remove(0);
            }
        }
        final String key = id + "/" + kind;
        final long now = SystemClock.elapsedRealtime();
        final Long last = lastNotice.get(key);
        if (last != null && now - last < NOTICE_INTERVAL) {
            return;
        }
        lastNotice.put(key, now);
        AndroidUtilities.runOnUIThread(() -> com.solegram.messenger.ui.PluginSecuritySheet.showBlockedActionNotice(pluginName(id), kind));
    }

    /** What was stopped for this plugin since the app started, newest first. */
    public static List<Event> eventsOf(String pluginId) {
        final ArrayList<Event> list = new ArrayList<>();
        synchronized (events) {
            for (int i = events.size() - 1; i >= 0; i--) {
                final Event e = events.get(i);
                if (pluginId == null || pluginId.equals(e.pluginId)) {
                    list.add(e);
                }
            }
        }
        return list;
    }

    private static String pluginName(String id) {
        if (id == null || id.isEmpty()) {
            return LocaleController.getString(R.string.SG_GuardUnknownPlugin);
        }
        final PluginMeta meta = PluginsController.getInstance().getPlugin(id);
        return meta != null && meta.name != null ? meta.name : id;
    }

    /** A line for the user: what kind of thing was stopped. */
    public static String describeKind(String kind) {
        switch (kind == null ? "" : kind.toLowerCase(Locale.ROOT)) {
            case "account":
                return LocaleController.getString(R.string.SG_GuardKindAccount);
            case "network":
                return LocaleController.getString(R.string.SG_GuardKindNetwork);
            case "shell":
                return LocaleController.getString(R.string.SG_GuardKindShell);
            case "native":
            case "dex":
                return LocaleController.getString(R.string.SG_GuardKindNative);
            case "exec":
            case "deserialize":
                return LocaleController.getString(R.string.SG_GuardKindExec);
            case "secrets":
                return LocaleController.getString(R.string.SG_GuardKindSecrets);
            case "files":
                return LocaleController.getString(R.string.SG_GuardKindFiles);
            case "tamper":
                return LocaleController.getString(R.string.SG_GuardKindTamper);
            case "install":
                return LocaleController.getString(R.string.SG_GuardKindInstall);
            default:
                return kind;
        }
    }
    // endregion
}
