package dev.posedmcp.mcp;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import dev.posedmcp.Logx;
import dev.posedmcp.a11y.AccessibilityBridge;
import dev.posedmcp.dex.ApkInfo;
import dev.posedmcp.dex.DexClient;
import dev.posedmcp.ipc.BridgeServer;
import dev.posedmcp.root.ConfirmationGate;
import dev.posedmcp.root.RootShell;
import dev.posedmcp.state.DeviceStatus;
import dev.posedmcp.state.EventStore;
import dev.posedmcp.state.Prefs;
import dev.posedmcp.tools.Capabilities;

/**
 * The tool surface exposed to agents.
 *
 * <p>Two conventions run through every mutating tool:
 * <ul>
 *   <li>a <b>reason</b> argument is mandatory - it is shown to the user in the
 *       confirmation prompt, and a prompt with no reason is a prompt the user
 *       cannot evaluate;</li>
 *   <li>a tool whose implementation runs a shell command requires the SHELL
 *       confirmation, which cannot be disabled. Tools that use the module's
 *       platform privileges instead are gated by a relaxation-capable kind.</li>
 * </ul>
 */
public final class ToolRegistry {

    private static final int MAX_PACKAGES = 400;
    private static final int MAX_UI_NODES = 600;
    private static final long MAX_DEX_BYTES = 32L * 1024 * 1024;

    private final Context context;
    private final Prefs prefs;
    private final Capabilities capabilities;
    private final BridgeServer bridge;
    private final EventStore events;
    private final Map<String, McpTool> tools = new LinkedHashMap<>();

    /** Set from the MCP handshake so prompts can name the agent that asked. */
    private static final AtomicReference<String> REQUESTER = new AtomicReference<>("an MCP client");

    public ToolRegistry(Context context, Prefs prefs, Capabilities capabilities, BridgeServer bridge,
            EventStore events) {
        this.context = context;
        this.prefs = prefs;
        this.capabilities = capabilities;
        this.bridge = bridge;
        this.events = events;
        registerAll();
    }

    public static void setRequester(String name) {
        REQUESTER.set(name == null || name.isEmpty() ? "an MCP client" : name);
    }

    private static String requester() {
        return REQUESTER.get();
    }

    public McpTool get(String name) {
        return tools.get(name);
    }

    public List<McpTool> all() {
        return new ArrayList<>(tools.values());
    }

    public int size() {
        return tools.size();
    }

    private void add(McpTool tool) {
        tools.put(tool.name, tool);
    }

    // =====================================================================
    // Read-only tools
    // =====================================================================

