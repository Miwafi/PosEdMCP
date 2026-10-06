package dev.posedmcp.xposed;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

import dev.posedmcp.Logx;

/**
 * Module entry point for frameworks that speak the modern API, named in
 * {@code META-INF/xposed/java_init.list}.
 *
 * <p>The twin of {@link PosEdMcpModule}, and deliberately a thin one: it decides
 * nothing itself. It records the backend and hands the same class loader to the
 * same {@link SystemHooks} and {@link AppHost} the classic entry hands them, so
 * there is one implementation of what this module does and two ways of being
 * started.
 *
 * <p>Both package callbacks are implemented on purpose. They differ only in
 * which class loader they can offer — {@code onPackageLoaded} has the default
 * one, {@code onPackageReady} the one the app actually ended up with, after any
 * custom {@code AppComponentFactory} has had its say — and relying on just one
 * of them means doing nothing at all if the framework is older than we assumed.
 * {@link AppHost#install} is a one-shot per process, so whichever arrives first
 * wins and the second is a no-op.
 *
 * <p>Nothing throws out of these. A module that crashes the process it was
 * loaded into would be far more damaging than one that quietly does nothing.
 */
public class VectorModule extends XposedModule {

    /** Only {@code ModuleLoadedParam} carries this, so it is kept for the rest. */
    private volatile String processName = "";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        try {
            String framework = getFrameworkName() + " " + getFrameworkVersion()
                    + ", libxposed API " + getApiVersion();
            Framework.adoptLibXposed(
                    loader -> new LibXposedHookApi(VectorModule.this, loader), framework);
            processName = param.getProcessName();
            log(Log.INFO, "PosEdMCP", "loaded via libxposed in " + processName + " under " + framework);
        } catch (Throwable t) {
            Logx.e("onModuleLoaded failed", t);
        }
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        try {
            log(Log.INFO, "PosEdMCP", "installing system hooks in system_server (libxposed)");
            SystemHooks.install(param.getClassLoader());
        } catch (Throwable t) {
            Logx.e("onSystemServerStarting failed", t);
        }
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        route(param.getPackageName(), param.getDefaultClassLoader());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        route(param.getPackageName(), param.getClassLoader());
    }

    private void route(String packageName, ClassLoader classLoader) {
        try {
            if (packageName == null) {
                return;
            }
            if (Framework.isSystemServer(packageName, processName)) {
                // In system server the first phase arrives as
                // onSystemServerStarting; this catches the later package loads.
                SystemHooks.install(classLoader);
                return;
            }
            AppHost.install(packageName, classLoader);
        } catch (Throwable t) {
            Logx.e("could not install into " + packageName, t);
        }
    }
}
