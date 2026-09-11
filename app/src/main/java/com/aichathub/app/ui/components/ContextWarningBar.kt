package com.aichathub.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aichathub.app.chat.TokenContextEngine

private val ContextGreen = Color(0xFF4CAF50)
private val ContextYellow = Color(0xFFFFC107)
private val ContextOrange = Color(0xFFFF9800)
private val ContextRed = Color(0xFFF44336)

private fun contextColor(utilizationPercent: Float): Color = when {
    utilizationPercent > 95f -> ContextRed
    utilizationPercent > 80f -> ContextOrange
    utilizationPercent > 60f -> ContextYellow
    else -> ContextGreen
}

@Composable
fun ContextWarningBar(
    contextStatus: TokenContextEngine.ContextStatus,
    suggestion: com.aichathub.app.chat.CompactionSuggestion?,
    onSuggestionAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = contextStatus.utilizationPercent > 60f,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            val barColor = contextColor(contextStatus.utilizationPercent)

            LinearProgressIndicator(
                progress = { (contextStatus.utilizationPercent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = barColor,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )

            Spacer(Modifier.height(4.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (contextStatus.isNearLimit) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = barColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }

                Text(
                    text = formatTokenStatus(contextStatus),
                    style = MaterialTheme.typography.labelSmall,
                    color = barColor,
                    modifier = Modifier.weight(1f)
                )

                if (contextStatus.isAtLimit) {
                    Text(
                        text = "LIMIT",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = ContextRed
                    )
                }
            }

            AnimatedVisibility(
                visible = suggestion != null && contextStatus.isNearLimit,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                suggestion?.let {
                    SuggestionChip(
                        suggestion = it,
                        onAction = onSuggestionAction
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionChip(
    suggestion: com.aichathub.app.chat.CompactionSuggestion,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .padding(top = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onAction)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = ContextOrange,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = suggestionText(suggestion.type),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = formatTokensSaved(suggestion.estimatedTokensSaved),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatTokenStatus(status: TokenContextEngine.ContextStatus): String {
    val used = formatTokenCount(status.totalUsed)
    val max = formatTokenCount(status.maxTokens)
    val pct = status.utilizationPercent.toInt()
    return "Context: $used / $max tokens ($pct%)"
}

private fun formatTokenCount(count: Int): String {
    return if (count >= 1000) {
        String.format("%,d", count)
    } else {
        count.toString()
    }
}

private fun formatTokensSaved(tokens: Int): String {
    val saved = formatTokenCount(tokens)
    return "~$saved tokens"
}

private fun suggestionText(type: com.aichathub.app.chat.CompactionType): String = when (type) {
    com.aichathub.app.chat.CompactionType.TRIM_OLDEST -> "Drop oldest messages"
    com.aichathub.app.chat.CompactionType.SUMMARIZE -> "Summarize & continue"
    com.aichathub.app.chat.CompactionType.START_NEW -> "Start new chat"
}
