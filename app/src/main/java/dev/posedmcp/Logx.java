package dev.posedmcp;

import android.util.Log;

/**
 * Logging that survives being called from system_server, where an uncaught
 * throw would take the whole system down. Everything is best-effort.
 */
public final class Logx {

    public static final String TAG = "PosEdMCP";

    private Logx() {
    }

    public static void i(String msg) {
        log(Log.INFO, msg);
    }

    public static void w(String msg) {
        log(Log.WARN, msg);
    }

    public static void e(String msg) {
        log(Log.ERROR, msg);
    }

    public static void e(String msg, Throwable t) {
        try {
            Log.e(TAG, msg, t);
        } catch (Throwable ignored) {
        }
    }

    private static void log(int priority, String msg) {
        try {
            Log.println(priority, TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    /** Never let diagnostics become the failure. */
    public static String stack(Throwable t) {
        if (t == null) {
            return "null";
        }
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
}
