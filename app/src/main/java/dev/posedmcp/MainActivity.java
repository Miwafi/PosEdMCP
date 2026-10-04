package dev.posedmcp;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import dev.posedmcp.a11y.AccessibilityBridge;
import dev.posedmcp.ipc.BridgeCredentials;
import dev.posedmcp.state.Prefs;

/**
 * Status and controls. Deliberately plain: this app exists to run a service,
 * and the screen it needs is a way to see whether that service is healthy and
 * to hand the user the endpoint details.
 */
public class MainActivity extends Activity {

    private static final int REQUEST_NOTIFICATIONS = 100;

    private Prefs prefs;
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.of(this);
        prefs.ensureTokens();
        BridgeCredentials.publish(this, prefs.bridgeToken(), prefs.bridgePort());

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        content.setPadding(pad, pad, pad, pad);
        scroll.addView(content);
        setContentView(scroll);

        ensureNotificationPermission();

        // Opening the app is the user asking for the server to be up; there is
        // no other way to start it without adb.
        McpService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        content.removeAllViews();

        TextView title = text("PosEdMCP", 24, true);
        content.addView(title);
        content.addView(muted("Root-backed MCP server for on-device agents."));

        // ---- status -------------------------------------------------------
        McpService service = McpService.instance();
        boolean running = service != null && service.isRunning();

        content.addView(section("STATUS"));
        content.addView(keyValue("Service", running ? "running" : "stopped"));
        if (running) {
            content.addView(keyValue("MCP endpoint", "http://127.0.0.1:" + service.mcpPort() + "/mcp"));
            content.addView(keyValue("Bridge port", String.valueOf(service.bridgePort())));
            content.addView(keyValue("System bridge",
                    service.systemBridgeConnected() ? "connected" : "offline"));
            content.addView(keyValue("Module processes", service.connectedPeers() + " connected"));
        }
        content.addView(keyValue("Overlay permission",
                Settings.canDrawOverlays(this) ? "granted" : "NOT granted"));
        content.addView(keyValue("Battery",
                isBatteryExempt() ? "unrestricted" : "OPTIMISED - the service will freeze"));
        content.addView(keyValue("Accessibility",
                AccessibilityBridge.isConnected() ? "enabled" : "NOT enabled"));
        if (!AccessibilityBridge.isConnected()) {
            content.addView(muted("Accessibility is what keeps this app running: an application"
                    + " hosting an enabled accessibility service holds a system binding, so it is"
                    + " not frozen once it leaves the screen. Without it the MCP endpoint goes"
                    + " silent exactly when an agent in another app tries to use it. It is also"
                    + " what provides screen capture, gestures and the view tree without root."));
        }
        if (!isBatteryExempt()) {
            content.addView(muted("Battery optimisation also freezes the process in the"
                    + " background. Grant unrestricted battery use, and on ColorOS also allow"
                    + " background activity for PosEdMCP in the battery settings."));
        }
        content.addView(muted("Without the overlay permission, approval prompts fall back to a"
                + " notification. If that also fails, privileged calls are refused."));

        // ---- endpoint -----------------------------------------------------
        content.addView(section("ENDPOINT"));
        final String url = "http://127.0.0.1:" + prefs.mcpPort() + "/mcp";
        content.addView(mono(url));
        content.addView(muted("Bearer token"));
        content.addView(mono(prefs.mcpToken()));

        LinearLayout tokenRow = row();
        tokenRow.addView(button("Copy URL", v -> copy("PosEdMCP URL", url)));
        tokenRow.addView(button("Copy token", v -> copy("PosEdMCP token", prefs.mcpToken())));
        tokenRow.addView(button("Rotate", v -> {
            prefs.rotateTokens();
            if (McpService.instance() != null) {
                McpService.stop(this);
                McpService.start(this);
            }
            toast("Tokens rotated");
            render();
        }));
        content.addView(tokenRow);

        content.addView(muted("The listener binds to 127.0.0.1 only. To reach it from a PC over"
                + " USB: adb forward tcp:" + prefs.mcpPort() + " tcp:" + prefs.mcpPort()));

