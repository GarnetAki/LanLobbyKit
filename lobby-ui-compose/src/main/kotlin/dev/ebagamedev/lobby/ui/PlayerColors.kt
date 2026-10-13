package dev.ebagamedev.lobby.ui

import androidx.compose.ui.graphics.Color

/** Фиксированная палитра цветов игроков (0xAARRGGBB). */
object PlayerColors {
    val palette: List<Long> = listOf(
        0xFFF44336, // red
        0xFF2196F3, // blue
        0xFF4CAF50, // green
        0xFFFFEB3B, // yellow
        0xFF9C27B0, // purple
        0xFFFF9800, // orange
        0xFF00BCD4, // cyan
        0xFFE91E63, // pink
    )

    /** ARGB Long → Compose Color. НЕ через toULong(): Color(ULong) — другая упаковка, крэшит отрисовку. */
    fun colorOf(argb: Long): Color = Color(argb)
}
