package dev.posedmcp.state;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Remembers which application packages the user has allowed to use the bridge.
 *
 * <p>An application that hosts the module cannot be handed the bridge token out
 * of band: Android blocks abstract sockets between apps, hides this app's
 * package from ordinary third-party callers, and refuses cross-uid reads of its
 * files. The token is therefore delivered over the connection the application
 * already opened - which means the connection itself has to be the thing the
 * user approves, once per package.
 *
 * <p>Approving a package trusts whatever process claims that package name. That
 * is not a cryptographic guarantee, and it is not meant to be: the dialog names
 * the application, the decision is the user's, and everything dangerous behind
 * the bridge still needs a separate, per-command approval.
 */
public final class PeerTrust {

    private static final String KEY_PREFIX = "trusted_pkg_";

    private final SharedPreferences sp;

    private PeerTrust(SharedPreferences sp) {
        this.sp = sp;
    }

    public static PeerTrust of(Context ctx) {
        return new PeerTrust(ctx.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE));
    }

    public boolean isApproved(String pkg) {
        return pkg != null && !pkg.isEmpty() && sp.getBoolean(KEY_PREFIX + pkg, false);
    }

    public void approve(String pkg) {
        if (pkg != null && !pkg.isEmpty()) {
            sp.edit().putBoolean(KEY_PREFIX + pkg, true).commit();
        }
    }

    public void revoke(String pkg) {
        if (pkg != null && !pkg.isEmpty()) {
            sp.edit().remove(KEY_PREFIX + pkg).commit();
        }
    }
}
