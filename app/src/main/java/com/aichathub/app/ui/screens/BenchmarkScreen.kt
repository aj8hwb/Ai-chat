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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichathub.app.ui.components.AppCard
import com.aichathub.app.ui.components.GradientButton
import com.aichathub.app.ui.components.MetricRow
import com.aichathub.app.ui.components.SectionHeader
import com.aichathub.app.ui.theme.Primary
import com.aichathub.app.ui.theme.TextPrimary
import com.aichathub.app.ui.theme.TextSecondary

@Composable
fun BenchmarkScreen(
    viewModel: BenchmarkViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
    ) {
        item {
            Text("Device Benchmark", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text("Measure how your device performs with local AI.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Spacer(Modifier.height(12.dp))
        }

        item {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Model to benchmark", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                    Text(
                        state.selectedModel?.name ?: "No installed model available",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (state.selectedModel == null) {
                        Spacer(Modifier.height(6.dp))
                        Text("Install a model first to run a real benchmark.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (state.running) {
            item {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = Primary)
                        Spacer(Modifier.height(12.dp))
                        Text(state.statusText, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        val result = state.result
        if (result != null) {
            item {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SectionHeader(title = "Result")
                        MetricRow("Speed", String.format("%.1f tok/s", result.tokensPerSecond))
                        MetricRow("Total time", String.format("%.0f ms", result.generationMs))
                        MetricRow("Total tokens", result.tokens.toString())
                        MetricRow("Memory used", com.aichathub.app.util.Formatters.bytes(result.memoryBytes))
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        item {
            GradientButton(
                text = if (state.running) "Benchmarking…" else "Run Benchmark",
                onClick = viewModel::runBenchmark,
                icon = Icons.Filled.PlayArrow,
                enabled = state.selectedModel != null && !state.running,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}