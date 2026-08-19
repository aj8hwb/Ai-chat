package com.aichathub.app.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichathub.app.domain.model.CompatibilityLevel
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.download.DownloadStatus
import com.aichathub.app.ui.components.AppCard
import com.aichathub.app.ui.components.CompatibilityBadge
import com.aichathub.app.ui.components.DownloadProgressBlock
import com.aichathub.app.ui.components.ErrorState
import com.aichathub.app.ui.components.GradientButton
import com.aichathub.app.ui.components.MetricRow
import com.aichathub.app.ui.components.ModelIcon
import com.aichathub.app.ui.components.SectionHeader
import com.aichathub.app.ui.navigation.Screen
import com.aichathub.app.ui.theme.Error
import com.aichathub.app.ui.theme.ErrorContainer
import com.aichathub.app.ui.theme.Heavy
import com.aichathub.app.ui.theme.HeavyContainer
import com.aichathub.app.ui.theme.Primary
import com.aichathub.app.ui.theme.SurfaceElevated
import com.aichathub.app.ui.theme.TextPrimary
import com.aichathub.app.ui.theme.TextSecondary
import com.aichathub.app.util.Formatters
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable

@Composable
fun ModelDetailsScreen(
    modelId: String,
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
    viewModel: ModelDetailsViewModel = viewModel()
) {
    LaunchedEffect(modelId) { viewModel.load(modelId) }
    val state by viewModel.state.collectAsState()
    val model = state.model
    val uriHandler = LocalUriHandler.current
    var pendingDownload by remember { mutableStateOf(false) }

    if (model == null) {
        ErrorState(title = "Model not found", message = "This model is not in the catalog.", actionLabel = "Back", onAction = onBack)
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Text("Model Details", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            // Hero
            item {
                AppCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        ModelIcon(model = model, size = 56.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(model.name, style = MaterialTheme.typography.headlineSmall, color = TextPrimary, fontWeight = FontWeight.Bold)
                            Text("By ${model.provider}", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                            Spacer(Modifier.height(4.dp))
                            Text("${model.parameters} · ${model.category} · ${Formatters.bytes(model.fileSizeBytes)}", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                        }
                    }
                    HorizontalDivider(color = androidx.compose.ui.graphics.Color(0xFF2A2A3A), modifier = Modifier.padding(horizontal = 16.dp))
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        state.compatibility?.let {
                            CompatibilityBadge(level = it)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(model.runtime, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    }
                }
            }

            // Warning state
            state.warningMessage?.let { warning ->
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
                            .background(HeavyContainer, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = Heavy)
                        Spacer(Modifier.width(10.dp))
                        Text(warning, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }
            }

            // Download progress
            state.download?.let { d ->
                if (d.status == DownloadStatus.DOWNLOADING ||
                    d.status == DownloadStatus.PAUSED ||
                    d.status == DownloadStatus.QUEUED ||
                    d.status == DownloadStatus.VERIFYING
                ) {
                    item {
                        AppCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Downloading", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                                Spacer(Modifier.height(10.dp))
                                DownloadProgressBlock(
                                    download = d,
                                    onPause = viewModel::pauseDownload,
                                    onResume = viewModel::resumeDownload,
                                    onCancel = viewModel::cancelDownload
                                )
                            }
                        }
                    }
                }
            }

            // Description
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    SectionHeader(title = "Overview")
                    Text(model.description, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                }
            }

            // Purpose
            if (model.purposeTitle.isNotBlank()) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        SectionHeader(title = "Purpose")
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "${model.purposeEmoji} ${model.purposeTitle}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (model.primaryPurpose.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(model.primaryPurpose, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                }
                                if (model.bestFor.isNotBlank()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text("Best for: ${model.bestFor}", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                                }
                                if (model.strengths.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text("Strengths", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                                    model.strengths.forEach { s ->
                                        Text("• $s", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                    }
                                }
                                if (model.limitations.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text("Limitations", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                                    model.limitations.forEach { l ->
                                        Text("• $l", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Compatibility
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    SectionHeader(title = "Compatibility")
                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            MetricRow("RAM", levelText(state.compatibility), valueColor = levelColor(state.compatibility))
                            MetricRow("Estimated RAM", Formatters.bytes(model.estimatedMemoryBytes))
                            MetricRow("Runtime", model.runtime)
                            MetricRow("Format", model.format.extension.uppercase())
                            MetricRow("Storage", Formatters.bytes(model.fileSizeBytes))
                            if (model.checksumSha256 != null) {
                                MetricRow("Checksum", "SHA-256 verified", valueColor = com.aichathub.app.ui.theme.Success)
                            }
                            state.recommendation?.quantizationNote?.let {
                                MetricRow("Note", it, valueColor = Heavy)
                            }
                        }
                    }
                }
            }

            // Model information
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    SectionHeader(title = "Model Information")
                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            MetricRow("Parameters", model.parameters)
                            MetricRow("Quantization", model.quantization)
                            MetricRow("Context Length", "${model.contextLength} tokens")
                            MetricRow("License", model.license, valueColor = TextSecondary)
                            MetricRow("License Type", model.licenseType)
                        }
                    }
                }
            }

            // Source
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    SectionHeader(title = "Source")
                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(model.sourceNote ?: "Official source", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            Spacer(Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    uriHandler.openUri(model.officialRepositoryUrl)
                                }
                            ) {
                                Text("View repository", style = MaterialTheme.typography.labelLarge, color = Primary, modifier = Modifier.weight(1f))
                                Icon(Icons.Filled.OpenInNew, contentDescription = null, tint = Primary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }

        // Bottom action bar
        Box(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.lifecycle) {
                    ModelLifecycleState.INSTALLED, ModelLifecycleState.READY, ModelLifecycleState.RUNNING ->
                        GradientButton(
                            text = "Open Chat",
                            onClick = onOpenChat,
                            icon = Icons.Filled.ChatBubbleOutline,
                            modifier = Modifier.fillMaxWidth()
                        )
                    ModelLifecycleState.DOWNLOADING, ModelLifecycleState.VERIFYING -> {}
                    else ->
                        GradientButton(
                            text = "Download",
                            onClick = { pendingDownload = true },
                            icon = Icons.Filled.Download,
                            modifier = Modifier.fillMaxWidth()
                        )
                }
                if (state.lifecycle == ModelLifecycleState.INSTALLED ||
                    state.lifecycle == ModelLifecycleState.READY ||
                    state.lifecycle == ModelLifecycleState.RUNNING
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        TextButton(onClick = {
                            if (state.isDefaultModel) viewModel.clearDefault() else viewModel.setAsDefault()
                        }) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = null,
                                tint = if (state.isDefaultModel) Primary else TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (state.isDefaultModel) "Default model" else "Set as default",
                                color = if (state.isDefaultModel) Primary else TextSecondary
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = viewModel::deleteModel) {
                            Icon(Icons.Filled.Delete, contentDescription = null, tint = Error, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Delete Model", color = Error)
                        }
                    }
                }
            }
        }
    }

    // Large downloads consume significant storage (and possibly mobile data) —
    // confirm before starting them.
    if (pendingDownload && model != null) {
        AlertDialog(
            onDismissRequest = { pendingDownload = false },
            title = { Text("Download ${model.name}?") },
            text = {
                Text(
                    "This is a ${Formatters.bytes(model.fileSizeBytes)} file. It will use storage (and mobile data if you're not on Wi-Fi). Continue?",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.startDownload()
                    pendingDownload = false
                }) { Text("Download", color = Primary) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDownload = false }) { Text("Cancel", color = TextSecondary) }
            }
        )
    }
}

private fun levelText(level: CompatibilityLevel?): String = when (level) {
    CompatibilityLevel.EXCELLENT -> "🟢 Excellent"
    CompatibilityLevel.RECOMMENDED -> "🟢 Recommended"
    CompatibilityLevel.USABLE -> "🟡 Good"
    CompatibilityLevel.HEAVY -> "🟠 Heavy"
    CompatibilityLevel.NOT_RECOMMENDED -> "🔴 Not Recommended"
    null -> "Unknown"
}
private fun levelColor(level: CompatibilityLevel?): Color = when (level) {
    CompatibilityLevel.EXCELLENT, CompatibilityLevel.RECOMMENDED -> Color(0xFF34D399)
    CompatibilityLevel.USABLE -> Color(0xFF22D3EE)
    CompatibilityLevel.HEAVY -> Heavy
    CompatibilityLevel.NOT_RECOMMENDED -> Error
    null -> TextSecondary
}