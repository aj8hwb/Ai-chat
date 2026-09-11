package com.aichathub.app.privacy

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Centralized crash log management with structured metadata.
 *
 * All crash log operations go through this repository to ensure:
 *  - Single source of truth for crash log location
 *  - Consistent read/write/clear behavior
 *  - Release build redaction (no prompts, conversations, or sensitive paths)
 *  - Bounded file size (max 4000 lines)
 *  - Structured metadata for diagnostics (no user content)
 */
object CrashLogRepository {

    private const val TAG = "CrashLogRepo"
    private const val CRASH_LOG_FILE = "crash_log.txt"
    private const val MAX_LINES = 4000

    /**
     * Writes a crash entry to the centralized log file.
     *
     * In release builds, sensitive data is redacted:
     *  - No user prompts or conversation content
     *  - No full file paths (only class names)
     *  - No unnecessary stack trace details
     */
    fun write(
        context: Context,
        thread: Throwable,
        memoryStats: String? = null,
        metadata: CrashMetadata? = null,
        threadName: String? = null
    ) {
        try {
            val logFile = getLogFile(context)
            val entry = buildLogEntry(thread, memoryStats, metadata, threadName)
            val combined = (if (logFile.exists()) logFile.readText() + "\n" else "") + entry
            val lines = combined.split("\n")
            val capped = if (lines.size > MAX_LINES) {
                lines.takeLast(MAX_LINES).joinToString("\n")
            } else combined
            logFile.writeText(capped)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write crash log", e)
        }
    }

    /**
     * Reads all crash log entries.
     */
    fun read(context: Context): String {
        return try {
            val logFile = getLogFile(context)
            if (logFile.exists()) logFile.readText() else ""
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read crash log", e)
            ""
        }
    }

    /**
     * Clears all crash log data.
     */
    fun clear(context: Context) {
        try {
            val logFile = getLogFile(context)
            if (logFile.exists()) logFile.delete()

            context.filesDir.listFiles { file ->
                file.name.startsWith("crash_") || file.name.endsWith(".log")
            }?.forEach { it.delete() }

            context.cacheDir.listFiles { file ->
                file.name.startsWith("crash_") || file.name.endsWith(".log")
            }?.forEach { it.delete() }

            Log.d(TAG, "All crash logs cleared")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear crash logs", e)
        }
    }

    /**
     * Returns the number of crash log entries.
     */
    fun entryCount(context: Context): Int {
        return try {
            val text = read(context)
            if (text.isBlank()) 0
            else text.split("\n").count { it.startsWith("=== ") }
        } catch (e: Exception) {
            0
        }
    }

    private fun getLogFile(context: Context): File {
        return File(context.filesDir, CRASH_LOG_FILE)
    }

    /**
     * Builds a crash log entry with structured metadata.
     */
    private fun buildLogEntry(
        throwable: Throwable,
        memoryStats: String?,
        metadata: CrashMetadata?,
        threadName: String?
    ): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val crashId = UUID.randomUUID().toString().take(8)
        val resolvedThreadName = threadName ?: "unknown"
        val exceptionType = throwable.javaClass.simpleName

        val isRelease = !android.os.Build.TYPE.equals("userdebug", ignoreCase = true) &&
            !android.os.Build.TYPE.equals("eng", ignoreCase = true)

        val message = if (isRelease) {
            redactSensitiveInfo(throwable.message ?: "No message")
        } else {
            throwable.message ?: "No message"
        }

        val stackTrace = if (isRelease) {
            redactStackTrace(Log.getStackTraceString(throwable))
        } else {
            Log.getStackTraceString(throwable)
        }

        val mem = memoryStats ?: "memory stats unavailable"

        return buildString {
            append("=== ").append(timestamp).append(" ===\n")
            append("crashId: ").append(crashId).append("\n")
            append("appVersion: ").append(metadata?.appVersion ?: "unknown").append("\n")
            append("buildNumber: ").append(metadata?.buildNumber ?: "unknown").append("\n")
            append("androidVersion: ").append(Build.VERSION.RELEASE).append("\n")
            append("sdkLevel: ").append(Build.VERSION.SDK_INT).append("\n")
            append("device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
            append("deviceClass: ").append(metadata?.deviceClass ?: "unknown").append("\n")
            append("backend: ").append(metadata?.backend ?: "unknown").append("\n")
            append("modelRuntime: ").append(metadata?.modelRuntime ?: "none").append("\n")
            append("memory: ").append(mem).append("\n")
            append("thermalState: ").append(metadata?.thermalState ?: "unknown").append("\n")
            append("lastAction: ").append(metadata?.lastAction ?: "unknown").append("\n")
            append("thread: ").append(resolvedThreadName).append("\n")
            append("exception: ").append(exceptionType).append(": ").append(message).append("\n")
            append(stackTrace).append("\n")
        }
    }

    /**
     * Redacts sensitive information from error messages.
     */
    private fun redactSensitiveInfo(message: String): String {
        return message
            .replace(Regex("/data/user/\\d+/[^\\s]+"), "[REDACTED_PATH]")
            .replace(Regex("/storage/emulated/\\d+/[^\\s]+"), "[REDACTED_PATH]")
            .replace(Regex("token[=:]\\s*\\S+", RegexOption.IGNORE_CASE), "token=[REDACTED]")
            .replace(Regex("key[=:]\\s*\\S+", RegexOption.IGNORE_CASE), "key=[REDACTED]")
            .replace(Regex("password[=:]\\s*\\S+", RegexOption.IGNORE_CASE), "password=[REDACTED]")
            .replace(Regex("secret[=:]\\s*\\S+", RegexOption.IGNORE_CASE), "secret=[REDACTED]")
    }

    /**
     * Redacts file paths from stack traces.
     */
    private fun redactStackTrace(trace: String): String {
        return trace.lines().joinToString("\n") { line ->
            line.replace(Regex("/data/user/\\d+/[^\\s]+"), "[app]")
                .replace(Regex("/storage/emulated/\\d+/[^\\s]+"), "[storage]")
                .replace(Regex("/system/framework/[^\\s]+"), "[system]")
        }
    }
}

/**
 * Structured metadata for crash logs.
 * Contains diagnostic information only — NO user content (prompts, messages).
 */
data class CrashMetadata(
    val appVersion: String = "unknown",
    val buildNumber: String = "unknown",
    val deviceClass: String = "unknown",
    val backend: String = "unknown",
    val modelRuntime: String = "none",
    val thermalState: String = "unknown",
    val lastAction: String = "unknown"
)
