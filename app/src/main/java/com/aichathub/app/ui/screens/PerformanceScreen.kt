package com.aichathub.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cpu
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.aichathub.app.ui.components.MetricRow
import com.aichathub.app.ui.components.ProgressBlock
import com.aichathub.app.ui.components.SectionHeader
import com.aichathub.app.ui.theme.Secondary
import com.aichathub.app.ui.theme.Success
import com.aichathub.app.ui.theme.TextPrimary
import com.aichathub.app.ui.theme.TextSecondary

@Composable
fun PerformanceScreen(
    viewModel: PerformanceViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Performance", style = MaterialTheme.typography.headlineMedium, color = TextPrimary, modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).background(if (state.active) Success else Color(0xFF6B6B7D), CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.active) "Live" else "Idle", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        item {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Generation Speed", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.filled.Speed, contentDescription = null, tint = Secondary, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            String.format("%.1f tok/s", state.tokensPerSecond),
                            style = MaterialTheme.typography.headlineMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard(
                    label = "Model Memory",
                    value = com.aichathub.app.util.Formatters.bytes(state.modelMemoryBytes),
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    label = "Tokens Generated",
                    value = state.tokensGenerated.toString(),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        item {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionHeader(title = "Context Usage")
                    ProgressBlock(
                        label = "Context",
                        progress = if (state.contextMax > 0) state.contextUsed.toFloat() / state.contextMax.toFloat() else 0f,
                        valueText = "${state.contextUsed} / ${state.contextMax}"
                    )
                    Spacer(Modifier.height(8.dp))
                    MetricRow("Active Model", state.activeModelName ?: "None")
                    MetricRow("Runtime", "LiteRT-LM")
                }
            }
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    AppCard(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, color = TextPrimary, fontWeight = FontWeight.Bold)
        }
    }
}