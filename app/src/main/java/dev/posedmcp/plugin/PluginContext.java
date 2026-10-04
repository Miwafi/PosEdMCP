package dev.posedmcp.plugin;

import android.content.Context;

/**
 * The handle an injected plugin gets on the process it was loaded into.
 *
 * <p>Kept deliberately small: the point of a plugin is to reach the target
 * application, so what it mostly needs is that application's context, class
 * loader and a way to install hooks.
 */
public interface PluginContext {

    /** Package name of the application this plugin is running inside. */
    String packageName();

    /** The target application's Context, or {@code null} if it is not ready yet. */
    Context appContext();

    /** The target application's class loader. */
    ClassLoader appClassLoader();

    /** The class loader the plugin's own classes came from. */
    ClassLoader pluginClassLoader();

    /** Writes to the PosEdMCP log, tagged with the package name. */
    void log(String message);

    /** Hook installation, backed by the LSPosed framework. */
    HookApi hooks();
}
