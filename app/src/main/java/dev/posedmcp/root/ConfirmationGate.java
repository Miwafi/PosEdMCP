package dev.posedmcp.root;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import dev.posedmcp.Logx;
import dev.posedmcp.state.Prefs;

/**
 * The approval gate in front of everything that can hurt.
 *
 * <p>Root shell execution always requires an explicit answer - there is no
 * setting that turns that off, because the whole point of the design is that an
 * agent cannot run a command the user has not read. The other kinds
 * (screen capture, synthetic input, loading code into another app) are gated by
 * default and can be relaxed in the app.
 *
 * <p>If the answer cannot be collected - no overlay permission and no
 * notification permission - the request is denied rather than allowed. Failing
 * closed is the only safe direction here.
 */
public final class ConfirmationGate {

    public static final String ACTION_CONFIRM = "dev.posedmcp.action.CONFIRM";
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_APPROVED = "approved";

    private static final String CHANNEL_ID = "posedmcp-confirm";
    private static final int NOTIFICATION_ID_BASE = 41000;

    public enum Kind {
        /** A root shell command. Always confirmed. */
        SHELL,
        /** Screen capture or a UI hierarchy dump. */
        SCREEN,
        /** Synthetic input events. */
        INPUT,
        /** Loading code into a scoped app's process. */
        PLUGIN,
        /** An application asking to use the device bridge. Always confirmed. */
        PEER,
    }

    public static final class Request {
        public final Kind kind;
        public final String title;
        public final String detail;
        public final String reason;
        public final String requester;
        public final long timeoutMs;

        public Request(Kind kind, String title, String detail, String reason, String requester,
                long timeoutMs) {
            this.kind = kind;
            this.title = title;
            this.detail = detail;
            this.reason = reason == null ? "" : reason;
            this.requester = requester == null ? "" : requester;
            this.timeoutMs = timeoutMs;
        }

        String approveLabel() {
            switch (kind) {
                case SHELL:
                    return "Run as root";
                case PEER:
                    return "Allow";
                default:
                    return "Allow";
            }
        }
    }

    public static final class Decision {
        public final boolean approved;
        public final String note;

        Decision(boolean approved, String note) {
            this.approved = approved;
            this.note = note;
        }
    }

    private static final AtomicLong IDS = new AtomicLong(1L);
    private static final Map<Long, Pending> PENDING = new ConcurrentHashMap<>();
    /** Only one prompt is on screen at a time, so approvals cannot be confused. */
    private static final ReentrantLock PROMPT_LOCK = new ReentrantLock(true);

    private static final class Pending {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean approved;
        volatile String note = "no answer";
    }

    private ConfirmationGate() {
    }

    public static boolean isRequired(Context ctx, Kind kind) {
        Prefs prefs = Prefs.of(ctx);
        switch (kind) {
            case SHELL:
            case PEER:
                // Deciding who may talk to the bridge is a trust decision; it is
                // not something a setting should be able to wave through.
                return true;
            case SCREEN:
                return prefs.confirmScreen();
            case INPUT:
                return prefs.confirmInput();
            case PLUGIN:
                return prefs.confirmPlugin();
            default:
                return true;
        }
    }

    public static Decision request(Context ctx, Request req) {
        // An audit line for every prompt: what was asked, and why the asker said
        // it was needed. The dialog is the decision point, so what it displayed
        // is worth keeping.
        Logx.i("confirmation[" + req.kind + "] " + oneLine(req.detail, 160)
                + " | reason: " + oneLine(req.reason, 160));

        if (!isRequired(ctx, req.kind)) {
            return new Decision(true, "confirmation disabled for " + req.kind.name().toLowerCase());
        }

        PROMPT_LOCK.lock();
        try {
            if (Settings.canDrawOverlays(ctx)) {
                Decision viaOverlay = askViaOverlay(ctx, req);
                if (viaOverlay != null) {
                    return viaOverlay;
                }
                Logx.w("overlay unavailable despite permission; falling back to a notification");
            }
            if (canNotify(ctx)) {
                return askViaNotification(ctx, req);
            }
            return new Decision(false,
                    "cannot ask for approval: grant \"display over other apps\" (and notification) "
                            + "permission to PosEdMCP, then retry");
        } finally {
            PROMPT_LOCK.unlock();
        }
    }

    private static Decision askViaOverlay(Context ctx, Request req) {
        Pending pending = new Pending();
        boolean shown = ConfirmOverlay.show(ctx, req, (approved, note) -> {
            pending.approved = approved;
            pending.note = note;
            pending.latch.countDown();
        });
        if (!shown) {
            return null;
        }
        // The overlay enforces its own countdown and removes itself.
        return await(pending, req.timeoutMs + 5_000L, null);
    }

    private static Decision askViaNotification(Context ctx, Request req) {
        Pending pending = new Pending();
        long id = IDS.getAndIncrement();
        PENDING.put(id, pending);

        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) {
            PENDING.remove(id);
            return new Decision(false, "notification service unavailable");
        }
        ensureChannel(nm);

        String body = req.detail + (TextUtils.isEmpty(req.reason) ? "" : "\n\nWhy: " + req.reason);

        Notification.Builder builder = new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle(req.title)
                .setContentText(req.detail.replace('\n', ' '))
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setOngoing(true)
                .setAutoCancel(false)
                .setCategory(Notification.CATEGORY_ALARM)
                .addAction(new Notification.Action.Builder(null, "Deny", action(ctx, id, false)).build())
                .addAction(new Notification.Action.Builder(null, "Approve", action(ctx, id, true)).build());

        Notification notification = builder.build();
        nm.notify(NOTIFICATION_ID_BASE + (int) (id % 1000), notification);

        try {
            return await(pending, req.timeoutMs,
                    () -> nm.cancel(NOTIFICATION_ID_BASE + (int) (id % 1000)));
        } finally {
            PENDING.remove(id);
        }
    }

    private static PendingIntent action(Context ctx, long id, boolean approved) {
        Intent intent = new Intent(ctx, ConfirmActionReceiver.class)
                .setAction(ACTION_CONFIRM)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_APPROVED, approved);
        return PendingIntent.getBroadcast(ctx, (int) (id % 1000) * 2 + (approved ? 1 : 0), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Called by {@link ConfirmActionReceiver} when the user taps a notification action. */
    static boolean resolve(long id, boolean approved) {
        Pending pending = PENDING.get(id);
        if (pending == null) {
            return false;
        }
        pending.approved = approved;
        pending.note = approved ? "approved by the user" : "denied by the user";
        pending.latch.countDown();
        return true;
    }

    private static Decision await(Pending pending, long timeoutMs, Runnable cleanup) {
        try {
            boolean answered = pending.latch.await(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
            if (!answered) {
                return new Decision(false, "no answer within " + (timeoutMs / 1000) + "s");
            }
            return new Decision(pending.approved, pending.note);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Decision(false, "confirmation interrupted");
        } finally {
            if (cleanup != null) {
                cleanup.run();
            }
        }
    }

    private static String oneLine(String value, int limit) {
        if (value == null) {
            return "";
        }
        String flat = value.replace('\n', ' ').trim();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "...";
    }

    private static boolean canNotify(Context ctx) {        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) {
            return false;
        }
        return ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private static void ensureChannel(NotificationManager nm) {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Approval requests", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Asks for approval before a privileged action runs");
        channel.setBypassDnd(true);
        nm.createNotificationChannel(channel);
    }
}
