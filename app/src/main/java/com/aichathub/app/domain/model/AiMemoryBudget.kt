package com.aichathub.app.domain.model

/**
 * The safe amount of memory the AI runtime may use.
 * 
 * This model provides detailed memory breakdown for LLM inference:
 * - Model weights: The actual GGUF model file size
 * - KV cache: Memory for key-value cache during generation (scales with context length)
 * - Context memory: Memory for prompt context processing
 * - Runtime overhead: Memory used by the inference runtime itself
 * - Thread buffers: Memory used by worker threads
 */
data class AiMemoryBudget(
    val availableBytes: Long,
    val reservedBytes: Long,
    val runtimeOverheadBytes: Long,
    val safetyReserveBytes: Long,
    val modelMemoryBytes: Long,
    // Detailed memory components for LLM inference
    val weightsMemoryBytes: Long = 0L,      // Model weights (GGUF file size)
    val kvCacheMemoryBytes: Long = 0L,      // Key-value cache memory
    val contextMemoryBytes: Long = 0L,      // Prompt context processing memory
    val threadBufferBytes: Long = 0L,       // Worker thread buffers
    val maxContextTokens: Int = 0           // Maximum context tokens for this budget
) {
    val usableBytes: Long
        get() = (availableBytes - runtimeOverheadBytes - safetyReserveBytes)
            .coerceAtLeast(0)

    val usableMb: Long get() = usableBytes / (1024 * 1024)
    val usableGb: Float get() = usableBytes / (1024f * 1024f * 1024f)

    val modelMemoryMb: Long get() = modelMemoryBytes / (1024 * 1024)
    val modelMemoryGb: Float get() = modelMemoryBytes / (1024f * 1024f * 1024f)

    val availableGb: Float get() = availableBytes / (1024f * 1024f * 1024f)
    
    val weightsMemoryMb: Long get() = weightsMemoryBytes / (1024 * 1024)
    val kvCacheMemoryMb: Long get() = kvCacheMemoryBytes / (1024 * 1024)
    val contextMemoryMb: Long get() = contextMemoryBytes / (1024 * 1024)
    val threadBufferMb: Long get() = threadBufferBytes / (1024 * 1024)
    
    /**
     * Calculates the maximum safe context length for a given model.
     * This takes into account the available memory and the memory required
     * per token for the KV cache.
     */
    fun calculateMaxContextLength(
        kvCachePerTokenBytes: Long,
        contextOverheadBytes: Long = 0L
    ): Int {
        val availableForContext = usableBytes - weightsMemoryBytes - threadBufferBytes - contextOverheadBytes
        if (availableForContext <= 0 || kvCachePerTokenBytes <= 0) return 0
        return (availableForContext / kvCachePerTokenBytes).toInt().coerceAtLeast(0)
    }
    
    companion object {
        // Memory estimates per token for different model sizes
        // These are approximate values based on typical GGUF quantization
        const val KV_CACHE_BYTES_PER_TOKEN_7B = 512L   // ~512 bytes per token for 7B models
        const val KV_CACHE_BYTES_PER_TOKEN_13B = 1024L // ~1 KB per token for 13B models
        const val KV_CACHE_BYTES_PER_TOKEN_70B = 4096L // ~4 KB per token for 70B models
        
        // Thread buffer estimates
        const val THREAD_BUFFER_PER_THREAD = 32L * 1024L // 32 KB per thread
        const val DEFAULT_THREADS = 4
    }
}
