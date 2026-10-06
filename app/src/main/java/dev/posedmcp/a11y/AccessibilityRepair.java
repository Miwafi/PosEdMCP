package dev.posedmcp.a11y;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import dev.posedmcp.root.RootShell;

/**
 * Switches a faulted accessibility service off and on again, which is the only
 * thing that clears it.
 *
 * <p>When the app's process is killed the service's connection drops, and the
 * accessibility framework records that as a crash: the component goes into
 * {@code mCrashedServices} and the framework stops binding it. The setting still
 * lists it, the switch in Settings still looks on, and nothing short of removing
 * it from {@code enabled_accessibility_services} and putting it back will make
 * the system try again. On this ROM that is not a rare state - the system's own
 * cleaner kills apps on a schedule - and losing the binding is the thing that
 * makes the app freezable, so it tends to happen again once it has happened.
 *
 * <p>Needs root, and only the user can start it: it is a button in the app, not
 * a tool, because an agent quietly re-granting itself an accessibility service
 * is exactly the shape of thing the confirmation gate exists to stop.
 */
public final class AccessibilityRepair {

    /**
     * What a component name in that setting may look like.
     *
     * <p>The value is read back out of a secure setting, which any app holding
     * WRITE_SECURE_SETTINGS could have written, and it ends up inside a root
     * shell command. Anything that is not shaped like a package/class pair is
     * dropped rather than quoted - the two commands below are the only two
     * commands this class ever runs, and nothing from the setting may add to
     * them.
     */
    private static final Pattern SAFE_COMPONENT = Pattern.compile("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+");

    /** Time for the framework to notice the service leaving the list. */
    private static final long SETTLE_MS = 1_200L;

    private AccessibilityRepair() {
    }

    /** The two values to write, in order. */
    public static final class Plan {
        /** The list with this app's service taken out. */
        public final String without;
        /** The list put back, with this app's service first. */
        public final String full;

        Plan(String without, String full) {
            this.without = without;
            this.full = full;
        }

        public String commandWithout() {
            return put(without);
        }

        public String commandFull() {
            return put(full);
        }
    }

    /**
     * Works out what to write, or {@code null} when the setting cannot be read.
     *
     * <p>Every other service in the list is carried through untouched: this
     * repairs one entry, and dropping somebody else's would be a much worse bug
     * than the one it is fixing.
     */
    public static Plan plan(Context ctx) {
        String current = AccessibilityBridge.enabledSetting(ctx);
        if (current == null) {
            return null;
        }
        String component = AccessibilityBridge.component(ctx);
        List<String> others = new ArrayList<>();
        for (String token : current.split(":")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty() || trimmed.equals(component)) {
                continue;
            }
            if (!SAFE_COMPONENT.matcher(trimmed).matches()) {
                continue;
            }
            others.add(trimmed);
        }
        String without = String.join(":", others);
        String full = without.isEmpty() ? component : component + ":" + without;
        return new Plan(without, full);
    }

    /**
     * Runs it.
     *
     * <p>Two commands, and the second one matters more than the first: leaving
     * the service out of the list would quietly take accessibility away from an
     * app that needs it to stay alive. So a failure to switch it off stops
     * everything, and a failure to put it back says so in those words rather
     * than reporting a repair.
     *
     * @return {@code null} when it worked, otherwise what to tell the user
     */
    public static String apply(Plan plan) {
        RootShell.Result off = RootShell.exec(plan.commandWithout(), 15_000L);
        if (!off.ok()) {
            return "Could not switch it off (" + why(off) + "). Nothing was changed.";
        }

        try {
            Thread.sleep(SETTLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        RootShell.Result on = RootShell.exec(plan.commandFull(), 15_000L);
        if (!on.ok()) {
            return "It was switched off, but putting it back failed (" + why(on)
                    + "). Turn it on yourself in Settings - Accessibility.";
        }
        return null;
    }

    private static String put(String value) {
        return "settings put secure enabled_accessibility_services '" + value + "'";
    }

    private static String why(RootShell.Result result) {
        if (result.timedOut) {
            return "timed out";
        }
        String detail = result.stderr == null ? "" : result.stderr.trim();
        if (detail.isEmpty()) {
            detail = result.stdout == null ? "" : result.stdout.trim();
        }
        return detail.isEmpty() ? "exit " + result.exitCode : detail.replace('\n', ' ');
    }
}
