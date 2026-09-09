plugins {
    id("com.android.application") version "9.0.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
    id("com.google.devtools.ksp") version "2.4.20-1.0.28" apply false
    id("com.google.dagger.hilt.android") version "2.56.2" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.2.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("androidx.baselineprofile") version "1.4.0" apply false
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

ktlint {
    android = true
    ignoreFailures = false
    enableExperimentalRules = true
}
