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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
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
import com.aichathub.app.download.DownloadInfo
import com.aichathub.app.download.DownloadStatus
import com.aichathub.app.ui.components.AppCard
import com.aichathub.app.ui.components.DownloadProgressBlock
import com.aichathub.app.ui.components.EmptyState
import com.aichathub.app.ui.theme.Success
import com.aichathub.app.util.Formatters

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val active = state.downloads.filter {
        it.status == DownloadStatus.DOWNLOADING ||
            it.status == DownloadStatus.PAUSED ||
            it.status == DownloadStatus.QUEUED ||
            it.status == DownloadStatus.VERIFYING
    }
    val completed = state.downloads.filter { it.status == DownloadStatus.COMPLETED }
    val failed = state.downloads.filter { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(16.dp))
            Text("Download Manager", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                "${active.size} active · ${completed.size} completed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.downloads.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Download,
                        title = "No Downloads",
                        description = "Your download queue is empty.\nInstall a model from the Model Store.",
                        modifier = Modifier.padding(vertical = 40.dp)
                    )
                }
            } else {
                if (active.isNotEmpty()) {
                    item {
                        Text("Active", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    }
                    items(active, key = { it.modelId }) { d ->
                        ActiveDownloadCard(
                            download = d,
                            onPause = { viewModel.pause(d.modelId) },
                            onResume = { viewModel.resume(d.modelId) },
                            onCancel = { viewModel.cancel(d.modelId) }
                        )
                    }
                }
                if (completed.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text("Completed", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    }
                    items(completed, key = { it.modelId }) { d ->
                        CompletedCard(d)
                    }
                }
                if (failed.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text("Failed / Cancelled", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    }
                    items(failed, key = { it.modelId }) { d ->
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(d.modelName, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    d.error ?: "Cancelled",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                    if (d.status == DownloadStatus.FAILED) {
                                        com.aichathub.app.ui.components.GradientButton(
                                            text = "Retry",
                                            onClick = { viewModel.resume(d.modelId) },
                                            icon = Icons.Filled.Download
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveDownloadCard(
    download: DownloadInfo,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(download.modelName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Text(
                    when (download.status) {
                        DownloadStatus.VERIFYING -> "Verifying"
                        DownloadStatus.PAUSED -> "Paused"
                        DownloadStatus.DOWNLOADING -> "Downloading"
                        else -> "Queued"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (download.status == DownloadStatus.PAUSED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(10.dp))
            DownloadProgressBlock(
                download = download,
                onPause = onPause,
                onResume = onResume,
                onCancel = onCancel
            )
            if (download.status != DownloadStatus.VERIFYING) {
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (download.networkType != null) {
                        Stat(label = "Network", value = download.networkType)
                    }
                    if (download.segments > 1) {
                        Stat(label = "Segments", value = "${download.segments} parallel")
                    }
                    if (download.averageSpeedBytesPerSec > 0) {
                        Stat(label = "Avg speed", value = "${Formatters.bytes(download.averageSpeedBytesPerSec)}/s")
                    }
                    if (download.etaSeconds > 0) {
                        Stat(label = "ETA", value = etaText(download.etaSeconds))
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
    }
}

private fun etaText(seconds: Long): String = when {
    seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds}s"
}

@Composable
private fun CompletedCard(download: DownloadInfo) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Success, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(download.modelName, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text("Verified & installed · ${Formatters.bytes(download.totalBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("✓ Ready", style = MaterialTheme.typography.labelMedium, color = Success)
        }
    }
}