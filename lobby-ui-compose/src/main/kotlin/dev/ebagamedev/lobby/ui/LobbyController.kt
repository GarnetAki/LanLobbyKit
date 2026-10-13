package dev.ebagamedev.lobby.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ebagamedev.lobby.core.client.ConnectionState
import dev.ebagamedev.lobby.core.client.LobbyClient
import dev.ebagamedev.lobby.core.profile.PlayerProfile
import dev.ebagamedev.lobby.core.profile.ProfileStore
import dev.ebagamedev.lobby.core.server.GameSessionHooks
import dev.ebagamedev.lobby.core.server.LobbyServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Связывает UI с LobbyClient/LobbyServer/ProfileStore. Живёт независимо от Compose-композиции,
 *  чтобы клиент пережил закрытие окна лобби при старте игры. */
class LobbyController(val game: LobbyGameUi) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val profileStore = ProfileStore(game.appId)

    var profile: PlayerProfile = profileStore.loadOrCreate()
        private set

    var client by mutableStateOf<LobbyClient?>(null)
        private set

    /** Фактический порт сервера при хостинге (после перебора занятых). */
    var hostedPort by mutableStateOf<Int?>(null)
        private set

    private var server: LobbyServer? = null

    fun saveProfile(nick: String) {
        profile = profile.copy(nick = nick)
        profileStore.save(profile)
    }

    fun saveAvatar(png: ByteArray) = profileStore.saveAvatarPng(png)

    fun avatarBase64(): String? = profileStore.avatarBase64()

    /** Поднимает встроенный сервер и подключается loopback-клиентом. */
    suspend fun host(requestedPort: Int): LobbyClient {
        leave()
        val srv = LobbyServer(
            scenarios = game.scenarios(),
            requestedPort = requestedPort,
            hooks = GameSessionHooks { playerId, _ ->
                client?.let { game.onPlayerRejoin(playerId, it) }
            },
        )
        srv.start()
        server = srv
        hostedPort = srv.actualPort
        return connect("127.0.0.1", srv.actualPort)
    }

    suspend fun join(host: String, port: Int): LobbyClient {
        leave()
        val c = connect(host, port)
        scope.launch {
            c.connectionState.first { it == ConnectionState.InLobby }
            profile = profile.copy(lastHost = host, lastPort = port)
            profileStore.save(profile)
        }
        return c
    }

    private fun connect(host: String, port: Int): LobbyClient {
        val c = LobbyClient(scope)
        c.connect(host, port, profile, avatarBase64())
        client = c
        return c
    }

    suspend fun leave() {
        client?.disconnect()
        client = null
        server?.stop()
        server = null
        hostedPort = null
    }

    suspend fun shutdown() {
        client?.shutdown()
        client = null
        server?.stop()
        server = null
    }
}
