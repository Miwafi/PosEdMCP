package dev.posedmcp.xposed;

import dev.posedmcp.plugin.HookApi;

/**
 * Which hook framework loaded this module, and the one thing that depends on it.
 *
 * <p>The module ships two entry points and does not choose between them — the
 * framework invokes the one it is built for. That is not a preference that
 * could be expressed any other way: the classic entry is handed an
 * {@code XposedBridge}, the modern one is handed an {@code XposedInterface}, and
 * neither can reach the other's. Which backend this module runs on was decided
 * by whoever called it.
 *
 * <p>So this is where the two entries meet. Whichever ran records itself here,
 * and everything downstream — the hook registry, {@code app.hook} in the Lua
 * runtime — asks for a {@link HookApi} without caring which one arrives.
 *
 * <p>Why both exist at all: Vector is LSPosed rewritten by the same author, and
 * it loads classic modules through a compatibility bridge that is thinner than
 * the real thing. Measured on Vector 2.2, {@code AndroidAppHelper.currentApplication()}
 * returns null there, so {@code app.context()} inside a scoped process was
 * always nil. The framework's own release notes describe the legacy bridge as
 * the fragile half. Running on each framework's native API is the way out of
 * emulating one with the other.
 */
public final class Framework {

    public enum Backend {
        /** {@code de.robv.android.xposed}, the 2012 API. LSPosed 1.x, and Vector's bridge. */
        CLASSIC,
        /** {@code io.github.libxposed.api}, the API Vector is built on. */
        LIBXPOSED,
    }

    /** Builds a {@link HookApi} for one application's class loader. */
    public interface Hooks {
        HookApi forClassLoader(ClassLoader loader);
    }

    private static volatile Backend backend = Backend.CLASSIC;
    private static volatile Hooks hooks = XposedHookApi::new;
    private static volatile String version = "";

    private Framework() {
    }

    /** Called by the classic entry, once per process. */
    public static void adoptClassic(String reportedVersion) {
        backend = Backend.CLASSIC;
        hooks = XposedHookApi::new;
        version = reportedVersion == null ? "" : reportedVersion;
    }

    /** Called by the modern entry, once per process. */
    public static void adoptLibXposed(Hooks factory, String reportedVersion) {
        backend = Backend.LIBXPOSED;
        hooks = factory;
        version = reportedVersion == null ? "" : reportedVersion;
    }

    public static Backend backend() {
        return backend;
    }

    /** One line for the log and for {@code module_status}. */
    public static String describe() {
        String name = backend == Backend.LIBXPOSED ? "libxposed" : "classic xposed";
        return version.isEmpty() ? name : name + " " + version;
    }

    public static HookApi hookApi(ClassLoader loader) {
        return hooks.forClassLoader(loader);
    }

    /**
     * Whether this process is system_server.
     *
     * <p>Classic Xposed reports system_server as package {@code android}, and
     * this module originally tested for exactly that — which is why the system
     * hooks were never installed: LSPosed delivered the callback with the name
     * of whichever system package was loading. Three signals are checked,
     * cheapest first, because getting this wrong silently downgrades every
     * system-level feature. The last one reads this process's own
     * {@code /proc/self/cmdline}, which is authoritative and does not depend on
     * what the framework chose to pass us.
     */
    public static boolean isSystemServer(String packageName, String processName) {
        if ("android".equals(packageName)) {
            return true;
        }
        if ("system_server".equals(processName) || "system".equals(processName)) {
            return true;
        }
        return "system_server".equals(processNameFromCmdline());
    }

    private static String processNameFromCmdline() {
        try (java.io.FileInputStream in = new java.io.FileInputStream("/proc/self/cmdline")) {
            byte[] buffer = new byte[128];
            int read = in.read(buffer);
            if (read <= 0) {
                return null;
            }
            int end = 0;
            while (end < read && buffer[end] != 0) {
                end++;
            }
            return new String(buffer, 0, end, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
