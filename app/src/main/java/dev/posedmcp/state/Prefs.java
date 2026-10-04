package dev.posedmcp.state;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/**
 * Settings for the MCP server and for the confirmation policy.
 *
 * <p>Only the app process uses this. The LSPosed module code running inside
 * system_server and inside scoped apps reads the bridge token straight out of
 * this same file (see {@code BridgeAuth}).
 */
public final class Prefs {

    public static final String FILE = "posedmcp";

    private static final String KEY_MCP_PORT = "mcp_port";
    private static final String KEY_BRIDGE_PORT = "bridge_port";
    private static final String KEY_MCP_TOKEN = "mcp_token";
    private static final String KEY_BRIDGE_TOKEN = "bridge_token";
    private static final String KEY_CONFIRM_SCREEN = "confirm_screen";
    private static final String KEY_CONFIRM_INPUT = "confirm_input";
    private static final String KEY_CONFIRM_PLUGIN = "confirm_plugin";
    private static final String KEY_CONFIRM_TIMEOUT = "confirm_timeout_ms";
    private static final String KEY_EXEC_TIMEOUT = "exec_timeout_ms";
    private static final String KEY_AUTOSTART = "autostart";

    public static final int DEFAULT_MCP_PORT = 8765;
    public static final int DEFAULT_BRIDGE_PORT = 8766;

    private final SharedPreferences sp;

    private Prefs(SharedPreferences sp) {
        this.sp = sp;
    }

    public static Prefs of(Context ctx) {
        return new Prefs(ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE));
    }

    private static String randomToken() {
        byte[] buf = new byte[32];
        new SecureRandom().nextBytes(buf);
        StringBuilder sb = new StringBuilder(buf.length * 2);
        for (byte b : buf) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * Creates both tokens if they do not exist yet, and waits for the write.
     *
     * <p>This has to be synchronous and has to happen away from any getter.
     * The LSPosed module reads these tokens out of this file from other
     * processes, so a token that exists only in this process's memory — which is
     * exactly what {@code apply()} can leave behind if the process dies — would
     * lock the bridge out of its own server.
     */
    public void ensureTokens() {
        SharedPreferences.Editor editor = sp.edit();
        boolean changed = false;
        if (sp.getString(KEY_MCP_TOKEN, null) == null) {
            editor.putString(KEY_MCP_TOKEN, randomToken());
            changed = true;
        }
        if (sp.getString(KEY_BRIDGE_TOKEN, null) == null) {
            editor.putString(KEY_BRIDGE_TOKEN, randomToken());
            changed = true;
        }
        if (changed) {
            editor.commit();
        }
    }

    /** Token MCP clients must present. Empty until {@link #ensureTokens()} has run. */
    public String mcpToken() {
        return sp.getString(KEY_MCP_TOKEN, "");
    }

    /** Token in-process bridge clients must present. */
    public String bridgeToken() {
        return sp.getString(KEY_BRIDGE_TOKEN, "");
    }

    public int mcpPort() {
        return sp.getInt(KEY_MCP_PORT, DEFAULT_MCP_PORT);
    }

    public void setMcpPort(int port) {
        sp.edit().putInt(KEY_MCP_PORT, port).apply();
    }

    public int bridgePort() {
        return sp.getInt(KEY_BRIDGE_PORT, DEFAULT_BRIDGE_PORT);
    }

    public void setBridgePort(int port) {
        sp.edit().putInt(KEY_BRIDGE_PORT, port).apply();
    }

    /** Screen capture and UI dumps expose whatever is on screen. */
    public boolean confirmScreen() {
        return sp.getBoolean(KEY_CONFIRM_SCREEN, true);
    }

    /** Synthetic input can drive any app, so it is gated by default. */
    public boolean confirmInput() {
        return sp.getBoolean(KEY_CONFIRM_INPUT, true);
    }

    /** Loading code into another app's process. */
    public boolean confirmPlugin() {
        return sp.getBoolean(KEY_CONFIRM_PLUGIN, true);
    }

    public void setConfirm(String key, boolean value) {
        sp.edit().putBoolean(key, value).apply();
    }

    public long confirmTimeoutMs() {
        return sp.getLong(KEY_CONFIRM_TIMEOUT, 120_000L);
    }

    public void setConfirmTimeoutMs(long ms) {
        sp.edit().putLong(KEY_CONFIRM_TIMEOUT, ms).apply();
    }

    public long execTimeoutMs() {
        return sp.getLong(KEY_EXEC_TIMEOUT, 60_000L);
    }

    public boolean autostart() {
        return sp.getBoolean(KEY_AUTOSTART, true);
    }

    public void setAutostart(boolean value) {
        sp.edit().putBoolean(KEY_AUTOSTART, value).apply();
    }

    /**
     * Rotates both tokens. Existing clients are cut off, which is the point:
     * the MCP endpoint has no other authentication. Committed synchronously so
     * the module's copy cannot go stale.
     */
    public void rotateTokens() {
        sp.edit()
                .putString(KEY_MCP_TOKEN, randomToken())
                .putString(KEY_BRIDGE_TOKEN, randomToken())
                .commit();
    }
}
