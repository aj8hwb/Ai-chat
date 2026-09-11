package com.aichathub.app.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.aichathub.app.ui.components.AppCard
import com.aichathub.app.ui.screens.ThinkingInfo

@Composable
fun LiveThinkingTracePanel(
    trace: List<String>,
    expanded: Boolean,
    liveThinkingSec: Int,
    contextTokensMax: Int,
    onToggle: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggle)
                .padding(vertical = 4.dp, horizontal = 4.dp)
        ) {
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("Thinking… ${liveThinkingSec}s", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (contextTokensMax > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "ctx $contextTokensMax",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            AppCard(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp)
                ) {
                    trace.forEach { line -> TraceRow(line) }
                }
            }
        }
    }
}

@Composable
fun ThinkingTracePanel(
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
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text("How the AI thought", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        AnimatedVisibility(visible = expanded) {
            AppCard(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    TraceRow("Generated ${info.tokens} tokens in ${info.elapsedSec}s at ${info.tps} tok/s.")
                    TraceRow("Response length: ${info.responseChars} characters.")
                    TraceRow("Ran entirely on-device with llama.cpp.")
                    TraceRow("Memory usage was recorded in the device logs (AICHATHUB_MEM).")
                }
            }
        }
    }
}

@Composable
fun TraceRow(text: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Box(modifier = Modifier.size(5.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)).align(Alignment.CenterVertically))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
