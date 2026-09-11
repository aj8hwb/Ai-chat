package com.aichathub.app.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ThinkingModeRow(
    mode: String,
    onSelect: (String) -> Unit,
    enabled: Boolean
) {
    val modes = listOf("INSTANT" to "Instant", "DEFAULT" to "Default", "HARD" to "Hard")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        modes.forEach { (id, label) ->
            FilterChip(
                selected = mode == id,
                onClick = { onSelect(id) },
                enabled = enabled,
                label = {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (mode == id) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            )
            Spacer(Modifier.width(8.dp))
        }
    }
}
