package dev.posedmcp.xposed;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Calls a method inside a hooked application's process.
 *
 * <p>This is the counterpart to {@link HookRegistry}: that one intercepts a call
 * the application makes, this one makes a call as the application. Together they
 * cover what one usually wants from code injection - watch behaviour, or drive
 * it - and neither needs a plugin DEX, which matters because authoring one
 * on-device means hand-writing smali.
 *
 * <p>It runs with the target application's class loader and privileges, so a
 * method that is private, package-private or not exported at all is reachable. A
 * call made from outside the process would not be.
 */
public final class MethodInvoker {

    private static final int MAX_RESULT_CHARS = 2000;

    private MethodInvoker() {
    }

    /**
     * Resolves, calls and reports.
     *
     * @param instanceClassName class holding the receiver, or empty for a static call
     * @param instanceField     static field on it holding the receiver, or empty
     * @param instanceMethod    static no-argument method on it returning the receiver, or empty
     */
    public static JSONObject invoke(String className, String methodName, String paramTypes,
            JSONArray args, String instanceClassName, String instanceField, String instanceMethod,
            ClassLoader loader) throws Exception {
        if (className == null || className.isEmpty()) {
            throw new IllegalArgumentException("class is required");
        }
        if (methodName == null || methodName.isEmpty()) {
            throw new IllegalArgumentException("method is required");
        }

        Class<?> clazz = Class.forName(className, true, loader);
        Object[] arguments = toArray(args);

        Method method = resolveMethod(clazz, methodName, paramTypes, arguments.length, loader);
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < arguments.length && i < types.length; i++) {
            arguments[i] = HookRegistry.coerce(arguments[i], types[i]);
        }

        Object receiver = null;
        if (!Modifier.isStatic(method.getModifiers())) {
            receiver = obtainInstance(instanceClassName, instanceField, instanceMethod, loader);
            if (receiver == null) {
                throw new IllegalStateException(className + "." + methodName + " is not static, and"
                        + " no receiver was given. Pass instance_class plus either instance_field"
                        + " (a static field) or instance_method (a static no-argument accessor).");
            }
            if (!method.getDeclaringClass().isInstance(receiver)) {
                throw new IllegalStateException("the receiver is a "
                        + receiver.getClass().getName() + ", which is not a "
                        + method.getDeclaringClass().getName());
            }
        }

        method.setAccessible(true);
        Object result;
        try {
            result = method.invoke(receiver, arguments);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            // The application's own exception is the interesting part; hiding it
            // behind a reflection wrapper would tell the caller nothing.
            throw new IllegalStateException(methodName + " threw "
                    + cause.getClass().getName() + ": " + cause.getMessage(), cause);
        }

        JSONObject out = new JSONObject();
        out.put("class", className);
        out.put("method", methodName);
        out.put("returnType", method.getReturnType().getName());
        if (result == null) {
            out.put("returned", JSONObject.NULL);
        } else {
            out.put("returned", describe(result));
            out.put("returnedClass", result.getClass().getName());
        }
        return out;
    }

    private static Object obtainInstance(String className, String fieldName, String methodName,
            ClassLoader loader) throws Exception {
        if (className == null || className.isEmpty()) {
            return null;
        }
        Class<?> holder = Class.forName(className, true, loader);
        if (fieldName != null && !fieldName.isEmpty()) {
            Field field = holder.getDeclaredField(fieldName);
            if (!Modifier.isStatic(field.getModifiers())) {
                throw new IllegalStateException(fieldName + " is not static, so it needs a holder"
                        + " instance of its own; name a static field or accessor instead");
            }
            field.setAccessible(true);
            return field.get(null);
        }
        if (methodName != null && !methodName.isEmpty()) {
            Method accessor = holder.getDeclaredMethod(methodName);
            if (!Modifier.isStatic(accessor.getModifiers())) {
                throw new IllegalStateException(methodName + " is not static, so it needs a holder"
                        + " instance of its own; name a static field or accessor instead");
            }
            accessor.setAccessible(true);
            return accessor.invoke(null);
        }
        return null;
    }

    /**
     * Finds the method to call.
     *
     * <p>With parameter types given the match is exact. Without them, a name with
     * a unique arity is chosen; anything ambiguous is refused rather than
     * guessed, because calling the wrong overload silently is worse than an
     * error that says which names were in the running.
     */
    private static Method resolveMethod(Class<?> clazz, String name, String paramTypes, int argCount,
            ClassLoader loader) throws Exception {
        if (paramTypes != null && !paramTypes.trim().isEmpty()) {
            List<Class<?>> types = new ArrayList<>();
            for (String token : paramTypes.split(",")) {
                types.add(HookRegistry.resolveType(loader, token.trim()));
            }
            return clazz.getDeclaredMethod(name, types.toArray(new Class<?>[0]));
        }

        List<Method> candidates = new ArrayList<>();
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == argCount) {
                candidates.add(method);
            }
        }
        if (candidates.isEmpty()) {
            throw new NoSuchMethodException("no method " + name + " taking " + argCount
                    + " argument(s) on " + clazz.getName());
        }
        if (candidates.size() > 1) {
            StringBuilder sb = new StringBuilder();
            for (Method candidate : candidates) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                for (Class<?> type : candidate.getParameterTypes()) {
                    if (sb.charAt(sb.length() - 1) != '(') {
                        sb.append(',');
                    }
                    sb.append(type.getSimpleName());
                }
                sb.append(')');
            }
            throw new IllegalStateException(name + " is overloaded at " + argCount
                    + " argument(s); pass params to choose: " + sb);
        }
        return candidates.get(0);
    }

    private static Object[] toArray(JSONArray args) throws Exception {
        if (args == null) {
            return new Object[0];
        }
        Object[] out = new Object[args.length()];
        for (int i = 0; i < args.length(); i++) {
            Object value = args.get(i);
            // A JSON null has to become a Java null; JSONObject.NULL is a
            // sentinel object and would be rejected as an argument.
            out[i] = value == JSONObject.NULL ? null : value;
        }
        return out;
    }

    /** Results are summarised: an arbitrary object's toString can be enormous. */
    private static Object describe(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof CharSequence) {
            String text = value.toString();
            return text.length() <= MAX_RESULT_CHARS ? text
                    : text.substring(0, MAX_RESULT_CHARS) + "...";
        }
        if (value.getClass().isArray()) {
            try {
                int length = Array.getLength(value);
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < length && i < 20; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(String.valueOf(Array.get(value, i)));
                }
                return sb.append(length > 20 ? ", ...(" + length + ")" : "").append(']').toString();
            } catch (Throwable ignored) {
            }
        }
        try {
            String text = String.valueOf(value);
            return text.length() <= MAX_RESULT_CHARS ? text
                    : text.substring(0, MAX_RESULT_CHARS) + "...";
        } catch (Throwable t) {
            return "<unprintable " + value.getClass().getName() + ">";
        }
    }
}
