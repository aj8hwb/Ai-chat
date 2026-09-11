package com.aichathub.app.util

import android.content.Context
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.transform.CircleCropTransformation
import coil.transform.RoundedCornersTransformation

/**
 * Image loading utility using Coil.
 * Provides efficient image loading with caching and transformations.
 */
object ImageLoader {
    /**
     * Load an image from a URL with optional transformations.
     */
    @Composable
    fun NetworkImage(
        url: String?,
        contentDescription: String?,
        modifier: Modifier = Modifier,
        contentScale: ContentScale = ContentScale.Crop,
        shape: Shape = RoundedCornerShape(8.dp),
        placeholder: ImageVector? = null,
        error: ImageVector? = null,
        size: Dp? = null
    ) {
        val context = LocalContext.current

        val request = remember(url, context) {
            ImageRequest.Builder(context)
                .data(url)
                .crossfade(true)
                .apply {
                    size?.let { size(it.dp, it.dp) }
                }
                .build()
        }

        val painter = rememberAsyncImagePainter(
            model = request,
            contentScale = contentScale
        )

        Box(
            modifier = modifier
                .then(if (size != null) Modifier.size(size) else Modifier)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            when (painter.state) {
                is AsyncImagePainter.State.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .align(Alignment.Center),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp
                    )
                }
                is AsyncImagePainter.State.Error -> {
                    error?.let {
                        Icon(
                            it,
                            contentDescription = contentDescription,
                            modifier = Modifier
                                .size(24.dp)
                                .align(Alignment.Center),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                is AsyncImagePainter.State.Success -> {
                    AsyncImage(
                        model = request,
                        contentDescription = contentDescription,
                        modifier = modifier.fillMaxSize(),
                        contentScale = contentScale,
                        painter = painter
                    )
                }
                else -> {
                    placeholder?.let {
                        Icon(
                            it,
                            contentDescription = contentDescription,
                            modifier = Modifier
                                .size(24.dp)
                                .align(Alignment.Center),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    /**
     * Load a circular image (avatar) from a URL.
     */
    @Composable
    fun AvatarImage(
        url: String?,
        contentDescription: String?,
        modifier: Modifier = Modifier,
        size: Dp = 48.dp
    ) {
        NetworkImage(
            url = url,
            contentDescription = contentDescription,
            modifier = modifier,
            shape = CircleShape,
            size = size,
            placeholder = Icons.Filled.Image,
            error = Icons.Filled.BrokenImage
        )
    }

    /**
     * Load a rounded image from a URL.
     */
    @Composable
    fun RoundedImage(
        url: String?,
        contentDescription: String?,
        modifier: Modifier = Modifier,
        size: Dp? = null,
        cornerRadius: Dp = 8.dp
    ) {
        NetworkImage(
            url = url,
            contentDescription = contentDescription,
            modifier = modifier,
            shape = RoundedCornerShape(cornerRadius),
            size = size,
            placeholder = Icons.Filled.Image,
            error = Icons.Filled.BrokenImage
        )
    }
}