    private void registerAll() {
        add(McpTool.of("device_info")
                .title("Device information")
                .description("Model, Android version, ABI, root availability and whether the"
                        + " LSPosed module is loaded. Call this first to learn what the device"
                        + " supports. Read-only, never prompts.")
                .readOnly()
                .input(new JSONObject())
                .handler(args -> {
                    JSONObject out = DeviceStatus.toJson();
                    out.put("root", DeviceStatus.rootJson());
                    out.put("module", moduleStatusJson());
                    out.put("displayProbe", dev.posedmcp.tools.DisplayProbe.describe());
                    return McpTool.json(out);
                })
                .build());

        add(McpTool.of("module_status")
                .title("Module and bridge status")
                .description("Which processes the LSPosed module is currently loaded into, and"
                        + " whether the system_server bridge is connected. Use this to tell"
                        + " whether system-privileged tools will work, or whether a target app"
                        + " still needs its scope enabled and the app restarted. Read-only.")
                .readOnly()
                .input(new JSONObject())
                .handler(args -> McpTool.json(moduleStatusJson()))
                .build());

        add(McpTool.of("list_packages")
                .title("List installed apps")
                .description("Installed packages with their labels. Filter with a substring."
                        + " Useful before targeting an app. Read-only.")
                .readOnly()
                .input(props(
                        "filter", McpTool.string("Case-insensitive substring of package name or label"),
                        "include_system", McpTool.type("boolean", "Include system apps (default false)"),
                        "limit", McpTool.integer("Maximum results, default 100")))
                .handler(args -> {
                    String filter = args.optString("filter", "").toLowerCase(Locale.ROOT);
                    boolean includeSystem = args.optBoolean("include_system", false);
                    int limit = args.optInt("limit", 100);
                    JSONArray packages = listPackages(filter, includeSystem, limit);
                    JSONObject out = new JSONObject();
                    out.put("packages", packages);
                    out.put("count", packages.length());
                    return McpTool.json(out);
                })
                .build());

        add(McpTool.of("foreground_app")
                .title("Current foreground app")
                .description("The activity that currently has focus, reported by the module inside"
                        + " system_server. Requires the module's system scope. Read-only.")
                .readOnly()
                .input(new JSONObject())
                .handler(args -> {
                    requireSystemBridge("foreground_app");
                    return McpTool.json(capabilities.systemForeground());
                })
                .build());

        add(McpTool.of("events_poll")
                .title("Poll device events")
                .description("Reads the event feed pushed by the module: foreground activity"
                        + " changes, screen on/off, user present, bridge connects and disconnects."
                        + " Pass the last seq you saw as 'since' to get only new events. Read-only.")
                .readOnly()
                .input(props(
                        "since", McpTool.integer("Return events with seq greater than this."
                                + " Omit to get the most recent events."),
                        "limit", McpTool.integer("Maximum events to return, default 100"),
                        "type", McpTool.string("Only return events whose type contains this substring")))
                .handler(args -> {
                    long since = args.has("since") ? args.optLong("since", 0L) : events.oldestSeq() - 1;
                    int limit = Math.max(1, Math.min(args.optInt("limit", 100), 500));
                    String typeFilter = args.optString("type", "").toLowerCase(Locale.ROOT);

                    JSONArray array = new JSONArray();
                    for (EventStore.Entry entry : events.since(since, limit)) {
                        if (!typeFilter.isEmpty()
                                && !entry.type.toLowerCase(Locale.ROOT).contains(typeFilter)) {
                            continue;
                        }
                        array.put(entry.toJson());
                    }
                    JSONObject out = new JSONObject();
                    out.put("events", array);
                    out.put("lastSeq", events.lastSeq());
                    out.put("oldestSeq", events.oldestSeq());
                    return McpTool.json(out);
                })
                .build());

        add(McpTool.of("plugin_list")
                .title("List loaded plugins")
                .description("Plugins currently loaded into scoped app processes, as reported by"
                        + " those processes. Read-only.")
                .readOnly()
                .input(props("package", McpTool.string("Only list plugins in this package")))
                .handler(args -> {
                    String pkg = args.optString("package", "");
                    JSONArray array = new JSONArray();
                    for (String candidate : connectedPackages()) {
                        if (!pkg.isEmpty() && !pkg.equals(candidate)) {
                            continue;
                        }
                        JSONObject entry = new JSONObject();
                        entry.put("package", candidate);
                        try {
                            JSONObject listed = capabilities.appCall(candidate, "list_plugins",
                                    new JSONObject(), 5_000L);
                            entry.put("plugins", listed.optJSONArray("plugins"));
                        } catch (Throwable t) {
                            entry.put("error", String.valueOf(t.getMessage()));
                        }
                        array.put(entry);
                    }
                    JSONObject out = new JSONObject();
                    out.put("packages", array);
                    return McpTool.json(out);
                })
                .build());

        // =================================================================
        // Static analysis
        // =================================================================

        add(McpTool.of("dex_classes")
                .title("List classes in a DEX or APK")
                .description("Lists the classes in a DEX file or in every classes*.dex of an APK,"
                        + " optionally with their method and field signatures."
                        + " This reads the index rather than disassembling, so it is cheap and is"
                        + " the right first step before picking a target to disassemble or hook."
                        + " Name the target with 'package' for an installed app, or 'path' for an"
                        + " APK/DEX file on disk. Read-only.")
                .readOnly()
                .input(props(
                        "package", McpTool.string("Installed package name, e.g. com.example.app"),
                        "path", McpTool.string("Absolute path to an .apk/.dex/.jar instead of a package"),
                        "filter", McpTool.string("Only classes whose name contains this substring"),
                        "with_members", McpTool.type("boolean", "Include method and field signatures"),
                        "limit", McpTool.integer("Maximum classes to return, default 200")))
                .handler(args -> {
                    JSONObject out = DexClient.get(context).call("classes", dexArgs(args),
                            60_000L);
                    return McpTool.json(out);
                })
                .build());

        add(McpTool.of("dex_search")
                .title("Search inside a DEX or APK")
                .description("Searches the string table, class names, method names or field names."
                        + " Use kind=string to find hard-coded text such as URLs, API keys, error"
                        + " messages or log tags; kind=method to jump straight to a method by name."
                        + " Name the target with 'package' or 'path'. Read-only.")
                .readOnly()
                .input(props(
                        "pattern", McpTool.string("Substring to look for, case-insensitive"),
                        "kind", enumOf("What to search. Default string",
                                "string", "class", "method", "field"),
                        "package", McpTool.string("Installed package name"),
                        "path", McpTool.string("Absolute path to an .apk/.dex/.jar instead of a package"),
                        "limit", McpTool.integer("Maximum hits, default 100")),
                        "pattern")
                .handler(args -> {
                    JSONObject out = DexClient.get(context).call("search", dexArgs(args),
                            60_000L);
                    return McpTool.json(out);
                })
                .build());

        add(McpTool.of("apk_info")
                .title("Read an APK's manifest")
                .description("Package name, version, SDK levels, permissions, the four component"
                        + " lists, and the signing certificate digest. Parsed by the platform's own"
                        + " package parser, so it is exact. Use it to find entry points and to tell"
                        + " whether a build is debuggable. Name the target with 'package' or 'path'."
                        + " Read-only.")
                .readOnly()
                .input(props(
                        "package", McpTool.string("Installed package name"),
                        "path", McpTool.string("Absolute path to an .apk instead of a package")))
                .handler(args -> {
                    String path = args.optString("path", "");
                    String pkg = args.optString("package", "");
                    if (path.isEmpty()) {
                        path = resolveSourcePath(pkg);
                    }
                    return McpTool.json(ApkInfo.describe(context, path, pkg));
                })
                .build());

        add(McpTool.of("apk_list")
                .title("List files inside an APK")
                .description("The archive's entries with their sizes, so you can see which"
                        + " classes*.dex and resource files exist before pulling one apart."
                        + " Read-only.")
                .readOnly()
                .input(props(
                        "package", McpTool.string("Installed package name"),
                        "path", McpTool.string("Absolute path to an .apk instead of a package"),
                        "filter", McpTool.string("Only entries whose path contains this substring"),
                        "limit", McpTool.integer("Maximum entries, default 200")))
                .handler(args -> {
                    String path = args.optString("path", "");
                    if (path.isEmpty()) {
                        path = resolveSourcePath(args.optString("package", ""));
                    }
                    return McpTool.json(ApkInfo.listEntries(path, args.optString("filter", ""),
                            args.optInt("limit", 200)));
                })
                .build());

        add(McpTool.of("smali_disassemble")
                .title("Disassemble to smali")
                .description("Runs baksmali over a DEX or APK and writes a .smali tree to disk,"
                        + " returning the path plus file and byte counts. Only a single small class"
                        + " is returned inline, because a full tree is megabytes and would swamp"
                        + " the conversation - read individual files with the shell tools if you"
                        + " need detail. Filter with a class name fragment to keep the output"
                        + " manageable. Read-only (it writes into the app's own cache).")
                .readOnly()
                .input(props(
                        "package", McpTool.string("Installed package name"),
                        "path", McpTool.string("Absolute path to an .apk/.dex instead of a package"),
                        "filter", McpTool.string("Only classes whose name contains this substring")))
                .handler(args -> {
                    JSONObject call = new JSONObject();
                    call.put("path", dexArgs(args).optString("path"));
                    call.put("filter", args.optString("filter", ""));
                    return McpTool.json(DexClient.get(context).call("disassemble", call, 600_000L));
                })
                .build());

        add(McpTool.of("smali_assemble")
                .title("Assemble smali into a DEX")
                .description("Runs smali over smali sources and writes a DEX, returning its path."
                        + " Pass 'dir' for a directory already on disk, or 'sources' as"
                        + " [{path, content}] to hand over smali text directly."
                        + " \n\nThis is how you produce code to inject without a computer: a phone"
                        + " has no javac and no d8, so write the plugin as smali, assemble it here,"
                        + " then pass the resulting path to plugin_load. Assembling on its own"
                        + " executes nothing and does not prompt; the approval happens when the DEX"
                        + " is actually loaded into another app.")
                .readOnly()
                .input(props(
                        "dir", McpTool.string("Directory containing .smali files"),
                        "sources", McpTool.array("object",
                                "Inline smali: [{path: \"com/example/Foo.smali\", content: \"...\"}]"),
                        "output", McpTool.string("Output file name, default classes.dex")))
                .handler(args -> {
                    JSONObject call = new JSONObject();
                    call.put("dir", args.optString("dir", ""));
                    call.put("output", args.optString("output", "classes.dex"));
                    if (args.has("sources")) {
                        call.put("sources", args.optJSONArray("sources"));
                    }
                    return McpTool.json(DexClient.get(context).call("assemble", call, 600_000L));
                })
                .build());

        // =================================================================
        // Root shell
        // =================================================================

        add(McpTool.of("root_shell_exec")
                .title("Run a root shell command")
                .description("Runs one command as uid 0 via su. The user is shown a blocking dialog"
                        + " containing the exact command string and your stated reason, and must tap"
                        + " approve before anything executes. There is no way to skip this and no"
                        + " allow-list; every call prompts. A refusal or a timeout returns an error"
                        + " - treat that as a decision, not a transient failure, and do not retry"
                        + " the same command repeatedly."
                        + " \n\nWrite the command the way you would type it in a shell. Do not chain"
                        + " unrelated work with ';' or '&&' just to reduce the number of prompts;"
                        + " that hides what is being run from the person approving it.")
                .mutating()
                .input(props(
                        "command", McpTool.string("The exact shell command to run as root"),
                        "reason", McpTool.string("Why this command is needed. Shown to the user."
                                + " Be specific about what it changes and why."),
                        "timeout_ms", McpTool.integer("Kill the command after this many ms, default 60000")),
                        "command", "reason")
                .handler(args -> {
                    String command = require(args, "command");
                    String reason = require(args, "reason");
                    long timeout = args.optLong("timeout_ms", prefs.execTimeoutMs());

                    requireConfirmation(ConfirmationGate.Kind.SHELL, "Root shell command", command,
                            reason);

                    RootShell.Result result = capabilities.confirmedShell(command, timeout);
                    JSONObject out = new JSONObject();
                    out.put("exitCode", result.exitCode);
                    out.put("stdout", result.stdout);
                    out.put("stderr", result.stderr);
                    out.put("timedOut", result.timedOut);
                    out.put("durationMs", result.durationMs);
                    if (result.timedOut) {
                        out.put("note", "The command was killed after " + timeout + "ms.");
                    }
                    return McpTool.json(out);
                })
                .build());

        // =================================================================
        // Screen
        // =================================================================

        add(McpTool.of("screen_capture")
                .title("Capture the screen")
                .description("Takes a screenshot. Route preference in mode=auto is accessibility,"
                        + " then system_server, then the root shell - accessibility first because"
                        + " it needs no root and is the only one of the three that still exists on"
                        + " Android 16."
                        + " \n\nmode=a11y captures through the accessibility service; mode=system"
                        + " uses the module inside system_server; mode=root runs 'screencap' as root,"
                        + " which always prompts as a root shell command."
                        + " \n\nThe image is downscaled by default so it stays cheap to look at.")
                .mutating()
                .input(props(
                        "mode", enumOf("Which capture path to use. Default auto",
                                "auto", "a11y", "system", "root"),
                        "format", enumOf("Image format. Default png", "png", "jpeg"),
                        "max_dimension", McpTool.integer("Longest edge in pixels after scaling, default 1024. 0 keeps full size"),
                        "quality", McpTool.integer("JPEG quality 1-100, default 80"),
                        "reason", McpTool.string("Why you need to see the screen. Shown to the user.")),
                        "reason")
                .handler(args -> {
                    String mode = normalizeMode(args.optString("mode", Capabilities.MODE_AUTO));
                    String reason = require(args, "reason");
                    String format = args.optString("format", "png");
                    int maxDim = args.optInt("max_dimension", 1024);
                    int quality = Math.max(1, Math.min(args.optInt("quality", 80), 100));

                    // Route preference: accessibility, then system_server, then the
                    // shell. Accessibility is first because it needs no root and is
                    // the only one of the three Android 16 still offers.
                    boolean allowFallback = !Capabilities.MODE_A11Y.equals(mode)
                            && !Capabilities.MODE_SYSTEM.equals(mode)
                            && !Capabilities.MODE_ROOT.equals(mode);
                    boolean tryA11y = Capabilities.MODE_A11Y.equals(mode)
                            || (allowFallback && Capabilities.accessibilityOnline());
                    boolean trySystem = !tryA11y && (Capabilities.MODE_SYSTEM.equals(mode)
                            || (allowFallback && capabilities.systemScreenshotUsable()));

                    android.graphics.Bitmap shot = null;
                    byte[] raw = null;
                    String route = null;

                    if (tryA11y) {
                        requireConfirmation(ConfirmationGate.Kind.SCREEN,
                                "Capture the screen contents",
                                "Take a screenshot through the accessibility service and hand it to "
                                        + requester() + ".", reason);
                        try {
                            shot = Capabilities.accessibilityScreenshot();
                            route = Capabilities.MODE_A11Y;
                        } catch (IOException e) {
                            if (!allowFallback) {
                                throw new McpTool.ToolError("the accessibility route failed: "
                                        + e.getMessage());
                            }
                            Logx.w("accessibility screenshot failed, falling back: " + e.getMessage());
                        }
                    }

                    if (shot == null && raw == null && trySystem) {
                        requireSystemBridge("screen_capture");
                        requireConfirmation(ConfirmationGate.Kind.SCREEN,
                                "Capture the screen contents",
                                "Take a screenshot of the current screen and hand it to "
                                        + requester() + ".", reason);
                        try {
                            raw = capabilities.systemScreenshot();
                            route = Capabilities.MODE_SYSTEM;
                        } catch (Exception e) {
                            capabilities.markSystemScreenshotBroken(String.valueOf(e.getMessage()));
                            if (!allowFallback) {
                                throw new McpTool.ToolError("the system screenshot route failed: "
                                        + e.getMessage() + ". Use mode=root, or mode=auto to"
                                        + " fall back automatically.");
                            }
                        }
                    }

                    if (shot == null && raw == null) {
                        route = Capabilities.MODE_ROOT;
                        requireConfirmation(ConfirmationGate.Kind.SHELL, "Root shell command",
                                "screencap -p", reason);
                        try {
                            raw = RootShell.execBinary("screencap -p", 30_000L);
                        } catch (IOException e) {
                            throw new McpTool.ToolError("screencap failed: " + e.getMessage());
                        }
                    }

                    JSONObject out = new JSONObject();
                    out.put("route", route);
                    out.put("format", format);
                    out.put("imageBase64", shot != null
                            ? Capabilities.encodeImage(shot, format, maxDim, quality)
                            : Capabilities.encodeImage(raw, format, maxDim, quality));
                    return McpTool.json(out);
                })
                .build());

        add(McpTool.of("ui_dump")
                .title("Dump the UI hierarchy")
                .description("Dumps the control tree of the current screen as JSON: text, content"
                        + " descriptions, resource ids, bounds and clickability."
                        + " Far cheaper than a screenshot for locating a control to tap."
                        + " \n\nmode=a11y reads it through the accessibility service, which needs no"
                        + " root; mode=root runs 'uiautomator' as root, which prompts as a root"
                        + " shell command every time. mode=auto prefers accessibility.")
                .mutating()
                .input(props(
                        "reason", McpTool.string("Why you need the UI tree. Shown to the user."),
                        "mode", enumOf("Which route to use. Default auto",
                                "auto", "a11y", "root"),
                        "max_nodes", McpTool.integer("Stop after this many nodes, default 300"),
                        "simplify", McpTool.type("boolean", "Drop uninteresting nodes. Default true")),
                        "reason")
                .handler(args -> {
                    String reason = require(args, "reason");
                    boolean simplify = args.optBoolean("simplify", true);
                    int maxNodes = args.optInt("max_nodes", 300);
                    String mode = normalizeMode(args.optString("mode", Capabilities.MODE_AUTO));

                    boolean allowFallback = !Capabilities.MODE_A11Y.equals(mode)
                            && !Capabilities.MODE_ROOT.equals(mode);
                    boolean tryA11y = Capabilities.MODE_A11Y.equals(mode)
                            || (allowFallback && Capabilities.accessibilityOnline());

                    if (tryA11y) {
                        requireConfirmation(ConfirmationGate.Kind.SCREEN, "Read the current screen",
                                "Read the controls on screen and hand the tree to " + requester()
                                        + ".", reason);
                        try {
                            return McpTool.json(
                                    AccessibilityBridge.activeWindowTree(maxNodes, simplify));
                        } catch (IOException e) {
                            if (!allowFallback) {
                                throw new McpTool.ToolError("the accessibility route failed: "
                                        + e.getMessage());
                            }
                            Logx.w("accessibility ui dump failed, falling back: " + e.getMessage());
                        }
                    }

                    // The shell route needs a root command for every dump, which is
                    // why it is the fallback rather than the default.
                    String path = "/data/local/tmp/posedmcp_ui.xml";
                    String command = "uiautomator dump --compressed " + shellQuote(path)
                            + " >/dev/null 2>&1; cat " + shellQuote(path);

                    requireConfirmation(ConfirmationGate.Kind.SHELL, "Root shell command", command,
                            reason);

                    RootShell.Result result = capabilities.confirmedShell(command, 30_000L);
                    if (!result.ok() || result.stdout.trim().isEmpty()) {
                        throw new McpTool.ToolError("uiautomator dump produced nothing"
                                + " (exit " + result.exitCode + ")"
                                + (result.stderr.isEmpty() ? "" : ": " + result.stderr.trim()));
                    }
                    JSONObject out = parseUiDump(result.stdout, simplify);
                    out.put("source", "uiautomator");
                    return McpTool.json(out);
                })
                .build());

        // =================================================================
        // Input
        // =================================================================

        add(McpTool.of("input_inject")
                .title("Inject input events")
                .description("Sends synthetic touch, swipe, text or key events to the device."
                        + " Route preference in mode=auto is accessibility, then system_server,"
                        + " then root."
                        + " \n\nmode=a11y uses the accessibility service: real gestures, and text"
                        + " through ACTION_SET_TEXT on the focused field. It needs no root and"
                        + " keeps working while another app is in front. Keys it cannot express"
                        + " (volume, media) automatically fall through to another route."
                        + " mode=system injects through the module inside system_server;"
                        + " mode=root runs the 'input' command as root, which always prompts as a"
                        + " root shell command."
                        + " \n\nGet coordinates from ui_dump or screen_capture first.")
                .mutating()
                .input(props(
                        "action", enumOf("The gesture or event to send",
                                "tap", "swipe", "long_press", "text", "key"),
                        "x", McpTool.integer("X coordinate for tap/long_press/swipe start"),
                        "y", McpTool.integer("Y coordinate for tap/long_press/swipe start"),
                        "x2", McpTool.integer("End X for swipe"),
                        "y2", McpTool.integer("End Y for swipe"),
                        "duration_ms", McpTool.integer("Swipe/long-press duration, default 300"),
                        "text", McpTool.string("Text to type for action=text"),
                        "keycode", McpTool.string("Key name or code for action=key, e.g. HOME, BACK, 3"),
                        "mode", enumOf("Which injection path to use. Default auto",
                                "auto", "a11y", "system", "root"),
                        "reason", McpTool.string("Why this input is needed. Shown to the user.")),
                        "action", "reason")
                .handler(args -> {
                    String action = require(args, "action");
                    String reason = require(args, "reason");
                    String mode = normalizeMode(args.optString("mode", Capabilities.MODE_AUTO));

                    boolean allowFallback = !Capabilities.MODE_A11Y.equals(mode)
                            && !Capabilities.MODE_SYSTEM.equals(mode)
                            && !Capabilities.MODE_ROOT.equals(mode);
                    boolean tryA11y = Capabilities.MODE_A11Y.equals(mode)
                            || (allowFallback && Capabilities.accessibilityOnline());

                    if (tryA11y) {
                        try {
                            return McpTool.json(accessibilityInput(action, args, reason));
                        } catch (McpTool.ToolError e) {
                            throw e;
                        } catch (IOException e) {
                            if (!allowFallback) {
                                throw new McpTool.ToolError(e.getMessage());
                            }
                            // Some keys have no accessibility equivalent, so this
                            // is a normal outcome rather than a failure.
                            Logx.w("accessibility input unavailable, falling back: " + e.getMessage());
                        }
                    }

                    JSONObject payload = new JSONObject(args.toString());
                    payload.remove("reason");
                    payload.remove("mode");

                    boolean trySystem = !Capabilities.MODE_ROOT.equals(mode)
                            && (Capabilities.MODE_SYSTEM.equals(mode) || capabilities.systemOnline());
                    if (trySystem) {
                        requireSystemBridge("input_inject");
                        requireConfirmation(ConfirmationGate.Kind.INPUT, "Inject input into the device",
                                "Send " + action + " " + describeInputTarget(action, args) + " to "
                                        + "the foreground app as " + requester() + ".", reason);
                        JSONObject out = capabilities.systemInput(payload);
                        out.put("route", Capabilities.MODE_SYSTEM);
                        return McpTool.json(out);
                    }

                    String command = buildInputCommand(action, args);
                    requireConfirmation(ConfirmationGate.Kind.SHELL, "Root shell command",
                            command, reason);
                    RootShell.Result result = capabilities.confirmedShell(command, 20_000L);
                    JSONObject out = new JSONObject();
                    out.put("route", Capabilities.MODE_ROOT);
                    out.put("command", command);
                    out.put("exitCode", result.exitCode);
                    if (!result.stderr.isEmpty()) {
                        out.put("stderr", result.stderr);
                    }
                    return McpTool.json(out);
                })
                .build());

        // =================================================================
        // Dynamic code injection
        // =================================================================

        add(McpTool.of("plugin_load")
                .title("Load code into a scoped app")
                .description("Loads a DEX into a target app's process via reflection"
                        + " (InMemoryDexClassLoader) and calls its entry point. This is how you"
                        + " extend a third-party app: write a class implementing"
                        + " dev.posedmcp.plugin.PluginEntry, compile it to DEX, and pass the bytes."
                        + " \n\nSupply the DEX either as dex_base64, or as dex_path pointing at a"
                        + " file this app can read - typically the output of smali_assemble, which"
                        + " lets you build the payload entirely on the phone."
                        + " \n\nThe target package must be ticked in LSPosed Manager and its process"
                        + " must have started after that (module_status lists which processes are"
                        + " reachable). The plugin runs inside the target app with that app's"
                        + " privileges. Always prompts.")
                .mutating()
                .input(props(
                        "package", McpTool.string("Target package name, e.g. com.example.app"),
                        "class_name", McpTool.string("Fully qualified DEX class implementing PluginEntry"),
                        "dex_base64", McpTool.string("Base64-encoded DEX containing the class"),
                        "dex_path", McpTool.string("Path to a .dex file instead of dex_base64"),
                        "reason", McpTool.string("What the injected code does and why. Shown to the user.")),
                        "package", "class_name", "reason")
                .handler(args -> {
                    String pkg = require(args, "package");
                    String className = require(args, "class_name");
                    String reason = require(args, "reason");

                    byte[] dex = readDex(args);
                    if (dex.length < 8 || dex[0] != 'd' || dex[1] != 'e' || dex[2] != 'x') {
                        throw new McpTool.ToolError("the payload is not a DEX file");
                    }

                    requireConfirmation(ConfirmationGate.Kind.PLUGIN,
                            "Load code into " + pkg,
                            "Class: " + className + "\nDEX size: " + dex.length + " bytes",
                            reason);

                    requireAppPeer(pkg);
                    JSONObject callArgs = new JSONObject();
                    callArgs.put("class_name", className);
                    callArgs.put("entry", "");
                    callArgs.put("dex_base64",
                            android.util.Base64.encodeToString(dex, android.util.Base64.NO_WRAP));
                    // Into every process: an application commonly has several,
                    // and the code you are extending may run in any of them.
                    JSONObject out = summarizeAcrossProcesses(
                            capabilities.appCallAll(pkg, "load_plugin", callArgs, 30_000L),
                            "loadedIn",
                            "The payload is loaded into every process of the package.");
                    out.put("package", pkg);
                    out.put("class", className);
                    return McpTool.json(out);
                })
                .build());

        add(McpTool.of("plugin_invoke")
                .title("Call into a loaded plugin")
                .description("Invokes a plugin's entry method inside the target app's process."
                        + " Use plugin_list first to see what is loaded. Always prompts.")
                .mutating()
                .input(props(
                        "package", McpTool.string("Target package whose process runs the plugin"),
                        "class_name", McpTool.string("Plugin class name"),
                        "method", McpTool.string("Method on the plugin to call"),
                        "args_json", McpTool.string("JSON array of arguments, default []"),
                        "reason", McpTool.string("Why this call is needed. Shown to the user.")),
                        "package", "class_name", "method", "reason")
                .handler(args -> {
                    String pkg = require(args, "package");
                    String className = require(args, "class_name");
                    String method = require(args, "method");
                    String reason = require(args, "reason");
                    String argsJson = args.optString("args_json", "[]");

                    requireConfirmation(ConfirmationGate.Kind.PLUGIN,
                            "Call plugin code in " + pkg,
                            className + "." + method + "(" + argsJson + ")", reason);

                    requireAppPeer(pkg);
                    return McpTool.json(capabilities.systemInvokePlugin(pkg, className, method,
                            argsJson));
                })
                .build());

        // =================================================================
        // Runtime observation
        // =================================================================

        add(McpTool.of("hook_method")
                .title("Watch or change a method at runtime")
                .description("Installs a hook on a method inside a running application."
                        + " By default it only records: every call's arguments, return value,"
                        + " exception and thread. Supply any of the modification options and it"
                        + " also changes what the application does."
                        + " \n\nThis is the dynamic half of analysis, and it needs no plugin DEX -"
                        + " the module installs the hook directly. The usual loop is: find a"
                        + " method with dex_search or smali_disassemble, hook it here, use the app,"
                        + " then read hook_records to see what flowed through it."
                        + " \n\nModification options, all optional and combinable:"
                        + " return_value makes the method produce that value and skips the original"
                        + " entirely; set_args replaces arguments before the call; set_fields"
                        + " assigns fields on the instance after the call. Values are parsed as"
                        + " JSON when possible, so \"false\" is a boolean and \"42\" a number, and"
                        + " are converted to whatever type the method actually declares."
                        + " \n\nPrefer this over plugin_load whenever the change can be expressed"
                        + " as a value: nothing has to be compiled, and the effect is visible in"
                        + " hook_records as 'altered'. Always prompts.")
                .mutating()
                .input(props(
                        "package", McpTool.string("Target package whose process should be hooked"),
                        "class", McpTool.string("Fully qualified class name, e.g. com.example.Foo"),
                        "method", McpTool.string("Method name"),
                        "params", McpTool.string("Comma-separated parameter types to pick one"
                                + " overload, e.g. java.lang.String,int. Omit to hook every overload."),
                        "return_value", McpTool.string("Make the method return this and skip the"
                                + " original. Parsed as JSON when possible."),
                        "set_args", McpTool.freeformObject("Map of argument index to new value,"
                                + " applied before the call, e.g. {\"0\":\"hello\",\"2\":false}"),
                        "set_fields", McpTool.freeformObject("Map of field name to new value,"
                                + " assigned on the instance after the call, e.g. {\"mEnabled\":true}"),
                        "observe", McpTool.type("boolean", "Keep recording calls. Default true"),
                        "max_records", McpTool.integer("Keep at most this many calls, default 200"),
                        "reason", McpTool.string("Why this is needed. Shown to the user.")),
                        "package", "class", "method", "reason")
                .handler(args -> {
                    String pkg = require(args, "package");
                    String className = require(args, "class");
                    String method = require(args, "method");
                    String reason = require(args, "reason");

                    String effect = describeHookEffect(args);
                    requireConfirmation(ConfirmationGate.Kind.PLUGIN,
                            effect == null
                                    ? "Watch a method in " + pkg
                                    : "Change behaviour in " + pkg,
                            className + "." + method
                                    + "(" + args.optString("params", "") + ")"
                                    + (effect == null ? "" : "\n" + effect),
                            reason);

                    requireAppPeer(pkg);
                    JSONObject call = new JSONObject();
                    call.put("class", className);
                    call.put("method", method);
                    call.put("params", args.optString("params", ""));
                    call.put("max_records", args.optInt("max_records", 200));
                    call.put("observe", args.optBoolean("observe", true));
                    for (String key : new String[]{"return_value", "set_args", "set_fields"}) {
                        if (args.has(key)) {
                            call.put(key, args.get(key));
                        }
                    }
                    return McpTool.json(summarizeAcrossProcesses(
                            capabilities.appCallAll(pkg, "hook_method", call, 30_000L),
                            "hookedIn", "The hook is installed wherever the method runs."));
                })
                .build());

        add(McpTool.of("hook_records")
                .title("Read recorded calls")
                .description("Returns what the hooks installed by hook_method have captured,"
                        + " newest first. Read-only.")
                .readOnly()
                .input(props(
                        "package", McpTool.string("Target package to read from"),
                        "subject", McpTool.string("Only hooks whose class or method contains this"),
                        "limit", McpTool.integer("Maximum records, default 100")),
                        "package")
                .handler(args -> {
                    String pkg = require(args, "package");
                    JSONObject call = new JSONObject();
                    call.put("subject", args.optString("subject", ""));
                    call.put("limit", args.optInt("limit", 100));

                    // Hooks are per-process, so every process has to be asked;
                    // querying one of them would report an empty list while a
                    // hook sits in a sibling.
                    JSONArray across = capabilities.appCallAll(pkg, "hook_records", call, 20_000L);
                    JSONArray hooks = new JSONArray();
                    JSONArray records = new JSONArray();
                    for (int i = 0; i < across.length(); i++) {
                        JSONObject entry = across.optJSONObject(i);
                        if (entry == null || !entry.optBoolean("ok", false)) {
                            continue;
                        }
                        JSONObject result = entry.optJSONObject("result");
                        if (result == null) {
                            continue;
                        }
                        tag(entry.optString("process", ""), result.optJSONArray("hooks"), hooks);
                        tag(entry.optString("process", ""), result.optJSONArray("records"), records);
                    }
                    JSONObject out = new JSONObject();
                    out.put("hooks", hooks);
                    out.put("records", records);
                    if (hooks.length() == 0) {
                        out.put("note", "nothing is hooked in any process of this package");
                    }
                    return McpTool.json(out);
                })
                .build());

        add(McpTool.of("hook_clear")
                .title("Remove runtime hooks")
                .description("Unhooks everything matching, or everything in the process when no"
                        + " subject is given. Worth doing once you are finished: a watched app"
                        + " keeps paying for hooks you no longer read. Prompts.")
                .mutating()
                .input(props(
                        "package", McpTool.string("Target package"),
                        "subject", McpTool.string("Only hooks whose class or method contains this."
                                + " Omit to remove all of them."),
                        "reason", McpTool.string("Why the hooks are being removed. Shown to the user.")),
                        "package", "reason")
                .handler(args -> {
                    String pkg = require(args, "package");
                    String reason = require(args, "reason");
                    String subject = args.optString("subject", "");

                    requireConfirmation(ConfirmationGate.Kind.PLUGIN,
                            "Remove runtime hooks from " + pkg,
                            subject.isEmpty() ? "all hooks in this process"
                                    : "hooks matching \"" + subject + "\"",
                            reason);

                    requireAppPeer(pkg);
                    JSONObject call = new JSONObject();
                    call.put("subject", subject);
                    return McpTool.json(summarizeAcrossProcesses(
                            capabilities.appCallAll(pkg, "hook_clear", call, 20_000L),
                            "clearedIn", "Every process of the package was asked."));
                })
                .build());
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private void requireConfirmation(ConfirmationGate.Kind kind, String title, String detail,
            String reason) throws McpTool.ToolError {
        ConfirmationGate.Decision decision = ConfirmationGate.request(context,
                new ConfirmationGate.Request(kind, title, detail, reason, requester(),
                        prefs.confirmTimeoutMs()));
        if (!decision.approved) {
            throw new McpTool.ToolError("Refused: " + decision.note
                    + ". The action was not performed. Ask the user what they would prefer"
                    + " instead of retrying.");
        }
    }

    private void requireSystemBridge(String tool) throws McpTool.ToolError {
        if (!capabilities.systemOnline()) {
            throw new McpTool.ToolError(tool + " needs the system bridge, which is not connected."
                    + " Enable the PosEdMCP module in LSPosed Manager with scope including"
                    + " \"System Framework\", reboot or restart system_server, then check"
                    + " module_status again.");
        }
    }

    private void requireAppPeer(String pkg) throws McpTool.ToolError {
        if (!bridge.hasAppPeer(pkg)) {
            throw new McpTool.ToolError("no PosEdMCP module inside '" + pkg + "'."
                    + " Add the package to the module's scope in LSPosed Manager, then start (or"
                    + " restart) that app so the module can load into its process.");
        }
    }

    private JSONObject moduleStatusJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("systemBridgeConnected", capabilities.systemOnline());
            JSONArray peers = new JSONArray();
            for (String key : bridge.connectedKeys()) {
                peers.put(key);
            }
            out.put("bridgePeers", peers);
            out.put("bridgePort", bridge.port());
            out.put("mcpPort", prefs.mcpPort());
            out.put("accessibility", AccessibilityBridge.describe());
            if (!AccessibilityBridge.isConnected()) {
                out.put("accessibilityHint",
                        "Without the accessibility service the platform freezes this app once it"
                                + " leaves the screen, so the MCP endpoint stops answering exactly"
                                + " when an agent in another app needs it.");
            }
            out.put("confirmations", confirmationsJson());
            if (capabilities.systemOnline()) {
                try {
                    out.put("system", capabilities.systemStatus());
                } catch (Throwable t) {
                    out.put("systemError", String.valueOf(t.getMessage()));
                }
                try {
                    out.put("displayProbe", capabilities.systemDisplayProbe());
                } catch (Throwable t) {
                    out.put("displayProbeError", String.valueOf(t.getMessage()));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private JSONObject confirmationsJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("root_shell_exec", "always prompts");
            o.put("screen_capture", prefs.confirmScreen() ? "prompts" : "not prompted");
            o.put("ui_dump", "always prompts (runs a shell command)");
            o.put("input_inject", prefs.confirmInput() ? "prompts" : "not prompted (system mode)");
            o.put("plugin_load/plugin_invoke", prefs.confirmPlugin() ? "prompts" : "not prompted");
            o.put("confirmTimeoutMs", prefs.confirmTimeoutMs());
        } catch (Throwable ignored) {
        }
        return o;
    }

    private JSONArray listPackages(String filter, boolean includeSystem, int limit) {
        PackageManager pm = context.getPackageManager();
        int flags = PackageManager.GET_META_DATA;
        List<PackageInfo> installed = pm.getInstalledPackages(flags);
        JSONArray array = new JSONArray();
        for (PackageInfo info : installed) {
            if (array.length() >= Math.max(1, Math.min(limit, MAX_PACKAGES))) {
                break;
            }
            ApplicationInfo app = info.applicationInfo;
            if (app == null) {
                continue;
            }
            boolean system = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (system && !includeSystem) {
                continue;
            }
            String label;
            try {
                label = String.valueOf(pm.getApplicationLabel(app));
            } catch (Throwable t) {
                label = info.packageName;
            }
            if (!filter.isEmpty()
                    && !info.packageName.toLowerCase(Locale.ROOT).contains(filter)
                    && !label.toLowerCase(Locale.ROOT).contains(filter)) {
                continue;
            }
            JSONObject entry = new JSONObject();
            try {
                entry.put("package", info.packageName);
                entry.put("label", label);
                entry.put("system", system);
                entry.put("uid", app.uid);
                entry.put("enabled", app.enabled);
                entry.put("moduleLoaded", bridge.hasAppPeer(info.packageName));
            } catch (Throwable ignored) {
            }
            array.put(entry);
        }
        return array;
    }

    /**
     * Normalises analysis arguments so a target can be named either way.
     *
     * <p>A package is friendlier for an agent that just listed the installed
     * apps; a path is what it needs for a file it produced itself.
     */
    private JSONObject dexArgs(JSONObject args) throws Exception {
        JSONObject out = new JSONObject(args.toString());
        String path = args.optString("path", "");
        if (path.isEmpty()) {
            out.put("path", resolveSourcePath(args.optString("package", "")));
        }
        out.remove("package");
        return out;
    }

    private String resolveSourcePath(String pkg) throws McpTool.ToolError {
        if (pkg.isEmpty()) {
            throw new McpTool.ToolError("name the target with either 'package' or 'path'");
        }
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(pkg, 0);
            if (info.sourceDir == null || info.sourceDir.isEmpty()) {
                throw new McpTool.ToolError("no APK path reported for " + pkg);
            }
            return info.sourceDir;
        } catch (PackageManager.NameNotFoundException e) {
            throw new McpTool.ToolError("no such package: " + pkg);
        }
    }

