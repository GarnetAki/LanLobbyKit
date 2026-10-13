package dev.ebagamedev.lobby.core.client

import dev.ebagamedev.lobby.core.protocol.PlayerSessionEvent
import kotlinx.serialization.json.JsonElement

sealed interface LobbyEvent {
    data class ErrorReceived(val reason: String) : LobbyEvent
    data object Kicked : LobbyEvent
    data class GameStartedEvent(val settings: JsonElement?) : LobbyEvent
    data object HostDisconnectedEvent : LobbyEvent
    data class PlayerSession(val playerId: String, val nick: String, val event: PlayerSessionEvent) : LobbyEvent
    data object Reconnected : LobbyEvent
}

data class IncomingGameMsg(val fromPlayerId: String, val payload: JsonElement)
