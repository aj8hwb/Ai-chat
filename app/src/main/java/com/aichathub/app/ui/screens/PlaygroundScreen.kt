package com.aichathub.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.ui.components.AppCard
import com.aichathub.app.ui.components.ErrorState
import com.aichathub.app.ui.components.GradientButton
import com.aichathub.app.ui.components.ModelIcon
import com.aichathub.app.ui.navigation.Screen

@Composable
fun PlaygroundScreen(
    viewModel: PlaygroundViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(16.dp))
            Text("Model Playground", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text("Test models with custom prompts and parameters", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
        }

        // Model selection
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.models) { model ->
                val installed = state.states[model.id] != null
                FilterChip(
                    selected = state.selectedModelId == model.id,
                    onClick = { viewModel.selectModel(model.id) },
                    label = { Text("${model.name}${if (installed) "" else " (not installed)"}") },
                    enabled = installed,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Prompt
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    OutlinedTextField(
                        value = state.prompt,
                        onValueChange = viewModel::onPromptChange,
                        label = { Text("Prompt", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        minLines = 4,
                        maxLines = 8,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            // Parameters
            item {
                AppCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Parameters", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(8.dp))
                        Text("Temperature: ${state.temperature}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(
                            value = state.temperature,
                            onValueChange = viewModel::onTemperatureChange,
                            valueRange = 0f..1f,
                            enabled = !state.running
                        )
                        Text("Max Tokens: ${state.maxTokens}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(
                            value = state.maxTokens.toFloat(),
                            onValueChange = { viewModel.onMaxTokensChange(it.toInt()) },
                            valueRange = 128f..2048f,
                            enabled = !state.running
                        )
                    }
                }
            }

            // Run
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (state.running) {
                        GradientButton(
                            text = "Stop",
                            onClick = viewModel::stop,
                            icon = Icons.Filled.Stop,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        GradientButton(
                            text = "Run",
                            onClick = viewModel::run,
                            icon = Icons.Filled.PlayArrow,
                            enabled = state.selectedModelId != null && state.prompt.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Output
            if (state.output.isNotBlank()) {
                item {
                    AppCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Result", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(8.dp))
                            Text(state.output, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            state.stats?.let {
                                Spacer(Modifier.height(10.dp))
                                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            state.error?.let { err ->
                item {
                    ErrorState(title = "Playground Error", message = err, modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
        }
    }
}