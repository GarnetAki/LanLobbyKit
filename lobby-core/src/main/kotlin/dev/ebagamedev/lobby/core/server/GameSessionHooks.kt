package dev.ebagamedev.lobby.core.server

import kotlinx.serialization.json.JsonElement

/** Колбэки игры на стороне хоста. */
fun interface GameSessionHooks {
    /** Игрок playerId переподключился во время игры — игра должна отправить ему снапшот через [send]. */
    suspend fun onPlayerRejoin(playerId: String, send: suspend (JsonElement) -> Unit)

    companion object {
        val None: GameSessionHooks = GameSessionHooks { _, _ -> }
    }
}
