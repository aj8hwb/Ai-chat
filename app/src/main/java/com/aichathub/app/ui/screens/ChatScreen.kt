package com.aichathub.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichathub.app.data.local.ConversationEntity
import com.aichathub.app.data.local.MessageEntity
import com.aichathub.app.ui.components.AppCard
import com.aichathub.app.ui.components.GradientButton
import com.aichathub.app.ui.components.ModelIcon
import com.aichathub.app.ui.navigation.Screen
import com.aichathub.app.ui.theme.NearBlack
import com.aichathub.app.ui.theme.Primary
import com.aichathub.app.ui.theme.Success
import com.aichathub.app.ui.theme.SurfaceElevated
import com.aichathub.app.ui.theme.SurfaceHigh
import com.aichathub.app.ui.theme.TextPrimary
import com.aichathub.app.ui.theme.TextSecondary
import com.aichathub.app.data.model.LocalModelCatalog
import com.aichathub.app.util.ConversationGroups
import android.widget.Toast
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
                                state.generating -> "Generating…"
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
                if (state.messages.isEmpty() && state.lastStreamedText.isEmpty()) {
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
                                }
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        if (state.lastStreamedText.isNotEmpty() && state.generating) {
                            item {
                                StreamingBubble(text = state.lastStreamedText)
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
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${conv.modelId} · ${ConversationGroups.timeLabel(conv.updatedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = TextSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun MessageBubble(
    message: MessageEntity,
    onCopy: () -> Unit
) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(
                    if (isUser) Primary else SurfaceHigh,
                    RoundedCornerShape(if (isUser) 18.dp else 18.dp)
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
                    modifier = Modifier.align(Alignment.End)
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