package com.aichathub.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import com.aichathub.app.device.ModelScanner
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.ui.components.AppCard
import com.aichathub.app.ui.components.EmptyState
import com.aichathub.app.ui.components.GradientButton
import com.aichathub.app.ui.components.ModelCard
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

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.onFolderPicked(uri)
        }
    }

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
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GradientButton(
                    text = if (state.scanning) "Scanning…" else "Scan device",
                    onClick = viewModel::scan,
                    icon = Icons.Filled.Search,
                    enabled = !state.scanning
                )
                GradientButton(
                    text = "Import from folder",
                    onClick = { folderPicker.launch(null) },
                    icon = Icons.Filled.FolderOpen
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.scanning) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), color = Primary)
                        Spacer(Modifier.width(12.dp))
                        Text("Scanning for GGUF model files…", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    }
                }
            }

            state.scanMessage?.let { msg ->
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "Dismiss",
                            style = MaterialTheme.typography.labelMedium,
                            color = Primary,
                            modifier = Modifier.padding(start = 8.dp).clickable { viewModel.clearScanMessage() }
                        )
                    }
                }
            }

            if (state.discovered.isNotEmpty()) {
                item {
                    Text("Found on device", style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                }
                items(state.discovered, key = { "${it.fileName}-${it.sizeBytes}" }) { file ->
                    DiscoveredCard(file = file, onImport = { viewModel.import(file) })
                }
            }

            if (state.models.isEmpty() && state.discovered.isEmpty() && !state.scanning) {
                item {
                    EmptyState(
                        icon = Icons.Filled.SmartToy,
                        title = "No AI models yet",
                        description = "Find a model that fits your device, or scan this device for existing GGUF files.",
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

@Composable
private fun DiscoveredCard(
    file: ModelScanner.DiscoveredFile,
    onImport: () -> Unit
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        file.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${Formatters.bytes(file.sizeBytes)} · " +
                            (file.matchedModel?.let { "${it.name} (${it.parameters})" } ?: "Not in catalog"),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                if (file.matchedModel != null) {
                    GradientButton(text = "Import", onClick = onImport)
                } else {
                    Text("Unsupported", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                }
            }
        }
    }
}