    /**
     * Takes the payload either as bytes or as a path.
     *
     * <p>The path form is what makes the on-device loop work: smali_assemble
     * writes a DEX and plugin_load picks it up, with no base64 round trip through
     * the conversation.
     */
    private byte[] readDex(JSONObject args) throws McpTool.ToolError {
        String path = args.optString("dex_path", "");
        if (!path.isEmpty()) {
            File file = new File(path);
            if (!file.canRead()) {
                throw new McpTool.ToolError("cannot read dex_path: " + path);
            }
            if (file.length() > MAX_DEX_BYTES) {
                throw new McpTool.ToolError("dex_path is larger than "
                        + (MAX_DEX_BYTES / (1024 * 1024)) + " MB");
            }
            try {
                return java.nio.file.Files.readAllBytes(file.toPath());
            } catch (Throwable t) {
                throw new McpTool.ToolError("could not read dex_path: " + t.getMessage());
            }
        }
        String base64 = args.optString("dex_base64", "");
        if (base64.isEmpty()) {
            throw new McpTool.ToolError("provide either dex_base64 or dex_path");
        }
        try {
            return android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
        } catch (Throwable t) {
            throw new McpTool.ToolError("dex_base64 is not valid base64");
        }
    }

    /** Distinct application packages that currently have a bridge peer. */    private java.util.Set<String> connectedPackages() {
        java.util.Set<String> packages = new java.util.LinkedHashSet<>();
        for (String key : bridge.connectedKeys()) {
            // Keys look like "app:<pkg>:<pid>".
            if (!key.startsWith("app:")) {
                continue;
            }
            String rest = key.substring(4);
            int sep = rest.lastIndexOf(':');
            packages.add(sep > 0 ? rest.substring(0, sep) : rest);
        }
        packages.remove("android");
        return packages;
    }

