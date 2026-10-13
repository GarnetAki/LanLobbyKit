package dev.ebagamedev.lobby.core.spi

import dev.ebagamedev.lobby.core.client.LobbyClient
import dev.ebagamedev.lobby.core.protocol.Scenario
import kotlinx.serialization.json.JsonElement

/** Контракт игры для LanLobbyKit. Регистрируется через META-INF/services (ServiceLoader). */
interface LobbyGame {
    val gameId: String                 // "cherniyboomer"
    val gameName: String
    val appId: String                  // для ~/.<appId>/ профиля
    fun scenarios(): List<Scenario>    // миссии/сэйвы/уровни

    /** Вызывается при GameStarted: игра забирает LobbyClient и открывает своё окно. */
    fun onGameStarted(client: LobbyClient, settings: JsonElement?)

    /** Хост: игрок playerId вернулся в игру после обрыва — отправить ему снапшот
     *  через client.sendGame(payload, toPlayerId = playerId). Вызывается только на хосте. */
    fun onPlayerRejoin(playerId: String, client: LobbyClient) {}
}
