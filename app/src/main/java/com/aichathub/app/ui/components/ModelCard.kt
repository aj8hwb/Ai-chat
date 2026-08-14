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
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.CompatibilityLevel
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.ui.theme.GradientCyan
import com.aichathub.app.ui.theme.GradientPurple
import com.aichathub.app.ui.theme.SurfaceHigh
import com.aichathub.app.ui.theme.TextMuted
import com.aichathub.app.ui.theme.TextPrimary
import com.aichathub.app.ui.theme.TextSecondary
import com.aichathub.app.util.Formatters

/**
 * Reusable model card. Rendered identically on Home, Model Store and
 * My Models; the action area adapts to the model's lifecycle state.
 */
@Composable
fun ModelCard(
    model: CatalogModel,
    lifecycleState: ModelLifecycleState,
    compatibility: CompatibilityLevel?,
    modifier: Modifier = Modifier,
    recommended: Boolean = false,
    downloadProgress: Float? = null,
    downloadBytes: Long? = null,
    showSize: Boolean = true,
    onClick: () -> Unit,
    primaryAction: (@Composable () -> Unit)? = null,
    onMore: (() -> Unit)? = null
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
                    Icon(Icons.filled.MoreVert, contentDescription = "More", tint = TextSecondary)
                }
            } else if (primaryAction == null) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Open",
                    tint = TextSecondary
                )
            }
        }
        if (downloadProgress != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { downloadProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f).height(5.dp),
                    color = Color(0xFF8B5CF6),
                    trackColor = SurfaceHigh,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                if (downloadBytes != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        Formatters.bytes(downloadBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (primaryAction != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                primaryAction()
            }
        } else {
            Spacer(Modifier.height(4.dp))
        }
        if (onClick.let { true } && primaryAction == null && downloadProgress == null) {
            // Make the whole card clickable by wrapping in a clickable modifier at the caller.
        }
    }
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
            .background(Brush.linearGradient(gradient), RoundedCornerShape(size.value * 0.28f.dp)),
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
                icon = Icons.filled.Download
            )
        ModelLifecycleState.INSTALLED ->
            GradientButton(
                text = "Open Chat",
                onClick = onChat,
                icon = Icons.filled.ChatBubbleOutline
            )
        ModelLifecycleState.READY, ModelLifecycleState.RUNNING ->
            GradientButton(
                text = "Chat",
                onClick = onChat,
                icon = Icons.filled.ChatBubbleOutline
            )
        ModelLifecycleState.ERROR ->
            GradientButton(text = "Retry", onClick = onDownload)
        else -> {}
    }
}