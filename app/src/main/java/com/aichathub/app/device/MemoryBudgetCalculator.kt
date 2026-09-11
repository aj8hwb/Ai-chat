package com.aichathub.app.device

import com.aichathub.app.domain.model.AiMemoryBudget
import com.aichathub.app.domain.model.DeviceProfile

/**
 * Computes a conservative "safe AI memory budget".
 *
 * Free RAM is NOT equal to usable AI RAM. We reserve an app overhead,
 * a runtime overhead and a safety reserve before deciding how much of the
 * current available memory the inference runtime may safely use.
 *
 * The calculator estimates detailed memory components for LLM inference:
 * - Model weights (GGUF file size)
 * - KV cache memory (scales with context length and model size)
 * - Context processing memory
 * - Thread buffers
 *
 * KV cache estimation uses model parameter count when available, falling
 * back to device RAM heuristics only when parameter count is unknown.
 */
object MemoryBudgetCalculator {

    private const val KB = 1024L
    private const val MB = 1024L * KB
    private const val GB = 1024L * MB

    fun calculate(profile: DeviceProfile): AiMemoryBudget {
        return calculate(profile, null, null)
    }

    fun calculate(
        profile: DeviceProfile,
        modelWeightsBytes: Long?,
        contextLength: Int?
    ): AiMemoryBudget {
        return calculate(profile, modelWeightsBytes, contextLength, null)
    }

    /**
     * Enhanced memory calculation that uses model parameter count for more
     * accurate KV cache estimation when available.
     *
     * @param profile device profile with RAM information
     * @param modelWeightsBytes GGUF file size in bytes (model weights)
     * @param contextLength requested context length in tokens
     * @param parameterCount model parameter count (e.g. 7_000_000_000L for 7B);
     *   when provided, enables much more accurate KV cache estimation than
     *   the device-RAM fallback.
     */
    fun calculate(
        profile: DeviceProfile,
        modelWeightsBytes: Long?,
        contextLength: Int?,
        parameterCount: Long?
    ): AiMemoryBudget {
        val available = profile.availableRamBytes
        val total = profile.totalRamBytes

        // App + system overhead: ~12% of total RAM, min 512 MB.
        val appOverhead = maxOf(512L * MB, total / 8L)

        // Runtime native allocation overhead estimate.
        val runtimeOverhead = when {
            total <= 4L * GB -> 300L * MB
            total <= 6L * GB -> 350L * MB
            else -> 400L * MB
        }

        // Safety reserve so the OS is never left starved.
        val safetyReserve = when {
            total <= 4L * GB -> 500L * MB
            total <= 6L * GB -> 600L * MB
            else -> 700L * MB
        }

        val usable = (available - appOverhead - runtimeOverhead - safetyReserve)
            .coerceAtLeast(0L)

        // Calculate detailed memory components
        val weightsMemory = modelWeightsBytes ?: 0L

        // Estimate KV cache memory: prefer parameter-count-based estimation,
        // fall back to device-RAM heuristic when parameter count is unknown.
        val kvCachePerToken = if (parameterCount != null && parameterCount > 0) {
            estimateKvCachePerTokenFromParams(parameterCount)
        } else {
            estimateKvCachePerTokenFromRam(total)
        }
        val kvCacheMemory = if (contextLength != null && contextLength > 0) {
            kvCachePerToken * contextLength
        } else {
            0L
        }

        // Context processing memory (rough estimate)
        val contextMemory = if (contextLength != null && contextLength > 0) {
            // Approximately 16 bytes per token for context processing
            16L * contextLength
        } else {
            0L
        }

        // Thread buffer memory
        val threadBuffers = AiMemoryBudget.THREAD_BUFFER_PER_THREAD * AiMemoryBudget.DEFAULT_THREADS

        // Calculate maximum safe context length
        val maxContextTokens = if (weightsMemory > 0) {
            val availableForContext = usable - weightsMemory - threadBuffers
            if (availableForContext > 0 && kvCachePerToken > 0) {
                (availableForContext / kvCachePerToken).toInt()
            } else {
                0
            }
        } else {
            0
        }

        return AiMemoryBudget(
            availableBytes = available,
            reservedBytes = appOverhead,
            runtimeOverheadBytes = runtimeOverhead,
            safetyReserveBytes = safetyReserve,
            modelMemoryBytes = usable,
            weightsMemoryBytes = weightsMemory,
            kvCacheMemoryBytes = kvCacheMemory,
            contextMemoryBytes = contextMemory,
            threadBufferBytes = threadBuffers,
            maxContextTokens = maxContextTokens
        )
    }

    /**
     * Estimates KV cache memory per token based on model parameter count.
     *
     * KV cache size = 2 × n_layers × n_kv_heads × head_dim × sizeof(dtype)
     * For typical GGUF models using f16 KV cache:
     * - 7B (32 layers, 32 KV heads, 128 head_dim): ~512 bytes/token
     * - 13B (40 layers, 40 KV heads, 128 head_dim): ~1024 bytes/token
     * - 70B (80 layers, 64 KV heads, 128 head_dim): ~4096 bytes/token
     *
     * We scale linearly by parameter count relative to a 7B reference.
     */
    private fun estimateKvCachePerTokenFromParams(parameterCount: Long): Long {
        // Reference: 7B model ≈ 512 bytes/token for f16 KV cache
        val referenceParams = 7_000_000_000L
        val referenceBytesPerToken = 512L
        return (parameterCount * referenceBytesPerToken / referenceParams)
            .coerceIn(256L, 8192L) // clamp to reasonable bounds
    }

    /**
     * Fallback: estimates KV cache per token based on total device RAM.
     * Larger devices typically run larger models which need more KV cache per token.
     * This is less accurate than parameter-count-based estimation.
     */
    private fun estimateKvCachePerTokenFromRam(totalRamBytes: Long): Long {
        return when {
            totalRamBytes <= 4L * GB -> AiMemoryBudget.KV_CACHE_BYTES_PER_TOKEN_7B
            totalRamBytes <= 6L * GB -> AiMemoryBudget.KV_CACHE_BYTES_PER_TOKEN_13B
            else -> AiMemoryBudget.KV_CACHE_BYTES_PER_TOKEN_70B
        }
    }

    /**
     * Calculates the safe context length for a specific model.
     */
    fun calculateSafeContextLength(
        profile: DeviceProfile,
        modelWeightsBytes: Long,
        requestedContextLength: Int
    ): Int {
        val budget = calculate(profile, modelWeightsBytes, requestedContextLength)
        return budget.maxContextTokens.coerceAtMost(requestedContextLength)
    }
}