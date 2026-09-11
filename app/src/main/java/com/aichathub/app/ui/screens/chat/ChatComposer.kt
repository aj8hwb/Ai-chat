package com.aichathub.app.ui.screens.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aichathub.app.ui.components.GradientButton

@Composable
fun ChatComposer(
    input: String,
    generating: Boolean,
    isLoadingModel: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
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

private fun androidx.compose.material3.Text(
    text: String,
    color: androidx.compose.ui.graphics.Color
) {
    // Placeholder - actual Text is imported from Material3
}
