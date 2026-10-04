package dev.posedmcp.dex;

import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.dexbacked.reference.DexBackedStringReference;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Field;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MultiDexContainer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Read-only queries over DEX content, backed by dexlib2.
 *
 * <p>These walk the index structures rather than disassembling, so they answer
 * "what is in here" cheaply - which is the question an agent asks first, before
 * deciding which class is worth a full disassembly.
 *
 * <p>Runs only inside the isolated {@code :dex} process. See {@link DexService}.
 */
public final class DexQuery {

    private static final int MAX_RESULTS = 2000;

    private DexQuery() {
    }

    /** Opens a DEX, or every {@code classes*.dex} inside an APK/JAR/ZIP. */
    static List<DexFile> open(File source) throws IOException {
        String name = source.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".apk") || name.endsWith(".jar") || name.endsWith(".zip")
                || name.endsWith(".apex")) {
            MultiDexContainer<? extends DexBackedDexFile> container =
                    org.jf.dexlib2.DexFileFactory.loadDexContainer(source, opcodes());
            List<DexFile> out = new ArrayList<>();
            for (String entry : container.getDexEntryNames()) {
                MultiDexContainer.DexEntry<? extends DexBackedDexFile> dexEntry =
                        container.getEntry(entry);
                if (dexEntry != null && dexEntry.getDexFile() != null) {
                    out.add(dexEntry.getDexFile());
                }
            }
            return out;
        }
        return java.util.Collections.singletonList(
                org.jf.dexlib2.DexFileFactory.loadDexFile(source, opcodes()));
    }

    private static Opcodes opcodes() {
        try {
            return Opcodes.forApi(android.os.Build.VERSION.SDK_INT);
        } catch (Throwable t) {
            return Opcodes.getDefault();
        }
    }

    /** Class descriptors, optionally carrying member signatures. */
    public static JSONObject listClasses(File source, String filter, int limit, boolean withMembers)
            throws Exception {
        String needle = filter == null ? "" : filter.toLowerCase(Locale.ROOT);
        int max = limit <= 0 ? 200 : Math.min(limit, MAX_RESULTS);

        JSONArray classes = new JSONArray();
        int matched = 0;
        int total = 0;
        boolean truncated = false;

        for (DexFile dex : open(source)) {
            for (ClassDef def : dex.getClasses()) {
                total++;
                String type = def.getType();
                if (!needle.isEmpty() && !type.toLowerCase(Locale.ROOT).contains(needle)) {
                    continue;
                }
                if (matched >= max) {
                    truncated = true;
                    break;
                }
                matched++;
                classes.put(withMembers ? describe(def) : type);
            }
            if (truncated) {
                break;
            }
        }

        JSONObject out = new JSONObject();
        out.put("source", source.getAbsolutePath());
        out.put("totalClasses", total);
        out.put("matched", matched);
        out.put("classes", classes);
        if (truncated) {
            out.put("truncated", true);
            out.put("note", "Stopped at " + max + " matches; narrow the filter.");
        }
        return out;
    }

    private static JSONObject describe(ClassDef def) {
        JSONObject o = new JSONObject();
        try {
            o.put("class", def.getType());
            String superType = def.getSuperclass();
            if (superType != null) {
                o.put("extends", superType);
            }
            JSONArray interfaces = new JSONArray();
            for (String iface : def.getInterfaces()) {
                interfaces.put(iface);
            }
            if (interfaces.length() > 0) {
                o.put("implements", interfaces);
            }

            JSONArray methods = new JSONArray();
            int methodCount = 0;
            for (Method m : def.getMethods()) {
                methodCount++;
                if (methods.length() < 60) {
                    methods.put(signature(m));
                }
            }
            o.put("methodCount", methodCount);
            o.put("methods", methods);

            JSONArray fields = new JSONArray();
            int fieldCount = 0;
            for (Field f : def.getFields()) {
                fieldCount++;
                if (fields.length() < 40) {
                    fields.put(f.getType() + " " + f.getName());
                }
            }
            o.put("fieldCount", fieldCount);
            o.put("fields", fields);
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** {@code name(Ltype;I)Lret;} - the form smali and dexlib2 both use. */
    static String signature(Method m) {
        StringBuilder sb = new StringBuilder(m.getName());
        sb.append('(');
        for (CharSequence p : m.getParameterTypes()) {
            sb.append(p);
        }
        sb.append(')');
        sb.append(m.getReturnType());
        return sb.toString();
    }

    /** Free-text search across the string table and the type/member indexes. */
    public static JSONObject search(File source, String pattern, String kind, int limit)
            throws Exception {
        if (pattern == null || pattern.isEmpty()) {
            throw new IllegalArgumentException("pattern is required");
        }
        String needle = pattern.toLowerCase(Locale.ROOT);
        int max = limit <= 0 ? 100 : Math.min(limit, MAX_RESULTS);
        String what = kind == null || kind.isEmpty() ? "string" : kind.toLowerCase(Locale.ROOT);

        JSONArray hits = new JSONArray();
        boolean truncated = false;

        outer:
        for (DexFile dex : open(source)) {
            if ("string".equals(what) && dex instanceof DexBackedDexFile) {
                for (DexBackedStringReference ref : ((DexBackedDexFile) dex).getStringReferences()) {
                    String value = ref.getString();
                    if (value != null && value.toLowerCase(Locale.ROOT).contains(needle)) {
                        if (hits.length() >= max) {
                            truncated = true;
                            break outer;
                        }
                        hits.put(value);
                    }
                }
                continue;
            }

            for (ClassDef def : dex.getClasses()) {
                if ("class".equals(what)) {
                    if (def.getType().toLowerCase(Locale.ROOT).contains(needle)) {
                        if (hits.length() >= max) {
                            truncated = true;
                            break outer;
                        }
                        hits.put(def.getType());
                    }
                    continue;
                }
                if ("method".equals(what)) {
                    for (Method m : def.getMethods()) {
                        if (m.getName().toLowerCase(Locale.ROOT).contains(needle)) {
                            if (hits.length() >= max) {
                                truncated = true;
                                break outer;
                            }
                            hits.put(def.getType() + "->" + signature(m));
                        }
                    }
                    continue;
                }
                if ("field".equals(what)) {
                    for (Field f : def.getFields()) {
                        if (f.getName().toLowerCase(Locale.ROOT).contains(needle)) {
                            if (hits.length() >= max) {
                                truncated = true;
                                break outer;
                            }
                            hits.put(def.getType() + "->" + f.getName() + ":" + f.getType());
                        }
                    }
                }
            }
        }

        JSONObject out = new JSONObject();
        out.put("source", source.getAbsolutePath());
        out.put("kind", what);
        out.put("pattern", pattern);
        out.put("hits", hits);
        if (truncated) {
            out.put("truncated", true);
            out.put("note", "Stopped at " + max + " matches.");
        }
        return out;
    }
}
