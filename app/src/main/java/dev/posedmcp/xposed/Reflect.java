package dev.posedmcp.xposed;

import org.json.JSONObject;

/**
 * Null-tolerant reflection helpers.
 *
 * <p>Every call here targets a hidden platform API, so each one is expected to
 * fail on some OEM build. Returning {@code null} / a sentinel instead of
 * throwing keeps the callers readable and lets them try the next candidate
 * quietly, which is the only sane way to write against internals that differ
 * per Android release and per vendor.
 */
final class Reflect {

    private Reflect() {
    }

    static Class<?> findClass(String name) {
        try {
            return Class.forName(name, false, Reflect.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    static Object callStatic(Class<?> clazz, String method, Class<?>[] types, Object... args) {
        if (clazz == null) {
            return null;
        }
        try {
            java.lang.reflect.Method m = clazz.getDeclaredMethod(method, types);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (Throwable t) {
            return null;
        }
    }

    static Object callStatic(String className, String method, Class<?>[] types, Object... args) {
        return callStatic(findClass(className), method, types, args);
    }

    static Object call(Object target, String method) {
        return call(target, method, new Class<?>[0]);
    }

    static Object call(Object target, String method, Class<?>[] types, Object... args) {
        if (target == null) {
            return null;
        }
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(method, types);
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Calls the first overload of {@code method} on {@code target} that accepts
     * {@code args}, walking from the most to the least specific arity. Hidden
     * APIs gain and lose parameters between releases, so the arity is not fixed.
     */
    static Object callAnyArity(Object target, String method, Class<?>[] prefixTypes,
            Object[] prefixArgs, Class<?> tailType, Object... tailArgs) {
        if (target == null) {
            return null;
        }
        for (java.lang.reflect.Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(method)) {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            if (params.length < prefixTypes.length) {
                continue;
            }
            Object[] callArgs = new Object[params.length];
            boolean compatible = true;
            for (int i = 0; i < prefixTypes.length && compatible; i++) {
                if (!boxed(params[i]).isAssignableFrom(boxed(prefixTypes[i]))) {
                    compatible = false;
                } else {
                    callArgs[i] = prefixArgs[i];
                }
            }
            for (int i = prefixTypes.length; i < params.length && compatible; i++) {
                if (tailType == null || !boxed(params[i]).isAssignableFrom(boxed(tailType))) {
                    compatible = false;
                } else {
                    callArgs[i] = tailArgs.length > i - prefixTypes.length
                            ? tailArgs[i - prefixTypes.length] : null;
                }
            }
            if (!compatible) {
                continue;
            }
            try {
                m.setAccessible(true);
                return m.invoke(target, callArgs);
            } catch (Throwable ignored) {
                // Fall through to the next overload.
            }
        }
        return null;
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }

    static JSONObject error(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error", message);
        } catch (Throwable ignored) {
        }
        return o;
    }
}
