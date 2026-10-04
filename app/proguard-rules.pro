# The class named in assets/xposed_init is loaded reflectively by the
# framework, so it must not be renamed or stripped.
-keep class dev.posedmcp.xposed.** { *; }

# Entry points the framework looks up by interface.
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }
-keep class * implements de.robv.android.xposed.IXposedHookZygoteInit { *; }
-keep class * implements de.robv.android.xposed.IXposedHookInitPackageResources { *; }
-keepclassmembers class * extends de.robv.android.xposed.XC_MethodHook {
    protected void beforeHookedMethod(...);
    protected void afterHookedMethod(...);
}

# Provided by the framework at runtime.
-dontwarn de.robv.android.xposed.**
-dontwarn androidx.annotation.**

# Plugin DEX entry points are resolved reflectively by name.
-keep class dev.posedmcp.plugin.** { *; }
