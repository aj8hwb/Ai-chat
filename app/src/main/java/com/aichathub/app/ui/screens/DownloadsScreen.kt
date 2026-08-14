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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.aichathub.app.ui.components.EmptyState
import com.aichathub.app.ui.components.ProgressBlock
import com.aichathub.app.ui.theme.Error
import com.aichathub.app.ui.theme.TextPrimary
import com.aichathub.app.ui.theme.TextSecondary
import com.aichathub.app.util.Formatters

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val active = state.downloads.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.QUEUED }
    val completed = state.downloads.filter { it.status == DownloadStatus.COMPLETED }
    val failed = state.downloads.filter { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(16.dp))
            Text("Download Manager", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
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
                        Text("Active", style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
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
                        Text("Completed", style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    }
                    items(completed, key = { it.modelId }) { d ->
                        CompletedCard(d)
                    }
                }
                if (failed.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text("Failed / Cancelled", style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    }
                    items(failed, key = { it.modelId }) { d ->
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(d.modelName, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                                Text(
                                    d.error ?: "Cancelled",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Error
                                )
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
                Text(download.modelName, style = MaterialTheme.typography.titleMedium, color = TextPrimary, modifier = Modifier.weight(1f))
                Text(
                    if (download.status == DownloadStatus.PAUSED) "Paused" else "Downloading",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (download.status == DownloadStatus.PAUSED) TextSecondary else TextPrimary
                )
            }
            Spacer(Modifier.height(10.dp))
            ProgressBlock(
                label = "${Formatters.bytes(download.downloadedBytes)} / ${Formatters.bytes(download.totalBytes)}",
                progress = download.progress / 100f,
                valueText = "${download.progress}%"
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (download.status == DownloadStatus.DOWNLOADING) {
                    IconButton(onClick = onPause) {
                        Icon(Icons.Filled.Pause, contentDescription = "Pause", tint = TextSecondary)
                    }
                } else {
                    IconButton(onClick = onResume) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Resume", tint = TextSecondary)
                    }
                }
                IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.Cancel, contentDescription = "Cancel", tint = Error)
                }
                Spacer(Modifier.width(8.dp))
                if (download.speedBytesPerSec > 0) {
                    Text(
                        "${Formatters.bytes(download.speedBytesPerSec)}/s",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
            }
        }
    }
}

@Composable
private fun CompletedCard(download: DownloadInfo) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(download.modelName, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Text("Completed · ${Formatters.bytes(download.totalBytes)}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Text("✓ Ready", style = MaterialTheme.typography.labelMedium, color = com.aichathub.app.ui.theme.Success)
        }
    }
}