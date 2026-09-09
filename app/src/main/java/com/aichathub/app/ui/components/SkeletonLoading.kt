package com.aichathub.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shimmer effect for skeleton loading states.
 */
@Composable
fun shimmerBrush(
    shimmerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    targetValue: Float = 1000f
): Brush {
    val shimmerTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerAlpha = shimmerTransition.animateFloat(
        initialValue = 0f,
        targetValue = targetValue,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )

    return Brush.linearGradient(
        colors = listOf(
            shimmerColor.copy(alpha = 0.6f),
            shimmerColor.copy(alpha = 0.2f),
            shimmerColor.copy(alpha = 0.6f)
        ),
        start = Offset(0f, 0f),
        end = Offset(shimmerAlpha.value, shimmerAlpha.value)
    )
}

/**
 * Skeleton placeholder for text content.
 */
@Composable
fun TextSkeleton(
    modifier: Modifier = Modifier,
    width: Float = 1f,
    height: Int = 16
) {
    Box(
        modifier = modifier
            .fillMaxWidth(width)
            .height(height.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(shimmerBrush())
    )
}

/**
 * Skeleton placeholder for circular elements (avatars, icons).
 */
@Composable
fun CircleSkeleton(
    modifier: Modifier = Modifier,
    size: Int = 48
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(shimmerBrush())
    )
}

/**
 * Skeleton placeholder for card content.
 */
@Composable
fun CardSkeleton(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row {
            CircleSkeleton(size = 48)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                TextSkeleton(height = 20)
                Spacer(Modifier.height(8.dp))
                TextSkeleton(width = 0.6f, height = 14)
            }
        }
        Spacer(Modifier.height(12.dp))
        TextSkeleton(height = 14)
        Spacer(Modifier.height(4.dp))
        TextSkeleton(width = 0.8f, height = 14)
    }
}

/**
 * Skeleton for model card.
 */
@Composable
fun ModelCardSkeleton(
    modifier: Modifier = Modifier
) {
    CardSkeleton(modifier)
}

/**
 * Skeleton for list of items.
 */
@Composable
fun ListSkeleton(
    modifier: Modifier = Modifier,
    itemCount: Int = 5
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(itemCount) {
            CardSkeleton()
        }
    }
}
