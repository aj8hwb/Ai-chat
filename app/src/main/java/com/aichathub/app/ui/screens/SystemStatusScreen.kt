package com.aichathub.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichathub.app.domain.model.AiMemoryBudget
import com.aichathub.app.domain.model.DeviceProfile
import com.aichathub.app.ui.components.AppCard
import com.aichathub.app.ui.components.MetricRow
import com.aichathub.app.ui.components.ProgressBlock
import com.aichathub.app.ui.components.SectionHeader
import com.aichathub.app.ui.theme.Primary
import com.aichathub.app.ui.theme.Success
import com.aichathub.app.ui.theme.TextPrimary
import com.aichathub.app.ui.theme.TextSecondary
import com.aichathub.app.ui.theme.Warning
import com.aichathub.app.util.Formatters

@Composable
fun SystemStatusScreen(
    viewModel: HomeViewModel = viewModel()
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
                Text("System Status", style = MaterialTheme.typography.headlineMedium, color = TextPrimary, modifier = Modifier.weight(1f))
                IconButton(onClick = viewModel::analyze) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = TextSecondary)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (state.analyzing || state.deviceProfile == null) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Primary)
                        Spacer(Modifier.height(12.dp))
                        Text("Analyzing your device…", color = TextSecondary)
                    }
                }
            }
        } else {
            // Device AI score
            val profile = state.deviceProfile!!
            item {
                DeviceScoreCard(profile = profile, budget = state.memoryBudget)
                Spacer(Modifier.height(16.dp))
            }

            // AI Memory Budget
            state.memoryBudget?.let { budget ->
                item {
                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            SectionHeader(title = "AI Memory Budget")
                            Text(
                                "Available for AI: ${budget.modelMemoryGb.toString().take(3)} GB",
                                style = MaterialTheme.typography.titleLarge,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Recommended memory budget for model loading.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            Spacer(Modifier.height(10.dp))
                            val maxSafe = budget.usableBytes.toFloat().coerceAtLeast(1f)
                            val total = maxOf(budget.availableBytes.toFloat(), 1f)
                            ProgressBlock(
                                label = "Available RAM",
                                progress = budget.availableBytes.toFloat() / total,
                                valueText = "${budget.availableGb.toString().take(3)} GB"
                            )
                            Spacer(Modifier.height(10.dp))
                            ExpandableDetails(budget = budget)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            // Metrics
            item {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SectionHeader(title = "System Metrics")
                        MetricRow("RAM", "${profile.availableRamGb.toString().take(3)} / ${profile.totalRamGb.toString().take(3)} GB", icon = Icons.Filled.Memory)
                        MetricRow("Storage Free", Formatters.bytes(profile.storageAvailableBytes), icon = Icons.Filled.Storage)
                        MetricRow("CPU Cores", profile.cpuCores.toString(), icon = Icons.Filled.PhoneAndroid)
                        MetricRow("ABI", profile.abi)
                        MetricRow("Android API", profile.androidVersion.toString())
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceScoreCard(
    profile: DeviceProfile,
    budget: AiMemoryBudget?
) {
    val ramRatio = profile.availableRamGb / profile.totalRamGb.coerceAtLeast(1f)
    val score = (ramRatio * 100).toInt().coerceIn(0, 100)
    val scoreLabel = when {
        score >= 75 -> "Excellent"
        score >= 50 -> "Good"
        score >= 30 -> "Limited"
        else -> "Low"
    }
    val scoreColor = when (scoreLabel) {
        "Excellent" -> Success
        "Good" -> Primary
        else -> Warning
    }

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(88.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = { score / 100f },
                    modifier = Modifier.size(88.dp),
                    color = scoreColor,
                    trackColor = Color(0xFF2A2A3A),
                    strokeWidth = 8.dp
                )
                Text("$score%", style = MaterialTheme.typography.titleLarge, color = TextPrimary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text("RAM Headroom", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Text(scoreLabel, style = MaterialTheme.typography.headlineSmall, color = scoreColor, fontWeight = FontWeight.Bold)
                Text(
                    "Share of RAM free right now. This is a live snapshot, not a benchmark score.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun ExpandableDetails(budget: AiMemoryBudget) {
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column {
        Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF232330), RoundedCornerShape(12.dp)).padding(12.dp)) {
            Text(
                "Advanced details",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (expanded) "▲" else "▼",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            MetricRow("Total available", Formatters.bytes(budget.availableBytes))
            MetricRow("Reserved overhead", Formatters.bytes(budget.reservedBytes))
            MetricRow("Runtime overhead", Formatters.bytes(budget.runtimeOverheadBytes))
            MetricRow("Safety reserve", Formatters.bytes(budget.safetyReserveBytes))
            MetricRow("Safe AI budget", Formatters.bytes(budget.modelMemoryBytes))
        }
    }
}