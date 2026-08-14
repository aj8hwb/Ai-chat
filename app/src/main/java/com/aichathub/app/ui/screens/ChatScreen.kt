package com.aichathub.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichathub.app.data.local.MessageEntity
import com.aichathub.app.ui.components.AppCard
import com.aichathub.app.ui.components.GradientButton
import com.aichathub.app.ui.components.ModelIcon
import com.aichathub.app.ui.navigation.Screen
import com.aichathub.app.ui.theme.Primary
import com.aichathub.app.ui.theme.Success
import com.aichathub.app.ui.theme.SurfaceElevated
import com.aichathub.app.ui.theme.SurfaceHigh
import com.aichathub.app.ui.theme.TextPrimary
import com.aichathub.app.ui.theme.TextSecondary
import com.aichathub.app.data.model.LocalModelCatalog
import android.widget.Toast
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString

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

    LaunchedEffect(conversationId) {
        if (conversationId != null) viewModel.loadConversation(conversationId)
    }

    // Auto-scroll to bottom when messages change
    LaunchedEffect(state.messages.size, state.lastStreamedText) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size)
        }
    }

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
                    Icon(Icons.filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
            } else {
                IconButton(onClick = { viewModel.newChat() }) {
                    Icon(Icons.filled.Add, contentDescription = "New Chat", tint = TextPrimary)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.activeModelName ?: "Select a model",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(6.dp).background(Success, RoundedCornerShape(50)))
                    Spacer(Modifier.width(5.dp))
                    Text("Local · On-device", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
            }
            IconButton(onClick = { onNavigate(Screen.ChatSettings.route) }) {
                Icon(Icons.filled.Tune, contentDescription = "Chat Settings", tint = TextSecondary)
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
                        Icons.filled.Tune,
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
                        Icons.filled.ContentCopy,
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
                icon = Icons.filled.Stop,
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