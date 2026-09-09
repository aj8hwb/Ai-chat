package com.aichathub.app.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates a Baseline Profile for the app.
 *
 * The profile tells ART which classes and methods to pre-compile
 * for faster startup and smoother performance.
 *
 * Run with: ./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.aichathub.app.benchmark.BaselineProfileGenerator
 * Output: app/src/main/generated/baselineProfiles/
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generateBaselineProfile() {
        rule.collect(
            packageName = "com.aichathub.app",
            includeInStartupProfile = true
        ) {
            // Cold start
            pressHome()
            startActivityAndWait()

            // Navigate through key screens
            device.findObject(By.text("Chat"))?.click()
            device.waitForIdle()

            device.findObject(By.text("Models"))?.click()
            device.waitForIdle()

            device.findObject(By.text("Downloads"))?.click()
            device.waitForIdle()

            device.findObject(By.text("History"))?.click()
            device.waitForIdle()

            device.findObject(By.text("Settings"))?.click()
            device.waitForIdle()

            // Back to home
            device.findObject(By.text("Home"))?.click()
            device.waitForIdle()
        }
    }
}
