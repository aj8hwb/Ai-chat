package com.aichathub.app.chat

import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Abstraction over the on-device inference engine.
 *
 * The UI and business logic depend ONLY on this interface — never on a
 * specific native engine. This keeps the app decoupled from the concrete
 * runtime (llama.cpp via llama-android today, something else later).
 */
interface InferenceRuntime {

    val isLoaded: Boolean

    /** Model currently loaded into this runtime, if any. */
    val activeModelId: String?

    /** Live performance snapshot while generating. */
    val performance: StateFlow<Performance>

    data class Performance(
        val tokensPerSecond: Float = 0f,
        val generationActive: Boolean = false,
        val tokensGenerated: Int = 0,
        val contextUsed: Int = 0
    )

    /** Loads a model file into memory. Blocks until loaded or throws. */
    suspend fun load(modelId: String, file: File, contextLength: Int)

    /** Unloads the current model and releases native resources. */
    suspend fun unload()

    /** Generates a full response (non-streaming). */
    suspend fun generate(
        prompt: String,
        config: GenerationConfig,
        systemPrompt: String?
    ): String

    /** Generates a response with streaming tokens via [onToken]. */
    suspend fun generateStreaming(
        prompt: String,
        config: GenerationConfig,
        systemPrompt: String?,
        onToken: (String) -> Unit
    ): String

    /** Cancels any active generation. */
    fun cancelGeneration()

    fun release()
}

data class GenerationConfig(
    val temperature: Float = 0.8f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val maxTokens: Int = 512,
    val randomSeed: Int = 0
)
