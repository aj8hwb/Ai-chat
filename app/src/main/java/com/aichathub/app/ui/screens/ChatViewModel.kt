package com.aichathub.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichathub.app.chat.ChatCoordinator
import com.aichathub.app.chat.ChatGenerationHelper
import com.aichathub.app.chat.ChatGenerationState
import com.aichathub.app.chat.GenerationConfig
import com.aichathub.app.chat.InferenceRuntime
import com.aichathub.app.data.CatalogRepository
import com.aichathub.app.data.ConversationExportManager
import com.aichathub.app.data.local.ConversationDao
import com.aichathub.app.data.local.ConversationEntity
import com.aichathub.app.data.local.MessageDao
import com.aichathub.app.data.local.MessageEntity
import com.aichathub.app.data.ModelRepository
import com.aichathub.app.data.SettingsRepository
import com.aichathub.app.device.CompatibilityEngine
import com.aichathub.app.device.DeviceInfoProvider
import com.aichathub.app.device.MemoryBudgetCalculator
import com.aichathub.app.chat.CompactionSuggestion
import com.aichathub.app.chat.TokenContextEngine
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.ModelLifecycleState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class ChatUiState(
    val conversationId: Long? = null,
    val messages: List<MessageEntity> = emptyList(),
    val input: String = "",
    val generating: Boolean = false,
    val isLoadingModel: Boolean = false,
    val error: String? = null,
    val activeModelId: String? = null,
    val activeModelName: String? = null,
    val selectedConversationId: Long? = null,
    val lastStreamedText: String = "",
    val installedModels: List<CatalogModel> = emptyList(),
    val conversations: List<ConversationEntity> = emptyList(),
    val thinkingMode: String = "DEFAULT",
    val lastThinking: ThinkingInfo? = null,
    val isModelLoaded: Boolean = false,
    val generationPhase: String = ChatGenerationState.IDLE.name,
    val liveThinkingSec: Int = 0,
    val thinkingExpanded: Boolean = false,
    val thinkingTrace: List<String> = emptyList(),
    val contextTokensMax: Int = 0,
    val contextStatus: TokenContextEngine.ContextStatus? = null,
    val compactionSuggestion: CompactionSuggestion? = null,
    val exportedFileName: String? = null,
    val exportedContent: String? = null
)

