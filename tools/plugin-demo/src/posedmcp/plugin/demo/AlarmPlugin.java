package posedmcp.plugin.demo;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

import dev.posedmcp.plugin.PluginContext;
import dev.posedmcp.plugin.PluginEntry;

/**
 * Adds an alarm to the system clock, from inside the clock's own process.
 *
 * <p>This is the payoff of running injected code rather than talking to the app
 * from outside: the plugin holds the application's Context and its uid, so it can
 * use the app's private alarm path without any of it being exported.
 *
 * <p>Two routes, in order of preference:
 * <ol>
 *   <li>the app's own AI provider, {@code AiSupportContentProvider.addAlarm},
 *       which is the path the vendor's voice assistant uses - it validates and
 *       schedules the alarm properly;</li>
 *   <li>a direct insert into the {@code alarms} table, which is the AOSP
 *       DeskClock schema the app still uses.</li>
 * </ol>
 * The first is tried with several plausible key spellings because the bundle
 * format is not documented anywhere; the reply says which one the app accepted.
 */
public class AlarmPlugin implements PluginEntry {

    /** The class the app registers for its assistant-facing alarm API. */
    private static final String AI_PROVIDER_CLASS =
            "com.oplus.alarmclock.ai.AiSupportContentProvider";

    private static final String AI_PROVIDER_CLASS_PURE =
            "com.oplus.alarmclock.ai.AiSupportContentProviderPure";

    /** The extras the app's own bundle parser reads, taken from its smali. */
    private static final String EXTRA_HOUR = "android.intent.extra.alarm.HOUR";
    private static final String EXTRA_MINUTES = "android.intent.extra.alarm.MINUTES";
    private static final String EXTRA_DAYS_OF_WEEK = "android.intent.extra.alarm.DAYS_OF_WEEK";
    private static final String EXTRA_LABEL = "label";
    private static final String EXTRA_SKIP_UI = "android.intent.extra.alarm.SKIP_UI";

    private PluginContext context;

    @Override
    public void onLoad(PluginContext ctx) {
        this.context = ctx;
        ctx.log("alarm plugin loaded into " + ctx.packageName());
    }

    @Override
    public Object invoke(String method, String argsJson) throws Exception {
        JSONObject args = argsJson == null || argsJson.isEmpty()
                ? new JSONObject() : new JSONObject(argsJson);
        switch (method) {
            case "addAlarm":
                return addAlarm(args);
            case "deleteAlarmByLabel":
                return deleteByLabel(args);
            case "info":
                return info();
            default:
                throw new IllegalArgumentException("unknown method '" + method + "'");
        }
    }

    /**
     * Removes alarms whose label matches, so a demonstration does not leave
     * stray alarms behind on someone's phone.
     */
    private JSONObject deleteByLabel(JSONObject args) throws Exception {
        Context ctx = context.appContext();
        String label = args.optString("label", "");
        JSONObject out = new JSONObject();
        out.put("label", label);

        String authority = authorityOf(ctx, AI_PROVIDER_CLASS);
        Bundle query = new Bundle();
        Bundle listed = ctx.getContentResolver()
                .call(Uri.parse("content://" + authority), "get_alarm_list", null, query);
        if (listed == null) {
            throw new IllegalStateException("the app returned no alarm list");
        }

        String[] labels = listed.getStringArray("alarm_label_list");
        long[] ids = listed.getLongArray("alarm_id_list");
        JSONArray removed = new JSONArray();
        if (labels != null && ids != null && labels.length == ids.length) {
            for (int i = 0; i < labels.length; i++) {
                if (labels[i] != null && labels[i].contains(label)) {
                    Bundle del = new Bundle();
                    del.putLong("alarm_id", ids[i]);
                    del.putLongArray("alarm_id_list", new long[]{ids[i]});
                    ctx.getContentResolver().call(Uri.parse("content://" + authority),
                            "delete_alarm", null, del);
                    removed.put(ids[i]);
                }
            }
        }
        out.put("removedIds", removed);
        return out;
    }