    private static String normalizeMode(String mode) {
        String m = mode == null ? "" : mode.toLowerCase(Locale.ROOT);
        if (Capabilities.MODE_SYSTEM.equals(m) || Capabilities.MODE_ROOT.equals(m)
                || Capabilities.MODE_A11Y.equals(m)) {
            return m;
        }
        return Capabilities.MODE_AUTO;
    }

    /** Builds the equivalent {@code input} command for the root route. */
    private static String buildInputCommand(String action, JSONObject args) throws McpTool.ToolError {
        switch (action) {
            case "tap":
                return "input tap " + requireInt(args, "x") + " " + requireInt(args, "y");
            case "long_press": {
                int duration = args.optInt("duration_ms", 800);
                return "input swipe " + requireInt(args, "x") + " " + requireInt(args, "y")
                        + " " + requireInt(args, "x") + " " + requireInt(args, "y") + " " + duration;
            }
            case "swipe": {
                int duration = args.optInt("duration_ms", 300);
                return "input swipe " + requireInt(args, "x") + " " + requireInt(args, "y")
                        + " " + requireInt(args, "x2") + " " + requireInt(args, "y2") + " " + duration;
            }
            case "text": {
                String text = require(args, "text");
                return "input text " + shellQuote(text.replace(" ", "%s"));
            }
            case "key": {
                String keycode = require(args, "keycode");
                return "input keyevent " + shellQuote(keycode);
            }
            default:
                throw new McpTool.ToolError("unsupported action '" + action
                        + "'. Use tap, swipe, long_press, text or key.");
        }
    }

