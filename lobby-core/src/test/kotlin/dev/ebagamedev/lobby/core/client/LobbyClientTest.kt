package dev.ebagamedev.lobby.core.client

import dev.ebagamedev.lobby.core.profile.PlayerProfile
import dev.ebagamedev.lobby.core.protocol.Scenario
import dev.ebagamedev.lobby.core.server.LobbyServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LobbyClientTest {

    private val scenarios = listOf(Scenario("m1", "Mission 1"))
    private var server: LobbyServer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = mutableListOf<LobbyClient>()

    @AfterTest
    fun tearDown() {
        runBlocking {
            clients.forEach { it.shutdown() }
            server?.stop()
            scope.cancel()
        }
    }

    private suspend fun newServer(): Int {
        val s = LobbyServer(scenarios, requestedPort = 0)
        server = s
        return s.start()
    }

    private fun newClient(): LobbyClient {
        val c = LobbyClient(scope, initialBackoffMs = 50)
        clients += c
        return c
    }

    private fun profile(id: String, nick: String) = PlayerProfile(profileId = id, nick = nick)

    private suspend fun awaitConnectionState(c: LobbyClient, state: ConnectionState) {
        withTimeout(5000) { c.connectionState.first { it == state } }
    }

    private suspend fun connectClient(port: Int, profileId: String, nick: String): LobbyClient {
        val c = newClient()
        c.connect("127.0.0.1", port, profile(profileId, nick))
        awaitConnectionState(c, ConnectionState.InLobby)
        return c
    }

    private suspend fun LobbyClient.playerId(): String =
        withTimeout(5000) { myPlayerId.first { it != null }!! }

    @Test
    fun `connect reaches InLobby and receives lobby state`() = runBlocking {
        val port = newServer()
        val host = connectClient(port, "prof-host", "Host")
        assertNotNull(host.myPlayerId.value)
        assertTrue(host.isHost.value)
        awaitConnectionState(host, ConnectionState.InLobby)
        withTimeout(5000) { host.lobbyState.first { it != null && it.players.any { p -> p.nick == "Host" } } }
        Unit
    }

    @Test
    fun `two clients see each other and game msgs flow`() = runBlocking {
        val port = newServer()
        val host = connectClient(port, "prof-host", "Host")
        val a = connectClient(port, "prof-a", "Alice")

        withTimeout(5000) { host.lobbyState.first { it?.players?.size == 2 } }

        host.setReady(true)
        a.setReady(true)
        host.startGame()
        awaitConnectionState(host, ConnectionState.InGame)
        awaitConnectionState(a, ConnectionState.InGame)

        val aId = a.playerId()
        a.sendGame(JsonPrimitive("hi"))
        val gm = withTimeout(5000) { host.incomingGame.first() }
        assertEquals(JsonPrimitive("hi"), gm.payload)
        assertEquals(aId, gm.fromPlayerId)
        Unit
    }

    @Test
    fun `auto reconnect after socket drop`() = runBlocking {
        val port = newServer()
        val host = connectClient(port, "prof-host", "Host")
        val a = connectClient(port, "prof-a", "Alice")
        a.setColor(0xFFFF5722)
        withTimeout(5000) { host.lobbyState.first { s -> s?.players?.first { it.nick == "Alice" }?.color == 0xFFFF5722L } }

        server!!.forceDisconnect(a.playerId())

        // StateFlow конфлатирует — Connecting можно пропустить; реконнект доказывает событие Reconnected
        withTimeout(5000) { a.events.first { it == LobbyEvent.Reconnected } }
        awaitConnectionState(a, ConnectionState.InLobby)
        // цвет сохранился на сервере
        withTimeout(5000) {
            host.lobbyState.first { s ->
                s?.players?.first { it.nick == "Alice" }?.let { it.connected && it.color == 0xFFFF5722L } == true
            }
        }
        Unit
    }

    @Test
    fun `kicked client does not reconnect`() = runBlocking {
        val port = newServer()
        val host = connectClient(port, "prof-host", "Host")
        val a = connectClient(port, "prof-a", "Alice")

        host.kick(a.playerId())
        withTimeout(5000) { a.events.first { it is LobbyEvent.Kicked } }
        awaitConnectionState(a, ConnectionState.Disconnected)
        delay(500)
        assertEquals(ConnectionState.Disconnected, a.connectionState.value)
        Unit
    }

    @Test
    fun `host disconnect ends session`() = runBlocking {
        val port = newServer()
        val host = connectClient(port, "prof-host", "Host")
        val a = connectClient(port, "prof-a", "Alice")

        host.disconnect()
        withTimeout(5000) { a.events.first { it is LobbyEvent.HostDisconnectedEvent } }
        awaitConnectionState(a, ConnectionState.Disconnected)
        Unit
    }

    @Test
    fun `reconnect in game keeps game channel`() = runBlocking {
        val port = newServer()
        val host = connectClient(port, "prof-host", "Host")
        val a = connectClient(port, "prof-a", "Alice")
        host.setReady(true)
        a.setReady(true)
        host.startGame()
        awaitConnectionState(host, ConnectionState.InGame)
        awaitConnectionState(a, ConnectionState.InGame)

        server!!.forceDisconnect(a.playerId())
        withTimeout(5000) { a.events.first { it == LobbyEvent.Reconnected } }
        awaitConnectionState(a, ConnectionState.InGame)

        a.sendGame(JsonPrimitive("after-reconnect"))
        val gm = withTimeout(5000) { host.incomingGame.first() }
        assertEquals(JsonPrimitive("after-reconnect"), gm.payload)
        Unit
    }
}
