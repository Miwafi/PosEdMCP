package dev.posedmcp.xposed;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.posedmcp.plugin.HookApi;

/**
 * Runtime observers installed into a hooked application's process.
 *
 * <p>This is the other half of static analysis. Reading smali tells you where to
 * look; this tells you what actually happens when the code runs - the argument
 * values, the return value, the exception, which thread. Doing it without a
 * plugin DEX matters because the loop should not require a build step: an agent
 * finds a method with {@code smali_disassemble} and watches it with one call.
 *
 * <p>Lives inside the target process, one registry per process. Everything here
 * runs on the application's own threads, so a hook must never throw and must
 * never block: a recording hook that breaks the app it is observing is worse
 * than no observation at all.
 */
public final class HookRegistry {

    private static final int DEFAULT_MAX_RECORDS = 200;
    private static final int HARD_MAX_RECORDS = 2000;
    private static final int MAX_ARGUMENTS = 8;
    private static final int VALUE_LIMIT = 120;

    private static final Map<String, Entry> HOOKS = new ConcurrentHashMap<>();

    /** Correlation for the two halves of one call, per thread. */
    private static final ThreadLocal<Record> IN_FLIGHT = new ThreadLocal<>();

    private HookRegistry() {
    }

    private static final class Record {
        long timestamp;
        String thread;
        String[] args;
        String result;
        String threw;
    }

    private static final class Entry {
        final String className;
        final String methodName;
        final int maxRecords;
        final Deque<Record> records = new ArrayDeque<>();
        volatile HookApi.Unhook unhook;

        Entry(String className, String methodName, int maxRecords) {
            this.className = className;
            this.methodName = methodName;
            this.maxRecords = maxRecords;
        }

        String key() {
            return className + "#" + methodName;
        }
    }

    // ---- ops --------------------------------------------------------------

