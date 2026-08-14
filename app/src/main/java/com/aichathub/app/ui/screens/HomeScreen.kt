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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichathub.app.domain.model.Recommendation
import com.aichathub.app.ui.components.AppCard
import com.aichathub.app.ui.components.CompatibilityBadge
import com.aichathub.app.ui.components.GradientButton
import com.aichathub.app.ui.components.GradientHeaderCard
import com.aichathub.app.ui.components.ModelCard
import com.aichathub.app.ui.components.ModelIcon
import com.aichathub.app.ui.components.ProgressBlock
import com.aichathub.app.ui.components.SectionHeader
import com.aichathub.app.ui.navigation.Screen
import com.aichathub.app.ui.theme.GradientPrimary
import com.aichathub.app.ui.theme.Primary
import com.aichathub.app.ui.theme.Secondary
import com.aichathub.app.ui.theme.Success
import com.aichathub.app.ui.theme.SurfaceElevated
import com.aichathub.app.ui.theme.TextPrimary
import com.aichathub.app.ui.theme.TextSecondary
import com.aichathub.app.ui.theme.Warning
import com.aichathub.app.util.Formatters
import com.aichathub.app.domain.model.ModelLifecycleState

@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 100.dp)
    ) {
        // Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "AI Chat Hub",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextSecondary
                    )
                    Text(
                        "Your Local AI",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary
                    )
                }
                IconButton(onClick = { onNavigate(Screen.SystemStatus.route) }) {
                    Icon(Icons.filled.Speed, contentDescription = "System Status", tint = Secondary)
                }
            }
        }

        if (state.analyzing) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Primary)
                        Spacer(Modifier.height(16.dp))
                        Text("Analyzing your device…", color = TextSecondary)
                    }
                }
            }
        } else {
            // Hero card — current state
            item {
                val readyCount = state.installedStates.values.count {
                    it == ModelLifecycleState.READY || it == ModelLifecycleState.RUNNING
                }
                HeroCard(
                    installedCount = state.installedStates.size,
                    readyCount = readyCount,
                    onOpenChat = { onNavigate(Screen.Chat.route) },
                    onExplore = { onNavigate(Screen.Models.route) }
                )
            }

            // Quick actions
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    QuickAction(
                        icon = Icons.filled.Add,
                        label = "New Chat",
                        onClick = { onNavigate(Screen.Chat.route) },
                        modifier = Modifier.weight(1f)
                    )
                    QuickAction(
                        icon = Icons.filled.Widgets,
                        label = "Model Store",
                        onClick = { onNavigate(Screen.Models.route) },
                        modifier = Modifier.weight(1f)
                    )
                    QuickAction(
                        icon = Icons.filled.PlayArrow,
                        label = "Playground",
                        onClick = { onNavigate(Screen.Playground.route) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // System overview
            item {
                SystemOverview(
                    profile = state.deviceProfile,
                    budget = state.memoryBudget,
                    onViewSystem = { onNavigate(Screen.SystemStatus.route) }
                )
            }

            // Recommended for you
            item {
                SectionHeader(
                    title = "Recommended for You",
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
            val recs = state.recommendations
            items(count = recs.size) { index ->
                val rec = recs[index]
                RecommendationCard(
                    recommendation = rec,
                    isInstalled = state.installedStates[rec.model.id] != null,
                    onClick = { onNavigate(Screen.ModelDetails.routeFor(rec.model.id)) }
                )
            }
        }
    }
}

@Composable
private fun HeroCard(
    installedCount: Int,
    readyCount: Int,
    onOpenChat: () -> Unit,
    onExplore: () -> Unit
) {
    GradientHeaderCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Local AI Ready",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (readyCount > 0)
                            "$readyCount model ready · $installedCount installed"
                        else
                            if (installedCount > 0) "$installedCount models installed" else "No models installed yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(8.dp).background(Success, CircleShape)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Ready", color = TextPrimary, style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GradientButton(
                    text = if (installedCount > 0) "Open Chat" else "Explore Models",
                    onClick = if (installedCount > 0) onOpenChat else onExplore,
                    icon = if (installedCount > 0) Icons.filled.ChatBubbleOutline else Icons.filled.Widgets,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun QuickAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = Primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun SystemOverview(
    profile: com.aichathub.app.domain.model.DeviceProfile?,
    budget: com.aichathub.app.domain.model.AiMemoryBudget?,
    onViewSystem: () -> Unit
) {
    if (profile == null) return
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        SectionHeader(title = "System Overview", actionText = "View System Status", onAction = onViewSystem)
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    MetricBox(
                        icon = Icons.filled.Memory,
                        value = "${profile.availableRamGb.toString().take(3)} / ${profile.totalRamGb.toString().take(3)} GB",
                        label = "RAM",
                        modifier = Modifier.weight(1f)
                    )
                    MetricBox(
                        icon = Icons.filled.Storage,
                        value = Formatters.bytes(profile.storageAvailableBytes),
                        label = "Storage Free",
                        modifier = Modifier.weight(1f)
                    )
                }
                if (budget != null) {
                    Spacer(Modifier.height(14.dp))
                    ProgressBlock(
                        label = "AI Memory Budget",
                        progress = budget.usableBytes.toFloat() / profile.availableRamBytes.toFloat(),
                        valueText = "${budget.usableGb.toString().take(3)} GB"
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricBox(
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Secondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(value, style = MaterialTheme.typography.labelLarge, color = TextPrimary, fontWeight = FontWeight.SemiBold)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

@Composable
private fun RecommendationCard(
    recommendation: Recommendation,
    isInstalled: Boolean,
    onClick: () -> Unit
) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModelIcon(model = recommendation.model, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        recommendation.model.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        recommendation.model.provider,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                CompatibilityBadge(level = recommendation.level)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                recommendation.reason,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            if (isInstalled) {
                Spacer(Modifier.height(8.dp))
                Text("✓ Installed", style = MaterialTheme.typography.labelMedium, color = Success)
            }
        }
    }
}