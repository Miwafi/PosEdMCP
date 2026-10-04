package dev.posedmcp.state;

import android.os.Build;

import org.json.JSONObject;

import dev.posedmcp.Logx;
import dev.posedmcp.root.RootShell;

/**
 * Facts about the device and the module that are expensive or intrusive to
 * re-derive on every call.
 *
 * <p>The root probe runs {@code su -c id} once, when the user starts the
 * service. It is deliberately not exposed as a tool: an agent must not be able
 * to trigger a root shell outside the confirmation gate, not even a harmless
 * one, or the gate stops meaning anything.
 */
public final class DeviceStatus {

    private static volatile Boolean rootAvailable;
    private static volatile long probedAt;

    private DeviceStatus() {
    }

    public static void probeRootAsync() {
        Thread t = new Thread(() -> {
            boolean available = RootShell.isAvailable();
            rootAvailable = available;
            probedAt = System.currentTimeMillis();
            if (available) {
                Logx.i("root probe: available via " + RootShell.suPath());
            } else {
                Logx.w("root probe: unavailable via " + RootShell.suPath()
                        + ". Most common cause: the root manager hides su from apps"
                        + " (Magisk SuList / \"hide\" mode). Add PosEdMCP to the allow list"
                        + " or turn that mode off, then restart the service.");
            }
        }, "posedmcp-root-probe");
        t.setDaemon(true);
        t.start();
    }

    public static JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("manufacturer", Build.MANUFACTURER);
            o.put("model", Build.MODEL);
            o.put("device", Build.DEVICE);
            o.put("androidRelease", Build.VERSION.RELEASE);
            o.put("sdkInt", Build.VERSION.SDK_INT);
            o.put("securityPatch", Build.VERSION.SECURITY_PATCH);
            o.put("supportedAbis", String.join(",", Build.SUPPORTED_ABIS));
            o.put("buildId", Build.DISPLAY);

            Boolean root = rootAvailable;
            o.put("rootAvailable", root == null ? JSONObject.NULL : root);
            if (root != null) {
                o.put("rootProbedAt", probedAt);
            }
            o.put("suPath", RootShell.suPath());
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static JSONObject rootJson() {
        JSONObject o = new JSONObject();
        try {
            Boolean root = rootAvailable;
            o.put("available", root == null ? JSONObject.NULL : root);
            o.put("suPath", RootShell.suPath());
            o.put("note", "Probed once when the MCP service started. Individual commands are"
                    + " always confirmed regardless of this value.");
            o.put("diagnostics", RootShell.diagnostics());
        } catch (Throwable ignored) {
        }
        return o;
    }
}
