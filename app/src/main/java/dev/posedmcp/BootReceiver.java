package dev.posedmcp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import dev.posedmcp.state.Prefs;

/**
 * Restarts the bridge after a reboot, if the user asked for that.
 *
 * <p>Root shell access is always gated behind a fresh confirmation, so leaving
 * the endpoint up across reboots does not grant an agent anything it would not
 * otherwise have.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            String action = intent == null ? null : intent.getAction();
            if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                    && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
                return;
            }
            if (!Prefs.of(context).autostart()) {
                return;
            }
            McpService.start(context);
        } catch (Throwable t) {
            Logx.e("boot receiver failed", t);
        }
    }
}
