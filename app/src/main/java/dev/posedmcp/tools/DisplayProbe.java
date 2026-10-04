package dev.posedmcp.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import dev.posedmcp.HiddenApi;

/**
 * Reports the real signatures of the platform's screen-capture APIs.
 *
 * <p>Every step of the capture chain returns {@code null} instead of throwing, so
 * "the screenshot failed" says nothing about why. Hidden APIs also move between
 * releases - a method that existed as {@code getPhysicalDisplayToken(int)} can
 * reappear as {@code (long)} or vanish - and the framework's {@code framework.jar}
 * on a device is a stub that carries no usable signatures.
 *
 * <p>Enumerating members needs no privileges and does not invoke anything, so
 * this runs in the app process rather than requiring a system_server restart to
 * answer the question.
 */
public final class DisplayProbe {

    private DisplayProbe() {
    }

    public static JSONObject describe() {
        JSONObject out = new JSONObject();
        try {
            out.put("sdkInt", android.os.Build.VERSION.SDK_INT);
            out.put("hiddenApiExempt", HiddenApi.isExempt());
            out.put("surfaceControl", members("android.view.SurfaceControl"));
            out.put("screenCapture", members("android.window.ScreenCapture"));
            // Where a display token can come from, now that SurfaceControl no
            // longer exposes getPhysicalDisplayToken on this release.
            out.put("displayManagerGlobal",
                    members("android.hardware.display.DisplayManagerGlobal"));
            out.put("displayInfo", members("android.view.DisplayInfo"));
            out.put("displayInfoFields", fields("android.view.DisplayInfo"));

            Class<?> builder = findClass(
                    "android.window.ScreenCapture$DisplayCaptureArgs$Builder");
            if (builder != null) {
                JSONArray ctors = new JSONArray();
                for (Constructor<?> c : builder.getConstructors()) {
                    ctors.put(signature(c.getParameterTypes()));
                }
                out.put("displayCaptureArgsBuilderConstructors", ctors);
            }
            out.put("displayCaptureArgsFound",
                    findClass("android.window.ScreenCapture$DisplayCaptureArgs") != null);
            out.put("screenshotHardwareBufferFound",
                    findClass("android.window.ScreenshotHardwareBuffer") != null);
        } catch (Throwable t) {
            try {
                out.put("probeError", String.valueOf(t));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static Class<?> findClass(String name) {
        try {
            return Class.forName(name, false, DisplayProbe.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    /** Every member whose name mentions the screen, with its real parameter types. */
    private static JSONArray members(String className) {
        JSONArray array = new JSONArray();
        Class<?> clazz = findClass(className);
        if (clazz == null) {
            array.put("class not found");
            return array;
        }
        for (Method m : clazz.getDeclaredMethods()) {
            String name = m.getName();
            if (!matches(name)) {
                continue;
            }
            array.put(name + "(" + signature(m.getParameterTypes()) + ") -> "
                    + m.getReturnType().getSimpleName()
                    + (Modifier.isStatic(m.getModifiers()) ? " [static]" : ""));
        }
        return array;
    }

    private static JSONArray fields(String className) {
        JSONArray array = new JSONArray();
        Class<?> clazz = findClass(className);
        if (clazz == null) {
            array.put("class not found");
            return array;
        }
        for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
            if (!matches(f.getName())) {
                continue;
            }
            array.put(f.getName() + " : " + f.getType().getSimpleName()
                    + (Modifier.isStatic(f.getModifiers()) ? " [static]" : ""));
        }
        return array;
    }

    /** Narrow enough to keep the report readable, wide enough to catch a token. */
    private static boolean matches(String name) {
        return name.contains("Display") || name.contains("Token")
                || name.contains("screenshot") || name.contains("apture");
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
}
