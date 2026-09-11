package com.aichathub.app.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun <T> ListDetailPane(
    windowSizeClass: WindowSizeClass,
    modifier: Modifier = Modifier,
    items: List<T>,
    selectedItemId: Any?,
    onItemSelected: (T) -> Unit,
    listContent: @Composable (items: List<T>, selectedItemId: Any?, onItemSelected: (T) -> Unit) -> Unit,
    detailContent: @Composable (item: T) -> Unit,
    emptyDetailContent: @Composable () -> Unit = {},
) {
    if (windowSizeClass.isLargeScreen) {
        PermanentListDetail(
            modifier = modifier,
            items = items,
            selectedItemId = selectedItemId,
            onItemSelected = onItemSelected,
            listContent = listContent,
            detailContent = detailContent,
            emptyDetailContent = emptyDetailContent,
        )
    } else {
        MobileListNavigation(
            modifier = modifier,
            items = items,
            selectedItemId = selectedItemId,
            onItemSelected = onItemSelected,
            listContent = listContent,
            detailContent = detailContent,
        )
    }
}

@Composable
private fun <T> PermanentListDetail(
    modifier: Modifier = Modifier,
    items: List<T>,
    selectedItemId: Any?,
    onItemSelected: (T) -> Unit,
    listContent: @Composable (items: List<T>, selectedItemId: Any?, onItemSelected: (T) -> Unit) -> Unit,
    detailContent: @Composable (item: T) -> Unit,
    emptyDetailContent: @Composable () -> Unit = {},
) {
    val selectedItem = remember(selectedItemId, items) {
        items.firstOrNull { getItemId(it) == selectedItemId }
    }

    Row(
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 400.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            listContent(items, selectedItemId, onItemSelected)
        }

        VerticalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.fillMaxHeight(),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            if (selectedItem != null) {
                detailContent(selectedItem)
            } else {
                emptyDetailContent()
            }
        }
    }
}

@Composable
private fun <T> MobileListNavigation(
    modifier: Modifier = Modifier,
    items: List<T>,
    selectedItemId: Any?,
    onItemSelected: (T) -> Unit,
    listContent: @Composable (items: List<T>, selectedItemId: Any?, onItemSelected: (T) -> Unit) -> Unit,
    detailContent: @Composable (item: T) -> Unit,
) {
    var pendingNavigation by remember { mutableStateOf<T?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        listContent(items, selectedItemId) { item ->
            onItemSelected(item)
            pendingNavigation = item
        }
    }

    val navigatedItem = pendingNavigation
    if (navigatedItem != null) {
        Dialog(
            onDismissRequest = { pendingNavigation = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                detailContent(navigatedItem)
                pendingNavigation = null
            }
        }
    }
}

private fun <T> getItemId(item: T): Any {
    return when (item) {
        is HasId -> item.id
        else -> item.hashCode()
    }
}

interface HasId {
    val id: Any
}

@Composable
fun <T> rememberListDetailState(
    initialItemId: Any? = null,
): ListDetailState<T> {
    return rememberSaveable(saver = ListDetailState.Saver()) {
        ListDetailState(selectedItemId = initialItemId)
    }
}

class ListDetailState<T>(
    selectedItemId: Any? = null,
) {
    var selectedItemId by mutableStateOf(selectedItemId)
        private set

    var listExpanded by mutableStateOf(false)
        private set

    fun selectItem(itemId: Any?) {
        selectedItemId = itemId
        listExpanded = false
    }

    fun clearSelection() {
        selectedItemId = null
    }

    fun toggleList() {
        listExpanded = !listExpanded
    }

    companion object {
        fun <T> Saver() = androidx.compose.runtime.saveable.Saver<ListDetailState<T>, Any?>(
            save = { state -> state.selectedItemId },
            restore = { saved ->
                @Suppress("UNCHECKED_CAST")
                ListDetailState<T>(saved as Any?)
            },
        )
    }
}
