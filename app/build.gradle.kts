plugins {
    id("com.android.application")
}

android {
    namespace = "dev.posedmcp"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.posedmcp"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    dependenciesInfo {
        includeInApk = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

dependencies {
    compileOnly("androidx.annotation:annotation:1.9.1")
    // Provided by LSPosed at runtime; never packaged into the APK.
    compileOnly("de.robv.android.xposed:api:82")

    // On-device smali disassembly and assembly. Pure Java, so it runs on ART -
    // apktool would not, its resource decoding shells out to a host-native aapt2.
    // This is also what lets the agent author injected code without a PC
    // toolchain: write smali, assemble to DEX here, hand it to plugin_load.
    implementation("org.smali:baksmali:2.5.2")
    implementation("org.smali:smali:2.5.2")

    // A Lua interpreter, for injected logic that would otherwise have to be
    // hand-written smali. Pure Java, so it dexes like anything else and rides
    // into every scoped process on the module's own class loader - there is no
    // compile step and no DEX to push for a script.
    implementation("org.luaj:luaj-jse:3.0.1")
}
