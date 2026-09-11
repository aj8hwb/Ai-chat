package com.aichathub.app.domain.model

/** A live performance snapshot from the inference runtime. */
data class PerformanceSnapshot(
    val tokensPerSecond: Float = 0f,
    val modelMemoryBytes: Long = 0,
    val contextTokensUsed: Int = 0,
    val contextTokensMax: Int = 0,
    val running: Boolean = false
)
