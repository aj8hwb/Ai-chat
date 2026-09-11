package com.aichathub.app.chat

import android.util.Log
import com.aichathub.app.data.ModelRepository
import com.aichathub.app.data.local.ConversationDao
import com.aichathub.app.data.local.MessageDao
import com.aichathub.app.data.SettingsRepository
import com.aichathub.app.device.CompatibilityEngine
import com.aichathub.app.device.DeviceInfoProvider
import com.aichathub.app.device.LoadEligibility
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.util.ModelThreads
import com.aichathub.app.util.TokenEstimator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Handles the complex orchestration of sending a chat message:
 * model loading, config building, prompt preparation, generation, and result handling.
 *
 * Extracted from ChatViewModel to keep the ViewModel focused on UI state bridging.
 */
class ChatGenerationHelper(
    private val coordinator: ChatCoordinator,
    private val runtime: InferenceRuntime,
    private val settingsRepository: SettingsRepository,
    private val modelRepository: ModelRepository,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val compatibilityEngine: CompatibilityEngine,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {
    companion object {
        private const val TAG = "ChatGenerationHelper"
    }

    /**
     * Result of a generation attempt.
     */
    data class GenerationResult(
        val text: String,
        val mode: String,
        val tokensGenerated: Int,
        val elapsedMs: Long,
        val tokensPerSecond: Float,
        val responseChars: Int
    )

    /**
     * Encapsulates all the data needed for a generation attempt.
     */
    data class GenerationParams(
        val model: CatalogModel,
        val text: String,
        val persistUser: Boolean,
        val thinkingMode: String,
        val conversationId: Long?,
        val onStream: (String) -> Unit,
        val onTrace: (String) -> Unit,
        val onLoadState: (loading: Boolean, error: String?) -> Unit
    )

    /**
     * Orchestrates the full send flow: ensure model is loaded, build config,
     * prepare conversation, generate, and return the result.
     *
     * @throws CancellationException if the user cancelled
     * @throws OutOfMemoryError if the device ran out of memory
     * @throws Exception on any other failure
     */
    suspend fun generate(params: GenerationParams): GenerationResult = withContext(Dispatchers.IO) {
        val model = params.model
        val text = params.text

        // 1. Ensure model file is ready
        val installed = modelRepository.stateFor(model.id)
        val file = installed?.filePath?.let { File(it) }
        if (installed?.state != ModelLifecycleState.READY || file == null || !file.isFile) {
            throw IllegalStateException("Model not ready")
        }

        // 2. Load model if needed
        if (runtime.activeModelId != model.id) {
            params.onLoadState(true, null)
            params.onTrace("Loading model...")
            ensureModelLoaded(model, file, params.onTrace)
            params.onLoadState(false, null)
            params.onTrace("Model loaded")
        }

        // 3. Build generation config
        val settings = settingsRepository.settings.first()
        val config = buildConfig(model, text, settings, params.thinkingMode, params.onTrace)

        // 4. Prepare or find conversation
        val convId = prepareConversation(params.conversationId, model.id, text, params.onTrace)

        // 5. Get system prompt
        val systemPrompt = conversationDao.byId(convId)?.systemPrompt?.takeIf { it.isNotBlank() }
            ?: settings.systemPrompt

        // 6. Generate
        val startNanos = System.nanoTime()
        params.onTrace("Generating... (${params.thinkingMode} mode)")

        val result = coordinator.sendMessage(
            prompt = text,
            config = config,
            systemPrompt = systemPrompt,
            model = model,
            persistUserMessage = params.persistUser,
            historyTurns = settings.historyTurns,
            onStream = params.onStream
        )

        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        val perf = runtime.performance.value
        val tps = (perf.tokensPerSecond * 10).toInt() / 10f
        params.onTrace("Done, ${perf.tokensGenerated} tokens, ${tps} tok/s, ${elapsedMs / 1000f}s")

        GenerationResult(
            text = result,
            mode = params.thinkingMode,
            tokensGenerated = perf.tokensGenerated,
            elapsedMs = elapsedMs,
            tokensPerSecond = perf.tokensPerSecond,
            responseChars = result.length
        )
    }

    /**
     * Ensures the model is loaded, performing eligibility checks and loading via the coordinator.
     */
    private suspend fun ensureModelLoaded(
        model: CatalogModel,
        file: File,
        onTrace: (String) -> Unit
    ) {
        val settings = settingsRepository.settings.first()
        val gate = loadGate(model)
        if (gate.eligibility == LoadEligibility.BLOCK) {
            throw IllegalStateException(gate.message)
        }
        if (gate.eligibility == LoadEligibility.WARN) {
            onTrace(gate.message)
        }
        try {
            coordinator.loadModel(
                model,
                file,
                GenerationConfig(
                    temperature = settings.temperature,
                    topK = settings.topK,
                    topP = settings.topP,
                    maxTokens = settings.maxTokens
                ),
                threads = ModelThreads.recommended(settings.batteryConscious)
            )
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Couldn't start this model.", e)
        }
    }

    /**
     * Builds the GenerationConfig, clamping maxTokens to the model's context window.
     */
    private suspend fun buildConfig(
        model: CatalogModel,
        text: String,
        settings: SettingsRepository.Settings,
        thinkingMode: String,
        onTrace: (String) -> Unit
    ): GenerationConfig {
        val baseMaxTokens = when (thinkingMode) {
            "INSTANT" -> 96
            "HARD" -> 1024
            else -> settings.maxTokens
        }
        val promptTokens = TokenEstimator.estimate(text) +
            TokenEstimator.estimate(settings.systemPrompt)
        val headroom = ((model.contextLength * 0.8).toInt() - promptTokens).coerceAtLeast(64)
        val maxTokens = baseMaxTokens.coerceIn(1, headroom)
        if (maxTokens != baseMaxTokens) {
            onTrace("Max output clamped to $maxTokens tokens for this model's context window.")
        }
        return GenerationConfig(
            temperature = settings.temperature,
            topK = settings.topK,
            topP = settings.topP,
            maxTokens = maxTokens
        )
    }

    /**
     * Finds or creates a conversation for this message.
     */
    private suspend fun prepareConversation(
        existingConvId: Long?,
        modelId: String,
        text: String,
        onTrace: (String) -> Unit
    ): Long {
        var convId = existingConvId
        if (convId != null && conversationDao.byId(convId) == null) {
            convId = null
        }
        if (convId == null) {
            convId = coordinator.createConversation(
                modelId,
                com.aichathub.app.util.TextUtils.titleFromPrompt(text)
            )
        }
        return convId
    }

    private suspend fun loadGate(model: CatalogModel): com.aichathub.app.device.LoadEligibilityResult {
        val profile = deviceInfoProvider.getDeviceProfile()
        val measured = settingsRepository.measuredMemoryOnce()
        return compatibilityEngine.loadEligibility(model, profile, measured)
    }
}
