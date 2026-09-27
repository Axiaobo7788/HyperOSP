plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin)
}

android {
    namespace = "io.github.axiaobo7788.hyperosp"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.axiaobo7788.hyperosp"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            signingConfig = signingConfigs["debug"]
        }
    }

    kotlin {
        jvmToolchain(21)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        // M1 deliberately targets SDK 36 and retains the verified template
        // toolchain while the API 100 proof of concept is device-tested.
        disable += setOf(
            "AndroidGradlePluginVersion",
            "NewerVersionAvailable",
            "OldTargetApi",
        )
    }
}

dependencies {
    // API 100 compile-time stubs from libxposed/api commit 55efdf9.
    // The LSPosed runtime supplies these classes; they are never packaged.
    compileOnly(files("libs/libxposed-api-100-55efdf9.jar"))
}
