package dev.posedmcp;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import dev.posedmcp.a11y.AccessibilityBridge;
import dev.posedmcp.ipc.BridgeCredentials;
import dev.posedmcp.mcp.McpTool;
import dev.posedmcp.state.Prefs;
import dev.posedmcp.state.SavedScript;
import dev.posedmcp.state.ScriptStore;
import dev.posedmcp.xposed.LuaRuntime;

/**
 * Status, controls and the automation library.
 *
 * <p>Deliberately plain: this app exists to run a service, and the screen it
 * needs is a way to see whether that service is healthy, hand the user the
 * endpoint details, and run or remove the scripts the agent filed for them.
 */
public class MainActivity extends Activity {

    private static final int REQUEST_NOTIFICATIONS = 100;

    private Prefs prefs;
    private LinearLayout statusContent;
    private LinearLayout scriptsContent;
    private ScrollView statusScroll;
    private ScrollView scriptsScroll;
    private FrameLayout tabContent;
    private Button statusTab;
    private Button scriptsTab;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.of(this);
        prefs.ensureTokens();
        BridgeCredentials.publish(this, prefs.bridgeToken(), prefs.bridgePort());

        statusContent = column();
        scriptsContent = column();
        statusScroll = scrolled(statusContent);
        scriptsScroll = scrolled(scriptsContent);

