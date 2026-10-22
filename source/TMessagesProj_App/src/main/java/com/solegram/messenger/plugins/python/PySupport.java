/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.plugins.python;

import org.telegram.messenger.FileLog;

import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;

/** Static helpers used by the Python plugin SDK. */
public final class PySupport {

    private static volatile boolean mvelReady;

    private PySupport() {
    }

    private static void ensureMvel() {
        if (!mvelReady) {
            org.mvel2.optimizers.OptimizerFactory.setDefaultOptimizer("reflective");
            mvelReady = true;
        }
    }

    /**
     * HookFilter.Condition: evaluates an MVEL expression where {@code this} is the hooked object,
     * {@code param} the MethodHookParam and {@code object} the value passed to the filter.
     */
    public static boolean evalCondition(String expression, XC_MethodHook.MethodHookParam param, Object object) {
        try {
            ensureMvel();
            Map<String, Object> vars = new HashMap<>();
            vars.put("param", param);
            vars.put("object", object);
            vars.put("args", param.args);
            Object ctx = param.thisObject != null ? param.thisObject : param;
            Object result = org.mvel2.MVEL.eval(expression, ctx, vars);
            return result instanceof Boolean ? (Boolean) result : result != null;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /** Evaluates an MVEL expression with a context object and variables (class proxy jMVELmethod). */
    public static Object evalMvel(String expression, Object context, Map<String, Object> vars) {
        ensureMvel();
        return org.mvel2.MVEL.eval(expression, context, vars);
    }

    /** Creates a typed Java array (helper for plugins). */
    public static Object[] objectArray(int size) {
        return new Object[size];
    }
}
