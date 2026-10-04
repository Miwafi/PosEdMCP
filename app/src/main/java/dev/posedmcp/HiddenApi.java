package dev.posedmcp;

/**
 * Lifts the hidden-API restrictions for this process.
 *
 * <p>Without this, reflection over platform internals silently under-reports:
 * {@code Class.getDeclaredMethods()} returns only the non-hidden members, and
 * invoking a hidden method fails as if it did not exist. That is exactly how the
 * screen-capture chain failed - {@code SurfaceControl.getPhysicalDisplayToken}
 * and every {@code ScreenCapture} method were invisible, so the code concluded
 * the build had no capture path when in fact it was never allowed to look.
 *
 * <p>The module is a legitimate consumer of these APIs - it runs inside
 * system_server - and the platform's own tooling exempts modules the same way.
 * This is a deliberate, process-local relaxation, not a sandbox escape: it only
 * affects what this process may reflect on.
 */
public final class HiddenApi {

    private static volatile boolean attempted;
    private static volatile boolean exempt;

    private HiddenApi() {
    }

    public static boolean isExempt() {
        return exempt;
    }

    /** Idempotent; safe to call from any thread and from any process. */
    public static void exempt() {
        if (attempted) {
            return;
        }
        synchronized (HiddenApi.class) {
            if (attempted) {
                return;
            }
            attempted = true;
            try {
                Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
                Object runtime = vmRuntime.getDeclaredMethod("getRuntime").invoke(null);
                java.lang.reflect.Method setExemptions = vmRuntime
                        .getDeclaredMethod("setHiddenApiExemptions", String[].class);
                setExemptions.setAccessible(true);
                // "L" exempts every signature starting with L, i.e. all classes.
                setExemptions.invoke(runtime, (Object) new String[]{"L"});
                exempt = true;
                Logx.i("hidden API exemptions installed");
            } catch (Throwable t) {
                Logx.w("could not install hidden API exemptions: " + t);
            }
        }
    }
}
