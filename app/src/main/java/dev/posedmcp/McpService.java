package dev.posedmcp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.posedmcp.a11y.AccessibilityBridge;
import dev.posedmcp.ipc.BridgeCredentials;
import dev.posedmcp.ipc.BridgeServer;
import dev.posedmcp.mcp.McpServer;
import dev.posedmcp.mcp.McpTool;
import dev.posedmcp.mcp.ToolRegistry;
import dev.posedmcp.root.ConfirmationGate;
import dev.posedmcp.state.DeviceStatus;
import dev.posedmcp.state.EventStore;
import dev.posedmcp.state.PeerTrust;
import dev.posedmcp.state.Prefs;
import dev.posedmcp.tools.Capabilities;

/**
 * Keeps the MCP endpoint and the device bridge alive.
 *
 * <p>Owns the whole server-side object graph, so the app UI only ever starts
 * and stops this service rather than wiring components itself.
 */
public final class McpService extends Service {

    public static final String ACTION_START = "dev.posedmcp.action.START";
    public static final String ACTION_STOP = "dev.posedmcp.action.STOP";

    private static final String CHANNEL_ID = "posedmcp-service";
    private static final int NOTIFICATION_ID = 4100;
    /** How long a refusal is remembered, so a rejected app cannot spam prompts. */
    private static final long REFUSAL_MEMORY_MS = 10 * 60 * 1000L;

    private static volatile McpService instance;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final java.util.Map<String, Long> refusedPeers = new java.util.concurrent.ConcurrentHashMap<>();

    private Prefs prefs;
    private EventStore events;
    private BridgeServer bridge;
    private McpServer mcp;
    private ToolRegistry tools;

    public static McpService instance() {
        return instance;
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, McpService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, McpService.class).setAction(ACTION_STOP));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = Prefs.of(this);
        // The platform's hidden APIs are what the system-side features are built
        // on; without this, reflection over them silently reports nothing.
        HiddenApi.exempt();
        // Synchronously, before anything can read a token or start a listener.
        prefs.ensureTokens();
        // Mirror the bridge credentials where hooked processes can reach them.
        BridgeCredentials.publish(this, prefs.bridgeToken(), prefs.bridgePort());
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundNow();
        startServers();
        return START_STICKY;
    }

    private void startForegroundNow() {
        Notification notification = buildNotification("Starting…");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private synchronized void startServers() {
        if (started.get()) {
            updateNotification();
            return;
        }
        try {
            events = new EventStore();
            // Window transitions come from the accessibility service, which runs
            // in this process, so they can go straight into the feed.
            AccessibilityBridge.setEventSink((type, data) ->
                    events.add("a11y", type, data, System.currentTimeMillis()));
            bridge = new BridgeServer(prefs.bridgePort(), prefs.bridgeToken(), events,
                    this::trustPeer);
            bridge.start();

            Capabilities capabilities = new Capabilities(this, bridge);
            tools = new ToolRegistry(this, prefs, capabilities, bridge, events);
            mcp = new McpServer(this, prefs, tools, events);
            mcp.start();

            started.set(true);
            // Runs 'su -c id' once, now, so no agent-triggered call can reach
            // root outside the confirmation gate.
            DeviceStatus.probeRootAsync();

            Logx.i("service started: MCP on " + prefs.mcpPort() + ", bridge on " + prefs.bridgePort());
        } catch (Throwable t) {
            Logx.e("failed to start servers", t);
        }
        updateNotification();
    }

    private synchronized void shutdown() {
        started.set(false);
        try {
            if (mcp != null) {
                mcp.stop();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (bridge != null) {
                bridge.stop();
            }
        } catch (Throwable ignored) {
        }
        mcp = null;
        bridge = null;
        tools = null;
        AccessibilityBridge.setEventSink(null);
        Logx.i("service stopped");
    }

    @Override
    public void onDestroy() {
        shutdown();
        instance = null;
        super.onDestroy();
    }

    /**
     * Decides whether an application process may use the bridge.
     *
     * <p>An application that hosts the module has no way to obtain the token -
     * Android blocks every out-of-band channel - so it asks over the connection
     * it already opened, and the user decides once per package. Later
     * connections are answered from that decision.
     *
     * <p>Refusals are remembered for a while so a rejected application cannot
     * turn itself into a popup generator by reconnecting in a loop.
     */
    private boolean trustPeer(String pkg) {
        PeerTrust trust = PeerTrust.of(this);
        if (trust.isApproved(pkg)) {
            return true;
        }
        Long refusedAt = refusedPeers.get(pkg);
        if (refusedAt != null && SystemClock.elapsedRealtime() - refusedAt < REFUSAL_MEMORY_MS) {
            return false;
        }

        ConfirmationGate.Decision decision = ConfirmationGate.request(this,
                new ConfirmationGate.Request(
                        ConfirmationGate.Kind.PEER,
                        "Application wants to connect",
                        pkg + describeApp(pkg),
                        "This application hosts the PosEdMCP module and is asking to use the "
                                + "device bridge. Allowing it lets the module inside that app "
                                + "receive injected code and report events. It does not by "
                                + "itself grant any device control - every such action still "
                                + "asks you separately.",
                        "PosEdMCP", prefs.confirmTimeoutMs()));

        if (decision.approved) {
            trust.approve(pkg);
            refusedPeers.remove(pkg);
            return true;
        }
        refusedPeers.put(pkg, SystemClock.elapsedRealtime());
        return false;
    }

    private String describeApp(String pkg) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
            String label = String.valueOf(getPackageManager().getApplicationLabel(info));
            return label.equals(pkg) ? "" : " (" + label + ")";
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- status for the UI ------------------------------------------------

    public boolean isRunning() {
        return started.get() && mcp != null && mcp.isRunning();
    }

    public int mcpPort() {
        return prefs == null ? Prefs.DEFAULT_MCP_PORT : prefs.mcpPort();
    }

    public int bridgePort() {
        return prefs == null ? Prefs.DEFAULT_BRIDGE_PORT : prefs.bridgePort();
    }

    public int connectedPeers() {
        return bridge == null ? 0 : bridge.connectedKeys().size();
    }

    public boolean systemBridgeConnected() {
        return bridge != null && bridge.systemPeer() != null;
    }

    /**
     * Runs a saved script, because the user tapped Run on it in the automation
     * tab.     *
     * <p>That tap is the user making the decision themselves, so this goes
     * straight to the bridge instead of through the confirmation gate that the
     * agent's own calls use - asking again would be asking them twice.
     */
    public org.json.JSONObject runScript(String pkg, String source, long maxInstructions)
            throws Exception {
        ToolRegistry registry = tools;
        if (registry == null) {
            throw new IllegalStateException("the service is not running");
        }
        return registry.runScript(pkg, source, maxInstructions);
    }

    /** The registered tools, so the status tab lists what actually exists. */
    public List<McpTool> tools() {
        ToolRegistry registry = tools;
        return registry == null ? Collections.emptyList() : registry.all();
    }

    // ---- notification -----------------------------------------------------

    private void ensureChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "PosEdMCP service",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the MCP endpoint and device bridge running");
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String status) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, McpService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("PosEdMCP is running")
                .setContentText(status)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        String status = "127.0.0.1:" + mcpPort() + " · "
                + (systemBridgeConnected() ? "system bridge ok" : "system bridge offline")
                + " · " + connectedPeers() + " peer(s)";
        try {
            nm.notify(NOTIFICATION_ID, buildNotification(status));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // The user swiping the app away must not take the bridge down.
        updateNotification();
    }
}
