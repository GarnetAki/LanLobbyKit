package dev.ebagamedev.lobby.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/** Тёмная тема лобби (как в SRooms). Игры могут использовать её и для своих окон. */
@Composable
fun LobbyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}
