package dev.ebagamedev.lobby.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
sealed interface ServerMsg {

    @Serializable
    @SerialName("welcome")
    data class Welcome(val playerId: String, val isHost: Boolean) : ServerMsg

    @Serializable
    @SerialName("error")
    data class Error(val reason: String) : ServerMsg

    @Serializable
    @SerialName("lobby_state")
    data class LobbyStateMsg(val state: LobbyState) : ServerMsg

    @Serializable
    @SerialName("game_started")
    data class GameStarted(val settings: JsonElement?) : ServerMsg

    @Serializable
    @SerialName("game_msg")
    data class GameMsg(val fromPlayerId: String, val payload: JsonElement) : ServerMsg

    @Serializable
    @SerialName("host_disconnected")
    data object HostDisconnected : ServerMsg

    /** Системные события: игрок отвалился/вернулся. */
    @Serializable
    @SerialName("player_session")
    data class PlayerSessionInfo(val playerId: String, val nick: String, val event: PlayerSessionEvent) : ServerMsg
}
