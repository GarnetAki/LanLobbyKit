package dev.ebagamedev.lobby.core.server

import dev.ebagamedev.lobby.core.protocol.ClientMsg
import dev.ebagamedev.lobby.core.protocol.Scenario
import dev.ebagamedev.lobby.core.protocol.ServerMsg
import dev.ebagamedev.lobby.core.protocol.encodeToJson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LobbyServerLobbyTest {

    private val scenarios = listOf(Scenario("m1", "Mission 1"), Scenario("m2", "Mission 2"))
    private var server: LobbyServer? = null
    private val clients = mutableListOf<RawWsClient>()

    @AfterTest
    fun tearDown() {
        runBlocking {
            clients.forEach { it.close() }
            server?.stop()
        }
    }

    private suspend fun newServer(requestedPort: Int = 0): Int {
        val s = LobbyServer(scenarios, requestedPort)
        server = s
        return s.start()
    }

    private suspend fun join(port: Int, profileId: String, nick: String): Pair<RawWsClient, ServerMsg.Welcome> {
        val c = RawWsClient(port)
        clients += c
        c.connect()
        c.send(ClientMsg.Join(profileId, nick))
        val welcome = c.await<ServerMsg.Welcome>()
        return c to welcome
    }

    @Test
    fun `join lobby color ready start`() = runBlocking {
        val port = newServer()
        val (host, hostWelcome) = join(port, "prof-host", "Host")
        val (a, aWelcome) = join(port, "prof-a", "Alice")
        assertTrue(hostWelcome.isHost)
        assertFalse(aWelcome.isHost)
        assertNotEquals(hostWelcome.playerId, aWelcome.playerId)

        val state = host.awaitState { it.players.size == 2 }
        assertEquals(hostWelcome.playerId, state.hostId)
        assertEquals(listOf("Host", "Alice"), state.players.map { it.nick })
        assertEquals(scenarios, state.scenarios)

        a.send(ClientMsg.SetColor(0xFFFF5722))
        host.awaitState { s -> s.players.first { it.nick == "Alice" }.color == 0xFFFF5722L }

        a.send(ClientMsg.SetReady(true))
        val afterReady = host.awaitState { s -> s.players.first { it.nick == "Alice" }.ready }
        assertFalse(afterReady.players.first { it.nick == "Host" }.ready)

        host.send(ClientMsg.StartGame)
        host.await<ServerMsg.Error>() // не все ready
        host.send(ClientMsg.SetReady(true))
        host.send(ClientMsg.StartGame)
        host.await<ServerMsg.GameStarted>()
        a.await<ServerMsg.GameStarted>()
        Unit
    }

    @Test
    fun `duplicate nick rejected case insensitive`() = runBlocking {
        val port = newServer()
        join(port, "prof-1", "Bob")
        val c2 = RawWsClient(port)
        clients += c2
        c2.connect()
        c2.send(ClientMsg.Join("prof-2", "bob"))
        val err = c2.await<ServerMsg.Error>()
        assertEquals("nick taken", err.reason)
        withTimeout(5000) { c2.closed.await() }
    }

    @Test
    fun `duplicate color rejected`() = runBlocking {
        val port = newServer()
        join(port, "prof-1", "A")
        val (b, _) = join(port, "prof-2", "B")
        b.send(ClientMsg.SetColor(1))
        b.awaitState { s -> s.players.first { it.nick == "B" }.color == 1L }
        // игрок A ставит тот же цвет
        val aClient = clients[0]
        aClient.send(ClientMsg.SetColor(1))
        val err = aClient.await<ServerMsg.Error>()
        assertEquals("color taken", err.reason)
        // триггерим новый broadcast и проверяем, что цвет A не применился
        b.send(ClientMsg.SetReady(true))
        aClient.awaitState { s -> s.players.size == 2 && s.players.first { it.nick == "A" }.color == null }
        Unit
    }

    @Test
    fun `host-only commands rejected for guest`() = runBlocking {
        val port = newServer()
        join(port, "prof-1", "Host")
        val (a, _) = join(port, "prof-2", "Guest")
        a.send(ClientMsg.StartGame)
        assertEquals("not host", a.await<ServerMsg.Error>().reason)
        a.send(ClientMsg.SelectScenario("m1"))
        assertEquals("not host", a.await<ServerMsg.Error>().reason)
        a.send(ClientMsg.Kick("whoever"))
        assertEquals("not host", a.await<ServerMsg.Error>().reason)
        Unit
    }

    @Test
    fun `kick in lobby removes player`() = runBlocking {
        val port = newServer()
        val (host, _) = join(port, "prof-1", "Host")
        val (a, aWelcome) = join(port, "prof-2", "Alice")
        host.send(ClientMsg.Kick(aWelcome.playerId))
        assertEquals("kicked", a.await<ServerMsg.Error>().reason)
        withTimeout(5000) { a.closed.await() }
        host.awaitState { s -> s.players.none { it.nick == "Alice" } }
        Unit
    }

    @Test
    fun `occupied port probes next`() = runBlocking {
        ServerSocket(0).use { blocker ->
            val p = blocker.localPort
            val s = LobbyServer(scenarios, requestedPort = p)
            server = s
            val actual = s.start()
            assertEquals(p + 1, actual)
            assertEquals(actual, s.actualPort)
            val (c, _) = join(actual, "prof-1", "Host")
            val state = c.awaitState { true }
            assertEquals(actual, state.port)
        }
    }

    @Test
    fun `first message not join or rejoin rejected`() = runBlocking {
        val port = newServer()
        val c = RawWsClient(port)
        clients += c
        c.connect()
        c.send(ClientMsg.SetReady(true))
        assertEquals("first message must be join or rejoin", c.await<ServerMsg.Error>().reason)
        withTimeout(5000) { c.closed.await() }
        // сервер жив: другой клиент может присоединиться
        val (ok, _) = join(port, "prof-1", "Host")
        ok.awaitState { it.players.size == 1 }
        Unit
    }

    @Test
    fun `garbage frame logged and dropped`() = runBlocking {
        val port = newServer()
        val (c, _) = join(port, "prof-1", "Host")
        c.sendRaw("not json{{{")
        // соединение живо: следующая команда обрабатывается
        c.send(ClientMsg.SetReady(true))
        c.awaitState { s -> s.players.first { it.nick == "Host" }.ready }
        Unit
    }
}
