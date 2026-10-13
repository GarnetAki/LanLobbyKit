package dev.ebagamedev.lobby.ui

import androidx.compose.ui.graphics.Color
import dev.ebagamedev.lobby.core.DefaultPlayerColors

/** Палитра цветов игроков (совпадает с серверной, авто-назначение при входе). */
object PlayerColors {
    val palette: List<Long> = DefaultPlayerColors.palette

    /** ARGB Long → Compose Color. НЕ через toULong(): Color(ULong) — другая упаковка, крэшит отрисовку. */
    fun colorOf(argb: Long): Color = Color(argb)
}
