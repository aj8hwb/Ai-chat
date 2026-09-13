plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val keystoreFilePath: String? = System.getenv("KEYSTORE_FILE_PATH")
    ?: System.getenv("KEYSTORE_FILE")
val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")
val keyAlias: String? = System.getenv("KEY_ALIAS")
val keyPassword: String? = System.getenv("KEY_PASSWORD")

val hasReleaseSigning = keystoreFilePath != null && keystorePassword != null &&
    keyAlias != null && keyPassword != null

// Catalog signature verification public key (Base64-encoded Ed25519 32-byte raw key)
// This is a PUBLIC key and is safe to embed in the application.
// The private key is kept in CI/CD secrets for signing the manifest.
val catalogPublicKey: String = System.getenv("AICHATHUB_CATALOG_PUBLIC_KEY") ?: ""

// Fail release builds if the catalog public key is missing — the app's security
// model depends on Ed25519-signed catalogs, so a missing key is a build-time error.
if (catalogPublicKey.isBlank() && gradle.startParameter.taskNames.any { it.contains("release") }) {
    throw GradleException(
        "AICHATHUB_CATALOG_PUBLIC_KEY environment variable must be set for release builds. " +
        "This key is required for Ed25519 catalog signature verification."
    )
}

android {
    namespace = "com.aichathub.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aichathub.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        // Embed the catalog public key in BuildConfig for signature verification
        buildConfigField("String", "CATALOG_PUBLIC_KEY", "\"$catalogPublicKey\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreFilePath!!)
                storePassword = keystorePassword!!
                this.keyAlias = keyAlias!!
                this.keyPassword = keyPassword!!
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.navigation.compose)

    // Coroutines & Serialization
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // On-device LLM inference runtime (llama.cpp via llama-kotlin-android)
    implementation(libs.llama.kotlin.android)

    // Networking for model downloads
    implementation(libs.okhttp)

    // DataStore for settings
    implementation(libs.androidx.datastore.preferences)

    // SAF document tree helpers for model folder import
    implementation(libs.androidx.documentfile)

    // Hilt for dependency injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // WorkManager for background catalog sync
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Coil for image loading
    implementation(libs.coil.compose)

    // Accompanist for permissions
    implementation(libs.accompanist.permissions)

    // Biometric authentication for App Lock
    implementation(libs.androidx.biometric)

    implementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.android.compiler)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.androidx.hilt.testing)
}
