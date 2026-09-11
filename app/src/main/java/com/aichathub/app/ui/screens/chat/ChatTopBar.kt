package com.aichathub.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aichathub.app.data.local.MessageEntity
import com.aichathub.app.ui.theme.Success

@Composable
fun ChatTopBar(
    conversationId: Long?,
    activeModelName: String?,
    activeModelId: String?,
    installedModels: List<Pair<String, String>>,
    generating: Boolean,
    isLoadingModel: Boolean,
    isModelLoaded: Boolean,
    modelMenuExpanded: Boolean,
    showExportMenu: Boolean,
    onBack: () -> Unit,
    onMenuClick: () -> Unit,
    onModelClick: () -> Unit,
    onModelDismiss: () -> Unit,
    onModelSelect: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onSearchClick: () -> Unit,
    onExportClick: () -> Unit,
    onExportDismiss: () -> Unit,
    onExportSelect: (String) -> Unit,
    onMoreClick: () -> Unit,
    onModelMenuDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (conversationId != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
            }
        } else {
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Filled.Menu, contentDescription = "Chat history", tint = MaterialTheme.colorScheme.onSurface)
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onModelClick() }
                ) {
                    Text(
                        activeModelName ?: "Select a model",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = "Switch model",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = modelMenuExpanded,
                    onDismissRequest = onModelDismiss
                ) {
                    if (installedModels.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No models installed yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            onClick = onModelDismiss
                        )
                    } else {
                        installedModels.forEach { (id, name) ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (id == activeModelId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (id == activeModelId) {
                                            Spacer(Modifier.width(6.dp))
                                            Text("●", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                },
                                onClick = {
                                    onModelDismiss()
                                    if (id != activeModelId) onModelSelect(id)
                                }
                            )
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                val dotColor = when {
                    generating -> MaterialTheme.colorScheme.primary
                    isLoadingModel -> Color(0xFFFBBF24)
                    isModelLoaded -> Success
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Box(modifier = Modifier.size(6.dp).background(dotColor, RoundedCornerShape(50)))
                Spacer(Modifier.width(5.dp))
                Text(
                    when {
                        generating -> "Generating…"
                        isLoadingModel -> "Loading model…"
                        isModelLoaded -> "Model ready"
                        activeModelId != null -> "Model unloaded · reloads on send"
                        else -> "No model loaded"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        IconButton(onClick = onSettingsClick) {
            Icon(Icons.Filled.Tune, contentDescription = "Chat Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onSearchClick) {
            Icon(Icons.Filled.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Box {
            IconButton(onClick = onExportClick) {
                Icon(Icons.Filled.IosShare, contentDescription = "Export", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(
                expanded = showExportMenu,
                onDismissRequest = onExportDismiss
            ) {
                DropdownMenuItem(
                    text = { Text("Export as Markdown", color = MaterialTheme.colorScheme.onSurface) },
                    onClick = { onExportSelect("markdown") }
                )
                DropdownMenuItem(
                    text = { Text("Export as JSON", color = MaterialTheme.colorScheme.onSurface) },
                    onClick = { onExportSelect("json") }
                )
                DropdownMenuItem(
                    text = { Text("Export as Plain Text", color = MaterialTheme.colorScheme.onSurface) },
                    onClick = { onExportSelect("plaintext") }
                )
            }
        }

        IconButton(onClick = onMoreClick) {
            Icon(Icons.Filled.MoreVert, contentDescription = "System prompt", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
}

private fun Modifier.fillMaxWidth(): Modifier = this
