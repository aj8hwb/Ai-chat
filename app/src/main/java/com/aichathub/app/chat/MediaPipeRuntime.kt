package com.aichathub.app.chat

import android.content.Context
import android.util.Log
import com.google.common.util.concurrent.ListenableFuture
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Real on-device inference via the MediaPipe LLM Inference API
 * (com.google.mediapipe:tasks-genai).
 *
 * Reads the `.task` / `.litertlm` model files downloaded by the app and runs
 * them entirely on-device. Streaming is done through the session
 * ProgressListener so the chat UI updates token-by-token.
 *
 * IMPORTANT: this is the concrete engine. The rest of the app only talks to
 * the [InferenceRuntime] interface, so the engine can be swapped later.
 */
class MediaPipeRuntime(
    private val context: Context
) : InferenceRuntime {

    private val tag = "MediaPipeRuntime"
    private val _performance = MutableStateFlow(InferenceRuntime.Performance())
    override val performance: StateFlow<InferenceRuntime.Performance> = _performance.asStateFlow()

    private var inference: LlmInference? = null
    private var session: LlmInferenceSession? = null
    private val callbackExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "mp-callback") }

    override var isLoaded: Boolean = false
        private set

    override var activeModelId: String? = null
        private set

    private val cancelled = AtomicBoolean(false)
    private val generating = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    override suspend fun load(modelId: String, file: File, contextLength: Int) {
        withContext(Dispatchers.IO) {
            check(!closed.get()) { "Runtime already released" }
            unloadQuietly()

            Log.i(tag, "Loading model $modelId from ${file.absolutePath}")
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(file.absolutePath)
                .setMaxTokens(contextLength.coerceAtLeast(256))
                .setMaxTopK(64)
                .build()

            inference = LlmInference.createFromOptions(context, options)
            session = LlmInferenceSession.createFromOptions(
                inference!!,
                LlmInferenceSession.LlmInferenceSessionOptions.builder().build()
            )
            isLoaded = true
            activeModelId = modelId
            _performance.value = InferenceRuntime.Performance(contextUsed = 0)
            Log.i(tag, "Model $modelId loaded")
        }
    }

    override suspend fun unload() {
        withContext(Dispatchers.IO) {
            unloadQuietly()
        }
    }

    private fun unloadQuietly() {
        try {
            session?.close()
        } catch (e: Exception) {
            Log.w(tag, "Error closing session", e)
        }
        try {
            inference?.close()
        } catch (e: Exception) {
            Log.w(tag, "Error closing inference", e)
        }
        session = null
        inference = null
        isLoaded = false
        activeModelId = null
        _performance.value = InferenceRuntime.Performance()
    }

    override suspend fun generate(
        prompt: String,
        config: GenerationConfig,
        systemPrompt: String?
    ): String = withContext(Dispatchers.IO) {
        val s = requireSession()
        cancelled.set(false)
        generating.set(true)
        _performance.value = _performance.value.copy(generationActive = true)
        try {
            applyConfig(s, config)
            s.addQueryChunk(prompt)
            val start = System.nanoTime()
            val result = s.generateResponse()
            updatePerformance(result, start, 1)
            result
        } finally {
            generating.set(false)
            _performance.value = _performance.value.copy(generationActive = false)
        }
    }

    override suspend fun generateStreaming(
        prompt: String,
        config: GenerationConfig,
        systemPrompt: String?,
        onToken: (String) -> Unit
    ): String {
        val s = requireSession()
        cancelled.set(false)
        generating.set(true)
        _performance.value = _performance.value.copy(generationActive = true)
        return try {
            applyConfig(s, config)
            s.addQueryChunk(prompt)
            val start = System.nanoTime()
            val full = awaitFuture(s.generateResponseAsync { partial, done ->
                if (!closed.get() && !cancelled.get()) {
                    if (partial != null && partial.isNotEmpty()) {
                        onToken(partial)
                    }
                    if (done) {
                        updatePerformance(partial.orEmpty(), start, 1)
                    }
                }
            })
            updatePerformance(full, start, 1)
            full
        } finally {
            generating.set(false)
            _performance.value = _performance.value.copy(generationActive = false)
        }
    }

    override fun cancelGeneration() {
        // Cancellation of an in-flight native generation is not exposed by
        // this runtime version (added in a later release). We stop
        // forwarding tokens; the generation is discarded on completion.
        cancelled.set(true)
    }

    override fun release() {
        closed.set(true)
        cancelGeneration()
        runCatching { unloadQuietly() }
        runCatching { callbackExecutor.shutdown() }
    }

    private fun requireSession(): LlmInferenceSession =
        session ?: throw IllegalStateException("Model is not loaded")

    private fun applyConfig(s: LlmInferenceSession, config: GenerationConfig) {
        try {
            s.updateSessionOptions { builder ->
                builder
                    .setTopK(config.topK)
                    .setTopP(config.topP)
                    .setTemperature(config.temperature)
                    .setRandomSeed(config.randomSeed)
                    .build()
            }
        } catch (e: Exception) {
            Log.w(tag, "Could not apply generation config", e)
        }
    }

    private fun updatePerformance(text: String, startNanos: Long, responses: Int) {
        val elapsed = (System.nanoTime() - startNanos) / 1_000_000_000f
        val tokens = text.length / 4
        val tps = if (elapsed > 0f) tokens / elapsed else 0f
        _performance.value = _performance.value.copy(
            tokensPerSecond = tps,
            tokensGenerated = tokens
        )
    }

    private suspend fun awaitFuture(future: ListenableFuture<String>): String =
        suspendCancellableCoroutine { cont ->
            future.addListener({
                if (cont.isActive) {
                    try {
                        cont.resume(future.get())
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                }
            }, callbackExecutor)
            cont.invokeOnCancellation {
                cancelled.set(true)
            }
        }
}