    /**
     * Condenses a per-process fan-out into one answer.
     *
     * <p>Reporting a raw list of processes would leave the caller to work out
     * whether the operation actually took, and on how many of them.
     */
    private static JSONObject summarizeAcrossProcesses(JSONArray results, String countKey,
            String note) throws Exception {
        JSONObject out = new JSONObject();
        JSONArray detail = new JSONArray();
        int succeeded = 0;
        for (int i = 0; i < results.length(); i++) {
            JSONObject entry = results.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            boolean ok = entry.optBoolean("ok", false);
            if (ok) {
                succeeded++;
            }
            JSONObject row = new JSONObject();
            row.put("process", entry.optString("process", ""));
            row.put("ok", ok);
            if (ok) {
                JSONObject result = entry.optJSONObject("result");
                if (result != null) {
                    row.put("result", result);
                }
            } else {
                row.put("error", entry.optString("error", ""));
            }
            detail.put(row);
        }
        out.put("processes", results.length());
        out.put(countKey, succeeded);
        if (note != null) {
            out.put("note", note);
        }
        if (succeeded < results.length()) {
            out.put("partial", true);
        }
        out.put("detail", detail);
        return out;
    }

    /** Copies items into a combined list, recording which process each came from. */
    private static void tag(String process, JSONArray from, JSONArray into) throws Exception {
        if (from == null) {
            return;
        }
        for (int i = 0; i < from.length(); i++) {
            JSONObject item = from.optJSONObject(i);
            if (item == null) {
                continue;
            }
            item.put("process", process);
            into.put(item);
        }
    }

