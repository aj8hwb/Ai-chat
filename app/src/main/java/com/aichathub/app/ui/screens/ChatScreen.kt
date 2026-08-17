package com.aichathub.app.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichathub.app.data.local.ConversationEntity
import com.aichathub.app.data.local.MessageEntity
import com.aichathub.app.ui.components.AppCard
import com.aichathub.app.ui.components.GradientButton
import com.aichathub.app.ui.navigation.Screen
import com.aichathub.app.ui.theme.NearBlack
import com.aichathub.app.ui.theme.Primary
import com.aichathub.app.ui.theme.Success
import com.aichathub.app.ui.theme.SurfaceElevated
import com.aichathub.app.ui.theme.SurfaceHigh
import com.aichathub.app.ui.theme.TextPrimary
import com.aichathub.app.ui.theme.TextSecondary
import com.aichathub.app.util.ConversationGroups
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    onNavigate: (String) -> Unit,
    conversationId: Long? = null,
    viewModel: ChatViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var modelMenu by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var longPressMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var editingMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var traceExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(conversationId) {
        if (conversationId != null) viewModel.loadConversation(conversationId)
    }

    // Auto-scroll to bottom when messages change (never past the last item).
    LaunchedEffect(state.messages.size, state.lastStreamedText, state.generating) {
        if (state.messages.isNotEmpty()) {
            val streamItem = if (state.generating && state.lastStreamedText.isNotEmpty()) 1 else 0
            listState.animateScrollToItem((state.messages.size + streamItem - 1).coerceAtLeast(0))
        }
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
                onDelete = { viewModel.deleteConversation(it) },
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
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                        }
                    } else {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Chat history", tint = TextPrimary)
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
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    Icons.Filled.ArrowDropDown,
                                    contentDescription = "Switch model",
                                    tint = TextSecondary
                                )
                            }
                            DropdownMenu(
                                expanded = modelMenu,
                                onDismissRequest = { modelMenu = false }
                            ) {
                                if (state.installedModels.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("No models installed yet", style = MaterialTheme.typography.bodySmall, color = TextSecondary) },
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
                                                        color = if (model.id == state.activeModelId) Primary else TextPrimary
                                                    )
                                                    if (model.id == state.activeModelId) {
                                                        Spacer(Modifier.width(6.dp))
                                                        Text("●", style = MaterialTheme.typography.labelMedium, color = Primary)
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
                                state.generating -> Primary
                                state.isLoadingModel -> Color(0xFFFBBF24)
                                state.activeModelId != null -> Success
                                else -> TextSecondary
                            }
                            Box(modifier = Modifier.size(6.dp).background(dotColor, RoundedCornerShape(50)))
                            Spacer(Modifier.width(5.dp))
                            Text(
                                when {
                                    state.generating -> "Thinking…"
                                    state.isLoadingModel -> "Loading model…"
                                    state.activeModelId != null -> "Ready"
                                    else -> "No model loaded"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                    IconButton(onClick = { onNavigate(Screen.ChatSettings.route) }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Chat Settings", tint = TextSecondary)
                    }
                }
                HorizontalDivider(color = Color(0xFF232330))

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
                                tint = TextSecondary,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text("Ask your local AI anything…", style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
                            Text(
                                state.activeModelName?.let { "Using $it" } ?: "Select a model to begin",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
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
                                    onLongPress = { longPressMessage = msg }
                                )
                                Spacer(Modifier.height(10.dp))
                            }
                            if (state.generating && state.lastStreamedText.isNotEmpty()) {
                                item(key = "streaming") {
                                    StreamingBubble(text = state.lastStreamedText)
                                }
                            }
                            if (state.generating && state.lastStreamedText.isEmpty()) {
                                item(key = "thinking") {
                                    ThinkingBubble()
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
                        color = Color(0xFFF87171),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Thinking mode selector
                ThinkingModeRow(
                    mode = state.thinkingMode,
                    onSelect = viewModel::setThinkingMode,
                    enabled = !state.generating && !state.isLoadingModel
                )

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
                FullScreenEditor(
                    message = msg,
                    onSave = { newContent ->
                        viewModel.updateMessage(msg.copy(content = newContent))
                        editingMessage = null
                    },
                    onCopyAll = {
                        clipboard.setText(AnnotatedString(msg.content))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    onClose = { editingMessage = null }
                )
            }
        }
    }

    // Long-press options
    longPressMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { longPressMessage = null },
            title = { Text("Message options", color = TextPrimary) },
            text = { Text("What would you like to do with this message?", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(msg.content))
                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    longPressMessage = null
                }) { Text("Copy text", color = Primary) }
            },
            dismissButton = {
                TextButton(onClick = {
                    longPressMessage = null
                    editingMessage = msg
                }) { Text("Edit", color = Primary) }
            }
        )
    }
}

