package dev.posedmcp.plugin;

/**
 * What injected code implements.
 *
 * <p>A plugin is a plain DEX whose entry class implements this interface (or
 * just happens to expose the same methods - loading falls back to reflection so
 * a plugin can be written without compiling against this file).
 *
 * <p>The plugin runs inside the target application's process, with that
 * application's permissions and class loader. It is not sandboxed from the
 * target app, only from the rest of the system.
 */
public interface PluginEntry {

    /** Called once when the plugin is loaded into the target process. */
    default void onLoad(PluginContext ctx) throws Exception {
    }

    /**
     * Called by the {@code plugin_invoke} tool.
     *
     * @param method  the method name supplied by the caller
     * @param argsJson a JSON array of arguments
     * @return any JSON-serialisable value, or {@code null}
     */
    default Object invoke(String method, String argsJson) throws Exception {
        throw new UnsupportedOperationException("plugin does not implement invoke()");
    }

    /** Called before the plugin is dropped from the process. */
    default void onUnload() throws Exception {
    }
}