        // ---- actions ------------------------------------------------------
        content.addView(section("ACTIONS"));
        LinearLayout serviceRow = row();
        serviceRow.addView(button(running ? "Stop service" : "Start service", v -> {
            if (McpService.instance() != null && McpService.instance().isRunning()) {
                McpService.stop(this);
            } else {
                McpService.start(this);
            }
            content.postDelayed(this::render, 600L);
        }));
        serviceRow.addView(button("Overlay settings", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) {
                toast("Could not open overlay settings");
            }
        }));
        serviceRow.addView(button("Battery settings", v -> openBatterySettings()));
        serviceRow.addView(button("Accessibility", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                toast("Turn on PosEdMCP in the list");
            } catch (Throwable t) {
                toast("Could not open accessibility settings");
            }
        }));
        content.addView(serviceRow);

        // ---- confirmation policy -----------------------------------------
        content.addView(section("CONFIRMATION POLICY"));
        content.addView(muted("Root shell commands always prompt and cannot be turned off."
                + " The others can be relaxed, because they use the module's platform access"
                + " rather than a shell."));
        content.addView(toggle("Confirm screen capture and UI dumps", prefs.confirmScreen(),
                checked -> prefs.setConfirm("confirm_screen", checked)));
        content.addView(toggle("Confirm injected input", prefs.confirmInput(),
                checked -> prefs.setConfirm("confirm_input", checked)));
        content.addView(toggle("Confirm plugin loading and calls", prefs.confirmPlugin(),
                checked -> prefs.setConfirm("confirm_plugin", checked)));
        content.addView(toggle("Start automatically after reboot", prefs.autostart(),
                checked -> prefs.setAutostart(checked)));

        // ---- tools --------------------------------------------------------
        content.addView(section("TOOLS"));
        content.addView(muted("Read-only tools never prompt. Everything else asks the user"
                + " before it runs."));
        content.addView(mono(toolSummary()));
    }

    private String toolSummary() {
        String[][] tools = {
                {"device_info", "read-only"},
                {"module_status", "read-only"},
                {"list_packages", "read-only"},
                {"foreground_app", "read-only"},
                {"events_poll", "read-only"},
                {"plugin_list", "read-only"},
                {"screen_capture", "prompts"},
                {"ui_dump", "prompts"},
                {"input_inject", "prompts"},
                {"root_shell_exec", "always prompts"},
                {"plugin_load", "prompts"},
                {"plugin_invoke", "prompts"},
        };
        StringBuilder sb = new StringBuilder();
        for (String[] tool : tools) {
            sb.append(pad(tool[0], 18)).append(tool[1]).append('\n');
        }
        return sb.toString().trim();
    }

    private static String pad(String value, int width) {
        StringBuilder sb = new StringBuilder(value);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    // ---- small view helpers ----------------------------------------------

    private TextView text(String value, float size, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(size);
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return tv;
    }

    private TextView muted(String value) {
        TextView tv = text(value, 13, false);
        tv.setAlpha(0.7f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView mono(String value) {
        TextView tv = text(value, 13, false);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setBackgroundColor(0x22000000);
        int p = dp(10);
        tv.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView section(String value) {
        TextView tv = text(value, 12, true);
        tv.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(26);
        lp.bottomMargin = dp(4);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView keyValue(String key, String value) {
        TextView tv = text(key + ":  " + value, 14, false);
        tv.setPadding(0, dp(3), 0, 0);
        return tv;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.START);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        row.setLayoutParams(lp);
        return row;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        button.setLayoutParams(lp);
        return button;
    }

    private interface OnChecked {
        void onChecked(boolean checked);
    }

    private View toggle(String label, boolean initial, OnChecked listener) {
        Switch toggle = new Switch(this);
        toggle.setText(label);
        toggle.setChecked(initial);
        toggle.setTextSize(14);
        toggle.setPadding(0, dp(10), 0, dp(10));
        toggle.setOnCheckedChangeListener((v, checked) -> listener.onChecked(checked));
        return toggle;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private void copy(String label, String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
            toast("Copied");
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private boolean isBatteryExempt() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private void openBatterySettings() {
        // Asking directly is the shortest path; some ROMs refuse it, so fall
        // back to the list the user can pick from.
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
            return;
        } catch (Throwable ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Throwable t) {
            toast("Could not open battery settings");
        }
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        }
    }
}
