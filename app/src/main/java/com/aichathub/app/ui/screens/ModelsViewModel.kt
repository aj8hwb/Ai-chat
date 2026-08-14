package com.aichathub.app.ui.screens

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.aichathub.app.data.model.LocalModelCatalog
import com.aichathub.app.device.MemoryBudgetCalculator
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.CompatibilityLevel
import com.aichathub.app.domain.model.DeviceProfile
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.domain.model.Recommendation
import com.aichathub.app.ui.AiViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ModelsUiState(
    val models: List<CatalogModel> = LocalModelCatalog.models,
    val states: Map<String, ModelLifecycleState> = emptyMap(),
    val compatibility: Map<String, CompatibilityLevel> = emptyMap(),
    val query: String = "",
    val selectedCategory: String = "All",
    val profile: DeviceProfile? = null,
    val loading: Boolean = true
) {
    val filtered: List<CatalogModel>
        get() = models.filter { model ->
            val matchesQuery = query.isBlank() ||
                model.name.contains(query, ignoreCase = true) ||
                model.provider.contains(query, ignoreCase = true) ||
                model.category.contains(query, ignoreCase = true)
            val matchesCategory = selectedCategory == "All" ||
                model.category == selectedCategory ||
                (selectedCategory == "Recommended" &&
                    (compatibility[model.id]?.rank ?: 0) >= 4)
            matchesQuery && matchesCategory
        }
}

class ModelsViewModel(application: Application) : AiViewModel(application) {

    private val _state = MutableStateFlow(ModelsUiState())
    val state: StateFlow<ModelsUiState> = _state.asStateFlow()

    init {
        refresh()
        observeInstalled()
    }

    private fun observeInstalled() {
        viewModelScope.launch {
            container.modelRepository.installedModels.collect { installed ->
                _state.value = _state.value.copy(
                    states = installed.associate { it.modelId to it.state }
                )
            }
        }
    }

    fun refresh() {
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            val profile = container.deviceInfoProvider.getDeviceProfile()
            val budget = MemoryBudgetCalculator.calculate(profile)
            val recommendations = container.compatibilityEngine.recommendAll(
                LocalModelCatalog.models,
                profile,
                budget
            )
            _state.value = _state.value.copy(
                compatibility = recommendations.associate { it.model.id to it.level },
                profile = profile,
                loading = false
            )
        }
    }

    fun onQueryChange(q: String) {
        _state.value = _state.value.copy(query = q)
    }

    fun onCategoryChange(c: String) {
        _state.value = _state.value.copy(selectedCategory = c)
    }

    val categories: List<String> =
        listOf("All", "Recommended") + LocalModelCatalog.models.map { it.category }.distinct()
}