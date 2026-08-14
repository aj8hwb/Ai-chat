package com.aichathub.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.CompatibilityLevel
import com.aichathub.app.domain.model.ModelLifecycleState
import com.aichathub.app.ui.components.AppCard
import com.aichathub.app.ui.components.GradientButton
import com.aichathub.app.ui.components.ModelActionButtons
import com.aichathub.app.ui.components.ModelCard
import com.aichathub.app.ui.navigation.Screen
import com.aichathub.app.ui.theme.Primary
import com.aichathub.app.ui.theme.SurfaceHigh
import com.aichathub.app.ui.theme.TextPrimary
import com.aichathub.app.ui.theme.TextSecondary

@Composable
fun ModelsScreen(
    onNavigate: (String) -> Unit,
    viewModel: ModelsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(16.dp))
            Text("Model Store", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = { Text("Search models…", color = TextSecondary) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = TextSecondary) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = SurfaceHigh,
                    focusedContainerColor = SurfaceHigh,
                    unfocusedContainerColor = SurfaceHigh,
                    cursorColor = Primary
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
                            selectedContainerColor = Primary,
                            selectedLabelColor = TextPrimary
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
            items(state.filtered, key = { it.id }) { model ->
                val life = state.states[model.id] ?: ModelLifecycleState.NOT_INSTALLED
                val compat = state.compatibility[model.id]
                ModelCard(
                    model = model,
                    lifecycleState = life,
                    compatibility = compat,
                    recommended = (compat?.rank ?: 0) >= 4,
                    onClick = { onNavigate(Screen.ModelDetails.routeFor(model.id)) },
                    primaryAction = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (life) {
                                ModelLifecycleState.NOT_INSTALLED ->
                                    GradientButton(
                                        text = "Details",
                                        onClick = { onNavigate(Screen.ModelDetails.routeFor(model.id)) }
                                    )
                                else ->
                                    GradientButton(
                                        text = "Chat",
                                        onClick = { onNavigate(Screen.Chat.route) }
                                    )
                            }
                        }
                    }
                )
            }
        }
    }
}