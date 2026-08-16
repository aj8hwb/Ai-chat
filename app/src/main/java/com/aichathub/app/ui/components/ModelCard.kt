package com.aichathub.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.CompatibilityLevel
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.download.DownloadInfo
import com.aichathub.app.download.DownloadStatus
import com.aichathub.app.ui.theme.Error
import com.aichathub.app.ui.theme.GradientCyan
import com.aichathub.app.ui.theme.GradientPurple
import com.aichathub.app.ui.theme.SurfaceHigh
import com.aichathub.app.ui.theme.TextMuted
import com.aichathub.app.ui.theme.TextPrimary
import com.aichathub.app.ui.theme.TextSecondary
import com.aichathub.app.util.Formatters

/**
 * Reusable model card with a built-in Model Store experience: shows the
 * download progress / controls while downloading and a [primaryAction]
 * (Chat/Details) otherwise. Rendered on Home, Model Store and My Models.
 */
@Composable
fun ModelCard(
    model: CatalogModel,
    lifecycleState: ModelLifecycleState,
    compatibility: CompatibilityLevel?,
    modifier: Modifier = Modifier,
    recommended: Boolean = false,
    download: DownloadInfo? = null,
    showSize: Boolean = true,
    onClick: () -> Unit,
    primaryAction: (@Composable () -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onPause: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null
) {
    AppCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ModelIcon(model = model, size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        model.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (recommended) {
                        Spacer(Modifier.width(6.dp))
                        RecommendedTag()
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    model.provider,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (model.purposeTitle.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${model.purposeEmoji} ${model.purposeTitle}",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showSize) {
                        Text(
                            Formatters.bytes(model.fileSizeBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                    Text(
                        model.parameters,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    if (compatibility != null) {
                        Spacer(Modifier.width(2.dp))
                        CompatibilityBadge(level = compatibility)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            if (onMore != null) {
                IconButton(onClick = onMore) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = TextSecondary)
                }
            } else if (primaryAction == null && onDownload == null) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Open",
                    tint = TextSecondary
                )
            }
        }

        val downloadActive = download != null && (
            download.status == DownloadStatus.DOWNLOADING ||
                download.status == DownloadStatus.QUEUED ||
                download.status == DownloadStatus.PAUSED ||
                download.status == DownloadStatus.VERIFYING
            )
        if (downloadActive) {
            DownloadProgressBlock(download = download!!, onPause = onPause, onResume = onResume, onCancel = onCancel)
        } else if (primaryAction != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                primaryAction()
            }
        } else if (onDownload != null &&
            (lifecycleState == ModelLifecycleState.NOT_INSTALLED || lifecycleState == ModelLifecycleState.DOWNLOADED)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                GradientButton(
                    text = "Download",
                    onClick = onDownload ?: {},
                    icon = Icons.Filled.Download
                )
            }
        } else {
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * Real-time download block: progress %, bytes, current speed, ETA and
 * pause / resume / cancel controls.
 */
@Composable
fun DownloadProgressBlock(
    download: DownloadInfo,
    onPause: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val verifying = download.status == DownloadStatus.VERIFYING
        val paused = download.status == DownloadStatus.PAUSED
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (verifying) "Verifying integrity…"
                else if (paused) "Paused · ${download.progress}%"
                else "Downloading · ${download.progress}%",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                modifier = Modifier.weight(1f)
            )
            if (download.speedBytesPerSec > 0 && !verifying) {
                Text(
                    "${Formatters.bytes(download.speedBytesPerSec)}/s",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { if (verifying) 1f else (download.progress / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = if (verifying) Color(0xFF22D3EE) else Color(0xFF8B5CF6),
            trackColor = SurfaceHigh,
            strokeCap = StrokeCap.Round
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${Formatters.bytes(download.downloadedBytes)} / ${Formatters.bytes(download.totalBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.weight(1f)
            )
            if (download.etaSeconds > 0 && !verifying) {
                Text(
                    "ETA ${etaText(download.etaSeconds)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
                Spacer(Modifier.width(10.dp))
            }
            if (!verifying) {
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    if (paused) {
                        IconButton(onClick = onResume ?: {}, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Resume", tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    } else {
                        IconButton(onClick = onPause ?: {}, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Filled.Pause, contentDescription = "Pause", tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                    IconButton(onClick = onCancel ?: {}, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Filled.Cancel, contentDescription = "Cancel", tint = Error, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

private fun etaText(seconds: Long): String = when {
    seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds}s"
}

@Composable
fun RecommendedTag() {
    Box(
        modifier = Modifier
            .background(Color(0xFF8B5CF6).copy(alpha = 0.15f), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            "Recommended",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFA78BFA),
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Model identity icon with a gradient derived from the model id hash.
 */
@Composable
fun ModelIcon(
    model: CatalogModel,
    size: androidx.compose.ui.unit.Dp = 48.dp
) {
    val gradients = listOf(GradientPurple, GradientCyan, listOf(Color(0xFFF472B6), Color(0xFFA78BFA)))
    val idx = (model.id.hashCode() and Int.MAX_VALUE) % gradients.size
    val gradient = gradients[idx]

    Box(
        modifier = Modifier
            .size(size)
            .background(Brush.linearGradient(gradient), RoundedCornerShape((size.value * 0.28f).dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = model.name.split(" ").firstOrNull()?.firstOrNull()?.toString() ?: "AI",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun ModelActionButtons(
    lifecycleState: ModelLifecycleState,
    onDownload: () -> Unit,
    onChat: () -> Unit,
    onMore: () -> Unit
) {
    when (lifecycleState) {
        ModelLifecycleState.NOT_INSTALLED, ModelLifecycleState.DOWNLOADED ->
            GradientButton(
                text = "Download",
                onClick = onDownload,
                icon = Icons.Filled.Download
            )
        ModelLifecycleState.INSTALLED ->
            GradientButton(
                text = "Open Chat",
                onClick = onChat,
                icon = Icons.Filled.ChatBubbleOutline
            )
        ModelLifecycleState.READY, ModelLifecycleState.RUNNING ->
            GradientButton(
                text = "Chat",
                onClick = onChat,
                icon = Icons.Filled.ChatBubbleOutline
            )
        ModelLifecycleState.ERROR ->
            GradientButton(text = "Retry", onClick = onDownload)
        else -> {}
    }
}