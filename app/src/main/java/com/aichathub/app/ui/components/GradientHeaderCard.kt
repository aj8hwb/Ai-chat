package com.aichathub.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.aichathub.app.ui.theme.GradientPrimary

@Composable
fun GradientHeaderCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .background(Brush.linearGradient(GradientPrimary), RoundedCornerShape(24.dp))
            .padding(20.dp)
    ) {
        content()
    }
}