    /**
     * Renders the modification part of a hook request for the confirmation dialog.
     *
     * <p>Observing needs no explanation, but changing what a method does is the
     * kind of thing the user should see spelled out before agreeing to it.
     *
     * @return {@code null} when the hook only observes
     */
    private static String describeHookEffect(JSONObject args) {
        List<String> parts = new ArrayList<>();
        String returnValue = args.optString("return_value", "");
        if (!returnValue.isEmpty()) {
            parts.add("Always return " + returnValue + " (the original will not run)");
        }
        String setArgs = args.optString("set_args", "");
        if (!setArgs.isEmpty()) {
            parts.add("Replace arguments: " + setArgs);
        }
        String setFields = args.optString("set_fields", "");
        if (!setFields.isEmpty()) {
            parts.add("Assign fields after the call: " + setFields);
        }
        return parts.isEmpty() ? null : String.join("\n", parts);
    }

    private static String describeInputTarget(String action, JSONObject args) {
        switch (action) {
            case "tap":
                return "at (" + args.optInt("x") + "," + args.optInt("y") + ")";
            case "long_press":
                return "at (" + args.optInt("x") + "," + args.optInt("y") + ")";
            case "swipe":
                return "from (" + args.optInt("x") + "," + args.optInt("y") + ") to ("
                        + args.optInt("x2") + "," + args.optInt("y2") + ")";
            case "text":
                return "typing \"" + args.optString("text") + "\"";
            case "key":
                return "key " + args.optString("keycode");
            default:
                return action;
        }
    }

