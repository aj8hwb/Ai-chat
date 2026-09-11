package com.aichathub.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.ui.theme.Success
import com.aichathub.app.ui.theme.Warning

@Composable
fun MetricRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    icon: ImageVector? = null
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    progressColor: Color = MaterialTheme.colorScheme.primary
) {
    androidx.compose.foundation.layout.Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = progressColor,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
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
        ModelLifecycleState.INSTALLED, ModelLifecycleState.DOWNLOADED -> MaterialTheme.colorScheme.secondary
        ModelLifecycleState.LOADING, ModelLifecycleState.UNLOADING, ModelLifecycleState.DOWNLOADING, ModelLifecycleState.VERIFYING -> Warning
        ModelLifecycleState.ERROR -> MaterialTheme.colorScheme.error
        ModelLifecycleState.NOT_INSTALLED -> MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = modifier
            .size(8.dp)
            .background(color, CircleShape)
    )
}
