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
import androidx.compose.material.icons.filled.SmartToy
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
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.ui.components.AppCard
import com.aichathub.app.ui.components.EmptyState
import com.aichathub.app.ui.components.GradientButton
import com.aichathub.app.ui.components.ModelCard
import com.aichathub.app.ui.components.ModelIcon
import com.aichathub.app.ui.navigation.Screen
import com.aichathub.app.ui.theme.Error
import com.aichathub.app.ui.theme.Primary
import com.aichathub.app.ui.theme.TextPrimary
import com.aichathub.app.ui.theme.TextSecondary
import com.aichathub.app.util.Formatters

@Composable
fun MyModelsScreen(
    onNavigate: (String) -> Unit,
    viewModel: MyModelsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(16.dp))
            Text("My Models", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text(
                "Storage used: ${Formatters.bytes(state.totalStorageBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.models.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.SmartToy,
                        title = "No AI models yet",
                        description = "Find a model that fits your device.",
                        actionLabel = "Explore Models",
                        onAction = { onNavigate(Screen.Models.route) },
                        modifier = Modifier.padding(vertical = 40.dp)
                    )
                }
            } else {
                items(state.models, key = { it.id }) { model ->
                    ModelCard(
                        model = model,
                        lifecycleState = state.states[model.id] ?: ModelLifecycleState.INSTALLED,
                        compatibility = null,
                        onClick = { onNavigate(Screen.ModelDetails.routeFor(model.id)) },
                        primaryAction = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GradientButton(
                                    text = "Chat",
                                    onClick = { onNavigate(Screen.Chat.route) }
                                )
                                IconButton(onClick = { deleteTarget = model.id }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Error)
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    deleteTarget?.let { modelId ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Model?") },
            text = { Text("This will remove the model from your device.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(modelId)
                    deleteTarget = null
                }) { Text("Delete", color = Error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = TextSecondary) }
            }
        )
    }
}