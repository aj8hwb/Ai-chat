package com.aichathub.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichathub.app.ui.components.AppCard
import com.aichathub.app.ui.components.GradientButton
import com.aichathub.app.ui.components.SectionHeader
import com.aichathub.app.ui.navigation.Screen
import com.aichathub.app.ui.theme.Primary
import com.aichathub.app.ui.theme.SurfaceHigh
import com.aichathub.app.ui.theme.TextPrimary
import com.aichathub.app.ui.theme.TextSecondary

@Composable
fun SettingsScreen(
    onNavigate: (String) -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    var saved by remember { mutableStateOf(false) }
    var showDefaultModelPicker by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Spacer(Modifier.height(16.dp))
        }

        // General
        item {
            SectionHeader(title = "General")
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingRow(
                        title = "Default Model",
                        value = state.installedModels.firstOrNull { it.id == state.defaultModelId }?.name
                            ?: (state.defaultModelId ?: "Not set"),
                        onClick = { showDefaultModelPicker = true }
                    )
                    HorizontalDivider(color = SurfaceHigh)
                    SettingRow(
                        title = "System Status",
                        value = "Device analysis",
                        onClick = { onNavigate(Screen.SystemStatus.route) }
                    )
                    HorizontalDivider(color = SurfaceHigh)
                    SettingRow(
                        title = "Storage",
                        value = "Manage models & cache",
                        onClick = { onNavigate(Screen.Storage.route) }
                    )
                    HorizontalDivider(color = SurfaceHigh)
                    SettingRow(
                        title = "Conversations",
                        value = "History",
                        onClick = { onNavigate(Screen.History.route) }
                    )
                    HorizontalDivider(color = SurfaceHigh)
                    SettingRow(
                        title = "Chat Settings",
                        value = "Generation parameters",
                        onClick = { onNavigate(Screen.ChatSettings.route) }
                    )
                }
            }
        }

        // Performance
        item {
            Spacer(Modifier.height(16.dp))
            SectionHeader(title = "Performance")
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SwitchRow(
                        title = "Battery-Conscious Mode",
                        subtitle = "Reduce power use when generating",
                        checked = state.batteryConscious,
                        onCheckedChange = viewModel::onBatteryConsciousChange
                    )
                    HorizontalDivider(color = SurfaceHigh)
                    SwitchRow(
                        title = "Auto Unload Model",
                        subtitle = "Release memory when inactive",
                        checked = state.autoUnload,
                        onCheckedChange = viewModel::onAutoUnloadChange
                    )
                }
            }
        }

        // Downloads
        item {
            Spacer(Modifier.height(16.dp))
            SectionHeader(title = "Downloads")
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SwitchRow(
                        title = "Wi-Fi Only",
                        subtitle = "Pause model downloads on mobile data",
                        checked = state.wifiOnlyDownloads,
                        onCheckedChange = viewModel::onWifiOnlyChange
                    )
                }
            }
        }

        // Generation defaults
        item {
            Spacer(Modifier.height(16.dp))
            SectionHeader(title = "Generation Defaults")
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Temperature: ${state.temperature}", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Slider(value = state.temperature, onValueChange = viewModel::onTemperatureChange, valueRange = 0f..1f)
                    Text("Top K: ${state.topK}", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Slider(value = state.topK.toFloat(), onValueChange = { viewModel.onTopKChange(it.toInt()) }, valueRange = 1f..100f)
                    Text("Max Tokens: ${state.maxTokens}", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Slider(value = state.maxTokens.toFloat(), onValueChange = { viewModel.onMaxTokensChange(it.toInt()) }, valueRange = 128f..2048f)
                }
            }
        }

        // Privacy
        item {
            Spacer(Modifier.height(16.dp))
            SectionHeader(title = "Privacy")
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = Primary)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Local-First", style = MaterialTheme.typography.titleSmall, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            Text("Your AI. Your Data. Your Device.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                    }
                }
            }
        }

        // Advanced
        item {
            Spacer(Modifier.height(16.dp))
            SectionHeader(title = "Advanced")
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingRow(
                        title = "Performance Monitor",
                        value = "Live metrics",
                        onClick = { onNavigate(Screen.Performance.route) }
                    )
                    HorizontalDivider(color = SurfaceHigh)
                    SettingRow(
                        title = "Device Benchmark",
                        value = "Measure performance",
                        onClick = { onNavigate(Screen.Benchmark.route) }
                    )
                    HorizontalDivider(color = SurfaceHigh)
                    SettingRow(
                        title = "Compare Models",
                        value = "A/B testing",
                        onClick = { onNavigate(Screen.Compare.route) }
                    )
                    HorizontalDivider(color = SurfaceHigh)
                    SettingRow(
                        title = "Downloads",
                        value = "Download queue",
                        onClick = { onNavigate(Screen.Downloads.route) }
                    )
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    // The default model is the one the chat auto-selects on launch. Pick it
    // directly here from the installed models instead of hiding the setting.
    if (showDefaultModelPicker) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDefaultModelPicker = false },
            title = { Text("Default Model") },
            text = {
                Column {
                    Text(
                        "The model the chat opens with when you start a new conversation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(
                            selected = state.defaultModelId == null,
                            onClick = {
                                viewModel.setDefaultModel(null)
                                showDefaultModelPicker = false
                            }
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Not set (auto-pick best)", color = TextPrimary)
                    }
                    state.installedModels.forEach { model ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(
                                selected = state.defaultModelId == model.id,
                                onClick = {
                                    viewModel.setDefaultModel(model.id)
                                    showDefaultModelPicker = false
                                }
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(model.name, color = TextPrimary)
                        }
                    }
                    if (state.installedModels.isEmpty()) {
                        Text(
                            "No models installed yet. Install a model first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showDefaultModelPicker = false }) {
                    Text("Done", color = Primary)
                }
            }
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            Text(value, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = TextSecondary)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}