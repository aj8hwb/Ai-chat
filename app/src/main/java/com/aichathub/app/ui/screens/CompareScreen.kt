package com.aichathub.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichathub.app.ui.components.AppCard
import com.aichathub.app.ui.components.EmptyState
import com.aichathub.app.ui.components.GradientButton
import com.aichathub.app.ui.components.ModelIcon
import com.aichathub.app.ui.theme.Error
import com.aichathub.app.ui.theme.Primary
import com.aichathub.app.ui.theme.SurfaceHigh
import com.aichathub.app.ui.theme.TextPrimary
import com.aichathub.app.ui.theme.TextSecondary

@Composable
fun CompareScreen(
    viewModel: CompareViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Compare Models", style = MaterialTheme.typography.headlineMedium, color = TextPrimary, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.CompareArrows, contentDescription = null, tint = Primary)
            }
            Spacer(Modifier.height(4.dp))
            Text("Run the same prompt on your installed models.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Spacer(Modifier.height(12.dp))
        }

        if (state.models.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.CompareArrows,
                    title = "No models to compare",
                    description = "Install at least one model from the Model Store first.",
                    modifier = Modifier.padding(vertical = 32.dp)
                )
            }
        } else {
            item {
                Text(
                    "Models: ${state.models.joinToString(", ") { it.name }}",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.prompt,
                    onValueChange = viewModel::onPromptChange,
                    label = { Text("Prompt", color = TextSecondary) },
                    minLines = 3,
                    maxLines = 6,
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
                Spacer(Modifier.height(12.dp))
                GradientButton(
                    text = if (state.running) "Comparing…" else "Compare",
                    onClick = viewModel::runComparison,
                    enabled = !state.running,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
            }

            state.error?.let { err ->
                item {
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = Error,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }

            state.results.forEach { result ->
                item {
                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val model = com.aichathub.app.data.model.LocalModelCatalog.byId(result.modelId)
                                if (model != null) {
                                    ModelIcon(model = model, size = 36.dp)
                                    Spacer(Modifier.width(10.dp))
                                }
                                Text(result.modelName, style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text(
                                    String.format("%.1f tok/s", result.tokensPerSecond),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (result.failed) Error else TextSecondary
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                result.output,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (result.failed) Error else TextPrimary
                            )
                            Spacer(Modifier.height(6.dp))
                            Text("${result.tokens} tokens", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}