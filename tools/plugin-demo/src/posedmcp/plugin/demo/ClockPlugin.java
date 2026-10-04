package posedmcp.plugin.demo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Deque;

import dev.posedmcp.plugin.HookApi;
import dev.posedmcp.plugin.PluginContext;
import dev.posedmcp.plugin.PluginEntry;

/**
 * A demonstration plugin for the PosEdMCP injection path.
 *
 * <p>It is deliberately small but not trivial: it installs a real hook into the
 * host application and reports what it sees, so "the injection worked" can be
 * checked by observing behaviour rather than by trusting a log line.
 *
 * <p>Compile it against the interfaces in the PosEdMCP APK, convert to DEX, and
 * hand the bytes to {@code plugin_load}:
 *
 * <pre>
 *   ./tools/build-plugin.sh
 * </pre>
 */
public class ClockPlugin implements PluginEntry {

    /** Recent activity resumes, newest first. */
    private static final Deque<String> RESUMES = new ArrayDeque<>();
    private static final int MAX_RESUMES = 32;

    private PluginContext context;
    private HookApi.Unhook hook;

    @Override
    public void onLoad(PluginContext ctx) {
        this.context = ctx;
        ctx.log("plugin loading into " + ctx.packageName());

        // Hook the framework class, not an app-specific one, so the plugin keeps
        // working when the vendor moves their classes around.
        hook = ctx.hooks().hookMethod("android.app.Activity", "onResume", new Class<?>[0],
                new HookApi.Callback() {
                    @Override
                    public void after(HookApi.HookParam param) {
                        Object self = param.thisObject();
                        if (self == null) {
                            return;
                        }
                        String name = self.getClass().getName();
                        synchronized (RESUMES) {
                            RESUMES.addFirst(name);
                            while (RESUMES.size() > MAX_RESUMES) {
                                RESUMES.removeLast();
                            }
                        }
                    }
                });

        ctx.log("hook installed on Activity.onResume; appContext="
                + (ctx.appContext() == null ? "not ready" : ctx.appContext().getPackageName()));
    }

    @Override
    public Object invoke(String method, String argsJson) throws Exception {
        JSONObject out = new JSONObject();

        switch (method) {
            case "info":
                out.put("package", context.packageName());
                out.put("appContext",
                        context.appContext() == null ? null : context.appContext().getPackageName());
                out.put("appClassLoader", String.valueOf(context.appClassLoader()));
                out.put("pluginClassLoader", String.valueOf(context.pluginClassLoader()));
                out.put("activityOnResumeHookInstalled", hook != null);
                return out;

            case "recentActivities": {
                JSONArray array = new JSONArray();
                synchronized (RESUMES) {
                    for (String name : RESUMES) {
                        array.put(name);
                    }
                }
                out.put("activities", array);
                out.put("note", "Most recent Activity.onResume calls seen inside this app process.");
                return out;
            }

            case "activityCount":
                out.put("count", RESUMES.size());
                return out;

            default:
                throw new IllegalArgumentException("unknown method '" + method
                        + "'; try info, recentActivities or activityCount");
        }
    }

    @Override
    public void onUnload() {
        if (hook != null) {
            hook.unhook();
            hook = null;
        }
        if (context != null) {
            context.log("plugin unloaded");
        }
    }
}
