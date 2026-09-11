package com.aichathub.app.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Macrobenchmark tests for critical user journeys.
 *
 * Measures:
 *  - Cold/warm startup time
 *  - Chat screen rendering
 *  - Model list rendering
 *  - Scroll performance in long conversations
 *
 * Run with: ./gradlew :benchmark:connectedBenchmarkAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class AppBenchmarkTest {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = "com.aichathub.app",
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.DEFAULT()
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test
    fun warmStartup() = benchmarkRule.measureRepeated(
        packageName = "com.aichathub.app",
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.DEFAULT()
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test
    fun chatScreenScrollPerformance() = benchmarkRule.measureRepeated(
        packageName = "com.aichathub.app",
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        compilationMode = CompilationMode.DEFAULT()
    ) {
        // Navigate to chat
        startActivityAndWait()
        device.findObject(By.text("Chat"))?.click()
        device.wait(Until.hasObject(By.res("com.aichathub.app:id/messageList")), 5000)

        // Scroll through messages
        val list = device.findObject(By.res("com.aichathub.app:id/messageList"))
        list?.scroll(Direction.DOWN, 3f)
    }

    @Test
    fun modelListRendering() = benchmarkRule.measureRepeated(
        packageName = "com.aichathub.app",
        metrics = listOf(FrameTimingMetric()),
        iterations = 3,
        compilationMode = CompilationMode.DEFAULT()
    ) {
        startActivityAndWait()
        // Navigate to models screen
        device.findObject(By.text("Models"))?.click()
        device.wait(Until.hasObject(By.res("com.aichathub.app:id/modelList")), 5000)
    }

    @Test
    fun settingsScreenRender() = benchmarkRule.measureRepeated(
        packageName = "com.aichathub.app",
        metrics = listOf(FrameTimingMetric()),
        iterations = 3,
        compilationMode = CompilationMode.DEFAULT()
    ) {
        startActivityAndWait()
        device.findObject(By.text("Settings"))?.click()
        device.wait(Until.hasObject(By.res("com.aichathub.app:id/settingsList")), 5000)
    }
}
