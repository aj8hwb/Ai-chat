package com.aichathub.app.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aichathub.app.data.local.ConversationEntity
import com.aichathub.app.data.local.MessageEntity
import com.aichathub.app.ui.components.ChatSearchBar
import com.aichathub.app.ui.components.ContextWarningBar
import com.aichathub.app.ui.components.GradientButton
import com.aichathub.app.ui.components.MessageEditDialog
import com.aichathub.app.ui.navigation.Screen
import com.aichathub.app.ui.theme.Success
import com.aichathub.app.ui.screens.chat.ChatHistoryDrawer
import com.aichathub.app.ui.screens.chat.StreamingBubble
import com.aichathub.app.ui.screens.chat.ThinkingBubble
import com.aichathub.app.ui.screens.chat.LiveThinkingTracePanel
import com.aichathub.app.ui.screens.chat.ThinkingTracePanel
import com.aichathub.app.ui.screens.chat.SystemPromptDialog
import com.aichathub.app.ui.screens.chat.ThinkingModeRow
import com.aichathub.app.ui.screens.chat.highlightSearchText
import com.aichathub.app.ui.screens.chat.modelDisplayName
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    onNavigate: (String) -> Unit,
    conversationId: Long? = null,
    modelId: String? = null,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var modelMenu by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var longPressMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var editingMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var traceExpanded by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Long?>(null) }
    var showSystemPrompt by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchCurrentIndex by remember { mutableIntStateOf(0) }
    var showExportMenu by remember { mutableStateOf(false) }

    LaunchedEffect(conversationId) {
        if (conversationId != null) viewModel.loadConversation(conversationId)
    }

    // A model id supplied by navigation (Model Store / My Models "Chat" button)
    // pre-selects that model for the chat.
    LaunchedEffect(modelId) {
        if (modelId != null && modelId.isNotBlank()) viewModel.selectModelById(modelId)
    }

    // Share exported content
    LaunchedEffect(state.exportedContent, state.exportedFileName) {
        val content = state.exportedContent
        val fileName = state.exportedFileName
        if (content != null && fileName != null) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = when {
                    fileName.endsWith(".md") -> "text/markdown"
                    fileName.endsWith(".json") -> "application/json"
                    else -> "text/plain"
                }
                putExtra(Intent.EXTRA_TEXT, content)
                putExtra(Intent.EXTRA_SUBJECT, fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Export conversation"))
            viewModel.clearExportState()
        }
    }

    // Auto-scroll to bottom when messages change, but NEVER yank the list away
    // while the user is reading history (scrolled up) during generation.
    LaunchedEffect(state.messages.size, state.lastStreamedText, state.generating) {
        if (state.messages.isNotEmpty() || state.lastStreamedText.isNotEmpty()) {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = info.totalItemsCount
            val nearBottom = total == 0 || lastVisible >= (total - 2)
            if (nearBottom) {
                val streamItem = if (state.generating && state.lastStreamedText.isNotEmpty()) 1 else 0
                val target = (state.messages.size + streamItem - 1).coerceAtLeast(0)
                if (lastVisible != target) listState.animateScrollToItem(target)
            }
        }
    }

    val searchResults = remember(searchQuery, state.messages) {
        if (searchQuery.isBlank()) emptyList()
        else state.messages.mapIndexedNotNull { index, msg ->
            if (msg.content.contains(searchQuery, ignoreCase = true)) index to msg else null
        }
    }
    val totalSearchResults = searchResults.size

    LaunchedEffect(searchQuery, state.messages.size) {
        searchCurrentIndex = 0
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatHistoryDrawer(
                conversations = state.conversations,
                activeConversationId = state.conversationId,
                onSelect = {
                    scope.launch { drawerState.close() }
                    if (state.conversationId != it) viewModel.loadConversation(it)
                },
                onDelete = { deleteTarget = it },
                onNewChat = {
                    scope.launch { drawerState.close() }
                    viewModel.newChat()
                }
            )
        }
    ) {
        Box {
            Column(
                modifier = Modifier.fillMaxSize().imePadding()
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (conversationId != null) {
                        IconButton(onClick = { onNavigate(Screen.History.route) }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    } else {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Chat history", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Box {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { modelMenu = true }
                            ) {
                                Text(
                                    state.activeModelName ?: "Select a model",
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
                                expanded = modelMenu,
                                onDismissRequest = { modelMenu = false }
                            ) {
                                if (state.installedModels.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("No models installed yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                        onClick = { modelMenu = false }
                                    )
                                } else {
                                    state.installedModels.forEach { model ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        model.name,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = if (model.id == state.activeModelId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                    )
                                                    if (model.id == state.activeModelId) {
                                                        Spacer(Modifier.width(6.dp))
                                                        Text("●", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                                    }
                                                }
                                            },
                                            onClick = {
                                                modelMenu = false
                                                if (model.id != state.activeModelId) {
                                                    viewModel.selectModel(model)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val dotColor = when {
                                state.generating -> MaterialTheme.colorScheme.primary
                                state.isLoadingModel -> Color(0xFFFBBF24)
                                state.isModelLoaded -> Success
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Box(modifier = Modifier.size(6.dp).background(dotColor, RoundedCornerShape(50)))
                            Spacer(Modifier.width(5.dp))
                            Text(
                                when {
                                    state.generating -> "Generating…"
                                    state.isLoadingModel -> "Loading model…"
                                    state.isModelLoaded -> "Model ready"
                                    state.activeModelId != null -> "Model unloaded · reloads on send"
                                    else -> "No model loaded"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = { onNavigate(Screen.ChatSettings.route) }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Chat Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        IconButton(onClick = { showExportMenu = true }) {
                            Icon(Icons.Filled.IosShare, contentDescription = "Export", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(
                            expanded = showExportMenu,
                            onDismissRequest = { showExportMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Export as Markdown", color = MaterialTheme.colorScheme.onSurface) },
                                onClick = {
                                    showExportMenu = false
                                    viewModel.exportConversation("markdown")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Export as JSON", color = MaterialTheme.colorScheme.onSurface) },
                                onClick = {
                                    showExportMenu = false
                                    viewModel.exportConversation("json")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Export as Plain Text", color = MaterialTheme.colorScheme.onSurface) },
                                onClick = {
                                    showExportMenu = false
                                    viewModel.exportConversation("plaintext")
                                }
                            )
                        }
                    }
                    IconButton(onClick = { showSystemPrompt = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "System prompt", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

                // Search bar
                ChatSearchBar(
                    visible = showSearch,
                    query = searchQuery,
                    currentResult = if (searchResults.isEmpty()) 0 else searchCurrentIndex + 1,
                    totalResults = totalSearchResults,
                    onQueryChange = { searchQuery = it },
                    onNext = {
                        if (searchResults.isNotEmpty()) {
                            searchCurrentIndex = (searchCurrentIndex + 1) % searchResults.size
                            val targetIndex = searchResults[searchCurrentIndex].first
                            scope.launch { listState.animateScrollToItem(targetIndex) }
                        }
                    },
                    onPrevious = {
                        if (searchResults.isNotEmpty()) {
                            searchCurrentIndex = (searchCurrentIndex - 1 + searchResults.size) % searchResults.size
                            val targetIndex = searchResults[searchCurrentIndex].first
                            scope.launch { listState.animateScrollToItem(targetIndex) }
                        }
                    },
                    onClose = {
                        showSearch = false
                        searchQuery = ""
                    }
                )

                // Messages
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (state.messages.isEmpty() && state.lastStreamedText.isEmpty() && !state.generating) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Filled.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text("Ask your local AI anything…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                state.activeModelName?.let { "Using $it" } ?: "Select a model to begin",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            items(state.messages, key = { it.id }) { msg ->
                                MessageBubble(
                                    message = msg,
                                    onCopy = {
                                        clipboard.setText(AnnotatedString(msg.content))
                                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                    },
                                    onLongPress = { longPressMessage = msg },
                                    searchQuery = searchQuery
                                )
                                Spacer(Modifier.height(10.dp))
                            }
                            // Streaming bubble — hidden once the DAO has persisted
                            // the completed reply (content match) so there is no
                            // flash of the reply disappearing mid-transition.
                            val streamShown =
                                state.generating && state.lastStreamedText.isNotEmpty() &&
                                    state.messages.none { it.role == "assistant" && it.content == state.lastStreamedText }
                            if (streamShown) {
                                item(key = "streaming") {
                                    StreamingBubble(text = state.lastStreamedText, thinkingSec = state.liveThinkingSec)
                                }
                            }
                            if (state.generating && state.lastStreamedText.isEmpty()) {
                                item(key = "thinking") {
                                    ThinkingBubble(thinkingSec = state.liveThinkingSec)
                                }
                            }
                            if (state.thinkingTrace.isNotEmpty()) {
                                item(key = "livetrace") {
                                    Spacer(Modifier.height(4.dp))
                                    LiveThinkingTracePanel(
                                        trace = state.thinkingTrace,
                                        expanded = state.thinkingExpanded,
                                        liveThinkingSec = state.liveThinkingSec,
                                        contextTokensMax = state.contextTokensMax,
                                        onToggle = viewModel::toggleThinking
                                    )
                                }
                            }
                            val trace = state.lastThinking
                            if (trace != null && trace.mode == "HARD" && !state.generating && state.messages.isNotEmpty()) {
                                item(key = "trace") {
                                    Spacer(Modifier.height(4.dp))
                                    ThinkingTracePanel(
                                        info = trace,
                                        expanded = traceExpanded,
                                        onToggle = { traceExpanded = !traceExpanded }
                                    )
                                }
                            }
                        }
                    }
                }

                // Error
                state.error?.let { error ->
                    Text(
                        error,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Thinking mode selector
                ThinkingModeRow(
                    mode = state.thinkingMode,
                    onSelect = viewModel::setThinkingMode,
                    enabled = !state.generating && !state.isLoadingModel
                )

                // Context warning bar
                state.contextStatus?.let { ctxStatus ->
                    ContextWarningBar(
                        contextStatus = ctxStatus,
                        suggestion = state.compactionSuggestion,
                        onSuggestionAction = viewModel::handleCompaction
                    )
                }

                // Composer
                ComposerBar(
                    input = state.input,
                    generating = state.generating,
                    isLoadingModel = state.isLoadingModel,
                    onInputChange = viewModel::setInput,
                    onSend = viewModel::send,
                    onStop = viewModel::stopGeneration,
                    onNewChat = viewModel::newChat
                )
            }

            // Full-screen message editor overlay
            editingMessage?.let { msg ->
                MessageEditDialog(
                    messageId = msg.id,
                    originalContent = msg.content,
                    onDismiss = { editingMessage = null },
                    onSave = { id, newContent ->
                        viewModel.updateMessage(msg.copy(content = newContent))
                        editingMessage = null
                    }
                )
            }
        }
    }

    // Long-press options
    longPressMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { longPressMessage = null },
            title = { Text("Message options", color = MaterialTheme.colorScheme.onSurface) },
            text = { Text("What would you like to do with this message?", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(msg.content))
                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    longPressMessage = null
                }) { Text("Copy text", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                Row {
                    if (msg.role == "user" && state.generating.not()) {
                        TextButton(onClick = {
                            longPressMessage = null
                            viewModel.regenerate()
                        }) { Text("Regenerate", color = MaterialTheme.colorScheme.primary) }
                        TextButton(onClick = {
                            longPressMessage = null
                            editingMessage = msg
                        }) { Text("Edit", color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        )
    }

    // Delete confirmation (avoids an accidental tap wiping a whole chat)
    deleteTarget?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete conversation?") },
            text = { Text("This conversation and all of its messages will be permanently removed.", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteConversation(id)
                    deleteTarget = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        )
    }

    // Per-conversation system prompt editor
    if (showSystemPrompt) {
        SystemPromptDialog(
            onDismiss = { showSystemPrompt = false },
            onSave = { prompt ->
                viewModel.setConversationSystemPrompt(prompt)
                showSystemPrompt = false
            }
        )
    }
}

@Composable
private fun MessageBubble(
    // Long-press options
    message: MessageEntity,
    onCopy: () -> Unit,
    onLongPress: () -> Unit,
    searchQuery: String = ""
) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .background(
                    if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                    RoundedCornerShape(18.dp)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // User messages are plain text; assistant replies render Markdown
            // (code blocks, bold, lists, links) so code-heavy answers read well.
            if (isUser) {
                Text(
                    highlightSearchText(message.content, searchQuery),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
            } else {
                Text(
                    com.aichathub.app.util.MarkdownFormatter.render(
                        message.content,
                        baseColor = MaterialTheme.colorScheme.onSurface,
                        accentColor = MaterialTheme.colorScheme.primary
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (!isUser) {
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.End).clickable(onClick = onCopy)
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Copy",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Copy", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ComposerBar(
    input: String,
    generating: Boolean,
    isLoadingModel: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onNewChat: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            placeholder = { Text("Ask your local AI anything…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            enabled = !generating,
            minLines = 1,
            maxLines = 5,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.weight(1f),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                cursorColor = MaterialTheme.colorScheme.primary
            )
        )
        Spacer(Modifier.width(10.dp))
        if (generating || isLoadingModel) {
            GradientButton(
                text = "Stop",
                onClick = onStop,
                icon = Icons.Filled.Stop,
                enabled = !isLoadingModel
            )
        } else {
            GradientButton(
                text = "Send",
                onClick = onSend,
                enabled = input.isNotBlank()
            )
        }
    }
}