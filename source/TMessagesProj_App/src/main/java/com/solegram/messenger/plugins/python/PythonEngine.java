/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.plugins.python;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.solegram.messenger.SolegramConfig;
import com.solegram.messenger.plugins.PluginEngine;
import com.solegram.messenger.plugins.PluginHandle;
import com.solegram.messenger.plugins.PluginMeta;
import com.solegram.messenger.plugins.PluginsController;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Plugin engine backed by Chaquopy (CPython 3.11) running the exteraGram-compatible plugin SDK. */
public class PythonEngine implements PluginEngine {

    public static final String SDK_VERSION = "1.4.5.0";

    private volatile PyObject runtime;
    private volatile boolean mvelReady;

    @Override
    public synchronized void start() throws Exception {
        if (runtime != null) {
            return;
        }
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(ApplicationLoader.applicationContext));
        }
        PyObject module = Python.getInstance().getModule("solegram_runtime");
        module.callAttr("init",
            PluginsController.getPluginsDir().getAbsolutePath(),
            new File(ApplicationLoader.applicationContext.getFilesDir(), "plugin_libs").getAbsolutePath(),
            new File(ApplicationLoader.applicationContext.getCacheDir(), "plugin_dex").getAbsolutePath(),
            BuildConfig.BUILD_VERSION_STRING,
            SDK_VERSION,
            SolegramConfig.pluginsDevMode);
        runtime = module;
    }

    private PyObject rt() throws Exception {
        if (runtime == null) {
            start();
        }
        return runtime;
    }

    @Override
    public PluginMeta readMetadata(File file) throws Exception {
        PyObject dict = rt().callAttr("read_metadata", file.getAbsolutePath());
        if (dict == null) {
            return null;
        }
        Map<PyObject, PyObject> map = dict.asMap();
        PluginMeta meta = new PluginMeta();
        meta.id = str(map, "id");
        meta.name = str(map, "name");
        meta.description = orEmpty(str(map, "description"));
        meta.author = orEmpty(str(map, "author"));
        meta.version = str(map, "version") != null ? str(map, "version") : "1.0";
        meta.icon = str(map, "icon");
        meta.appVersion = str(map, "app_version");
        meta.sdkVersion = str(map, "sdk_version");
        PyObject req = map.get(PyObject.fromJava("requirements"));
        ArrayList<String> list = new ArrayList<>();
        if (req != null) {
            for (PyObject o : req.asList()) {
                list.add(o.toString());
            }
        }
        meta.requirements = list;
        meta.file = file;
        return meta;
    }

    private static String str(Map<PyObject, PyObject> map, String key) {
        PyObject v = map.get(PyObject.fromJava(key));
        return v == null ? null : v.toString();
    }

    private static String orEmpty(String s) {
        return s == null || "None".equals(s) ? "" : s;
    }

    @Override
    public String installRequirements(PluginMeta meta) {
        try {
            PyObject result = rt().callAttr("install_requirements", meta.id, meta.requirements.toArray(new String[0]));
            return result == null ? null : result.toString();
        } catch (Throwable e) {
            FileLog.e(e);
            return e.getMessage();
        }
    }

    @Override
    public PluginHandle load(PluginMeta meta) throws Exception {
        PyObject handle = rt().callAttr("load_plugin", meta.id, meta.file.getAbsolutePath());
        return handle.toJava(PluginHandle.class);
    }

    @Override
    public void unload(PluginMeta meta, PluginHandle handle) {
        try {
            rt().callAttr("unload_plugin", meta.id);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void setDevMode(boolean enabled) {
        try {
            if (runtime != null) {
                runtime.callAttr("set_dev_mode", enabled);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onPluginDeleted(String pluginId) {
        try {
            if (runtime != null) {
                runtime.callAttr("forget_plugin", pluginId);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public boolean evalCondition(String expression, Map<String, Object> context) {
        try {
            if (!mvelReady) {
                org.mvel2.optimizers.OptimizerFactory.setDefaultOptimizer("reflective");
                mvelReady = true;
            }
            Object result = org.mvel2.MVEL.eval(expression, context);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (Throwable e) {
            FileLog.e(e);
            return true;
        }
    }

    @Override
    public String describe() {
        String python = "Python 3.11";
        try {
            if (runtime != null) {
                python = "Python " + runtime.callAttr("python_version").toString();
            }
        } catch (Throwable ignore) {
        }
        return python + " · SDK " + SDK_VERSION;
    }

    @Override
    public String sdkVersion() {
        return SDK_VERSION;
    }

    /** Converts a Python list of plugin setting objects into Java (used by the runtime). */
    public static List<Object> toList(PyObject list) {
        ArrayList<Object> out = new ArrayList<>();
        if (list == null) {
            return out;
        }
        for (PyObject o : list.asList()) {
            out.add(o == null ? null : o.toJava(Object.class));
        }
        return out;
    }
}
