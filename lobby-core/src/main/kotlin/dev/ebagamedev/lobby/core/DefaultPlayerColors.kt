package dev.ebagamedev.lobby.core

/** Дефолтная палитра цветов игроков (0xAARRGGBB). Сервер назначает первый свободный при входе. */
object DefaultPlayerColors {
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
}
