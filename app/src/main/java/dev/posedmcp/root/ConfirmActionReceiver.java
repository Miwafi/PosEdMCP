package dev.posedmcp.root;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Receives the Approve/Deny taps from the notification fallback used when the
 * overlay permission is not granted.
 */
public class ConfirmActionReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ConfirmationGate.ACTION_CONFIRM.equals(intent.getAction())) {
            return;
        }
        long id = intent.getLongExtra(ConfirmationGate.EXTRA_ID, -1L);
        boolean approved = intent.getBooleanExtra(ConfirmationGate.EXTRA_APPROVED, false);
        if (id >= 0) {
            ConfirmationGate.resolve(id, approved);
        }
    }
}
