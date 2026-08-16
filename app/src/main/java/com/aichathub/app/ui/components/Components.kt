package com.aichathub.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aichathub.app.domain.model.CompatibilityLevel
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.ui.theme.BorderSubtle
import com.aichathub.app.ui.theme.Error
import com.aichathub.app.ui.theme.ErrorContainer
import com.aichathub.app.ui.theme.GradientPrimary
import com.aichathub.app.ui.theme.Heavy
import com.aichathub.app.ui.theme.HeavyContainer
import com.aichathub.app.ui.theme.OnPrimary
import com.aichathub.app.ui.theme.Primary
import com.aichathub.app.ui.theme.PrimaryContainer
import com.aichathub.app.ui.theme.Secondary
import com.aichathub.app.ui.theme.SecondaryContainer
import com.aichathub.app.ui.theme.Success
import com.aichathub.app.ui.theme.SuccessContainer
import com.aichathub.app.ui.theme.SurfaceElevated
import com.aichathub.app.ui.theme.SurfaceHigh
import com.aichathub.app.ui.theme.TextMuted
import com.aichathub.app.ui.theme.TextPrimary
import com.aichathub.app.ui.theme.TextSecondary
import com.aichathub.app.ui.theme.Warning
import com.aichathub.app.ui.theme.WarningContainer

/**
 * Reusable premium UI components shared across all screens.
 */

// ---------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
    ) {
        content()
    }
}

@Composable
fun GradientHeaderCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .background(Brush.linearGradient(GradientPrimary), RoundedCornerShape(24.dp))
            .padding(20.dp)
    ) {
        content()
    }
}

// ---------------------------------------------------------------------------
// Badges
// ---------------------------------------------------------------------------

@Composable
fun CompatibilityBadge(
    level: CompatibilityLevel,
    modifier: Modifier = Modifier
) {
    val (bg, fg, emoji) = when (level) {
        CompatibilityLevel.EXCELLENT -> Triple(SuccessContainer, Success, "🟢")
        CompatibilityLevel.RECOMMENDED -> Triple(SuccessContainer, Success, "🟢")
        CompatibilityLevel.USABLE -> Triple(SecondaryContainer, Secondary, "🟡")
        CompatibilityLevel.HEAVY -> Triple(HeavyContainer, Heavy, "🟠")
        CompatibilityLevel.NOT_RECOMMENDED -> Triple(ErrorContainer, Error, "🔴")
    }
    Row(
        modifier = modifier
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(emoji, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(6.dp))
        Text(
            text = level.label,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun StateDot(
    state: ModelLifecycleState,
    modifier: Modifier = Modifier
) {
    val color = when (state) {
        ModelLifecycleState.READY, ModelLifecycleState.RUNNING -> Success
        ModelLifecycleState.INSTALLED, ModelLifecycleState.DOWNLOADED -> Secondary
        ModelLifecycleState.LOADING, ModelLifecycleState.UNLOADING, ModelLifecycleState.DOWNLOADING, ModelLifecycleState.VERIFYING -> Warning
        ModelLifecycleState.ERROR -> Error
        ModelLifecycleState.NOT_INSTALLED -> TextMuted
    }
    Box(
        modifier = modifier
            .size(8.dp)
            .background(color, CircleShape)
    )
}

// ---------------------------------------------------------------------------
// Status / metric rows
// ---------------------------------------------------------------------------

@Composable
fun MetricRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = TextPrimary,
    icon: ImageVector? = null
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun ProgressBlock(
    label: String,
    progress: Float,
    valueText: String,
    modifier: Modifier = Modifier,
    progressColor: Color = Primary
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = progressColor,
            trackColor = SurfaceHigh,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------

@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Primary,
            contentColor = OnPrimary,
            disabledContainerColor = SurfaceHigh,
            disabledContentColor = TextMuted
        )
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------------------
// Empty / error / loading states
// ---------------------------------------------------------------------------

@Composable
fun EmptyState(
    icon: ImageVector = Icons.Filled.ChatBubbleOutline,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(72.dp).background(PrimaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Primary, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            GradientButton(text = actionLabel, onClick = onAction)
        }
    }
}

@Composable
fun ErrorState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(72.dp).background(ErrorContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = Error, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            GradientButton(text = actionLabel, onClick = onAction)
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        if (actionText != null && onAction != null) {
            Text(
                actionText,
                style = MaterialTheme.typography.labelLarge,
                color = Primary,
                modifier = Modifier.clickable(onClick = onAction)
            )
        }
    }
}