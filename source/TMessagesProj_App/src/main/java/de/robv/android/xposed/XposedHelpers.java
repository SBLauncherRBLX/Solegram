/*
 * Solegram: a compact re-implementation of the commonly used parts of the Xposed
 * XposedHelpers API for plugins (AliuHook only ships XposedBridge / XC_MethodHook).
 * Licensed under GNU GPL v. 2 or later.
 */

package de.robv.android.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public final class XposedHelpers {

    private static final ConcurrentHashMap<String, Field> fieldCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Method> methodCache = new ConcurrentHashMap<>();
    private static final WeakHashMap<Object, Map<String, Object>> additionalFields = new WeakHashMap<>();

    private XposedHelpers() {
    }

    public static final class ClassNotFoundError extends Error {
        public ClassNotFoundError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // region classes

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader != null ? classLoader : XposedHelpers.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new ClassNotFoundError(className, e);
        }
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        try {
            return findClass(className, classLoader);
        } catch (Throwable e) {
            return null;
        }
    }
    // endregion

    // region fields

    public static Field findField(Class<?> clazz, String fieldName) {
        String key = clazz.getName() + '#' + fieldName;
        Field cached = fieldCache.get(key);
        if (cached != null) {
            return cached;
        }
        Class<?> c = clazz;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                fieldCache.put(key, f);
                return f;
            } catch (NoSuchFieldException ignore) {
            }
            c = c.getSuperclass();
        }
        throw new NoSuchFieldError(key);
    }

    public static Field findFieldIfExists(Class<?> clazz, String fieldName) {
        try {
            return findField(clazz, fieldName);
        } catch (Throwable e) {
            return null;
        }
    }

    public static Object getObjectField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).get(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        try {
            java.lang.reflect.Field f = findField(obj.getClass(), fieldName);
            f.set(obj, coerce(value, f.getType()));
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static boolean getBooleanField(Object obj, String fieldName) {
        return (Boolean) getObjectField(obj, fieldName);
    }

    public static void setBooleanField(Object obj, String fieldName, boolean value) {
        setObjectField(obj, fieldName, value);
    }

    public static int getIntField(Object obj, String fieldName) {
        return ((Number) getObjectField(obj, fieldName)).intValue();
    }

    public static void setIntField(Object obj, String fieldName, int value) {
        try {
            findField(obj.getClass(), fieldName).setInt(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static long getLongField(Object obj, String fieldName) {
        return ((Number) getObjectField(obj, fieldName)).longValue();
    }

    public static void setLongField(Object obj, String fieldName, long value) {
        try {
            findField(obj.getClass(), fieldName).setLong(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static float getFloatField(Object obj, String fieldName) {
        return ((Number) getObjectField(obj, fieldName)).floatValue();
    }

    public static void setFloatField(Object obj, String fieldName, float value) {
        try {
            findField(obj.getClass(), fieldName).setFloat(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        try {
            return findField(clazz, fieldName).get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {
        try {
            java.lang.reflect.Field f = findField(clazz, fieldName);
            f.set(null, coerce(value, f.getType()));
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static boolean getStaticBooleanField(Class<?> clazz, String fieldName) {
        return (Boolean) getStaticObjectField(clazz, fieldName);
    }

    public static void setStaticBooleanField(Class<?> clazz, String fieldName, boolean value) {
        setStaticObjectField(clazz, fieldName, value);
    }

    public static int getStaticIntField(Class<?> clazz, String fieldName) {
        return ((Number) getStaticObjectField(clazz, fieldName)).intValue();
    }

    public static void setStaticIntField(Class<?> clazz, String fieldName, int value) {
        try {
            findField(clazz, fieldName).setInt(null, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static Object getAdditionalInstanceField(Object obj, String key) {
        synchronized (additionalFields) {
            Map<String, Object> map = additionalFields.get(obj);
            return map == null ? null : map.get(key);
        }
    }

    public static Object setAdditionalInstanceField(Object obj, String key, Object value) {
        synchronized (additionalFields) {
            Map<String, Object> map = additionalFields.get(obj);
            if (map == null) {
                map = new HashMap<>();
                additionalFields.put(obj, map);
            }
            return map.put(key, value);
        }
    }

    public static Object removeAdditionalInstanceField(Object obj, String key) {
        synchronized (additionalFields) {
            Map<String, Object> map = additionalFields.get(obj);
            return map == null ? null : map.remove(key);
        }
    }
    // endregion

    // region methods

    private static Class<?>[] resolveTypes(ClassLoader loader, Object... types) {
        Class<?>[] result = new Class[types.length];
        for (int i = 0; i < types.length; i++) {
            Object t = types[i];
            if (t instanceof Class) {
                result[i] = (Class<?>) t;
            } else if (t instanceof String) {
                result[i] = primitive((String) t);
                if (result[i] == null) {
                    result[i] = findClass((String) t, loader);
                }
            } else {
                throw new IllegalArgumentException("parameter type must be Class or String: " + t);
            }
        }
        return result;
    }

    private static Class<?> primitive(String name) {
        switch (name) {
            case "int":
                return int.class;
            case "long":
                return long.class;
            case "boolean":
                return boolean.class;
            case "float":
                return float.class;
            case "double":
                return double.class;
            case "short":
                return short.class;
            case "byte":
                return byte.class;
            case "char":
                return char.class;
            case "void":
                return void.class;
            default:
                return null;
        }
    }

    public static Method findMethodExact(Class<?> clazz, String methodName, Object... parameterTypes) {
        Class<?>[] types = resolveTypes(clazz.getClassLoader(), parameterTypes);
        StringBuilder key = new StringBuilder(clazz.getName()).append('#').append(methodName).append('(');
        for (Class<?> t : types) {
            key.append(t.getName()).append(',');
        }
        String k = key.append(")#exact").toString();
        Method cached = methodCache.get(k);
        if (cached != null) {
            return cached;
        }
        Class<?> c = clazz;
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(methodName, types);
                m.setAccessible(true);
                methodCache.put(k, m);
                return m;
            } catch (NoSuchMethodException ignore) {
            }
            c = c.getSuperclass();
        }
        throw new NoSuchMethodError(k);
    }

    public static Method findMethodExact(String className, ClassLoader classLoader, String methodName, Object... parameterTypes) {
        return findMethodExact(findClass(className, classLoader), methodName, parameterTypes);
    }

    public static Method findMethodExactIfExists(Class<?> clazz, String methodName, Object... parameterTypes) {
        try {
            return findMethodExact(clazz, methodName, parameterTypes);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Finds a method whose parameters can accept the given argument values. */
    public static Method findMethodBestMatch(Class<?> clazz, String methodName, Object... args) {
        Class<?> c = clazz;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(methodName) || m.getParameterTypes().length != args.length) {
                    continue;
                }
                if (isAssignable(m.getParameterTypes(), args)) {
                    m.setAccessible(true);
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        throw new NoSuchMethodError(clazz.getName() + "#" + methodName);
    }

    private static boolean isAssignable(Class<?>[] types, Object[] args) {
        for (int i = 0; i < types.length; i++) {
            Object a = args[i];
            Class<?> t = wrap(types[i]);
            if (a == null) {
                if (types[i].isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (!t.isInstance(a)) {
                if (a instanceof Number && Number.class.isAssignableFrom(t)) {
                    continue;
                }
                return false;
            }
        }
        return true;
    }

    /**
     * Python numbers arrive as Long/Double: convert them to the exact parameter/field type, otherwise
     * reflection rejects e.g. a Long for an int parameter.
     */
    static Object coerce(Object v, Class<?> type) {
        if (!(v instanceof Number) || type.isInstance(v)) {
            return v;
        }
        Number n = (Number) v;
        Class<?> t = wrap(type);
        if (t == Integer.class) return n.intValue();
        if (t == Long.class) return n.longValue();
        if (t == Float.class) return n.floatValue();
        if (t == Double.class) return n.doubleValue();
        if (t == Short.class) return n.shortValue();
        if (t == Byte.class) return n.byteValue();
        if (t == Character.class) return (char) n.intValue();
        return v;
    }

    /** Sets a field converting Python numbers to the field type (used by hook_utils.py). */
    public static void setFieldValue(java.lang.reflect.Field field, Object obj, Object value) throws IllegalAccessException {
        field.setAccessible(true);
        field.set(obj, coerce(value, field.getType()));
    }

    static Object[] coerceArgs(Class<?>[] types, Object[] args) {
        if (args == null) {
            return null;
        }
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            out[i] = i < types.length ? coerce(args[i], types[i]) : args[i];
        }
        return out;
    }

    private static Class<?> wrap(Class<?> c) {
        if (!c.isPrimitive()) {
            return c;
        }
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == boolean.class) return Boolean.class;
        if (c == float.class) return Float.class;
        if (c == double.class) return Double.class;
        if (c == short.class) return Short.class;
        if (c == byte.class) return Byte.class;
        if (c == char.class) return Character.class;
        return c;
    }

    public static Constructor<?> findConstructorExact(Class<?> clazz, Object... parameterTypes) {
        try {
            Constructor<?> c = clazz.getDeclaredConstructor(resolveTypes(clazz.getClassLoader(), parameterTypes));
            c.setAccessible(true);
            return c;
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(clazz.getName() + "#<init>");
        }
    }

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback.length == 0 || !(parameterTypesAndCallback[parameterTypesAndCallback.length - 1] instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("no callback defined");
        }
        XC_MethodHook callback = (XC_MethodHook) parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        Object[] types = new Object[parameterTypesAndCallback.length - 1];
        System.arraycopy(parameterTypesAndCallback, 0, types, 0, types.length);
        return XposedBridge.hookMethod(findMethodExact(clazz, methodName, types), callback);
    }

    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader, String methodName, Object... parameterTypesAndCallback) {
        return findAndHookMethod(findClass(className, classLoader), methodName, parameterTypesAndCallback);
    }

    public static XC_MethodHook.Unhook findAndHookConstructor(Class<?> clazz, Object... parameterTypesAndCallback) {
        XC_MethodHook callback = (XC_MethodHook) parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        Object[] types = new Object[parameterTypesAndCallback.length - 1];
        System.arraycopy(parameterTypesAndCallback, 0, types, 0, types.length);
        return XposedBridge.hookMethod(findConstructorExact(clazz, types), callback);
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        try {
            Method m = findMethodBestMatch(obj.getClass(), methodName, args);
            return m.invoke(obj, coerceArgs(m.getParameterTypes(), args));
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        try {
            Method m = findMethodBestMatch(clazz, methodName, args);
            if (!Modifier.isStatic(m.getModifiers())) {
                throw new IllegalArgumentException(methodName + " is not static");
            }
            return m.invoke(null, coerceArgs(m.getParameterTypes(), args));
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            if (c.getParameterTypes().length == args.length && isAssignable(c.getParameterTypes(), args)) {
                try {
                    c.setAccessible(true);
                    return c.newInstance(coerceArgs(c.getParameterTypes(), args));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
        throw new NoSuchMethodError(clazz.getName() + "#<init>");
    }
    // endregion
}