data class ThinkingInfo(
    val mode: String,
    val tokens: Int,
    val elapsedMs: Long,
    val tokensPerSecond: Float,
    val responseChars: Int
) {
    val elapsedSec: Float get() = elapsedMs / 1000f
    val tps: Float get() = tokensPerSecond
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val coordinator: ChatCoordinator,
    private val container_runtime: InferenceRuntime,
    private val container_settingsRepository: SettingsRepository,
    private val container_modelRepository: ModelRepository,
    private val container_deviceInfoProvider: DeviceInfoProvider,
    private val container_compatibilityEngine: CompatibilityEngine,
    private val container_conversationDao: ConversationDao,
    private val container_messageDao: MessageDao,
    private val container_exportManager: ConversationExportManager,
    private val catalogRepository: CatalogRepository
) : ViewModel() {

    private val generationHelper = ChatGenerationHelper(
        coordinator = coordinator,
        runtime = container_runtime,
        settingsRepository = container_settingsRepository,
        modelRepository = container_modelRepository,
        deviceInfoProvider = container_deviceInfoProvider,
        compatibilityEngine = container_compatibilityEngine,
        conversationDao = container_conversationDao,
        messageDao = container_messageDao
    )

    companion object {
        const val MAX_MESSAGE_CHARS = 1500
        private const val MAX_TRACE_LINES = 120
        /** Minimum interval between stream UI updates to reduce allocations and recomposition. */
        private const val STREAM_UPDATE_THROTTLE_MS = 80L
    }

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val tokenContextEngine = TokenContextEngine()
    private var loadSession = 0L
    private var sendSeq = 0L

    @Volatile
    private var sendInFlight = false

    private var messagesJob: Job? = null

    @Volatile
    private var lastStreamUpdateMs = 0L

    init {
        observeCoordinator()
        autoSelectDefaultModel()
        viewModelScope.launch {
            container_settingsRepository.settings.first().thinkingMode.let { mode ->
                _state.value = _state.value.copy(thinkingMode = mode)
            }
        }
    }

    fun setThinkingMode(mode: String) {
        if (mode == _state.value.thinkingMode) return
        _state.value = _state.value.copy(thinkingMode = mode)
        viewModelScope.launch {
            container_settingsRepository.setThinkingMode(mode)
        }
    }

    fun toggleThinking() {
        _state.value = _state.value.copy(thinkingExpanded = !_state.value.thinkingExpanded)
    }

    private fun appendTrace(line: String) {
        val capped = (_state.value.thinkingTrace + line).takeLast(MAX_TRACE_LINES)
        _state.value = _state.value.copy(thinkingTrace = capped)
    }

    fun updateMessage(message: MessageEntity) {
        viewModelScope.launch {
            container_messageDao.update(message)
            if (message.role == "user") {
                val convId = message.conversationId
                val later = container_messageDao.forConversation(convId)
                    .filter { it.createdAt > message.createdAt }
                later.forEach { container_messageDao.delete(it.id) }
            }
        }
    }

    private fun autoSelectDefaultModel() {
        viewModelScope.launch {
            runCatching { container_conversationDao.pruneEmpty() }
            val ready = readyModels()
            val best = pickBestModel(ready)
            if (best != null && _state.value.activeModelId == null) {
                _state.value = _state.value.copy(
                    activeModelId = best.id,
                    activeModelName = best.name
                )
            }
        }
    }

    private suspend fun readyModels(): List<CatalogModel> =
        container_modelRepository.installedModels.first()
            .filter { it.state == ModelLifecycleState.READY && it.filePath != null }
            .mapNotNull { catalogRepository.getModelById(it.modelId) }

    private suspend fun pickBestModel(ready: List<CatalogModel>): CatalogModel? {
        if (ready.isEmpty()) return null
        val savedDefault = container_settingsRepository.settings.first().defaultModelId
        savedDefault?.let { id -> ready.firstOrNull { it.id == id }?.let { return it } }
        return runCatching {
            val profile = container_deviceInfoProvider.getDeviceProfile()
            val budget = MemoryBudgetCalculator.calculate(profile)
            val measured = container_settingsRepository.measuredMemoryOnce()
            container_compatibilityEngine.recommendAll(ready, profile, budget, measured)
                .firstOrNull()?.model
        }.getOrNull() ?: ready.first()
    }

    private fun observeCoordinator() {
        viewModelScope.launch {
            coordinator.state.collect { s ->
                _state.value = _state.value.copy(
                    activeModelId = s.activeModelId ?: _state.value.activeModelId,
                    activeModelName = s.activeModelName ?: _state.value.activeModelName,
                    generating = s.generationState == ChatGenerationState.GENERATING,
                    isLoadingModel = s.isLoadingModel,
                    error = if (s.generationState == ChatGenerationState.LOADING ||
                        s.generationState == ChatGenerationState.GENERATING
                    ) {
                        s.error
                    } else {
                        _state.value.error
                    },
                    isModelLoaded = container_runtime.isLoaded && s.activeModelId != null,
                    generationPhase = s.generationState.name
                )
            }
        }
        viewModelScope.launch {
            container_runtime.performance.collect { p ->
                _state.value = _state.value.copy(contextTokensMax = p.contextTokensMax)
            }
        }
        viewModelScope.launch {
            coordinator.activeConversationId.collect { id ->
                _state.value = _state.value.copy(selectedConversationId = id)
            }
        }
        viewModelScope.launch {
            container_modelRepository.installedModels.collect { installed ->
                val ready = installed
                    .filter { it.state == ModelLifecycleState.READY && it.filePath != null }
                    .mapNotNull { catalogRepository.getModelById(it.modelId) }
                _state.value = _state.value.copy(installedModels = ready)
                if (_state.value.activeModelId == null && ready.isNotEmpty()) {
                    val best = pickBestModel(ready)
                    if (best != null) {
                        _state.value = _state.value.copy(
                            activeModelId = best.id,
                            activeModelName = best.name
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            container_conversationDao.observeAll().collect { conversations ->
                _state.value = _state.value.copy(conversations = conversations)
            }
        }
    }

    fun setInput(v: String) {
        _state.value = _state.value.copy(input = v)
        refreshContextStatus()
    }

    fun loadConversation(id: Long) {
        viewModelScope.launch {
            coordinator.selectConversation(id)
            _state.value = _state.value.copy(conversationId = id, selectedConversationId = id)
            observeMessages(id)
            val conv = container_conversationDao.byId(id)
            if (conv != null) {
                val catalog = catalogRepository.getModelById(conv.modelId)
                val st = container_modelRepository.stateFor(conv.modelId)
                if (catalog != null && st?.state == ModelLifecycleState.READY && st.filePath != null
                    && _state.value.activeModelId != catalog.id
                ) {
                    selectModel(catalog)
                }
            }
        }
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            coordinator.deleteConversation(id)
            if (_state.value.conversationId == id) newChat()
        }
    }

    fun setConversationSystemPrompt(prompt: String) {
        val convId = _state.value.conversationId ?: return
        viewModelScope.launch {
            val trimmed = prompt.trim().ifBlank { null }
            container_conversationDao.setSystemPrompt(convId, trimmed)
        }
    }

    fun newChat() {
        coordinator.newConversation()
        messagesJob?.cancel()
        _state.value = _state.value.copy(
            conversationId = null,
            selectedConversationId = null,
            messages = emptyList(),
            error = null,
            lastStreamedText = ""
        )
    }

    fun selectModel(model: CatalogModel) {
        val session = ++loadSession
        viewModelScope.launch {
            val st = container_modelRepository.stateFor(model.id)
            val file = st?.filePath?.let { File(it) }
            if (st?.state != ModelLifecycleState.READY || file == null || !file.isFile) {
                _state.value = _state.value.copy(
                    error = "This model is not ready yet. Download and verify it first."
                )
                return@launch
            }
            if (session != loadSession) return@launch
            _state.value = _state.value.copy(isLoadingModel = true, error = null)
            try {
                val settings = container_settingsRepository.settings.first()
                val gate = loadGate(model)
                if (gate.eligibility == LoadEligibility.BLOCK) {
                    _state.value = _state.value.copy(
                        isLoadingModel = false,
                        error = gate.message
                    )
                    return@launch
                }
                if (gate.eligibility == LoadEligibility.WARN) {
                    _state.value = _state.value.copy(error = gate.message)
                }
                coordinator.loadModel(
                    model,
                    file,
                    GenerationConfig(
                        temperature = settings.temperature,
                        topK = settings.topK,
                        topP = settings.topP,
                        maxTokens = settings.maxTokens
                    ),
                    threads = nativeThreads(settings)
                )
                if (session != loadSession) return@launch
                _state.value = _state.value.copy(
                    activeModelId = model.id,
                    activeModelName = model.name,
                    isLoadingModel = false
                )
            } catch (e: OutOfMemoryError) {
                _state.value = _state.value.copy(
                    isLoadingModel = false,
                    error = "Insufficient memory to load this model safely. Try a lighter model."
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoadingModel = false,
                    error = "The model could not be loaded. Please select another installed model."
                )
            }
        }
    }

    fun selectModelById(modelId: String) {
        if (modelId.isBlank()) return
        if (modelId == _state.value.activeModelId) return
        val catalog = catalogRepository.getModelById(modelId) ?: return
        viewModelScope.launch {
            val st = container_modelRepository.stateFor(modelId)
            if (st?.state != ModelLifecycleState.READY || st.filePath == null) {
                _state.value = _state.value.copy(
                    error = "This model is not ready yet. Download and verify it first."
                )
                return@launch
            }
            selectModel(catalog)
        }
    }

    fun send() {
        sendInternal(persistUser = true)
    }

    fun sendInternal(persistUser: Boolean) {
        val text = _state.value.input.trim()
        if (text.isEmpty() || sendInFlight || _state.value.generating) return
        if (text.length > MAX_MESSAGE_CHARS) {
            _state.value = _state.value.copy(
                error = "That message is too long (max $MAX_MESSAGE_CHARS characters). Please shorten it."
            )
            return
        }
        val model = _state.value.activeModelId?.let { catalogRepository.getModelById(it) }
        if (model == null) {
            _state.value = _state.value.copy(error = "Select a model to start chatting.")
            return
        }

        sendInFlight = true
        val seq = ++sendSeq
        lastStreamUpdateMs = 0L
        _state.value = _state.value.copy(
            generating = true,
            error = null,
            lastStreamedText = "",
            thinkingTrace = listOf("Preparing message...")
        )

        viewModelScope.launch {
            var thinkingTicker: Job? = null
            var lastTraceAt = 0L
            try {
                thinkingTicker = viewModelScope.launch {
                    var tick = 0
                    while (isActive) {
                        _state.value = _state.value.copy(liveThinkingSec = tick)
                        tick++
                        kotlinx.coroutines.delay(1000)
                    }
                }
                _state.value = _state.value.copy(input = "")
                val result = generationHelper.generate(
                    ChatGenerationHelper.GenerationParams(
                        model = model,
                        text = text,
                        persistUser = persistUser,
                        thinkingMode = _state.value.thinkingMode,
                        conversationId = _state.value.conversationId,
                        onStream = { streamed ->
                            val now = System.currentTimeMillis()
                            if (now - lastStreamUpdateMs >= STREAM_UPDATE_THROTTLE_MS) {
                                lastStreamUpdateMs = now
                                _state.value = _state.value.copy(lastStreamedText = streamed)
                            }
                            if (now - lastTraceAt > 500) {
                                lastTraceAt = now
                                val perf = container_runtime.performance.value
                                val tps = (perf.tokensPerSecond * 10).toInt() / 10f
                                appendTrace("${perf.tokensGenerated} tokens, ${tps} tok/s")
                            }
                        },
                        onTrace = ::appendTrace,
                        onLoadState = { loading, error ->
                            _state.value = _state.value.copy(isLoadingModel = loading)
                            if (error != null) _state.value = _state.value.copy(error = error)
                        }
                    )
                )
                // Update conversation ID if a new conversation was created
                val activeConvId = coordinator.activeConversationId.value
                if (activeConvId != null && _state.value.conversationId != activeConvId) {
                    _state.value = _state.value.copy(
                        conversationId = activeConvId,
                        selectedConversationId = activeConvId
                    )
                    observeMessages(activeConvId)
                }
                _state.value = _state.value.copy(
                    lastThinking = ThinkingInfo(
                        mode = result.mode,
                        tokens = result.tokensGenerated,
                        elapsedMs = result.elapsedMs,
                        tokensPerSecond = result.tokensPerSecond,
                        responseChars = result.responseChars
                    ),
                    lastStreamedText = result.text
                )
            } catch (e: OutOfMemoryError) {
                _state.value = _state.value.copy(
                    error = "Generation ran out of memory. Try a lighter model or a shorter message."
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Generation failed. Please try again."
                )
            } finally {
                if (seq == sendSeq) {
                    sendInFlight = false
                    _state.value = _state.value.copy(
                        generating = false,
                        isLoadingModel = false,
                        liveThinkingSec = 0
                    )
                    runCatching { coordinator.resetToIdle() }
                }
                thinkingTicker?.cancel()
            }
        }
    }

    fun stopGeneration() {
        coordinator.stopGeneration()
    }

    fun regenerate() {
        val messages = _state.value.messages
        val lastUser = messages.lastOrNull { it.role == "user" } ?: return
        if (sendInFlight || _state.value.generating) return
        viewModelScope.launch {
            val stale = messages.filter { it.createdAt > lastUser.createdAt }
            stale.forEach { container_messageDao.delete(it.id) }
            _state.value = _state.value.copy(input = lastUser.content)
            sendInternal(persistUser = false)
        }
    }

    fun exportConversation(format: String) {
        val convId = _state.value.conversationId ?: return
        viewModelScope.launch {
            try {
                val content = when (format) {
                    "markdown" -> container_exportManager.exportMarkdown(convId)
                    "json" -> container_exportManager.exportJson(convId)
                    "plaintext" -> container_exportManager.exportPlainText(convId)
                    else -> return@launch
                }
                val title = _state.value.conversations
                    .firstOrNull { it.id == convId }?.title
                    ?: "conversation"
                val safeName = title.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                val ext = when (format) {
                    "markdown" -> "md"
                    "json" -> "json"
                    "plaintext" -> "txt"
                    else -> "txt"
                }
                _state.value = _state.value.copy(
                    exportedFileName = "$safeName.$ext",
                    exportedContent = content
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "Export failed: ${e.message}")
            }
        }
    }

    fun clearExportState() {
        _state.value = _state.value.copy(exportedFileName = null, exportedContent = null)
    }

    private fun observeMessages(convId: Long) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            container_messageDao.observeForConversation(convId).collect { msgs ->
                _state.value = _state.value.copy(messages = msgs)
                refreshContextStatus()
            }
        }
    }

    private fun refreshContextStatus() {
        val model = _state.value.activeModelId?.let { catalogRepository.getModelById(it) } ?: return
        val messages = _state.value.messages
        val history = messages.map { it.role to it.content }
        val status = tokenContextEngine.estimateContext(
            userMessage = _state.value.input,
            systemPrompt = "",
            conversationHistory = history,
            model = model
        )
        val suggestion = tokenContextEngine.suggestCompaction(status, history)
        _state.value = _state.value.copy(
            contextStatus = status,
            compactionSuggestion = suggestion
        )
    }

    fun handleCompaction() {
        val suggestion = _state.value.compactionSuggestion ?: return
        when (suggestion.type) {
            com.aichathub.app.chat.CompactionType.TRIM_OLDEST -> {
                val messages = _state.value.messages
                if (messages.size > 2) {
                    val toDelete = messages.dropLast(2)
                    viewModelScope.launch {
                        toDelete.forEach { container_messageDao.delete(it.id) }
                    }
                }
            }
            com.aichathub.app.chat.CompactionType.SUMMARIZE -> {
                val messages = _state.value.messages
                val convId = _state.value.conversationId
                if (messages.size > 4 && convId != null) {
                    viewModelScope.launch {
                        // Generate a structured summary from older messages
                        val half = messages.size / 2
                        val toSummarize = messages.take(half)
                        val toKeep = messages.drop(half)
                        
                        // Create a structured summary
                        val summaryBuilder = StringBuilder()
                        summaryBuilder.appendLine("Conversation Summary:")
                        summaryBuilder.appendLine()
                        
                        // Group messages by role and create a coherent summary
                        val userMessages = toSummarize.filter { it.role == "user" }
                        val assistantMessages = toSummarize.filter { it.role == "assistant" }
                        
                        if (userMessages.isNotEmpty()) {
                            summaryBuilder.appendLine("User asked about:")
                            userMessages.forEach { msg ->
                                val truncated = msg.content.take(150).trim()
                                if (truncated.isNotEmpty()) {
                                    summaryBuilder.appendLine("- $truncated")
                                }
                            }
                            summaryBuilder.appendLine()
                        }
                        
                        if (assistantMessages.isNotEmpty()) {
                            summaryBuilder.appendLine("Assistant responded with:")
                            assistantMessages.forEach { msg ->
                                val truncated = msg.content.take(150).trim()
                                if (truncated.isNotEmpty()) {
                                    summaryBuilder.appendLine("- $truncated")
                                }
                            }
                            summaryBuilder.appendLine()
                        }
                        
                        // Add key topics and decisions
                        val allContent = toSummarize.joinToString(" ") { it.content }
                        val keyPhrases = extractKeyPhrases(allContent)
                        if (keyPhrases.isNotEmpty()) {
                            summaryBuilder.appendLine("Key topics discussed:")
                            keyPhrases.take(5).forEach { phrase ->
                                summaryBuilder.appendLine("- $phrase")
                            }
                        }
                        
                        val summary = summaryBuilder.toString().trim()
                        
                        // Store the summary in the conversation
                        container_conversationDao.setSummary(convId, summary)
                        
                        // Delete the older messages
                        toSummarize.forEach { container_messageDao.delete(it.id) }
                        
                        // Add a system message indicating the summary
                        container_messageDao.insert(
                            MessageEntity(
                                conversationId = convId,
                                role = "system",
                                content = "[Previous conversation summary: $summary]",
                                createdAt = System.currentTimeMillis(),
                                modelId = _state.value.activeModelId
                            )
                        )
                    }
                }
            }
            com.aichathub.app.chat.CompactionType.START_NEW -> {
                newChat()
            }
        }
        _state.value = _state.value.copy(compactionSuggestion = null)
    }

    /**
     * Extracts key phrases from text for summarization.
     */
    private fun extractKeyPhrases(text: String): List<String> {
        val words = text.lowercase()
            .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 3 }
        
        // Simple keyword extraction based on frequency
        val wordFrequency = mutableMapOf<String, Int>()
        words.forEach { word ->
            wordFrequency[word] = (wordFrequency[word] ?: 0) + 1
        }
        
        return wordFrequency.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { it.key }
    }

}