        // A tab strip built by hand rather than with TabHost, which cost two
        // platform quirks in a row: setIndicator(CharSequence) inflates a
        // framework layout that no longer resolves on Android 16 and takes the
        // activity down with it, and tapping a custom indicator did not switch
        // tabs. Neither is worth carrying a framework widget for two fixed tabs.
        statusTab = tabLabel("Status");
        scriptsTab = tabLabel("Scripts");
        statusTab.setOnClickListener(v -> selectTab(0));
        scriptsTab.setOnClickListener(v -> selectTab(1));

        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.addView(statusTab, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        strip.addView(scriptsTab, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tabContent = new FrameLayout(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // Apps targeting Android 15+ draw edge to edge, so without this the tab
        // strip sits under the status bar and taps at the top of the screen go to
        // the system instead of to the tabs.
        root.setFitsSystemWindows(true);
        root.addView(strip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(tabContent, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f));
        setContentView(root);
        selectTab(0);

        ensureNotificationPermission();

        // Opening the app is the user asking for the server to be up; there is
        // no other way to start it without adb.
        McpService.start(this);
    }

    private void selectTab(int index) {
        tabContent.removeAllViews();
        tabContent.addView(index == 0 ? statusScroll : scriptsScroll);
        statusTab.setBackgroundColor(index == 0 ? 0x22000000 : 0x00000000);
        scriptsTab.setBackgroundColor(index == 1 ? 0x22000000 : 0x00000000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderStatus();
        renderScripts();
    }

    // ---- status tab --------------------------------------------------------

    private void renderStatus() {
        statusContent.removeAllViews();

        TextView title = text("PosEdMCP", 24, true);
        statusContent.addView(title);
        statusContent.addView(muted("Root-backed MCP server for on-device agents."));

        McpService service = McpService.instance();
        boolean running = service != null && service.isRunning();

        statusContent.addView(section("STATUS"));
        statusContent.addView(keyValue("Service", running ? "running" : "stopped"));
        if (running) {
            statusContent.addView(keyValue("MCP endpoint",
                    "http://127.0.0.1:" + service.mcpPort() + "/mcp"));
            statusContent.addView(keyValue("Bridge port", String.valueOf(service.bridgePort())));
            statusContent.addView(keyValue("System bridge",
                    service.systemBridgeConnected() ? "connected" : "offline"));
            statusContent.addView(keyValue("Module processes",
                    service.connectedPeers() + " connected"));
        }
        statusContent.addView(keyValue("Overlay permission",
                Settings.canDrawOverlays(this) ? "granted" : "NOT granted"));
        statusContent.addView(keyValue("Battery",
                isBatteryExempt() ? "unrestricted" : "OPTIMISED - the service will freeze"));
        statusContent.addView(keyValue("Accessibility",
                AccessibilityBridge.isConnected() ? "enabled" : "NOT enabled"));
        if (!AccessibilityBridge.isConnected()) {
            statusContent.addView(muted("Accessibility is what keeps this app running: an"
                    + " application hosting an enabled accessibility service holds a system"
                    + " binding, so it is not frozen once it leaves the screen. Without it the MCP"
                    + " endpoint goes silent exactly when an agent in another app tries to use it."
                    + " It is also what provides screen capture, gestures and the view tree"
                    + " without root."));
        }
        if (!isBatteryExempt()) {
            statusContent.addView(muted("Battery optimisation also freezes the process in the"
                    + " background. Grant unrestricted battery use, and on ColorOS also allow"
                    + " background activity for PosEdMCP in the battery settings."));
        }
        statusContent.addView(muted("Without the overlay permission, approval prompts fall back"
                + " to a notification. If that also fails, privileged calls are refused."));

        statusContent.addView(section("ENDPOINT"));
        final String url = "http://127.0.0.1:" + prefs.mcpPort() + "/mcp";
        statusContent.addView(mono(url));
        statusContent.addView(muted("Bearer token"));
        statusContent.addView(mono(prefs.mcpToken()));

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
            renderStatus();
        }));
        statusContent.addView(tokenRow);

        statusContent.addView(muted("The listener binds to 127.0.0.1 only. To reach it from a PC"
                + " over USB: adb forward tcp:" + prefs.mcpPort() + " tcp:" + prefs.mcpPort()));

        statusContent.addView(section("ACTIONS"));
        LinearLayout serviceRow = row();
        serviceRow.addView(button(running ? "Stop service" : "Start service", v -> {
            if (McpService.instance() != null && McpService.instance().isRunning()) {
                McpService.stop(this);
            } else {
                McpService.start(this);
            }
            statusContent.postDelayed(this::renderStatus, 600L);
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
        statusContent.addView(serviceRow);

        statusContent.addView(section("CONFIRMATION POLICY"));
        statusContent.addView(muted("Root shell commands always prompt and cannot be turned off."
                + " The others can be relaxed, because they use the module's platform access"
                + " rather than a shell."));
        statusContent.addView(toggle("Confirm screen capture and UI dumps", prefs.confirmScreen(),
                checked -> prefs.setConfirm("confirm_screen", checked)));
        statusContent.addView(toggle("Confirm injected input", prefs.confirmInput(),
                checked -> prefs.setConfirm("confirm_input", checked)));
        statusContent.addView(toggle("Confirm plugin loading and calls", prefs.confirmPlugin(),
                checked -> prefs.setConfirm("confirm_plugin", checked)));
        statusContent.addView(toggle("Start automatically after reboot", prefs.autostart(),
                checked -> prefs.setAutostart(checked)));

        statusContent.addView(section("TOOLS"));
        statusContent.addView(muted("Read-only tools never prompt. Everything else asks the user"
                + " before it runs."));
        statusContent.addView(mono(toolSummary()));
    }

    /** Read off the registry rather than kept as a second list that goes stale. */
    private String toolSummary() {
        McpService service = McpService.instance();
        List<McpTool> tools = service == null ? Collections.emptyList() : service.tools();
        if (tools.isEmpty()) {
            return "(the service is not running)";
        }
        StringBuilder sb = new StringBuilder();
        for (McpTool tool : tools) {
            String kind = tool.readOnly ? "read-only"
                    : ("root_shell_exec".equals(tool.name) ? "ALWAYS prompts" : "prompts");
            sb.append(pad(tool.name, 20)).append(kind).append('\n');
        }
        return sb.toString().trim();
    }

    // ---- scripts tab -------------------------------------------------------

    private void renderScripts() {
        scriptsContent.removeAllViews();
        scriptsContent.addView(text("Saved scripts", 24, true));
        scriptsContent.addView(muted("Scripts the agent filed for you. Running one from here is"
                + " your own tap, so it runs without an approval prompt. The result of the last"
                + " run is kept under each script."));

        List<SavedScript> scripts = ScriptStore.of(this).all();
        if (scripts.isEmpty()) {
            scriptsContent.addView(section("NOTHING SAVED YET"));
            scriptsContent.addView(muted("Ask the agent to save a script and it appears here,"
                    + " with a line saying what it does."));
            return;
        }

        scriptsContent.addView(section(scripts.size() + (scripts.size() == 1 ? " SCRIPT" : " SCRIPTS")));
        for (SavedScript script : scripts) {
            scriptsContent.addView(scriptCard(script));
        }
    }

    private View scriptCard(SavedScript script) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        card.setPadding(pad, pad, pad, pad);
        card.setBackgroundColor(0x14000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        card.setLayoutParams(lp);

        card.addView(text(script.name, 16, true));
        card.addView(muted("in " + script.packageName));
        if (script.effect != null && !script.effect.isEmpty()) {
            card.addView(muted(script.effect));
        }
        TextView last = muted(lastRunLine(script));
        last.setTypeface(Typeface.MONOSPACE);
        card.addView(last);

        LinearLayout actions = row();
        actions.addView(button("Run", v -> runScript(script)));
        actions.addView(button("Open", v -> showSource(script)));
        actions.addView(button("Delete", v -> confirmDelete(script)));
        card.addView(actions);
        return card;
    }

    private String lastRunLine(SavedScript script) {
        if (script.lastRunAt == 0) {
            return "never run";
        }
        String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(script.lastRunAt));
        String outcome = script.lastOutcome == null || script.lastOutcome.isEmpty()
                ? "" : "\n" + script.lastOutcome;
        return (script.lastRunOk ? "ok  " : "FAILED  ") + when + outcome;
    }

    private void runScript(SavedScript script) {
        McpService service = McpService.instance();
        if (service == null || !service.isRunning()) {
            toast("Start the service first");
            return;
        }
        toast("Running " + script.name);
        // The bridge call blocks until the script finishes, so it cannot be on
        // the thread drawing this screen.
        new Thread(() -> {
            boolean ok;
            String summary;
            try {
                JSONObject result = service.runScript(script.packageName, script.source,
                        LuaRuntime.DEFAULT_MAX_INSTRUCTIONS);
                ok = result.optBoolean("ok", false);
                summary = summarize(result);
            } catch (Throwable t) {
                ok = false;
                summary = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            ScriptStore.of(this).recordRun(script.id, ok, trim(summary, 400));
            runOnUiThread(this::renderScripts);
        }, "posedmcp-script-run").start();
    }

    /** What the user needs to see: the value or the text it printed, or why it failed. */
    private static String summarize(JSONObject result) {
        String output = result.optString("output", "");
        if (!result.optBoolean("ok", false)) {
            String error = result.optString("error", "unknown error");
            return output.isEmpty() ? error : error + "\n" + output;
        }
        Object returned = result.opt("returned");
        String value = returned == null || returned == JSONObject.NULL
                ? "" : String.valueOf(returned);
        if (value.isEmpty()) {
            return output.isEmpty() ? "(finished, no output)" : output;
        }
        return output.isEmpty() ? value : value + "\n" + output;
    }

    private void showSource(SavedScript script) {
        TextView body = text(script.source, 12, false);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        int pad = dp(16);
        body.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);

        new AlertDialog.Builder(this)
                .setTitle(script.name)
                .setView(scroll)
                .setPositiveButton("Close", null)
                .setNeutralButton("Run", (dialog, which) -> runScript(script))
                .show();
    }

    private void confirmDelete(SavedScript script) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + script.name + "?")
                .setMessage("It is removed from the library. This cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    ScriptStore.of(this).delete(script.id);
                    renderScripts();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- small view helpers ------------------------------------------------

    private static String pad(String value, int width) {
        StringBuilder sb = new StringBuilder(value);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String trim(String value, int limit) {
        if (value == null) {
            return "";
        }
        String flat = value.trim();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }

    /**
     * A tab label.
     *
     * <p>A Button rather than a TextView: a plain TextView with a click listener
     * ignored a very short synthetic tap on this ROM, and a tab that sometimes
     * does nothing is worse than a slightly less tidy one.
     */
    private Button tabLabel(String label) {
        Button tab = new Button(this);
        tab.setText(label);
        tab.setAllCaps(false);
        tab.setTextSize(15);
        tab.setTypeface(Typeface.DEFAULT_BOLD);
        return tab;
    }

    private LinearLayout column() {        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        column.setPadding(pad, pad, pad, pad);
        return column;
    }

    private ScrollView scrolled(View content) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        return scroll;
    }

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
