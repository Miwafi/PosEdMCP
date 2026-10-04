package dev.posedmcp.xposed;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicReference;

import dev.posedmcp.Logx;

/**
 * The platform-privileged operations, performed from inside system_server.
 *
 * <p>Everything here is best-effort against hidden APIs. Each operation returns
 * either a result or a clear error, and the MCP layer falls back to the
 * confirmed root shell when the fast path is unavailable - so a platform
 * change degrades the experience rather than breaking the tool.
 */
final class SystemOps {

    /** MotionEvents injected with this flag are delivered without waiting for a result. */
    private static final int INJECT_ASYNC = 0;
    private static final int INJECT_WAIT_FOR_FINISH = 2;

    private final AtomicReference<String> lastForeground = new AtomicReference<>();

    SystemOps() {
    }

    void rememberForeground(String description) {
        lastForeground.set(description);
    }

    String lastForeground() {
        return lastForeground.get();
    }

    // =====================================================================
    // Screenshot
    // =====================================================================

    String screenshotBase64() throws Exception {
        byte[] png = captureToPng();
        return Base64.encodeToString(png, Base64.NO_WRAP);
    }

    private byte[] captureToPng() throws Exception {
        Bitmap bitmap = captureBitmap();
        if (bitmap == null) {
            throw new IllegalStateException("no screenshot path available on this build");
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new IllegalStateException("PNG compression failed");
            }
            return out.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    private Bitmap captureBitmap() {
        Bitmap bitmap = captureViaScreenCapture();
        if (bitmap != null) {
            return bitmap;
        }
        bitmap = captureViaSurfaceControl();
        if (bitmap == null) {
            Logx.w("no screenshot path worked; last problem: " + lastCaptureError);
        }
        return bitmap;
    }

    /**
     * Why the last capture attempt failed. Surfaced through {@code status} so the
     * reason is visible without digging through system_server's log.
     */
    private volatile String lastCaptureError = "not attempted";

    public String lastCaptureError() {
        return lastCaptureError;
    }

    /** Android 14+ path: {@code android.window.ScreenCapture}. */
    private Bitmap captureViaScreenCapture() {
        try {
            Class<?> argsClass = Reflect.findClass("android.window.ScreenCapture$DisplayCaptureArgs");
            Class<?> builderClass =
                    Reflect.findClass("android.window.ScreenCapture$DisplayCaptureArgs$Builder");
            if (argsClass == null || builderClass == null) {
                lastCaptureError = "ScreenCapture.DisplayCaptureArgs not found";
                return null;
            }

            Object token = displayToken();
            if (token == null) {
                lastCaptureError = "no physical display token";
                return null;
            }

            int[] size = displaySize();
            if (size == null) {
                // The capture still works at native resolution without an
                // explicit size; only the downscale hint is lost.
                size = new int[]{0, 0};
                Logx.w("could not read the display size; capturing at native resolution");
            }

            Object builder = builderClass.getConstructor(android.os.IBinder.class)
                    .newInstance(token);
            if (size[0] > 0 && size[1] > 0) {
                Reflect.call(builder, "setSize", new Class<?>[]{int.class, int.class},
                        size[0], size[1]);
            }
            Object args = Reflect.call(builder, "build");
            if (args == null) {
                lastCaptureError = "DisplayCaptureArgs.build() returned null";
                return null;
            }

            Object result = Reflect.callStatic("android.window.ScreenCapture", "captureDisplay",
                    new Class<?>[]{argsClass}, args);
            if (result == null) {
                lastCaptureError = "ScreenCapture.captureDisplay() returned null";
                return null;
            }
            Object buffer = Reflect.call(result, "getHardwareBuffer");
            if (!(buffer instanceof HardwareBuffer)) {
                lastCaptureError = "captureDisplay gave no HardwareBuffer (got " + buffer + ")";
                return null;
            }
            ColorSpace colorSpace = (ColorSpace) Reflect.call(result, "getColorSpace");
            HardwareBuffer hardwareBuffer = (HardwareBuffer) buffer;
            try {
                Bitmap wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace);
                if (wrapped == null) {
                    lastCaptureError = "wrapHardwareBuffer returned null";
                    return null;
                }
                // The wrapped bitmap aliases the buffer, which is released below;
                // copy it into normal heap memory before that happens.
                Bitmap copy = wrapped.copy(Bitmap.Config.ARGB_8888, false);
                wrapped.recycle();
                if (copy == null) {
                    lastCaptureError = "could not copy the hardware bitmap";
                    return null;
                }
                lastCaptureError = null;
                return copy;
            } finally {
                hardwareBuffer.close();
            }
        } catch (Throwable t) {
            lastCaptureError = "ScreenCapture path threw: " + t;
            Logx.w("ScreenCapture path unavailable: " + t);
            return null;
        }
    }

