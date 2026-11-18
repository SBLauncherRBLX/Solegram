/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.plugins;

import androidx.annotation.Nullable;

/**
 * Plugins give MVEL expressions to Solegram (menu item conditions, hook filters, methods of generated classes), and
 * MVEL runs Java directly: past the Python guard. An expression that reaches for what the guard would stop (a shell,
 * reflection, class loaders, files, the network, keys, Solegram's own internals, the account's session) is not run.
 */
public final class PluginMvel {

    private PluginMvel() {
    }

    // what such expressions never need (they read the hooked object, its fields and simple values)
    private static final String[] FORBIDDEN = {
        "Runtime", "ProcessBuilder", "ProcessImpl", "forName", "getClass", "ClassLoader", "loadClass", "defineClass",
        "java.lang.reflect", "getDeclared", "getMethod(", "getField(", "getConstructor", "setAccessible", ".invoke(",
        "newInstance", "System.", "Thread", "Unsafe", "dalvik", "solegram", "Solegram", "KeyStore", "keystore",
        "FileInputStream", "FileOutputStream", "RandomAccessFile", "FileReader", "FileWriter", "java.nio", "java.net",
        "Socket", "URLConnection", "HttpURLConnection", "okhttp", "exec(", "Xposed", "xposed", "SQLite", "SharedPreferences",
        "tgnet.dat", "shared_prefs", "cache4", "PackageInstaller", "ParcelFileDescriptor", "android.os.Process",
        "ApiCredentials", "chaquo", "import ", "def ",
    };

    /** Why the expression may not run, or null when it may. */
    @Nullable
    public static String unsafe(String expression) {
        if (expression == null) {
            return null;
        }
        // whitespace and comments do not hide a name: they are dropped before looking
        final String flat = expression.replaceAll("/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        final String squeezed = flat.replaceAll("\\s+", "");
        for (String f : FORBIDDEN) {
            final String needle = f.trim();
            if (flat.contains(f) || squeezed.contains(needle.replaceAll("\\s+", ""))) {
                return needle;
            }
        }
        return null;
    }

    /** True when the expression may run; otherwise the attempt is recorded for the plugin running it. */
    public static boolean allowed(String expression) {
        final String reason = unsafe(expression);
        if (reason == null) {
            return true;
        }
        String plugin = null;
        try {
            plugin = PluginRuntimeGuard.callingPlugin();
        } catch (Throwable ignore) {
        }
        PluginRuntimeGuard.onBlocked(plugin, "exec", "MVEL: " + reason);
        return false;
    }
}
