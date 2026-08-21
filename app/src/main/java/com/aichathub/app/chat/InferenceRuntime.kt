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
        val contextUsed: Int = 0,
        val contextTokensMax: Int = 0
    )

    /**
     * Loads a model file into memory. Blocks until loaded or throws.
     *
     * @param sampling sampling parameters baked into the native load. They are
     *   applied to every generation produced by this loaded model. Pass null to
     *   use sensible defaults.
     * @param threads native thread count (battery-conscious mode uses fewer).
     */
    suspend fun load(
        modelId: String,
        file: File,
        contextLength: Int,
        sampling: GenerationConfig? = null,
        threads: Int = 4
    )

    /** Unloads the current model and releases native resources. */
    suspend fun unload()

    /** Generates a full response (non-streaming). */
    suspend fun generate(
        prompt: String,
        config: GenerationConfig
    ): String

    /** Generates a response with streaming tokens via [onToken]. */
    suspend fun generateStreaming(
        prompt: String,
        config: GenerationConfig,
        onToken: (String) -> Unit
    ): String

    /**
     * Cancels any active generation.
     *
     * This must be safe to call from any thread. If it is invoked while a
     * generation has not yet entered the native engine (e.g. the coordinator is
     * still building the prompt), the cancellation is remembered and the
     * upcoming generation aborts immediately when it starts — so a Stop pressed
     * during prompt preparation is never silently dropped.
     */
    fun cancelGeneration()

    /**
     * Clears any remembered cancellation. MUST be called by a caller right
     * before it starts its OWN generation flow (synchronously, before any
     * suspension point) so a stale cancellation from another screen — e.g. the
     * Playground was left mid-run — can never abort a fresh generation here.
     */
    fun clearCancellation()

    fun release()
}

data class GenerationConfig(
    val temperature: Float = 0.8f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val maxTokens: Int = 512,
    val randomSeed: Int = 0,
    /**
     * Template stop sequences that terminate generation (e.g. "<|im_end|>" for
     * ChatML). Passed to the native engine and also enforced app-side as a
     * fallback, so generation cannot run away to maxTokens on models whose GGUF
     * does not declare a usable EOS token.
     */
    val stopSequences: List<String> = emptyList()
)
