/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.plugins.python;

import com.android.dx.stock.ProxyBuilder;
import com.chaquo.python.PyObject;

import org.mvel2.MVEL;
import org.mvel2.ParserConfiguration;
import org.mvel2.ParserContext;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime Java subclasses for the plugin SDK (extera_utils.classes), generated with DexMaker.
 * Overridden methods are dispatched by {@link Handler}: to a Python function, an MVEL body, or a
 * field getter/setter, without calling into Python for the latter two.
 */
public final class PyClassProxy {

    private static volatile File dexCache;
    private static volatile boolean mvelReady;

    private PyClassProxy() {
    }

    public static void setDexCache(String path) {
        File dir = new File(path);
        dir.mkdirs();
        dexCache = dir;
    }

    // region signatures

    public static String key(Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append('(');
        Class<?>[] types = m.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(types[i].getName());
        }
        return sb.append(')').toString();
    }

    public static Class<?> typeOf(String name, ClassLoader loader) throws ClassNotFoundException {
        switch (name) {
            case "int": return int.class;
            case "long": return long.class;
            case "boolean": return boolean.class;
            case "float": return float.class;
            case "double": return double.class;
            case "byte": return byte.class;
            case "short": return short.class;
            case "char": return char.class;
            case "void": return void.class;
        }
        return Class.forName(name, false, loader != null ? loader : PyClassProxy.class.getClassLoader());
    }

    /** All non-final, non-static, non-private methods a proxy of base+interfaces can override. */
    public static Method[] overridableMethods(Class<?> base, Class<?>[] interfaces) {
        LinkedHashMapBySig map = new LinkedHashMapBySig();
        for (Class<?> c = base; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                map.offer(m);
            }
        }
        for (Class<?> c = base; c != null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                addInterface(map, i);
            }
        }
        if (interfaces != null) {
            for (Class<?> i : interfaces) {
                addInterface(map, i);
            }
        }
        return map.values();
    }

    private static void addInterface(LinkedHashMapBySig map, Class<?> iface) {
        for (Method m : iface.getMethods()) {
            map.offer(m);
        }
    }

    private static final class LinkedHashMapBySig {
        private final java.util.LinkedHashMap<String, Method> map = new java.util.LinkedHashMap<>();

        void offer(Method m) {
            int mod = m.getModifiers();
            if (Modifier.isStatic(mod) || Modifier.isPrivate(mod) || m.isSynthetic() || m.isBridge()) {
                return;
            }
            String k = key(m);
            if (!map.containsKey(k)) {
                map.put(k, m);
            }
        }

        Method[] values() {
            ArrayList<Method> out = new ArrayList<>();
            for (Method m : map.values()) {
                if (!Modifier.isFinal(m.getModifiers())) {
                    out.add(m);
                }
            }
            return out.toArray(new Method[0]);
        }
    }

    /** Finds an overridable method by name and (optional) parameter type names. */
    public static Method findMethod(Class<?> base, Class<?>[] interfaces, String name, String[] params) throws Exception {
        Method found = null;
        for (Method m : overridableMethods(base, interfaces)) {
            if (!m.getName().equals(name)) {
                continue;
            }
            if (params != null) {
                Class<?>[] types = m.getParameterTypes();
                if (types.length != params.length) {
                    continue;
                }
                boolean ok = true;
                for (int i = 0; i < types.length; i++) {
                    if (!types[i].getName().equals(params[i]) && !types[i].getSimpleName().equals(params[i])) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) {
                    continue;
                }
                return m;
            }
            if (found != null) {
                throw new IllegalArgumentException("Method " + name + " is overloaded, use joverload(name, [arg types])");
            }
            found = m;
        }
        if (found == null) {
            throw new NoSuchMethodException(name + (params != null ? java.util.Arrays.toString(params) : "") + " is not overridable in " + base.getName());
        }
        return found;
    }
    // endregion

    // region class generation

    private static final ConcurrentHashMap<String, Class<?>> classCache = new ConcurrentHashMap<>();

    public static Class<?> buildClass(Class<?> base, Class<?>[] interfaces, Method[] methods) throws Exception {
        StringBuilder k = new StringBuilder(base.getName());
        if (interfaces != null) {
            for (Class<?> i : interfaces) {
                k.append('+').append(i.getName());
            }
        }
        for (Method m : methods) {
            k.append('|').append(key(m));
        }
        String cacheKey = k.toString();
        Class<?> cls = classCache.get(cacheKey);
        if (cls != null) {
            return cls;
        }
        if (dexCache == null) {
            throw new IllegalStateException("dex cache is not set");
        }
        // DexMaker caches generated classes by (base, interfaces, parent loader) and ignores onlyMethods:
        // a fresh delegating loader per distinct spec stops it from returning a class generated for
        // different overrides (e.g. two plugins subclassing FrameLayout, or a plugin reloaded after edits).
        ClassLoader specLoader = new ClassLoader(PyClassProxy.class.getClassLoader()) {
        };
        ProxyBuilder<?> builder = ProxyBuilder.forClass(base)
            .parentClassLoader(specLoader)
            .dexCache(dexCache)
            .onlyMethods(methods);
        if (interfaces != null && interfaces.length > 0) {
            builder.implementing(interfaces);
        }
        cls = builder.buildProxyClass();
        classCache.put(cacheKey, cls);
        return cls;
    }

    /** Instantiates a generated class with the constructor matching args, then attaches the handler. */
    public static Object newInstance(Class<?> proxyClass, Object[] args, Handler handler) throws Throwable {
        if (args == null) {
            args = new Object[0];
        }
        Constructor<?> best = null;
        Object[] converted = null;
        for (Constructor<?> c : proxyClass.getConstructors()) {
            Class<?>[] types = c.getParameterTypes();
            if (types.length != args.length) {
                continue;
            }
            Object[] out = new Object[args.length];
            boolean ok = true;
            for (int i = 0; i < args.length; i++) {
                Object v = coerce(args[i], types[i]);
                if (v == NO_MATCH) {
                    ok = false;
                    break;
                }
                out[i] = v;
            }
            if (ok) {
                best = c;
                converted = out;
                break;
            }
        }
        if (best == null) {
            throw new NoSuchMethodException("No constructor of " + proxyClass.getSuperclass().getName() + " accepts " + args.length + " argument(s) of these types");
        }
        Object instance;
        try {
            instance = best.newInstance(converted);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
        if (handler != null) {
            ProxyBuilder.setInvocationHandler(instance, handler);
        }
        return instance;
    }

    public static Handler handlerOf(Object instance) {
        try {
            if (instance != null && ProxyBuilder.isProxyClass(instance.getClass())) {
                InvocationHandler h = ProxyBuilder.getInvocationHandler(instance);
                return h instanceof Handler ? (Handler) h : null;
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    public static Object callSuper(Object proxy, Method method, Object[] args) throws Throwable {
        Class<?>[] types = method.getParameterTypes();
        Object[] converted = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Object v = coerce(args != null && i < args.length ? args[i] : null, types[i]);
            converted[i] = v == NO_MATCH ? args[i] : v;
        }
        if (Modifier.isAbstract(method.getModifiers())) {
            return defaultValue(method.getReturnType());
        }
        return ProxyBuilder.callSuper(proxy, method, converted);
    }

    public static Object callSuperList(Object proxy, String key, List<Object> args) throws Throwable {
        Handler h = handlerOf(proxy);
        Method m = h != null ? h.methodsByKey.get(key) : null;
        if (m == null) {
            throw new NoSuchMethodException(key);
        }
        return callSuper(proxy, m, args == null ? new Object[0] : args.toArray());
    }
    // endregion

    // region conversions

    private static final Object NO_MATCH = new Object();

    static Object coerce(Object v, Class<?> type) {
        if (v == null) {
            return type.isPrimitive() ? NO_MATCH : null;
        }
        if (type.isInstance(v)) {
            return v;
        }
        if (v instanceof Number) {
            Number n = (Number) v;
            if (type == int.class || type == Integer.class) return n.intValue();
            if (type == long.class || type == Long.class) return n.longValue();
            if (type == float.class || type == Float.class) return n.floatValue();
            if (type == double.class || type == Double.class) return n.doubleValue();
            if (type == short.class || type == Short.class) return n.shortValue();
            if (type == byte.class || type == Byte.class) return n.byteValue();
            if (type == char.class || type == Character.class) return (char) n.intValue();
        }
        if (v instanceof Boolean && (type == boolean.class)) {
            return v;
        }
        if (v instanceof Character && type == char.class) {
            return v;
        }
        if (type == CharSequence.class && v instanceof CharSequence) {
            return v;
        }
        return NO_MATCH;
    }

    static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return (char) 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        return 0;
    }

    static Object fromPython(PyObject result, Class<?> type) {
        if (type == void.class) {
            return null;
        }
        if (result == null) {
            return defaultValue(type);
        }
        if (type == boolean.class || type == Boolean.class) return result.toBoolean();
        if (type == int.class || type == Integer.class) return result.toInt();
        if (type == long.class || type == Long.class) return result.toLong();
        if (type == float.class || type == Float.class) return result.toFloat();
        if (type == double.class || type == Double.class) return result.toDouble();
        if (type == short.class || type == Short.class) return result.toShort();
        if (type == byte.class || type == Byte.class) return result.toByte();
        if (type == char.class || type == Character.class) return result.toChar();
        return result.toJava(type);
    }
    // endregion

    // region handler

    private static final int KIND_PYTHON = 0;
    private static final int KIND_MVEL = 1;
    private static final int KIND_GET = 2;
    private static final int KIND_SET = 3;

    private static final class Target {
        final int kind;
        final Method method;
        PyObject function;
        Serializable mvel;
        String[] argNames;
        String field;

        Target(int kind, Method method) {
            this.kind = kind;
            this.method = method;
        }
    }

    /** Per-instance dispatcher; the per-class targets are shared through {@link Spec}. */
    public static final class Spec {
        final HashMap<String, Target> targets = new HashMap<>();
        final HashMap<String, Method> methodsByKey = new HashMap<>();
        final HashMap<String, Object> fieldDefaults = new HashMap<>();

        public void addPython(Method m, PyObject function) {
            Target t = new Target(KIND_PYTHON, m);
            t.function = function;
            put(m, t);
        }

        public void addMvel(Method m, String code, String[] argNames) {
            ensureMvel();
            String name = m.getName();
            StringBuilder def = new StringBuilder("def SUPER_").append(name).append('(');
            StringBuilder list = new StringBuilder("[");
            int n = m.getParameterTypes().length;
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    def.append(", ");
                    list.append(", ");
                }
                def.append("__a").append(i);
                list.append("__a").append(i);
            }
            list.append(']');
            def.append(") { return com.solegram.messenger.plugins.python.PyClassProxy.callSuperList(java, \"")
                .append(key(m)).append("\", ").append(list).append("); }\n");
            Target t = new Target(KIND_MVEL, m);
            ParserContext ctx = new ParserContext(new ParserConfiguration());
            t.mvel = MVEL.compileExpression(def + code, ctx);
            t.argNames = argNames;
            put(m, t);
        }

        public void addGetter(Method m, String field) {
            Target t = new Target(KIND_GET, m);
            t.field = field;
            put(m, t);
        }

        public void addSetter(Method m, String field) {
            Target t = new Target(KIND_SET, m);
            t.field = field;
            put(m, t);
        }

        public void addField(String name, Object defaultValue) {
            fieldDefaults.put(name, defaultValue);
        }

        public void addMethod(Method m) {
            methodsByKey.put(key(m), m);
        }

        private void put(Method m, Target t) {
            String k = key(m);
            targets.put(k, t);
            methodsByKey.put(k, m);
        }

        public Handler newHandler() {
            return new Handler(this);
        }
    }

    public static final class Handler implements InvocationHandler {
        final Spec spec;
        final HashMap<String, Method> methodsByKey;
        final Map<String, Object> fields;
        volatile PyObject peer;
        volatile boolean released;

        Handler(Spec spec) {
            this.spec = spec;
            this.methodsByKey = spec.methodsByKey;
            this.fields = new ConcurrentHashMap<>();
            for (Map.Entry<String, Object> e : spec.fieldDefaults.entrySet()) {
                if (e.getValue() != null) {
                    fields.put(e.getKey(), e.getValue());
                }
            }
        }

        public void setPeer(PyObject peer) {
            this.peer = peer;
        }

        public PyObject getPeer() {
            return released ? null : peer;
        }

        public boolean isReleased() {
            return released;
        }

        public void release() {
            released = true;
            peer = null;
        }

        public Object getField(String name) {
            return fields.get(name);
        }

        public void setField(String name, Object value) {
            if (value == null) {
                fields.remove(name);
            } else {
                fields.put(name, value);
            }
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String k = key(method);
            Target t = released ? null : spec.targets.get(k);
            if (t == null) {
                if (k.equals("hashCode()") || k.equals("equals(java.lang.Object)") || k.equals("toString()")) {
                    return ProxyBuilder.callSuper(proxy, method, args);
                }
                return callSuper(proxy, method, args);
            }
            Class<?> ret = method.getReturnType();
            switch (t.kind) {
                case KIND_GET:
                    Object v = fields.get(t.field);
                    if (v == null) {
                        return defaultValue(ret);
                    }
                    Object c = coerce(v, ret);
                    return c == NO_MATCH ? v : c;
                case KIND_SET:
                    setField(t.field, args != null && args.length > 0 ? args[0] : null);
                    return defaultValue(ret);
                case KIND_MVEL: {
                    HashMap<String, Object> vars = new HashMap<>(fields);
                    vars.put("java", proxy);
                    PyObject p = peer;
                    vars.put("python", p);
                    vars.put("py", p);
                    vars.put("self", p);
                    Object[] a = args != null ? args : new Object[0];
                    vars.put("args", a);
                    vars.put("argc", a.length);
                    for (int i = 0; i < a.length; i++) {
                        vars.put("arg" + i, a[i]);
                        if (t.argNames != null && i < t.argNames.length && t.argNames[i] != null) {
                            vars.put(t.argNames[i], a[i]);
                        }
                    }
                    Object result = MVEL.executeExpression(t.mvel, proxy, vars);
                    for (String f : spec.fieldDefaults.keySet()) {
                        if (vars.containsKey(f)) {
                            setField(f, vars.get(f));
                        }
                    }
                    if (ret == void.class) {
                        return null;
                    }
                    if (result == null) {
                        return defaultValue(ret);
                    }
                    Object c2 = coerce(result, ret);
                    return c2 == NO_MATCH ? result : c2;
                }
                default: {
                    PyObject p = peer;
                    if (p == null) {
                        return callSuper(proxy, method, args);
                    }
                    Object[] a = args != null ? args : new Object[0];
                    Object[] callArgs = new Object[a.length + 1];
                    callArgs[0] = p;
                    System.arraycopy(a, 0, callArgs, 1, a.length);
                    PyObject result = t.function.call(callArgs);
                    return fromPython(result, ret);
                }
            }
        }
    }
    // endregion

    private static void ensureMvel() {
        if (!mvelReady) {
            org.mvel2.optimizers.OptimizerFactory.setDefaultOptimizer("reflective");
            mvelReady = true;
        }
    }

    public static void log(Throwable e) {
        FileLog.e(e);
    }
}
