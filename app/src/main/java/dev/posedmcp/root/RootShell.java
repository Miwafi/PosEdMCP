package dev.posedmcp.root;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import dev.posedmcp.Logx;

/**
 * Runs a command as root through {@code su}.
 *
 * <p>This class deliberately has no policy of its own: it executes whatever it
 * is given. Every caller must go through {@link ConfirmationGate} first, which
 * is the only thing standing between an agent and an unconfirmed root shell.
 *
 * <p>Note that {@code su} is invoked with piped stdio rather than through a
 * pseudo-terminal, because a pty rewrites {@code \n} to {@code \r\n} and
 * corrupts binary output such as {@code screencap}.
 */
public final class RootShell {

    /** Cap on each captured stream, so one runaway command cannot exhaust memory. */
    public static final int MAX_STREAM_BYTES = 1 << 20;

    private static volatile String suPath;
    private static volatile String lastProbeError;

    private RootShell() {
    }

    public static final class Result {
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final boolean timedOut;
        public final long durationMs;
        public final String command;

        Result(String command, int exitCode, String stdout, String stderr, boolean timedOut,
                long durationMs) {
            this.command = command;
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.timedOut = timedOut;
            this.durationMs = durationMs;
        }

        public boolean ok() {
            return !timedOut && exitCode == 0;
        }
    }

    /** Resolves the su binary once per process. */
    public static String suPath() {
        String cached = suPath;
        if (cached != null) {
            return cached;
        }
        String[] candidates = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su",
                "/debug_ramdisk/su", "/system/bin/magisk",
        };
        for (String c : candidates) {
            if (new File(c).exists()) {
                Logx.i("su resolved to " + c);
                suPath = c;
                return c;
            }
        }
        // Magisk hides its mounts from many app processes, so the symlink can be
        // invisible here even though a PATH lookup still resolves it.
        Logx.w("no su at a known path; falling back to PATH lookup for \"su\"");
        suPath = "su";
        return "su";
    }

    /** True when {@code su} actually hands back uid 0. */
    public static boolean isAvailable() {
        try {
            Result r = execRaw("id", 15_000L);
            boolean ok = r.stdout.contains("uid=0");
            if (!ok) {
                lastProbeError = "exit=" + r.exitCode
                        + " stdout=[" + trim(r.stdout) + "] stderr=[" + trim(r.stderr) + "]";
                Logx.w("root probe failed: " + lastProbeError);
            } else {
                lastProbeError = null;
            }
            return ok;
        } catch (Throwable t) {
            lastProbeError = String.valueOf(t);
            Logx.w("root probe threw: " + t);
            return false;
        }
    }

    private static String trim(String value) {
        if (value == null) {
            return "";
        }
        String flat = value.trim().replace('\n', ' ');
        return flat.length() > 200 ? flat.substring(0, 200) + "..." : flat;
    }

    /**
     * What this process can actually see, for when {@code su} is missing.
     *
     * <p>Root managers that use an allow-list (Magisk's SuList, KernelSU's
     * manager) hide {@code su} from every process not on the list, so an app can
     * be rooted and still get "No such file or directory". Reporting the view
     * from inside the app process is the only way to tell that apart from a
     * device that simply is not rooted.
     */
    public static JSONObject diagnostics() {
        JSONObject o = new JSONObject();
        try {
            JSONArray paths = new JSONArray();
            for (String candidate : new String[]{
                    "/system/bin/su", "/system/xbin/su", "/sbin/su",
                    "/debug_ramdisk/su", "/system/bin/magisk"}) {
                JSONObject entry = new JSONObject();
                entry.put("path", candidate);
                entry.put("exists", new File(candidate).exists());
                paths.put(entry);
            }
            o.put("candidates", paths);
            o.put("path", String.valueOf(System.getenv("PATH")));
            o.put("probeError", lastProbeError);
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static Result exec(String command, long timeoutMs) {
        return execRaw(command, timeoutMs);
    }

    static Result execRaw(String command, long timeoutMs) {
        long started = System.currentTimeMillis();
        List<String> argv = new ArrayList<>();
        argv.add(suPath());
        argv.add("-c");
        argv.add(command);

        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(false);
            process = pb.start();
        } catch (Throwable t) {
            return new Result(command, -1, "", "could not run "
                    + suPath() + ": " + t
                    + "\nIf this device is rooted, the root manager is probably hiding"
                    + " su from apps (Magisk SuList mode, or an equivalent allow list).",
                    false, System.currentTimeMillis() - started);
        }

        StreamDrain outDrain = new StreamDrain(process.getInputStream(), MAX_STREAM_BYTES);
        StreamDrain errDrain = new StreamDrain(process.getErrorStream(), MAX_STREAM_BYTES);
        Thread outThread = new Thread(outDrain, "posedmcp-su-stdout");
        Thread errThread = new Thread(errDrain, "posedmcp-su-stderr");
        outThread.setDaemon(true);
        errThread.setDaemon(true);
        outThread.start();
        errThread.start();

        // su reads the command from argv, so nothing needs writing to stdin.
        try {
            OutputStream stdin = process.getOutputStream();
            stdin.close();
        } catch (Throwable ignored) {
        }

        boolean timedOut = false;
        try {
            if (!process.waitFor(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS)) {
                timedOut = true;
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            timedOut = true;
        }

        joinQuietly(outThread);
        joinQuietly(errThread);

        int exit = -1;
        try {
            exit = process.exitValue();
        } catch (Throwable ignored) {
        }

        return new Result(command, exit, outDrain.text(), errDrain.text(), timedOut,
                System.currentTimeMillis() - started);
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join(2000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Reads a stream to exhaustion on its own thread so the process never blocks. */
    private static final class StreamDrain implements Runnable {
        private final InputStream in;
        private final int limit;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private volatile boolean truncated;

        StreamDrain(InputStream in, int limit) {
            this.in = in;
            this.limit = limit;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            try {
                int n;
                while ((n = in.read(chunk)) > 0) {
                    if (buffer.size() < limit) {
                        buffer.write(chunk, 0, Math.min(n, limit - buffer.size()));
                    } else {
                        truncated = true;
                    }
                }
            } catch (Throwable t) {
                Logx.w("stream drain ended: " + t);
            }
        }

        String text() {
            String s = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            return truncated ? s + "\n[... output truncated at " + limit + " bytes ...]" : s;
        }
    }

    /** Raw bytes, for callers that must not corrupt binary output. */
    public static byte[] execBinary(String command, long timeoutMs) throws IOException {
        Process process = new ProcessBuilder(suPath(), "-c", command).start();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        StreamDrain drain = new StreamDrain(process.getInputStream(), 32 << 20);
        Thread t = new Thread(drain, "posedmcp-su-binary");
        t.setDaemon(true);
        t.start();
        try {
            if (!process.waitFor(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("command timed out after " + timeoutMs + "ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("interrupted");
        }
        joinQuietly(t);
        buffer.write(drain.buffer.toByteArray());
        return buffer.toByteArray();
    }
}
