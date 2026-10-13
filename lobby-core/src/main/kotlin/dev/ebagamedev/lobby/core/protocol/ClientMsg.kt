package dev.ebagamedev.lobby.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
sealed interface ClientMsg {

    /** Обязательно первое сообщение сокета (или [Rejoin]). */
    @Serializable
    @SerialName("join")
    data class Join(val profileId: String, val nick: String, val avatarPngBase64: String? = null) : ClientMsg

    @Serializable
    @SerialName("rejoin")
    data class Rejoin(val profileId: String) : ClientMsg

    /** color — 0xAARRGGBB. */
    @Serializable
    @SerialName("set_color")
    data class SetColor(val color: Long) : ClientMsg

    @Serializable
    @SerialName("set_ready")
    data class SetReady(val ready: Boolean) : ClientMsg

    /** Только хост, только в лобби. */
    @Serializable
    @SerialName("kick")
    data class Kick(val playerId: String) : ClientMsg

    /** Только хост. */
    @Serializable
    @SerialName("select_scenario")
    data class SelectScenario(val scenarioId: String) : ClientMsg

    /** Только хост. */
    @Serializable
    @SerialName("update_settings")
    data class UpdateSettings(val json: JsonElement) : ClientMsg

    /** Только хост. */
    @Serializable
    @SerialName("start_game")
    data object StartGame : ClientMsg

    /** Только хост. */
    @Serializable
    @SerialName("return_to_lobby")
    data object ReturnToLobby : ClientMsg

    /** Игровые сообщения после старта; toPlayerId == null = broadcast. */
    @Serializable
    @SerialName("game_msg")
    data class GameMsg(val payload: JsonElement, val toPlayerId: String? = null) : ClientMsg
}