    /** Single-quotes a value for safe inclusion in a shell command. */
    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * Input through the accessibility service.
     *
     * <p>Gestures and global keys both go through APIs only this service may use,
     * which is why they need no root. Text goes through {@code ACTION_SET_TEXT}
     * on the focused field, which is both cleaner and more reliable than passing
     * a string to a shell command.
     */
    private JSONObject accessibilityInput(String action, JSONObject args, String reason)
            throws Exception {
        requireConfirmation(ConfirmationGate.Kind.INPUT, "Inject input into the device",
                "Send " + action + " " + describeInputTarget(action, args) + " to the foreground app"
                        + " as " + requester() + " (through the accessibility service).", reason);

        switch (action) {
            case "tap":
                AccessibilityBridge.gesture(requireInt(args, "x"), requireInt(args, "y"),
                        requireInt(args, "x"), requireInt(args, "y"), 60);
                break;
            case "long_press": {
                int x = requireInt(args, "x");
                int y = requireInt(args, "y");
                AccessibilityBridge.gesture(x, y, x, y, args.optInt("duration_ms", 800));
                break;
            }
            case "swipe":
                AccessibilityBridge.gesture(requireInt(args, "x"), requireInt(args, "y"),
                        requireInt(args, "x2"), requireInt(args, "y2"),
                        Math.max(1, args.optInt("duration_ms", 300)));
                break;
            case "text":
                AccessibilityBridge.setText(require(args, "text"));
                break;
            case "key": {
                int global = globalActionFor(args.optString("keycode", ""));
                if (global == 0) {
                    // Volume, media and other keys have no accessibility action;
                    // say so plainly so the caller can pick another route.
                    throw new IOException("'" + args.optString("keycode")
                            + "' has no accessibility equivalent");
                }
                AccessibilityBridge.globalAction(global);
                break;
            }
            default:
                throw new McpTool.ToolError("unsupported action '" + action
                        + "'. Use tap, swipe, long_press, text or key.");
        }

        JSONObject out = new JSONObject();
        out.put("route", Capabilities.MODE_A11Y);
        out.put("injected", true);
        out.put("action", action);
        return out;
    }

