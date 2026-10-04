package dev.posedmcp.dex;

import org.jf.baksmali.Baksmali;
import org.jf.baksmali.BaksmaliOptions;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.smali.Smali;
import org.jf.smali.SmaliOptions;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * baksmali and smali, wrapped so a tool call can use them.
 *
 * <p>Assembly is the part that matters most here: it lets the agent author code
 * for injection without a PC toolchain. A phone has no javac and no d8, so
 * before this the only way to get a plugin DEX was to build it on a computer -
 * which defeats the point of an on-device agent.
 *
 * <p>Both engines write to a working directory and report paths rather than
 * returning source: disassembled output is routinely megabytes, and shipping
 * that through an MCP response would drown the conversation.
 */
public final class SmaliTool {

    /** Inline the result instead of only reporting a path, below this size. */
    private static final int INLINE_LIMIT_BYTES = 16 * 1024;

    private SmaliTool() {
    }

    public static JSONObject disassemble(File source, File outDir, String classFilter, int apiLevel)
            throws Exception {
        List<DexFile> dexFiles = DexQuery.open(source);

        List<String> descriptors = null;
        if (classFilter != null && !classFilter.isEmpty()) {
            descriptors = resolveClasses(dexFiles, classFilter);
            if (descriptors.isEmpty()) {
                throw new IllegalArgumentException(
                        "no class matches '" + classFilter + "' in " + source.getName());
            }
        }

        BaksmaliOptions options = new BaksmaliOptions();
        options.apiLevel = apiLevel;
        // Debug info and line numbers are most of what makes disassembly useful
        // for reading, and the size cost lands in a file rather than in context.
        options.debugInfo = true;

        String log;
        synchronized (SmaliTool.class) {
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            try {
                PrintStream sink = new PrintStream(captured, true, "UTF-8");
                System.setOut(sink);
                System.setErr(sink);
                for (DexFile dex : dexFiles) {
                    Baksmali.disassembleDexFile(dex, outDir, 1, options, descriptors);
                }
            } finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
            log = captured.toString("UTF-8");
        }

        JSONObject stats = summarizeDirectory(outDir);
        stats.put("source", source.getAbsolutePath());
        stats.put("outputDir", outDir.getAbsolutePath());
        if (descriptors != null) {
            stats.put("filter", classFilter);
            stats.put("matchedClasses", descriptors.size());
        }

        // A single small class is worth showing directly; a tree is not.
        if (stats.optInt("fileCount", 0) == 1 && stats.optLong("totalBytes", 0) <= INLINE_LIMIT_BYTES) {
            File only = firstSmaliFile(outDir);
            if (only != null) {
                stats.put("smali", readText(only, INLINE_LIMIT_BYTES));
            }
        }
        if (!log.isEmpty()) {
            stats.put("engineLog", truncate(log, 2000));
        }
        return stats;
    }

    public static JSONObject assemble(List<String> inputs, File outDex, int apiLevel)
            throws Exception {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("no smali input given");
        }

        SmaliOptions options = new SmaliOptions();
        options.apiLevel = apiLevel;
        options.outputDexFile = outDex.getAbsolutePath();
        options.jobs = 1;
        options.verboseErrors = true;

        String log;
        boolean ok;
        synchronized (SmaliTool.class) {
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            try {
                PrintStream sink = new PrintStream(captured, true, "UTF-8");
                System.setOut(sink);
                System.setErr(sink);
                ok = Smali.assemble(options, inputs);
            } finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
            log = captured.toString("UTF-8");
        }

        JSONObject out = new JSONObject();
        out.put("assembled", ok);
        out.put("outputDex", outDex.getAbsolutePath());
        if (outDex.exists()) {
            out.put("dexBytes", outDex.length());
        }
        if (!ok) {
            throw new IllegalStateException("smali assembly failed:\n" + truncate(log, 4000));
        }
        if (!log.isEmpty()) {
            out.put("engineLog", truncate(log, 2000));
        }
        return out;
    }

    /**
     * Turns a loose name into the exact class descriptors baksmali wants.
     *
     * <p>Accepting a substring is worth the extra pass: an agent that just came
     * back from {@code dex_search} has a fragment of a name, not a descriptor.
     */
    private static List<String> resolveClasses(List<DexFile> dexFiles, String filter) {
        String needle = filter.toLowerCase(Locale.ROOT);
        List<String> found = new ArrayList<>();
        for (DexFile dex : dexFiles) {
            for (ClassDef def : dex.getClasses()) {
                String type = def.getType();
                if (type.equals(filter) || type.toLowerCase(Locale.ROOT).contains(needle)) {
                    found.add(type);
                }
            }
        }
        return found;
    }

    private static JSONObject summarizeDirectory(File dir) throws Exception {
        File[] files = dir.listFiles();
        int count = 0;
        long bytes = 0;
        if (files != null) {
            List<File> stack = new ArrayList<>();
            Collections.addAll(stack, files);
            while (!stack.isEmpty()) {
                File f = stack.remove(stack.size() - 1);
                if (f.isDirectory()) {
                    File[] children = f.listFiles();
                    if (children != null) {
                        Collections.addAll(stack, children);
                    }
                } else {
                    count++;
                    bytes += f.length();
                }
            }
        }
        JSONObject out = new JSONObject();
        out.put("fileCount", count);
        out.put("totalBytes", bytes);
        return out;
    }

    private static File firstSmaliFile(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                File nested = firstSmaliFile(f);
                if (nested != null) {
                    return nested;
                }
            } else if (f.getName().endsWith(".smali")) {
                return f;
            }
        }
        return null;
    }

    private static String readText(File file, int limit) {
        try {
            byte[] data = new byte[(int) Math.min(file.length(), limit)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int read = 0;
                while (read < data.length) {
                    int n = in.read(data, read, data.length - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
            }
            return new String(data, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        String flat = value.trim();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "\n... (truncated)";
    }
}
