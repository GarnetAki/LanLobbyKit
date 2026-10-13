package dev.ebagamedev.lobby.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerColorsTest {
    @Test
    fun `palette colors convert to identical ARGB`() {
        // регрессия: Color(value.toULong()) ломал упаковку и крэшил отрисовку (Index 54 out of bounds)
        PlayerColors.palette.forEach { c ->
            assertEquals(c, PlayerColors.colorOf(c).toArgb().toLong() and 0xFFFFFFFFL)
        }
    }
}