    public static JSONObject install(String className, String methodName, String paramTypes,
            int maxRecords, ClassLoader appClassLoader) throws Exception {
        if (className == null || className.isEmpty()) {
            throw new IllegalArgumentException("class is required");
        }
        if (methodName == null || methodName.isEmpty()) {
            throw new IllegalArgumentException("method is required");
        }
        int cap = maxRecords <= 0 ? DEFAULT_MAX_RECORDS : Math.min(maxRecords, HARD_MAX_RECORDS);

        Entry entry = new Entry(className, methodName, cap);
        HookApi api = new XposedHookApi(appClassLoader);

        HookApi.Callback callback = new HookApi.Callback() {
            @Override
            public void before(HookApi.HookParam param) {
                try {
                    Record record = new Record();
                    record.timestamp = System.currentTimeMillis();
                    record.thread = Thread.currentThread().getName();
                    Object[] args = param.args();
                    int n = args == null ? 0 : Math.min(args.length, MAX_ARGUMENTS);
                    record.args = new String[n];
                    for (int i = 0; i < n; i++) {
                        record.args[i] = describe(args[i]);
                    }
                    IN_FLIGHT.set(record);
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void after(HookApi.HookParam param) {
                try {
                    Record record = IN_FLIGHT.get();
                    IN_FLIGHT.remove();
                    if (record == null) {
                        return;
                    }
                    Throwable thrown = param.throwable();
                    if (thrown != null) {
                        record.threw = thrown.getClass().getName()
                                + (thrown.getMessage() == null ? "" : ": " + thrown.getMessage());
                    } else {
                        record.result = describe(param.result());
                    }
                    synchronized (entry.records) {
                        entry.records.addLast(record);
                        while (entry.records.size() > entry.maxRecords) {
                            entry.records.removeFirst();
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        };

        HookApi.Unhook unhook;
        if (paramTypes == null || paramTypes.trim().isEmpty()) {
            unhook = api.hookAllMethods(className, methodName, callback);
        } else {
            List<Class<?>> resolved = new ArrayList<>();
            Class<?> clazz = Class.forName(className, false, appClassLoader);
            for (String token : paramTypes.split(",")) {
                resolved.add(resolveType(appClassLoader, token.trim()));
            }
            unhook = api.hookMethod(className, methodName, resolved.toArray(new Class<?>[0]),
                    callback);
        }
        if (unhook == null) {
            throw new IllegalStateException("could not resolve " + className + "." + methodName
                    + " in this process");
        }
        entry.unhook = unhook;

        Entry previous = HOOKS.put(entry.key(), entry);
        if (previous != null && previous.unhook != null) {
            try {
                previous.unhook.unhook();
            } catch (Throwable ignored) {
            }
        }

        JSONObject out = new JSONObject();
        out.put("hooked", true);
        out.put("class", className);
        out.put("method", methodName);
        out.put("maxRecords", cap);
        out.put("note", "Call hook_records to read what it captures.");
        return out;
    }

    public static JSONObject records(String subject, int limit) throws Exception {
        String filter = subject == null ? "" : subject.toLowerCase(Locale.ROOT);
        int max = limit <= 0 ? 100 : Math.min(limit, HARD_MAX_RECORDS);

        JSONArray array = new JSONArray();
        int hookedMethods = 0;
        for (Entry entry : HOOKS.values()) {
            if (!filter.isEmpty() && !entry.key().toLowerCase(Locale.ROOT).contains(filter)) {
                continue;
            }
            hookedMethods++;
            synchronized (entry.records) {
                Iterator<Record> it = entry.records.descendingIterator();
                while (it.hasNext() && array.length() < max) {
                    array.put(toJson(entry.key(), it.next()));
                }
            }
        }

        JSONObject out = new JSONObject();
        out.put("records", array);
        out.put("hookedMethods", hookedMethods);
        if (hookedMethods == 0) {
            out.put("note", HOOKS.isEmpty()
                    ? "nothing is hooked in this process yet"
                    : "no hook matches '" + subject + "'");
        }
        return out;
    }

    public static JSONObject clear(String subject) throws Exception {
        String filter = subject == null ? "" : subject.toLowerCase(Locale.ROOT);
        JSONArray removed = new JSONArray();
        for (Iterator<Map.Entry<String, Entry>> it = HOOKS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Entry> mapEntry = it.next();
            if (!filter.isEmpty() && !mapEntry.getKey().toLowerCase(Locale.ROOT).contains(filter)) {
                continue;
            }
            Entry entry = mapEntry.getValue();
            if (entry.unhook != null) {
                try {
                    entry.unhook.unhook();
                } catch (Throwable ignored) {
                }
            }
            it.remove();
            removed.put(mapEntry.getKey());
        }
        JSONObject out = new JSONObject();
        out.put("removed", removed);
        out.put("remainingHooks", HOOKS.size());
        return out;
    }

    /** Summary used by the plugin listing so an agent sees what is being watched. */
    public static JSONObject summary() {
        JSONObject out = new JSONObject();
        JSONArray hooks = new JSONArray();
        for (Entry entry : HOOKS.values()) {
            JSONObject o = new JSONObject();
            try {
                o.put("target", entry.key());
                synchronized (entry.records) {
                    o.put("recordCount", entry.records.size());
                }
            } catch (Throwable ignored) {
            }
            hooks.put(o);
        }
        try {
            out.put("hooks", hooks);
        } catch (Throwable ignored) {
        }
        return out;
    }

    // ---- helpers ----------------------------------------------------------

    private static JSONObject toJson(String key, Record record) {
        JSONObject o = new JSONObject();
        try {
            o.put("target", key);
            o.put("ts", record.timestamp);
            o.put("thread", record.thread);
            JSONArray args = new JSONArray();
            if (record.args != null) {
                for (String a : record.args) {
                    args.put(a);
                }
            }
            o.put("args", args);
            if (record.threw != null) {
                o.put("threw", record.threw);
            } else {
                o.put("result", record.result);
            }
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** Values are truncated hard: a hooked method can return anything at all. */
    private static String describe(Object value) {
        try {
            if (value == null) {
                return "null";
            }
            String text = value instanceof String ? "\"" + value + "\"" : String.valueOf(value);
            return text.length() <= VALUE_LIMIT ? text : text.substring(0, VALUE_LIMIT) + "...";
        } catch (Throwable t) {
            return "<unprintable>";
        }
    }

    private static Class<?> resolveType(ClassLoader loader, String token) throws Exception {
        switch (token) {
            case "int": return int.class;
            case "long": return long.class;
            case "boolean": return boolean.class;
            case "float": return float.class;
            case "double": return double.class;
            case "byte": return byte.class;
            case "char": return char.class;
            case "short": return short.class;
            case "void": return void.class;
            default:
                if (token.endsWith("[]")) {
                    return java.lang.reflect.Array.newInstance(
                            resolveType(loader, token.substring(0, token.length() - 2)), 0).getClass();
                }
                return Class.forName(token, false, loader);
        }
    }

    /** Exposed so the app listing can show what is currently installed. */
    public static Map<String, Integer> snapshot() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Entry entry : HOOKS.values()) {
            synchronized (entry.records) {
                out.put(entry.key(), entry.records.size());
            }
        }
        return out;
    }
}
