package dev.ebagamedev.lobby.ui

import androidx.compose.runtime.Composable
import dev.ebagamedev.lobby.core.spi.LobbyGame
import kotlinx.serialization.json.JsonElement

/** UI-расширение игры: Compose-панель настроек в лобби (рендерится только у хоста). */
interface LobbyGameUi : LobbyGame {
    @Composable
    fun SettingsContent(settings: JsonElement?, onChange: (JsonElement) -> Unit)
}
