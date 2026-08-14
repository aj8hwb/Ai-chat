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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichathub.app.ui.components.AppCard
import com.aichathub.app.ui.components.GradientButton
import com.aichathub.app.ui.components.SectionHeader
import com.aichathub.app.ui.theme.Primary
import com.aichathub.app.ui.theme.SurfaceHigh
import com.aichathub.app.ui.theme.TextPrimary
import com.aichathub.app.ui.theme.TextSecondary

@Composable
fun ChatSettingsScreen(
    viewModel: SettingsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
    ) {
        item {
            Text("Chat Settings", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Text("Generation parameters applied to new messages", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Spacer(Modifier.height(12.dp))
        }

        item {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Temperature", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                    Text("${state.temperature}", style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Slider(value = state.temperature, onValueChange = viewModel::onTemperatureChange, valueRange = 0f..1f)
                    Spacer(Modifier.height(6.dp))
                    Text("Top P", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                    Text("${state.topP}", style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Slider(value = state.topP, onValueChange = viewModel::onTopPChange, valueRange = 0.1f..1f)
                    Spacer(Modifier.height(6.dp))
                    Text("Top K", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                    Text("${state.topK}", style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Slider(value = state.topK.toFloat(), onValueChange = { viewModel.onTopKChange(it.toInt()) }, valueRange = 1f..100f)
                    Spacer(Modifier.height(6.dp))
                    Text("Max Tokens", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                    Text("${state.maxTokens}", style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Slider(value = state.maxTokens.toFloat(), onValueChange = { viewModel.onMaxTokensChange(it.toInt()) }, valueRange = 128f..2048f)
                }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
            SectionHeader(title = "System Prompt")
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = state.systemPrompt,
                        onValueChange = viewModel::onSystemPromptChange,
                        label = { Text("System prompt", color = TextSecondary) },
                        minLines = 4,
                        maxLines = 8,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = SurfaceHigh,
                            focusedContainerColor = SurfaceHigh,
                            unfocusedContainerColor = SurfaceHigh,
                            cursorColor = Primary
                        )
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            GradientButton(
                text = "Settings saved locally",
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}