@Composable
private fun ChatHistoryDrawer(
    conversations: List<ConversationEntity>,
    activeConversationId: Long?,
    onSelect: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onNewChat: () -> Unit
) {
    val groups = remember(conversations) { ConversationGroups.groupByDay(conversations) }
    ModalDrawerSheet(
        modifier = Modifier.width(300.dp),
        drawerContainerColor = NearBlack
    ) {
        Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Chats", style = MaterialTheme.typography.titleLarge, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                GradientButton(
                    text = "New chat",
                    onClick = onNewChat,
                    icon = Icons.Filled.Add,
                    modifier = Modifier.padding(0.dp)
                )
            }
            HorizontalDivider(color = Color(0xFF232330))

            if (conversations.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.Menu, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("No chats yet", style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
                    Text(
                        "Your past conversations will appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    groups.forEach { group ->
                        item(key = "header_${group.label}") {
                            Text(
                                group.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = Primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }
                        items(group.conversations, key = { it.id }) { conv ->
                            DrawerConversationRow(
                                conv = conv,
                                selected = conv.id == activeConversationId,
                                onClick = { onSelect(conv.id) },
                                onDelete = { onDelete(conv.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerConversationRow(
    conv: ConversationEntity,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) Color(0x1F8B5CF6) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(8.dp).background(Primary, RoundedCornerShape(50)))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                conv.title.ifBlank { "New chat" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) Primary else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${conv.modelId} · ${ConversationGroups.timeLabel(conv.updatedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = TextSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: MessageEntity,
    onCopy: () -> Unit,
    onLongPress: () -> Unit
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
                    if (isUser) Primary else SurfaceHigh,
                    RoundedCornerShape(18.dp)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isUser) Color.White else TextPrimary
            )
            if (!isUser) {
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.End).clickable(onClick = onCopy)
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Copy",
                        tint = TextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Copy", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun ThinkingBubble() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val d1 by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "dot1"
    )
    val d2 by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, delayMillis = 200), RepeatMode.Reverse),
        label = "dot2"
    )
    val d3 by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, delayMillis = 400), RepeatMode.Reverse),
        label = "dot3"
    )
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(SurfaceHigh, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Thinking", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                Spacer(Modifier.width(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(alpha = d1)
                    Spacer(Modifier.width(4.dp))
                    Dot(alpha = d2)
                    Spacer(Modifier.width(4.dp))
                    Dot(alpha = d3)
                }
            }
        }
    }
}

@Composable
private fun Dot(alpha: Float) {
    Box(
        modifier = Modifier
            .size(6.dp)
            .background(Primary.copy(alpha = alpha), RoundedCornerShape(50))
    )
}

@Composable
private fun StreamingBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(SurfaceHigh, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(6.dp).background(Success, RoundedCornerShape(50)))
                Spacer(Modifier.width(5.dp))
                Text("Generating…", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun ThinkingTracePanel(
    info: ThinkingInfo,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClick = onToggle)
        ) {
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text("How the AI thought", style = MaterialTheme.typography.labelMedium, color = Primary)
        }
        AnimatedVisibility(visible = expanded) {
            AppCard(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    TraceRow("Read the question and loaded the conversation context.")
                    TraceRow("Reasoned about the best answer (Hard thinking).")
                    TraceRow("Generated ${info.tokens} tokens in ${info.elapsedSec}s at ${info.tps} tok/s.")
                    TraceRow("Response length: ${info.responseChars} characters.")
                    TraceRow("Memory usage was recorded in the device logs (AICHATHUB_MEM).")
                }
            }
        }
    }
}

@Composable
private fun TraceRow(text: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Box(modifier = Modifier.size(5.dp).background(Primary, RoundedCornerShape(50)).align(Alignment.CenterVertically))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}

@Composable
private fun ThinkingModeRow(
    mode: String,
    onSelect: (String) -> Unit,
    enabled: Boolean
) {
    val modes = listOf("INSTANT" to "Instant", "DEFAULT" to "Default", "HARD" to "Hard")
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
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
                        color = if (mode == id) TextPrimary else TextSecondary
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Primary,
                    containerColor = SurfaceElevated
                )
            )
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun FullScreenEditor(
    message: MessageEntity,
    onSave: (String) -> Unit,
    onCopyAll: () -> Unit,
    onClose: () -> Unit
) {
    var text by remember(message.id) { mutableStateOf(message.content) }
    var toolsMenu by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().background(NearBlack).imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Close", tint = TextPrimary)
            }
            Text(
                "Edit message",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            Box {
                IconButton(onClick = { toolsMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Tools", tint = TextSecondary)
                }
                DropdownMenu(expanded = toolsMenu, onDismissRequest = { toolsMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Copy all text", color = TextPrimary) },
                        onClick = {
                            toolsMenu = false
                            onCopyAll()
                        }
                    )
                }
            }
        }
        HorizontalDivider(color = Color(0xFF232330))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
            minLines = 8,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Primary,
                unfocusedBorderColor = SurfaceHigh,
                focusedContainerColor = SurfaceHigh,
                unfocusedContainerColor = SurfaceHigh,
                cursorColor = Primary
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GradientButton(
                text = "Save",
                onClick = { onSave(text) },
                icon = Icons.Filled.Check,
                enabled = text.isNotBlank()
            )
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
            placeholder = { Text("Ask your local AI anything…", color = TextSecondary) },
            enabled = !generating,
            minLines = 1,
            maxLines = 5,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.weight(1f),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Primary,
                unfocusedBorderColor = SurfaceHigh,
                focusedContainerColor = SurfaceElevated,
                unfocusedContainerColor = SurfaceElevated,
                cursorColor = Primary
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