    private JSONObject info() {
        JSONObject out = new JSONObject();
        try {
            Context ctx = context.appContext();
            out.put("package", context.packageName());
            out.put("appContextReady", ctx != null);
            if (ctx != null) {
                out.put("aiProviderAuthority", authorityOf(ctx, AI_PROVIDER_CLASS));
                out.put("aiProviderPureAuthority", authorityOf(ctx, AI_PROVIDER_CLASS_PURE));
                out.put("databasePath", ctx.getDatabasePath("alarms.db").getAbsolutePath());
            }
        } catch (Throwable t) {
            try {
                out.put("error", String.valueOf(t));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private JSONObject addAlarm(JSONObject args) throws Exception {
        Context ctx = context.appContext();
        if (ctx == null) {
            throw new IllegalStateException("the application context is not ready yet");
        }
        int hour = args.optInt("hour", 6);
        int minute = args.optInt("minute", args.optInt("minutes", 7));
        String label = args.optString("label", "PosEdMCP");
        // 0 means "no repeat": a one-shot alarm at the next occurrence.
        int days = args.optInt("daysofweek", 0);

        JSONObject out = new JSONObject();
        out.put("requested", new JSONObject()
                .put("hour", hour).put("minute", minute)
                .put("label", label).put("daysofweek", days));

        JSONArray attempts = new JSONArray();
        for (String providerClass : new String[]{AI_PROVIDER_CLASS, AI_PROVIDER_CLASS_PURE}) {
            String authority = authorityOf(ctx, providerClass);
            if (authority == null) {
                attempts.put(providerClass + ": not registered");
                continue;
            }
            JSONObject result = tryProvider(ctx, authority, hour, minute, label, days);
            result.put("provider", providerClass);
            attempts.put(result);
            if (result.optBoolean("accepted", false)) {
                out.put("route", "app-provider");
                out.put("attempts", attempts);
                out.put("result", result);
                return out;
            }
        }

        JSONObject direct = insertDirectly(ctx, hour, minute, label, days);
        attempts.put(direct);
        out.put("attempts", attempts);
        out.put("route", direct.optBoolean("inserted", false) ? "direct-database" : "none");
        out.put("result", direct);
        return out;
    }

    /**
     * Calls the app's own addAlarm, guessing at the method and bundle keys.
     *
     * <p>Neither is documented and the app is obfuscated. Its {@code call()}
     * dispatches on snake_case names ({@code get_alarm_list}, {@code match_alarm},
     * ...), so the add method is very likely {@code add_alarm}; the alternatives
     * are cheap to try and the reply says which one the app acted on.
     */
    private JSONObject tryProvider(Context ctx, String authority, int hour, int minute,
            String label, int days) throws Exception {
        JSONObject out = new JSONObject();
        JSONArray tried = new JSONArray();

        Bundle in = new Bundle();
        // The exact contract, read out of the app's own bundle parser: it uses
        // the standard AlarmClock intent extras. HOUR defaults to -1, which is
        // why an alarm added without it silently lands on the app's default time.
        in.putInt(EXTRA_HOUR, hour);
        in.putInt(EXTRA_MINUTES, minute);
        in.putByte(EXTRA_DAYS_OF_WEEK, (byte) days);
        in.putString(EXTRA_LABEL, label);
        in.putBoolean(EXTRA_SKIP_UI, true);

        ContentResolver resolver = ctx.getContentResolver();
        for (String method : new String[]{"add_alarm", "addAlarm", "METHOD_ADD_ALARM"}) {
            JSONObject attempt = new JSONObject();
            try {
                Bundle reply = resolver.call(Uri.parse("content://" + authority),
                        method, null, in);
                attempt.put("method", method);
                if (reply == null) {
                    attempt.put("reply", "null");
                    tried.put(attempt);
                    continue;
                }
                JSONObject keys = new JSONObject();
                for (String key : reply.keySet()) {
                    Object value = reply.get(key);
                    keys.put(key, value == null ? "null" : String.valueOf(value));
                }
                attempt.put("reply", keys);
                tried.put(attempt);
                if (reply.getInt("result", -99) >= 0) {
                    out.put("accepted", true);
                    out.put("method", method);
                    out.put("attempts", tried);
                    return out;
                }
            } catch (Throwable t) {
                try {
                    attempt.put("method", method);
                    attempt.put("error", String.valueOf(t));
                    tried.put(attempt);
                } catch (Throwable ignored) {
                }
            }
        }
        out.put("accepted", false);
        out.put("attempts", tried);
        return out;
    }

    /**
     * Writes the row the app's own UI writes.
     *
     * <p>The schema is the one DeskClock has used for years and this app kept it,
     * so only the NOT NULL columns need filling. {@code alarmtime} is the epoch
     * milliseconds of the next occurrence, which the framework column means.
     */
    private JSONObject insertDirectly(Context ctx, int hour, int minute, String label, int days) {
        JSONObject out = new JSONObject();
        try {
            String path = ctx.getDatabasePath("alarms.db").getAbsolutePath();
            out.put("database", path);
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(path, null,
                    SQLiteDatabase.OPEN_READWRITE)) {
                ContentValues values = new ContentValues();
                values.put("hour", hour);
                values.put("minutes", minute);
                values.put("daysofweek", days);
                values.put("alarmtime", nextOccurrence(hour, minute));
                values.put("enabled", 1);
                values.put("message", label);
                values.put("vibrate", 1);
                values.put("alerttype", 0);
                values.put("snooze", 600000);
                values.put("deleteAfterUse", 0);
                long id = db.insert("alarms", null, values);
                out.put("inserted", id >= 0);
                out.put("rowId", id);
            }
        } catch (Throwable t) {
            try {
                out.put("inserted", false);
                out.put("error", String.valueOf(t));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** Epoch millis of the next time the clock reads {@code hour:minute}. */
    private static long nextOccurrence(int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }
        return calendar.getTimeInMillis();
    }

    /** Looks up the authority the app registered for one of its own providers. */
    private static String authorityOf(Context ctx, String providerClass) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(
                    ctx.getPackageName(), android.content.pm.PackageManager.GET_PROVIDERS);
            if (info.providers == null) {
                return null;
            }
            for (ProviderInfo provider : info.providers) {
                if (provider != null && providerClass.equals(provider.name)) {
                    return provider.authority;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Override
    public void onUnload() {
        if (context != null) {
            context.log("alarm plugin unloaded");
        }
    }
}
