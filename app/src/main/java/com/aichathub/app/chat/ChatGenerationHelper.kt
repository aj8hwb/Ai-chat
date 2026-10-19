package com.aichathub.app.chat

import android.os.Build
import com.aichathub.app.data.ModelRepository
import com.aichathub.app.data.SettingsRepository
import com.aichathub.app.data.local.ConversationDao
import com.aichathub.app.data.local.MessageDao
import com.aichathub.app.device.CompatibilityEngine
import com.aichathub.app.device.ContextSelector
import com.aichathub.app.device.ContextSelector.ContextBudget
import com.aichathub.app.device.ContextSelector.ContextSelection
import com.aichathub.app.device.DeviceInfoProvider
import com.aichathub.app.device.GgufMetadataReader
import com.aichathub.app.device.LoadEligibility
import com.aichathub.app.device.MemoryBudgetCalculator
import com.aichathub.app.device.ThermalManager
import com.aichathub.app.device.ThermalPolicy
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.DeviceProfile
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.util.ModelThreads
import com.aichathub.app.util.ThreadCalibration
import com.aichathub.app.util.TokenEstimator
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

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
    private val messageDao: MessageDao,
    // Optional: thermal-aware thread scaling when provided (wired from Hilt).
    // Null keeps the legacy fixed thread policy — safe for tests/other callers.
    private val thermalManager: ThermalManager? = null
) {
    companion object {
        private const val TAG = "ChatGenerationHelper"

        /**
         * Minimum generation tokens the configured output may be CLAMPED down
         * to by [buildConfig] (its `coerceAtLeast(64)` headroom floor). The
         * context reservation below uses this as the hard generation floor —
         * settings.maxTokens does NOT have to fit; it gets clamped instead.
         */
        private const val MIN_GENERATION_TOKENS = 64L
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

        // 0. Settings are needed BEFORE the model load: the context
        //    reservation uses the system prompt, and the thread policy uses
        //    battery-conscious mode.
        val settings = settingsRepository.settings.first()

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
            // Hard floor for the session context: this message + its system
            // prompt + the minimum viable generation (buildConfig clamps the
            // output to at least [MIN_GENERATION_TOKENS], so a lower context
            // could never serve this message without a native overflow).
            val reservedTokens =
                TokenEstimator.estimate(text) +
                    TokenEstimator.estimate(activeSystemPrompt(params, settings)) +
                    MIN_GENERATION_TOKENS
            ensureModelLoaded(model, file, reservedTokens, params.onTrace)
            params.onLoadState(false, null)
            params.onTrace("Model loaded")
        }

        // 3. Build generation config
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
            onStream = params.onStream,
        )

        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        val perf = runtime.performance.value
        val tps = (perf.tokensPerSecond * 10).toInt() / 10f
        params.onTrace("Done, ${perf.tokensGenerated} tokens, $tps tok/s, ${elapsedMs / 1000f}s")

        GenerationResult(
            text = result,
            mode = params.thinkingMode,
            tokensGenerated = perf.tokensGenerated,
            elapsedMs = elapsedMs,
            tokensPerSecond = perf.tokensPerSecond,
            responseChars = result.length,
        )
    }

    /**
     * Ensures the model is loaded, performing eligibility checks and loading via the coordinator.
     *
     * The device profile is fetched ONCE and reused for the eligibility gate,
     * the thermal-aware thread selection and the adaptive context selection.
     *
     * DATA → DECISION flow (spec §9):
     *  1. REAL GGUF architecture metadata ([GgufMetadataReader]) → the KV-cache
     *     part of the memory budget ([MemoryBudgetCalculator.KvCacheSpec]).
     *  2. Memory budget → the adaptive session context ([ContextSelector]):
     *     explicit UNKNOWN / ZERO / FAILED budget states; zero/insufficient
     *     budgets REFUSE the load with an actionable error instead of
     *     over-allocating, and unknown/failed ones fall back to a documented
     *     conservative ceiling — never the model maximum.
     *  3. Persisted thread calibration ([ThreadCalibration], written by
     *     [ThreadCalibrationEngine]) → the thread count passed into the real
     *     native load configuration; thermal/memory reductions still apply ON
     *     TOP of a calibrated value — a benchmark never overrides a safety limit.
     *
     * Thread policy ([ModelThreads]): bounded by CPU core count, reduced by
     * thermal state, capped on low-RAM devices.
     */
    private suspend fun ensureModelLoaded(
        model: CatalogModel,
        file: File,
        reservedTokens: Long,
        onTrace: (String) -> Unit
    ) {
        val settings = settingsRepository.settings.first()
        val plan = prepareLoadPlan(model, file, reservedTokens, onTrace)
        try {
            coordinator.loadModel(
                model,
                file,
                GenerationConfig(
                    temperature = settings.temperature,
                    topK = settings.topK,
                    topP = settings.topP,
                    maxTokens = settings.maxTokens,
                ),
                threads = plan.threads,
                contextLength = plan.contextLength,
            )
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Couldn't start this model.", e)
        }
    }

    /**
     * Everything that must be decided BEFORE the native load (the load plan):
     * gate, GGUF metadata → budget, calibrated threads, adaptive context.
     *
     * Public so the ViewModel's direct pre-warm load path uses the EXACT same
     * decisions as the generation path instead of duplicating the policy
     * (spec §9.3: no duplicate calculations).
     *
     * @throws IllegalStateException when the load gate BLOCKs the model or the
     *   safe context budget cannot even serve the requested session.
     */
    suspend fun prepareLoadPlan(
        model: CatalogModel,
        file: File,
        reservedTokens: Long = MIN_GENERATION_TOKENS,
        onTrace: (String) -> Unit = {}
    ): LoadPlan {
        val settings = settingsRepository.settings.first()
        val profile = deviceInfoProvider.getDeviceProfile()
        val measured = settingsRepository.measuredMemoryOnce()
        val gate = loadGate(model, profile, measured)
        if (gate.eligibility == LoadEligibility.BLOCK) {
            throw IllegalStateException(gate.message)
        }
        if (gate.eligibility == LoadEligibility.WARN) {
            onTrace(gate.message)
        }

        // REAL model architecture metadata → KV-cache spec for the budget.
        val ggufMetadata = GgufMetadataReader.readMetadata(file)
        val kvSpec = GgufMetadataReader.toKvCacheSpec(ggufMetadata)
        onTrace(
            if (kvSpec != null) {
                "KV cache estimated from model metadata " +
                    "(${kvSpec.layers} layers × ${kvSpec.kvHeads} KV heads × ${kvSpec.headDim} dim, f16)."
            } else {
                "No readable GGUF architecture metadata — KV cache uses the " +
                    "conservative parameter heuristic."
            },
        )

        val thermalInfo = thermalManager?.getThermalInfo(ModelThreads.MAX_THREADS)
        val thermalLevel = thermalInfo
            ?.let { ThermalPolicy.toLoadLevel(it.state) }
            ?: ModelThreads.ThermalLoadLevel.NORMAL
        if (thermalInfo != null && thermalInfo.state == ThermalManager.ThermalState.CRITICAL) {
            onTrace("Device is running very hot — using minimal CPU threads.")
        }

        // Benchmark-calibrated base thread count (falls back to the static
        // baseline when no valid record exists — inside resolveThreadCount).
        val cacheKey = ThreadCalibration.cacheKey(
            deviceHardware = Build.HARDWARE,
            runtimeVersion = runtime.runtimeVersionLabel,
            modelId = model.id,
            contextLength = model.contextLength,
            quantization = model.quantization,
        )
        val calibrationRecord = settingsRepository.threadCalibrationOnce()[cacheKey]
        val baseThreads = ThreadCalibration.resolveThreadCount(
            record = calibrationRecord,
            nowMs = System.currentTimeMillis(),
            batteryConscious = settings.batteryConscious,
            cpuCores = profile.cpuCores,
        )
        val calibrated = calibrationRecord != null && baseThreads == calibrationRecord.threads
        if (calibrated) {
            onTrace("Using calibrated CPU threads: $baseThreads (validated cached calibration).")
        } else {
            onTrace("Using safe baseline CPU threads: $baseThreads (no valid cached calibration).")
        }
        val threads = ModelThreads.withThermal(
            ModelThreads.withMemoryPressure(baseThreads, profile.isLowRamDevice),
            thermalLevel,
        )

        // Adaptive session context from the SAME memory budget the gate uses.
        val budget = MemoryBudgetCalculator.calculate(
            profile,
            modelWeightsBytes = model.fileSizeBytes,
            contextLength = model.contextLength,
            parameterCount = model.parameterCount.takeIf { it > 0 },
            kvCacheSpec = kvSpec,
        )
        val budgetState = ContextSelector.toBudget(
            maxSafeContextTokens = budget.maxContextTokens,
            budgetKnown = budget.weightsMemoryBytes > 0,
        )
        val sessionContext = when (
            val selection =
                ContextSelector.selectContext(model.contextLength, budgetState, reservedTokens)
        ) {
            is ContextSelection.Selected -> selection.tokens
            is ContextSelection.Insufficient -> throw IllegalStateException(selection.reason)
        }
        if (sessionContext < model.contextLength) {
            onTrace(
                "Runtime context set to $sessionContext tokens " +
                    "(model limit ${model.contextLength}) for device memory safety.",
            )
        }
        when (budgetState) {
            ContextBudget.Unknown, ContextBudget.Failed ->
                onTrace(
                    "Device memory budget unavailable — context conservatively capped at " +
                        "$sessionContext tokens (unverified configurations are never given the model maximum).",
                )
            else -> Unit
        }

        return LoadPlan(threads = threads, contextLength = sessionContext, calibrated = calibrated)
    }

    /** Decisions made for one model load before the native runtime starts. */
    data class LoadPlan(
        val threads: Int,
        val contextLength: Int,
        /** True when a valid, fresh benchmark calibration decided the threads. */
        val calibrated: Boolean
    )

    /** Conversation-level system prompt when it exists, else the global one. */
    private suspend fun activeSystemPrompt(params: GenerationParams, settings: SettingsRepository.Settings): String =
        params.conversationId
            ?.let { conversationDao.byId(it)?.systemPrompt?.takeIf { s -> s.isNotBlank() } }
            ?: settings.systemPrompt

    /**
     * Builds the GenerationConfig, clamping maxTokens to the runtime context
     * window. Prefers the context ACTUALLY loaded in the runtime (which may be
     * adaptively smaller than the model's maximum on low-RAM devices) over the
     * model's max — falling back to the model max when nothing is loaded.
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
        val effectiveContext = runtime.performance.value.contextTokensMax
            .takeIf { it > 0 }
            ?: model.contextLength
        val promptTokens = TokenEstimator.estimate(text) +
            TokenEstimator.estimate(settings.systemPrompt)
        val headroom = ((effectiveContext * 0.8).toInt() - promptTokens).coerceAtLeast(64)
        val maxTokens = baseMaxTokens.coerceIn(1, headroom)
        if (maxTokens != baseMaxTokens) {
            onTrace("Max output clamped to $maxTokens tokens for this model's context window.")
        }
        return GenerationConfig(
            temperature = settings.temperature,
            topK = settings.topK,
            topP = settings.topP,
            maxTokens = maxTokens,
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
                com.aichathub.app.util.TextUtils.titleFromPrompt(text),
            )
        }
        return convId
    }

    private fun loadGate(
        model: CatalogModel,
        profile: DeviceProfile,
        measured: Map<String, Long>
    ): com.aichathub.app.device.LoadEligibilityResult {
        return compatibilityEngine.loadEligibility(model, profile, measured)
    }
}
