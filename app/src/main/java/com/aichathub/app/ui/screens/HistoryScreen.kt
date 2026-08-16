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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichathub.app.data.local.ConversationEntity
import com.aichathub.app.ui.components.AppCard
import com.aichathub.app.ui.components.EmptyState
import com.aichathub.app.ui.navigation.Screen
import com.aichathub.app.ui.theme.Error
import com.aichathub.app.ui.theme.Primary
import com.aichathub.app.ui.theme.TextPrimary
import com.aichathub.app.ui.theme.TextSecondary
import com.aichathub.app.util.ConversationGroups

@Composable
fun HistoryScreen(
    onNavigate: (String) -> Unit,
    viewModel: HistoryViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    var deleteTarget by remember { mutableStateOf<Long?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(16.dp))
            Text("Conversations", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Spacer(Modifier.height(8.dp))
        }

        val groups = remember(state.conversations) {
            ConversationGroups.groupByDay(state.conversations)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.conversations.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.History,
                        title = "No Conversations",
                        description = "Start your first local AI conversation.",
                        modifier = Modifier.padding(vertical = 40.dp)
                    )
                }
            } else {
                groups.forEach { group ->
                    item(key = "header_${group.label}") {
                        Text(
                            group.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = Primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                        )
                    }
                    items(group.conversations, key = { it.id }) { conv ->
                        ConversationRow(
                            conv = conv,
                            onClick = { onNavigate(Screen.Conversation.routeFor(conv.id)) },
                            onDelete = { deleteTarget = conv.id }
                        )
                    }
                }
            }
        }
    }

    deleteTarget?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Conversation?") },
            text = { Text("This will permanently remove the conversation.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(id)
                    deleteTarget = null
                }) { Text("Delete", color = Error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = TextSecondary) }
            }
        )
    }
}

@Composable
private fun ConversationRow(
    conv: ConversationEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    conv.title.ifBlank { "New chat" },
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Row {
                    Text(conv.modelId, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        ConversationGroups.timeLabel(conv.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = TextSecondary)
            }
        }
    }
}