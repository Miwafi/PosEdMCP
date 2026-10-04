package dev.posedmcp.root;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.posedmcp.Logx;

/**
 * The modal that a privileged request has to pass through.
 *
 * <p>It is an application overlay rather than an activity so that it can appear
 * while another app is in the foreground, and so a stray tap on the app
 * underneath cannot approve anything. The command is rendered verbatim: what
 * the user reads is exactly what {@code su} will run.
 */
final class ConfirmOverlay {

    private static final int COLOR_BG = 0xFF1B1F23;
    private static final int COLOR_PANEL = 0xFF0E1114;
    private static final int COLOR_TEXT = 0xFFE6E6E6;
    private static final int COLOR_MUTED = 0xFF9AA4AE;
    private static final int COLOR_ACCENT = 0xFF7FBF6A;
    private static final int COLOR_DANGER = 0xFFD96C6C;
    private static final int COLOR_NEUTRAL = 0xFF3A4249;

    private ConfirmOverlay() {
    }

    /** Handle to a window that is currently on screen. */
    static final class Session {
        private final WindowManager wm;
        private final View root;
        private final Handler main = new Handler(Looper.getMainLooper());
        private Runnable tick;
        private boolean dismissed;

        Session(WindowManager wm, View root) {
            this.wm = wm;
            this.root = root;
        }

        /** Safe to call from any thread; the window itself is only touched on the main one. */
        void dismiss() {
            main.post(() -> dismissOnMain());
        }

        private void dismissOnMain() {
            if (dismissed) {
                return;
            }
            dismissed = true;
            main.removeCallbacksAndMessages(null);
            try {
                wm.removeViewImmediate(root);
            } catch (Throwable t) {
                Logx.w("overlay remove failed: " + t);
            }
        }
    }

    interface OnDecision {
        void onDecision(boolean approved, String note);
    }

    /**
     * Shows the confirmation window and reports whether it made it onto the screen.
     *
     * <p>Tool calls arrive on a worker thread, and a View cannot be constructed or
     * attached there, so the whole build runs on the main looper. The caller is
     * about to block waiting for the user anyway, so waiting briefly for the
     * window to appear costs nothing and lets it fall back to a notification if
     * the window is refused.
     */
    static boolean show(Context ctx, ConfirmationGate.Request req, OnDecision onDecision) {
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            return false;
        }
        Handler main = new Handler(Looper.getMainLooper());
        java.util.concurrent.CountDownLatch attached =
                new java.util.concurrent.CountDownLatch(1);
        boolean[] success = {false};

        main.post(() -> {
            try {
                success[0] = buildAndAttach(ctx, wm, req, onDecision, main);
            } catch (Throwable t) {
                Logx.e("could not attach confirmation overlay", t);
            } finally {
                attached.countDown();
            }
        });

        try {
            if (!attached.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                Logx.w("confirmation overlay did not appear within 5s");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return success[0];
    }

    private static boolean buildAndAttach(Context ctx, WindowManager wm,
            ConfirmationGate.Request req, OnDecision onDecision, Handler main) {
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        int screenH = ctx.getResources().getDisplayMetrics().heightPixels;

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 18);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(rounded(COLOR_BG, dp(ctx, 16)));

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(titleView(ctx, req.title, 17f, COLOR_TEXT, true),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView countdown = new TextView(ctx);
        countdown.setTextColor(COLOR_MUTED);
        countdown.setTextSize(13f);
        header.addView(countdown);
        card.addView(header);

        if (!TextUtils.isEmpty(req.requester)) {
            TextView who = new TextView(ctx);
            who.setText("Requested by: " + req.requester);
            who.setTextColor(COLOR_MUTED);
            who.setTextSize(12f);
            card.addView(who, topMargin(ctx, 4));
        }

        card.addView(sectionLabel(ctx, "COMMAND", COLOR_DANGER));
        card.addView(scrollingBlock(ctx, req.detail, true), weighted(ctx, 6));

        if (!TextUtils.isEmpty(req.reason)) {
            card.addView(sectionLabel(ctx, "STATED REASON", COLOR_ACCENT));
            card.addView(scrollingBlock(ctx, req.reason, false), weighted(ctx, 6));
        }

        LinearLayout actions = new LinearLayout(ctx);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        card.addView(actions, topMargin(ctx, 14));

        Button deny = new Button(ctx);
        deny.setText("Deny");
        deny.setAllCaps(false);
        deny.setTextColor(COLOR_TEXT);
        deny.setBackground(rounded(COLOR_NEUTRAL, dp(ctx, 10)));
        actions.addView(deny, new LinearLayout.LayoutParams(dp(ctx, 104), dp(ctx, 46)));

        Button approve = new Button(ctx);
        approve.setText(req.approveLabel());
        approve.setAllCaps(false);
        approve.setTextColor(Color.BLACK);
        approve.setBackground(rounded(COLOR_DANGER, dp(ctx, 10)));
        LinearLayout.LayoutParams approveLp =
                new LinearLayout.LayoutParams(dp(ctx, 152), dp(ctx, 46));
        approveLp.leftMargin = dp(ctx, 10);
        actions.addView(approve, approveLp);

        WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(
                Math.min(dp(ctx, 380), screenW - dp(ctx, 32)),
                Math.min(dp(ctx, 520), screenH - dp(ctx, 96)),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        wlp.gravity = Gravity.CENTER;

        Session session = new Session(wm, card);
        final long deadline = System.currentTimeMillis() + req.timeoutMs;
        final boolean[] answered = {false};

        session.tick = new Runnable() {
            @Override
            public void run() {
                if (answered[0]) {
                    return;
                }
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    answered[0] = true;
                    session.dismiss();
                    onDecision.onDecision(false, "no answer within " + (req.timeoutMs / 1000) + "s");
                    return;
                }
                countdown.setText((left + 999) / 1000 + "s");
                main.postDelayed(this, 500L);
            }
        };

        deny.setOnClickListener(v -> {
            if (answered[0]) {
                return;
            }
            answered[0] = true;
            session.dismissOnMain();
            onDecision.onDecision(false, "denied by the user");
        });
        approve.setOnClickListener(v -> {
            if (answered[0]) {
                return;
            }
            answered[0] = true;
            session.dismissOnMain();
            onDecision.onDecision(true, "approved by the user");
        });

        try {
            wm.addView(card, wlp);
        } catch (Throwable t) {
            Logx.e("could not attach confirmation overlay", t);
            return false;
        }
        main.post(session.tick);
        return true;
    }

    private static TextView titleView(Context ctx, String text, float size, int color,
            boolean bold) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(size);
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return tv;
    }

    /** A scrollable monospace/text panel, so long commands stay fully readable. */
    private static ScrollView scrollingBlock(Context ctx, String text, boolean monospace) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(COLOR_TEXT);
        tv.setTextSize(13f);
        if (monospace) {
            tv.setTypeface(Typeface.MONOSPACE);
        }
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        tv.setBackground(rounded(COLOR_PANEL, dp(ctx, 10)));
        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(tv);
        return scroll;
    }

    private static TextView sectionLabel(Context ctx, String text, int color) {
        TextView tv = titleView(ctx, text, 11f, color, true);
        tv.setLetterSpacing(0.08f);
        return tv;
    }

    private static LinearLayout.LayoutParams topMargin(Context ctx, int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, dp);
        return lp;
    }

    private static LinearLayout.LayoutParams weighted(Context ctx, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0);
        lp.weight = 1f;
        lp.topMargin = dp(ctx, topDp);
        return lp;
    }

    private static int dp(Context ctx, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                ctx.getResources().getDisplayMetrics());
    }

    private static GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }
}
