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
        versionCode = 2
        versionName = "0.0.2"
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
        // toolchain while the API 101 proof of concept is device-tested.
        disable += setOf(
            "AndroidGradlePluginVersion",
            "NewerVersionAvailable",
            "OldTargetApi",
        )
    }
}

dependencies {
    // The Xposed framework supplies the API at runtime; never package it.
    compileOnly(libs.libxposed.api)
}
