plugins {
    // Version-less ids on purpose: the AGP jar (loaded by the root project's
    // `com.android.application apply false`) already carries these plugin
    // classes on the classpath WITHOUT a resolvable version, so requesting
    // them via a versioned catalog alias fails with "already on the classpath
    // with an unknown version". This is the same pattern the repo used when
    // CI previously built successfully.
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.aichathub.app.benchmark"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // Benchmark-ish build type kept for local macrobenchmark work. NOTE:
        // this is a LIBRARY module — library build types have no
        // isDebuggable/signingConfig knobs (those belong to app modules), so
        // the old script's isDebuggable line never compiled and fails now that
        // :benchmark is actually included in CI.
        create("benchmark") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.espresso.core)
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.0")
    implementation("androidx.benchmark:benchmark-common:1.4.0")
}

androidComponents {
    beforeVariants(selector().all()) {
        it.enable = it.buildType == "benchmark"
    }
}