    /** Pre-14 path: {@code SurfaceControl.screenshot}. */
    private Bitmap captureViaSurfaceControl() {
        try {
            int[] size = displaySize();
            Class<?> surfaceControl = Reflect.findClass("android.view.SurfaceControl");
            if (surfaceControl == null || size == null) {
                return null;
            }
            Rect rect = new Rect(0, 0, size[0], size[1]);
            Object bitmap = Reflect.callStatic(surfaceControl, "screenshot",
                    new Class<?>[]{Rect.class}, rect);
            if (bitmap instanceof Bitmap) {
                return (Bitmap) bitmap;
            }
            Object token = displayToken();
            Object full = Reflect.callStatic(surfaceControl, "screenshot",
                    new Class<?>[]{android.os.IBinder.class}, token);
            return full instanceof Bitmap ? (Bitmap) full : null;
        } catch (Throwable t) {
            Logx.w("SurfaceControl.screenshot unavailable: " + t);
            return null;
        }
    }

    private Object displayToken() {
        Object token = Reflect.callStatic("android.view.SurfaceControl", "getPhysicalDisplayToken",
                new Class<?>[]{int.class}, Display.DEFAULT_DISPLAY);
        if (token != null) {
            return token;
        }
        // Some builds only expose it with a long display id.
        token = Reflect.callStatic("android.view.SurfaceControl", "getPhysicalDisplayToken",
                new Class<?>[]{long.class}, (long) Display.DEFAULT_DISPLAY);
        if (token != null) {
            return token;
        }
        // Ask for the ids and take the first one.
        Object ids = Reflect.callStatic("android.view.SurfaceControl", "getPhysicalDisplayIds",
                new Class<?>[0]);
        if (ids instanceof long[] && ((long[]) ids).length > 0) {
            token = Reflect.callStatic("android.view.SurfaceControl", "getPhysicalDisplayToken",
                    new Class<?>[]{long.class}, ((long[]) ids)[0]);
            if (token != null) {
                return token;
            }
        }
        // Last resort: ask DisplayManagerGlobal, which knows the token of each
        // display without going through SurfaceControl at all.
        return displayTokenFromDisplayManager();
    }

    private Object displayTokenFromDisplayManager() {
        Object global = Reflect.callStatic("android.hardware.display.DisplayManagerGlobal",
                "getInstance", new Class<?>[0]);
        if (global == null) {
            return null;
        }
        Object token = Reflect.call(global, "getDisplayToken", new Class<?>[]{int.class},
                Display.DEFAULT_DISPLAY);
        if (token instanceof android.os.IBinder) {
            return token;
        }
        // DisplayInfo carries a token on some releases.
        Object info = Reflect.call(global, "getDisplayInfo", new Class<?>[]{int.class},
                Display.DEFAULT_DISPLAY);
        if (info != null) {
            Object field = fieldValue(info, "displayToken");
            if (field instanceof android.os.IBinder) {
                return field;
            }
        }
        return null;
    }

