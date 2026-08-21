package com.aichathub.app.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.ui.components.GradientButton
import com.aichathub.app.ui.components.ModelCard
import com.aichathub.app.ui.navigation.Screen
import com.aichathub.app.util.Formatters

@Composable
fun ModelsScreen(
    onNavigate: (String) -> Unit,
    viewModel: ModelsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    var pendingDownload by remember { mutableStateOf<CatalogModel?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(16.dp))
            Text("Model Store", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                "${state.models.size} models · all run on-device · GGUF",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = { Text("Search models…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    cursorColor = MaterialTheme.colorScheme.primary
                )
            )
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(viewModel.categories.size) { idx ->
                    val cat = viewModel.categories[idx]
                    FilterChip(
                        selected = state.selectedCategory == cat,
                        onClick = { viewModel.onCategoryChange(cat) },
                        label = { Text(cat) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            state.error?.let { error ->
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "Dismiss",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp).clickable { viewModel.clearMaterialTheme.colorScheme.error() }
                        )
                    }
                }
            }

            items(state.filtered, key = { it.id }) { model ->
                val life = state.states[model.id] ?: ModelLifecycleState.NOT_INSTALLED
                val compat = state.compatibility[model.id]
                val download = state.downloads[model.id]
                val isInstalled = life == ModelLifecycleState.INSTALLED ||
                    life == ModelLifecycleState.READY ||
                    life == ModelLifecycleState.RUNNING

                ModelCard(
                    model = model,
                    lifecycleState = life,
                    compatibility = compat,
                    recommended = (compat?.rank ?: 0) >= 4,
                    download = download,
                    onClick = { onNavigate(Screen.ModelDetails.routeFor(model.id)) },
                    primaryAction = if (isInstalled) {
                        {
                            GradientButton(
                                text = "Chat",
                                onClick = { onNavigate(Screen.Chat.routeFor(model.id)) }
                            )
                        }
                    } else null,
                    onDownload = {
                        pendingDownload = model
                    },
                    onPause = { viewModel.pause(model.id) },
                    onResume = { viewModel.resume(model.id) },
                    onCancel = { viewModel.cancel(model.id) }
                )
            }
        }
    }

    // Large downloads consume significant storage (and possibly mobile data) —
    // confirm before starting them.
    pendingDownload?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingDownload = null },
            title = { Text("Download ${model.name}?") },
            text = {
                Text(
                    "This is a ${Formatters.bytes(model.fileSizeBytes)} file. It will use storage (and mobile data if you're not on Wi-Fi). Continue?",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.download(model)
                    pendingDownload = null
                }) { Text("Download", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDownload = null }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        )
    }
}