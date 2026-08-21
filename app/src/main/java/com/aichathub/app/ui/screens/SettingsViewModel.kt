package com.aichathub.app.ui.screens

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.aichathub.app.data.model.LocalModelCatalog
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.ui.AiViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SettingsUiState(
    val defaultModelId: String? = null,
    val temperature: Float = 0.8f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val maxTokens: Int = 512,
    val systemPrompt: String = "You are a helpful, concise local AI assistant.",
    val autoUnload: Boolean = true,
    val batteryConscious: Boolean = false,
    val wifiOnlyDownloads: Boolean = false,
    val themeMode: String = "dark",
    val dynamicColor: Boolean = false,
    val historyTurns: Int = 8,
    val installedModels: List<CatalogModel> = emptyList(),
    val saved: Boolean = false
)

class SettingsViewModel(application: Application) : AiViewModel(application) {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val s = container.settingsRepository.settings.first()
            val installed = container.modelRepository.installedModelsOnce()
                .mapNotNull { LocalModelCatalog.byId(it.modelId) }
            _state.value = SettingsUiState(
                defaultModelId = s.defaultModelId,
                temperature = s.temperature,
                topK = s.topK,
                topP = s.topP,
                maxTokens = s.maxTokens,
                systemPrompt = s.systemPrompt,
                autoUnload = s.autoUnload,
                batteryConscious = s.batteryConscious,
                wifiOnlyDownloads = s.wifiOnlyDownloads,
                themeMode = s.themeMode,
                dynamicColor = s.dynamicColor,
                historyTurns = s.historyTurns,
                installedModels = installed
            )
        }
    }

    fun setDefaultModel(id: String?) {
        _state.value = _state.value.copy(defaultModelId = id)
        viewModelScope.launch { container.settingsRepository.setDefaultModel(id) }
    }

    fun onTemperatureChange(v: Float) {
        _state.value = _state.value.copy(temperature = v)
        viewModelScope.launch { container.settingsRepository.setTemperature(v) }
    }

    fun onTopKChange(v: Int) {
        _state.value = _state.value.copy(topK = v)
        viewModelScope.launch { container.settingsRepository.setTopK(v) }
    }

    fun onTopPChange(v: Float) {
        _state.value = _state.value.copy(topP = v)
        viewModelScope.launch { container.settingsRepository.setTopP(v) }
    }

    fun onMaxTokensChange(v: Int) {
        _state.value = _state.value.copy(maxTokens = v)
        viewModelScope.launch { container.settingsRepository.setMaxTokens(v) }
    }

    fun onSystemPromptChange(v: String) {
        _state.value = _state.value.copy(systemPrompt = v)
        viewModelScope.launch { container.settingsRepository.setSystemPrompt(v) }
    }

    fun onAutoUnloadChange(v: Boolean) {
        _state.value = _state.value.copy(autoUnload = v)
        viewModelScope.launch { container.settingsRepository.setAutoUnload(v) }
    }

    fun onBatteryConsciousChange(v: Boolean) {
        _state.value = _state.value.copy(batteryConscious = v)
        viewModelScope.launch { container.settingsRepository.setBatteryConscious(v) }
    }

    fun onWifiOnlyChange(v: Boolean) {
        _state.value = _state.value.copy(wifiOnlyDownloads = v)
        viewModelScope.launch { container.settingsRepository.setWifiOnlyDownloads(v) }
    }

    fun onHistoryTurnsChange(v: Int) {
        _state.value = _state.value.copy(historyTurns = v)
        viewModelScope.launch { container.settingsRepository.setHistoryTurns(v) }
    }

    fun onThemeModeChange(v: String) {
        _state.value = _state.value.copy(themeMode = v)
        viewModelScope.launch { container.settingsRepository.setThemeMode(v) }
    }

    fun onDynamicColorChange(v: Boolean) {
        _state.value = _state.value.copy(dynamicColor = v)
        viewModelScope.launch { container.settingsRepository.setDynamicColor(v) }
    }

    fun showSaved() {
        _state.value = _state.value.copy(saved = true)
    }

    fun clearSaved() {
        _state.value = _state.value.copy(saved = false)
    }
}