    private static Object fieldValue(Object target, String name) {
        try {
            java.lang.reflect.Field f = target.getClass().getField(name);
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Reports what the platform actually offers for capturing the screen.
     *
     * <p>Hidden APIs move between releases, and every failure in the capture
     * chain returns null rather than throwing, so "it did not work" is never
     * enough to act on. This enumerates the real signatures.
     */
    public JSONObject probeDisplay() {
        JSONObject out = new JSONObject();
        try {
            out.put("sdkInt", android.os.Build.VERSION.SDK_INT);

            out.put("surfaceControlMethods", displayRelatedMethods("android.view.SurfaceControl"));
            out.put("screenCaptureMethods", displayRelatedMethods("android.window.ScreenCapture"));

            Class<?> argsClass = Reflect.findClass("android.window.ScreenCapture$DisplayCaptureArgs");
            out.put("displayCaptureArgsFound", argsClass != null);
            Class<?> builderClass =
                    Reflect.findClass("android.window.ScreenCapture$DisplayCaptureArgs$Builder");
            if (builderClass != null) {
                JSONArray ctors = new JSONArray();
                for (java.lang.reflect.Constructor<?> c : builderClass.getConstructors()) {
                    ctors.put(signature(c.getParameterTypes()));
                }
                out.put("builderConstructors", ctors);
            }

            Object ids = Reflect.callStatic("android.view.SurfaceControl", "getPhysicalDisplayIds",
                    new Class<?>[0]);
            out.put("physicalDisplayIds", ids instanceof long[] ? java.util.Arrays.toString((long[]) ids)
                    : String.valueOf(ids));
            Object token = displayToken();
            out.put("resolvedToken", token == null ? "null" : token.getClass().getName());
            out.put("lastCaptureError", lastCaptureError);
        } catch (Throwable t) {
            try {
                out.put("probeError", String.valueOf(t));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static JSONArray displayRelatedMethods(String className) {
        JSONArray array = new JSONArray();
        Class<?> clazz = Reflect.findClass(className);
        if (clazz == null) {
            return array;
        }
        for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
            String name = m.getName();
            if (!name.contains("Display") && !name.contains("screenshot")
                    && !name.contains("capture")) {
                continue;
            }
            array.put(name + "(" + signature(m.getParameterTypes()) + ") -> "
                    + m.getReturnType().getSimpleName()
                    + (java.lang.reflect.Modifier.isStatic(m.getModifiers()) ? " [static]" : ""));
        }
        return array;
    }

    private static String signature(Class<?>[] types) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(types[i].getSimpleName());
        }
        return sb.toString();
    }

    /**
     * Display size in pixels.
     *
     * <p>The system resources carry the real display metrics, which is both
     * simpler and more reliable than walking {@code DisplayManagerGlobal} - that
     * path has changed shape repeatedly, and a null there silently disabled
     * capture entirely.
     */
    private int[] displaySize() {
        try {
            android.util.DisplayMetrics metrics =
                    android.content.res.Resources.getSystem().getDisplayMetrics();
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return new int[]{metrics.widthPixels, metrics.heightPixels};
            }
        } catch (Throwable t) {
            Logx.w("system resource metrics unavailable: " + t);
        }
        try {
            Object global = Reflect.callStatic("android.hardware.display.DisplayManagerGlobal",
                    "getInstance", new Class<?>[0]);
            Object info = Reflect.call(global, "getDisplayInfo", new Class<?>[]{int.class},
                    Display.DEFAULT_DISPLAY);
            if (info == null) {
                return null;
            }
            java.lang.reflect.Field w = info.getClass().getField("logicalWidth");
            java.lang.reflect.Field h = info.getClass().getField("logicalHeight");
            return new int[]{w.getInt(info), h.getInt(info)};
        } catch (Throwable t) {
            return null;
        }
    }

    // =====================================================================
    // Input injection
    // =====================================================================

    JSONObject inject(JSONObject args) throws Exception {
        String action = args.optString("action", "");
        Object inputManager = inputManager();
        if (inputManager == null) {
            throw new IllegalStateException("no InputManager available to inject through");
        }

        switch (action) {
            case "tap":
            case "long_press": {
                int x = args.getInt("x");
                int y = args.getInt("y");
                int duration = args.optInt("duration_ms", "long_press".equals(action) ? 800 : 60);
                gesture(inputManager, new float[]{x, x}, new float[]{y, y}, duration);
                return ok(action, x, y, duration);
            }
            case "swipe": {
                int x1 = args.getInt("x");
                int y1 = args.getInt("y");
                int x2 = args.getInt("x2");
                int y2 = args.getInt("y2");
                int duration = Math.max(1, args.optInt("duration_ms", 300));
                gesture(inputManager, new float[]{x1, x2}, new float[]{y1, y2}, duration);
                return ok(action, x1, y1, duration);
            }
            case "key": {
                int keyCode = resolveKeyCode(args.optString("keycode", ""));
                if (keyCode == 0) {
                    throw new IllegalArgumentException("unknown keycode '"
                            + args.optString("keycode") + "'");
                }
                long now = SystemClock.uptimeMillis();
                send(inputManager, new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0),
                        INJECT_WAIT_FOR_FINISH);
                send(inputManager, new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0),
                        INJECT_WAIT_FOR_FINISH);
                JSONObject out = new JSONObject();
                out.put("injected", true);
                out.put("action", "key");
                out.put("keyCode", keyCode);
                return out;
            }
            case "text":
                // Typing needs an InputConnection bound to a focused editor, which
                // only the app that owns the field can supply. The root route runs
                // 'input text' instead.
                throw new UnsupportedOperationException(
                        "typing text is not available through the system route;"
                                + " use mode=root for action=text");
            default:
                throw new IllegalArgumentException("unsupported action '" + action + "'");
        }
    }

    private JSONObject ok(String action, int x, int y, int duration) throws Exception {
        JSONObject out = new JSONObject();
        out.put("injected", true);
        out.put("action", action);
        out.put("x", x);
        out.put("y", y);
        out.put("durationMs", duration);
        return out;
    }

    /** Builds a down/move/up sequence, interpolated so the gesture has a plausible path. */
    private void gesture(Object inputManager, float[] xs, float[] ys, int duration)
            throws Exception {
        long start = SystemClock.uptimeMillis();
        float x1 = xs[0];
        float y1 = ys[0];
        float x2 = xs[xs.length - 1];
        float y2 = ys[ys.length - 1];

        send(inputManager, motion(0, start, start, MotionEvent.ACTION_DOWN, x1, y1, 1f),
                INJECT_ASYNC);

        int steps = 8;
        for (int i = 1; i <= steps; i++) {
            float fraction = i / (float) steps;
            long when = start + (long) (duration * fraction);
            send(inputManager, motion(0, start, when, MotionEvent.ACTION_MOVE,
                    x1 + (x2 - x1) * fraction, y1 + (y2 - y1) * fraction, 1f), INJECT_ASYNC);
        }

        send(inputManager, motion(0, start, start + duration, MotionEvent.ACTION_UP, x2, y2, 0f),
                INJECT_WAIT_FOR_FINISH);
    }

    private static MotionEvent motion(int pointerId, long downTime, long when, int action,
            float x, float y, float pressure) {
        MotionEvent event = MotionEvent.obtain(downTime, when, action, x, y, pressure, 1f, 0, 1f, 1f,
                0, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return event;
    }

    private void send(Object inputManager, InputEvent event, int mode) throws Exception {
        try {
            Object result = Reflect.call(inputManager, "injectInputEvent",
                    new Class<?>[]{InputEvent.class, int.class}, event, mode);
            if (result instanceof Boolean && !((Boolean) result)) {
                throw new IllegalStateException("injectInputEvent returned false"
                        + " (missing INJECT_EVENTS permission?)");
            }
        } finally {
            if (event instanceof MotionEvent) {
                ((MotionEvent) event).recycle();
            }
        }
    }

    private Object inputManager() {
        Object manager = Reflect.callStatic("android.hardware.input.InputManagerGlobal",
                "getInstance", new Class<?>[0]);
        if (manager != null) {
            return manager;
        }
        return Reflect.callStatic("android.hardware.input.InputManager", "getInstance",
                new Class<?>[0]);
    }

    /** Accepts either a numeric keycode or a {@code KEYCODE_*} name. */
    private static int resolveKeyCode(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            // fall through to the name lookup
        }
        String name = raw.toUpperCase(java.util.Locale.ROOT);
        if (!name.startsWith("KEYCODE_")) {
            name = "KEYCODE_" + name;
        }
        try {
            java.lang.reflect.Field field = KeyEvent.class.getField(name);
            return field.getInt(null);
        } catch (Throwable t) {
            return 0;
        }
    }
}
