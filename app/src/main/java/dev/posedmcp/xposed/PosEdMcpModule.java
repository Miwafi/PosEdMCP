package dev.posedmcp.xposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import dev.posedmcp.Logx;

/**
 * Module entry point, named in {@code assets/xposed_init}.
 *
 * <p>Scope decides what happens:
 * <ul>
 *   <li><b>System Framework</b> - {@link SystemHooks} runs inside system_server
 *       and provides screen capture, input injection, foreground tracking and
 *       screen-state events.</li>
 *   <li><b>any other scoped app</b> - {@link AppHost} runs inside that app's
 *       process and becomes a target for injected plugins.</li>
 * </ul>
 *
 * <p>Nothing throws out of the callbacks. A module that crashes the process it
 * was loaded into would be far more damaging than one that quietly does
 * nothing, so every failure is logged and swallowed.
 */
public class PosEdMcpModule implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String SYSTEM_PACKAGE = "android";
    private static final String SYSTEM_PROCESS = "system_server";

    @Override
    public void initZygote(StartupParam startupParam) {
        // Nothing is done this early: API 101+ forbids injecting into zygote
        // itself, and everything here is scoped per-process anyway.
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) {
            return;
        }
        try {
            String packageName = lpparam.packageName;
            String processName = lpparam.processName;

            if (isSystemServer(packageName, processName)) {
                log("installing system hooks in " + processName + " (for " + packageName + ")");
                SystemHooks.install(lpparam.classLoader);
                return;
            }

            log("installing app host in " + packageName);
            AppHost.install(packageName, lpparam.classLoader);
        } catch (Throwable t) {
            Logx.e("handleLoadPackage failed for " + safeName(lpparam), t);
        }
    }

    /**
     * Whether this callback is for system_server.
     *
     * <p>Classic Xposed reports system_server as package {@code android}, and this
     * module originally tested for exactly that - which is why the system hooks
     * were never installed: LSPosed instead delivered the callback with the name
     * of whichever system package was loading, and {@code AppHost} took it.
     *
     * <p>Three signals are checked, cheapest first, because getting this wrong
     * silently downgrades every system-level feature.
     */
    private static boolean isSystemServer(String packageName, String processName) {
        if (SYSTEM_PACKAGE.equals(packageName)) {
            return true;
        }
        if (SYSTEM_PROCESS.equals(processName) || "system".equals(processName)) {
            return true;
        }
        return SYSTEM_PROCESS.equals(processNameFromCmdline());
    }

    /**
     * Reads this process's own name from {@code /proc}, which is authoritative
     * and does not depend on what the framework chose to pass us.
     */
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

    private static String safeName(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            return lpparam.packageName + "/" + lpparam.processName;
        } catch (Throwable t) {
            return "<unknown>";
        }
    }

    private static void log(String message) {
        try {
            XposedBridge.log("PosEdMCP: " + message);
        } catch (Throwable ignored) {
        }
        Logx.i(message);
    }
}
