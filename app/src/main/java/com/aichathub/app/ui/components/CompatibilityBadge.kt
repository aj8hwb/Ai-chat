package com.aichathub.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aichathub.app.domain.model.CompatibilityLevel
import com.aichathub.app.ui.theme.Heavy
import com.aichathub.app.ui.theme.HeavyContainer
import com.aichathub.app.ui.theme.Success
import com.aichathub.app.ui.theme.SuccessContainer

@Composable
fun CompatibilityBadge(
    level: CompatibilityLevel,
    modifier: Modifier = Modifier
) {
    val (bg, fg, emoji) = when (level) {
        CompatibilityLevel.EXCELLENT -> Triple(SuccessContainer, Success, "\uD83D\uDFE2")
        CompatibilityLevel.RECOMMENDED -> Triple(SuccessContainer, Success, "\uD83D\uDFE2")
        CompatibilityLevel.USABLE -> Triple(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.secondary, "\uD83D\uDFE1")
        CompatibilityLevel.HEAVY -> Triple(HeavyContainer, Heavy, "\uD83D\uDFE0")
        CompatibilityLevel.NOT_RECOMMENDED -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error, "\uD83D\uDD34")
    }
    Row(
        modifier = modifier.semantics {
            contentDescription = "Compatibility: ${level.label}"
        }.background(bg, RoundedCornerShape(50))
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
