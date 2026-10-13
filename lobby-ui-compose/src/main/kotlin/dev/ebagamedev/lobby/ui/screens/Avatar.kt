package dev.ebagamedev.lobby.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Dp
import java.util.Base64
import javax.imageio.ImageIO

fun decodeAvatarPng(base64: String?): ImageBitmap? =
    base64?.let {
        runCatching {
            ImageIO.read(Base64.getDecoder().decode(it).inputStream()).toComposeImageBitmap()
        }.getOrNull()
    }

@Composable
fun AvatarImage(base64: String?, size: Dp, modifier: Modifier = Modifier) {
    val bitmap = decodeAvatarPng(base64)
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = modifier.size(size))
    }
}
