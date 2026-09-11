package com.aichathub.app.ui.screens

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichathub.app.data.SettingsRepository
import com.aichathub.app.privacy.CrashLogRepository
import com.aichathub.app.privacy.PrivacyCenter
import com.aichathub.app.privacy.PrivacyDashboard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PrivacyUiState(
    val appLockEnabled: Boolean = false,
    val incognitoMode: Boolean = false,
    val clipboardAutoClear: Boolean = false,
    val crashLogPurge: Boolean = false,
    val modelIntegrityOk: Boolean = true,
    val clearConfirmPending: Boolean = false,
    val operationInProgress: Boolean = false,
    val message: String? = null,
    val dashboard: PrivacyDashboard = PrivacyDashboard(),
    val crashLogCount: Int = 0
)

@HiltViewModel
class PrivacySettingsViewModel @Inject constructor(
    private val container_settingsRepository: SettingsRepository,
    private val container_application: Application
) : ViewModel() {

    private val _state = MutableStateFlow(PrivacyUiState())
    val state: StateFlow<PrivacyUiState> = _state.asStateFlow()

    init {
        PrivacyCenter.init(container_settingsRepository)
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val s = container_settingsRepository.settings.first()
            _state.value = PrivacyUiState(
                appLockEnabled = s.appLockEnabled,
                incognitoMode = s.incognitoMode,
                clipboardAutoClear = s.clipboardAutoClear,
                crashLogPurge = s.crashLogPurge,
                modelIntegrityOk = PrivacyCenter.verifyModelAvailability(container_application),
                dashboard = PrivacyCenter.getPrivacyDashboard(),
                crashLogCount = CrashLogRepository.entryCount(container_application)
            )
        }
    }

    fun onAppLockToggle(enabled: Boolean) {
        _state.value = _state.value.copy(appLockEnabled = enabled)
        viewModelScope.launch {
            PrivacyCenter.setAppLockEnabled(enabled)
            refreshDashboard()
        }
    }

    fun onIncognitoToggle(enabled: Boolean) {
        _state.value = _state.value.copy(incognitoMode = enabled)
        viewModelScope.launch {
            PrivacyCenter.setIncognitoMode(enabled)
            refreshDashboard()
        }
    }

    fun onClipboardAutoClearToggle(enabled: Boolean) {
        _state.value = _state.value.copy(clipboardAutoClear = enabled)
        viewModelScope.launch {
            container_settingsRepository.setClipboardAutoClear(enabled)
            if (enabled) {
                PrivacyCenter.scheduleClipboardClear(container_application)
            } else {
                PrivacyCenter.cancelClipboardClear()
            }
            refreshDashboard()
        }
    }

    fun requestClearAllData() {
        _state.value = _state.value.copy(clearConfirmPending = true)
    }

    fun dismissClearConfirm() {
        _state.value = _state.value.copy(clearConfirmPending = false)
    }

    fun clearCrashLogs() {
        viewModelScope.launch {
            _state.value = _state.value.copy(operationInProgress = true)
            PrivacyCenter.clearCrashLogs(container_application)
            container_settingsRepository.setCrashLogPurge(true)
            _state.value = _state.value.copy(
                operationInProgress = false,
                crashLogPurge = true,
                crashLogCount = 0,
                message = "Crash logs cleared"
            )
        }
    }

    fun clearAllLocalData() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                operationInProgress = true,
                clearConfirmPending = false
            )
            PrivacyCenter.clearAllLocalData(container_application)
            _state.value = _state.value.copy(
                operationInProgress = false,
                message = "All local data cleared"
            )
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun refreshDashboard() {
        _state.value = _state.value.copy(
            dashboard = PrivacyCenter.getPrivacyDashboard()
        )
    }
}
