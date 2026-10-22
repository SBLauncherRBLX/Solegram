/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.plugins.python;

import com.chaquo.python.PyObject;

import org.telegram.messenger.FileLog;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;

/**
 * Bridges Xposed callbacks to Python hook objects (base_plugin.MethodHook / MethodReplacement).
 * Values produced by Python (ints arrive as Long, floats as Double) are coerced to the exact Java
 * types expected by the hooked method, otherwise the ART trampoline would fail to unbox them.
 */
public final class PyXposedHook extends XC_MethodHook {

    private final PyObject target;
    private final String pluginId;

    public PyXposedHook(String pluginId, PyObject target, int priority) {
        super(priority);
        this.pluginId = pluginId;
        this.target = target;
    }

    public PyObject getTarget() {
        return target;
    }

    public String getPluginId() {
        return pluginId;
    }

    @Override
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
        try {
            target.callAttr("_sg_before", param);
        } catch (Throwable e) {
            FileLog.e("plugin " + pluginId + " before hook failed", e);
        }
        coerceArgs(param);
        coerceResult(param);
    }

    @Override
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        try {
            target.callAttr("_sg_after", param);
        } catch (Throwable e) {
            FileLog.e("plugin " + pluginId + " after hook failed", e);
        }
        coerceResult(param);
    }

    /** Replacement variant: the Python method becomes the whole implementation. */
    public static final class Replacement extends XC_MethodReplacement {
        private final PyObject target;
        private final String pluginId;

        public Replacement(String pluginId, PyObject target, int priority) {
            super(priority);
            this.pluginId = pluginId;
            this.target = target;
        }

        public PyObject getTarget() {
            return target;
        }

        @Override
        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
            Object result;
            try {
                PyObject r = target.callAttr("_sg_replace", param);
                result = r == null ? null : r.toJava(Object.class);
            } catch (Throwable e) {
                // The plugin failed: keep the app working by running the original method instead.
                FileLog.e("plugin " + pluginId + " replacement failed", e);
                try {
                    return de.robv.android.xposed.XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                } catch (java.lang.reflect.InvocationTargetException ite) {
                    throw ite.getCause() != null ? ite.getCause() : ite;
                }
            }
            Class<?> type = returnType(param.method);
            if (result == null && type.isPrimitive() && type != void.class) {
                // returning null for a primitive would crash the caller while unboxing
                return PyClassProxy.defaultValue(type);
            }
            return coerce(result, type);
        }
    }

    // region coercion

    static Class<?> returnType(Member member) {
        if (member instanceof Method) {
            return ((Method) member).getReturnType();
        }
        return void.class;
    }

    static Class<?>[] paramTypes(Member member) {
        if (member instanceof Method) {
            return ((Method) member).getParameterTypes();
        }
        if (member instanceof Constructor) {
            return ((Constructor<?>) member).getParameterTypes();
        }
        return new Class[0];
    }

    private static void coerceResult(MethodHookParam param) {
        if (param.hasThrowable()) {
            return;
        }
        Object result = param.getResult();
        if (result == null) {
            return;
        }
        Object coerced = coerce(result, returnType(param.method));
        if (coerced != result) {
            param.setResult(coerced);
        }
    }

    private static void coerceArgs(MethodHookParam param) {
        if (param.args == null) {
            return;
        }
        Class<?>[] types = paramTypes(param.method);
        for (int i = 0; i < param.args.length && i < types.length; i++) {
            Object a = param.args[i];
            if (a != null) {
                param.args[i] = coerce(a, types[i]);
            }
        }
    }

    /** Converts boxed numbers to the boxed type matching {@code type}. */
    public static Object coerce(Object value, Class<?> type) {
        if (value == null || type == null) {
            return value;
        }
        if (value instanceof Number) {
            Number n = (Number) value;
            if (type == int.class || type == Integer.class) {
                return value instanceof Integer ? value : n.intValue();
            }
            if (type == long.class || type == Long.class) {
                return value instanceof Long ? value : n.longValue();
            }
            if (type == float.class || type == Float.class) {
                return value instanceof Float ? value : n.floatValue();
            }
            if (type == double.class || type == Double.class) {
                return value instanceof Double ? value : n.doubleValue();
            }
            if (type == short.class || type == Short.class) {
                return value instanceof Short ? value : n.shortValue();
            }
            if (type == byte.class || type == Byte.class) {
                return value instanceof Byte ? value : n.byteValue();
            }
            if (type == char.class || type == Character.class) {
                return (char) n.intValue();
            }
            if (type == boolean.class || type == Boolean.class) {
                return n.intValue() != 0;
            }
        }
        if (value instanceof String && (type == CharSequence.class)) {
            return value;
        }
        return value;
    }
    // endregion
}