    /** Maps a key name onto the accessibility global action that performs it. */
    private static int globalActionFor(String keycode) {
        String key = keycode == null ? "" : keycode.trim().toUpperCase(Locale.ROOT);
        if (key.startsWith("KEYCODE_")) {
            key = key.substring("KEYCODE_".length());
        }
        switch (key) {
            case "HOME": return android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME;
            case "BACK": return android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK;
            case "RECENTS":
            case "APP_SWITCH": return android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS;
            case "NOTIFICATIONS": return android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS;
            case "QUICK_SETTINGS": return android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS;
            case "POWER_DIALOG": return android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG;
            default: return 0;
        }
    }

    private static int requireInt(JSONObject args, String name) throws McpTool.ToolError {
        if (!args.has(name)) {
            throw new McpTool.ToolError("missing required argument '" + name + "'");
        }
        return args.optInt(name, 0);
    }

    private static String require(JSONObject args, String name) throws McpTool.ToolError {
        String value = args.optString(name, "");
        if (value.trim().isEmpty()) {
            throw new McpTool.ToolError("missing required argument '" + name + "'");
        }
        return value;
    }

    private static JSONObject props(Object... pairs) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                o.put((String) pairs[i], pairs[i + 1]);
            }
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** A string property restricted to a fixed set of values. */
    private static JSONObject enumOf(String description, String... values) {
        JSONObject o = McpTool.string(description);
        try {
            JSONArray array = new JSONArray();
            for (String value : values) {
                array.put(value);
            }
            o.put("enum", array);
        } catch (Throwable ignored) {
        }
        return o;
    }

    // =====================================================================
    // uiautomator dump parsing
    // =====================================================================

    /**
     * Turns the {@code uiautomator} hierarchy XML into a compact tree.
     *
     * <p>With {@code simplify}, nodes carrying no text, id or interaction are
     * dropped and their children are re-parented. A raw dump is mostly layout
     * scaffolding, and an agent pays for every token of it.
     */
    static JSONObject parseUiDump(String xml, boolean simplify) throws McpTool.ToolError {
        try {
            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "UTF-8");

            JSONObject virtualRoot = new JSONObject();
            Deque<JSONObject> parents = new ArrayDeque<>();
            parents.push(virtualRoot);
            int nodes = 0;
            boolean truncated = false;

            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "node".equals(parser.getName())) {
                    if (nodes++ >= MAX_UI_NODES) {
                        truncated = true;
                        break;
                    }
                    JSONObject node = nodeFromAttributes(parser);
                    JSONObject parent = parents.peek();
                    if (!simplify || isInteresting(node)) {
                        childrenOf(parent).put(node);
                        parents.push(node);
                    } else {
                        // Transparent: this node disappears, its children do not.
                        parents.push(parent);
                    }
                } else if (event == XmlPullParser.END_TAG && "node".equals(parser.getName())) {
                    if (parents.size() > 1) {
                        parents.pop();
                    }
                }
                event = parser.next();
            }

            JSONObject out = new JSONObject();
            JSONArray roots = virtualRoot.optJSONArray("children");
            out.put("node", roots == null || roots.length() == 0 ? new JSONObject() : roots.get(0));
            if (truncated) {
                out.put("truncated", true);
                out.put("note", "Stopped after " + MAX_UI_NODES + " nodes.");
            }
            return out;
        } catch (McpTool.ToolError e) {
            throw e;
        } catch (Throwable t) {
            throw new McpTool.ToolError("could not parse the uiautomator dump: " + t);
        }
    }

    private static JSONArray childrenOf(JSONObject parent) throws Exception {
        JSONArray children = parent.optJSONArray("children");
        if (children == null) {
            children = new JSONArray();
            parent.put("children", children);
        }
        return children;
    }

    private static JSONObject nodeFromAttributes(XmlPullParser parser) throws Exception {
        JSONObject node = new JSONObject();
        String text = attr(parser, "text");
        String desc = attr(parser, "content-desc");
        String resId = attr(parser, "resource-id");
        String cls = attr(parser, "class");
        String pkg = attr(parser, "package");
        String bounds = attr(parser, "bounds");
        boolean clickable = "true".equals(attr(parser, "clickable"));
        boolean focusable = "true".equals(attr(parser, "focusable"));
        boolean scrollable = "true".equals(attr(parser, "scrollable"));
        boolean enabled = "true".equals(attr(parser, "enabled"));

        if (!text.isEmpty()) {
            node.put("text", text);
        }
        if (!desc.isEmpty()) {
            node.put("desc", desc);
        }
        if (!resId.isEmpty()) {
            node.put("id", resId);
        }
        if (!cls.isEmpty()) {
            node.put("class", cls);
        }
        if (!pkg.isEmpty()) {
            node.put("package", pkg);
        }
        if (!bounds.isEmpty()) {
            node.put("bounds", bounds);
            int[] centre = centreOf(bounds);
            if (centre != null) {
                JSONObject c = new JSONObject();
                c.put("x", centre[0]);
                c.put("y", centre[1]);
                node.put("center", c);
            }
        }
        if (clickable) {
            node.put("clickable", true);
        }
        if (focusable) {
            node.put("focusable", true);
        }
        if (scrollable) {
            node.put("scrollable", true);
        }
        if (!enabled) {
            node.put("enabled", false);
        }
        return node;
    }

    /** Nodes worth showing: they carry text, or the user can interact with them. */
    private static boolean isInteresting(JSONObject node) {
        return node.has("text") || node.has("desc") || node.has("id")
                || node.optBoolean("clickable", false) || node.optBoolean("scrollable", false);
    }

    /** Parses {@code [x1,y1][x2,y2]} into its centre point. */
    private static int[] centreOf(String bounds) {
        try {
            String cleaned = bounds.replace("[", "").replace("]", ",");
            String[] parts = cleaned.split(",");
            List<Integer> nums = new ArrayList<>();
            for (String p : parts) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty()) {
                    nums.add(Integer.parseInt(trimmed));
                }
            }
            if (nums.size() < 4) {
                return null;
            }
            return new int[]{(nums.get(0) + nums.get(2)) / 2, (nums.get(1) + nums.get(3)) / 2};
        } catch (Throwable t) {
            return null;
        }
    }

    private static String attr(XmlPullParser parser, String name) {
        String value = parser.getAttributeValue(null, name);
        return value == null ? "" : value;
    }

    /** Exposed for the UI: a stable snapshot of what is registered. */
    public Map<String, String> summary() {
        Map<String, String> out = new LinkedHashMap<>();
        for (McpTool tool : tools.values()) {
            out.put(tool.name, tool.readOnly ? "read-only" : "prompts");
        }
        return Collections.unmodifiableMap(out);
    }
}
