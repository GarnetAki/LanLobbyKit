package dev.ebagamedev.lobby.core.server

import dev.ebagamedev.lobby.core.protocol.ClientMsg
import dev.ebagamedev.lobby.core.protocol.PlayerSessionEvent
import dev.ebagamedev.lobby.core.protocol.Scenario
import dev.ebagamedev.lobby.core.protocol.ServerMsg
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LobbyServerRejoinTest {

    private val scenarios = listOf(Scenario("m1", "Mission 1"))
    private var server: LobbyServer? = null
    private val clients = mutableListOf<RawWsClient>()
    private val rejoinCalls = mutableListOf<String>()
    private var lastSnapshotSend: (suspend (JsonElement) -> Unit)? = null

    @AfterTest
    fun tearDown() {
        runBlocking {
            clients.forEach { it.close() }
            server?.stop()
        }
    }

    private suspend fun newServer(): Int {
        val s = LobbyServer(scenarios, requestedPort = 0, hooks = GameSessionHooks { playerId, send ->
            rejoinCalls += playerId
            lastSnapshotSend = send
        })
        server = s
        return s.start()
    }

    private suspend fun join(port: Int, profileId: String, nick: String): Pair<RawWsClient, ServerMsg.Welcome> {
        val c = RawWsClient(port)
        clients += c
        c.connect()
        c.send(ClientMsg.Join(profileId, nick))
        return c to c.await<ServerMsg.Welcome>()
    }

    private suspend fun rejoin(port: Int, profileId: String): Pair<RawWsClient, ServerMsg.Welcome> {
        val c = RawWsClient(port)
        clients += c
        c.connect()
        c.send(ClientMsg.Rejoin(profileId))
        return c to c.await<ServerMsg.Welcome>()
    }

    private suspend fun lobbyOfTwo(port: Int): Pair<Pair<RawWsClient, ServerMsg.Welcome>, Pair<RawWsClient, ServerMsg.Welcome>> {
        val host = join(port, "prof-host", "Host")
        val a = join(port, "prof-a", "Alice")
        host.first.awaitState { it.players.size == 2 }
        return host to a
    }

    private suspend fun startGame(host: RawWsClient, vararg others: RawWsClient) {
        host.send(ClientMsg.SetReady(true))
        others.forEach { it.send(ClientMsg.SetReady(true)) }
        host.send(ClientMsg.StartGame)
        host.await<ServerMsg.GameStarted>()
        others.forEach { it.await<ServerMsg.GameStarted>() }
    }

    private suspend fun awaitTrue(timeoutMs: Long = 5000, pred: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!pred()) delay(50)
        }
    }

    @Test
    fun `rejoin in lobby restores state`() = runBlocking {
        val port = newServer()
        val (hostP, aP) = lobbyOfTwo(port)
        val host = hostP.first
        val a = aP.first
        val aWelcome = aP.second

        a.send(ClientMsg.SetColor(0xFF112233))
        a.send(ClientMsg.SetReady(true))
        host.awaitState { s ->
            s.players.first { it.nick == "Alice" }.let { it.color == 0xFF112233L && it.ready }
        }

        a.close()
        val disc = host.await<ServerMsg.PlayerSessionInfo>()
        assertEquals(PlayerSessionEvent.DISCONNECTED, disc.event)
        host.awaitState { s -> !s.players.first { it.nick == "Alice" }.connected }

        val (a2, a2Welcome) = rejoin(port, "prof-a")
        assertEquals(aWelcome.playerId, a2Welcome.playerId)
        assertFalse(a2Welcome.isHost)
        val st = a2.awaitState { s -> s.players.first { it.nick == "Alice" }.connected }
        val me = st.players.first { it.nick == "Alice" }
        assertEquals(0xFF112233L, me.color)
        assertTrue(me.ready)

        val rec = host.await<ServerMsg.PlayerSessionInfo>()
        assertEquals(PlayerSessionEvent.RECONNECTED, rec.event)
        assertEquals("Alice", rec.nick)
        Unit
    }

    @Test
    fun `rejoin in game calls hook and game msgs flow`() = runBlocking {
        val port = newServer()
        val (hostP, aP) = lobbyOfTwo(port)
        val host = hostP.first
        val a = aP.first
        val aWelcome = aP.second
        startGame(host, a)

        a.close()
        host.awaitState { s -> !s.players.first { it.nick == "Alice" }.connected }

        val (a2, a2Welcome) = rejoin(port, "prof-a")
        assertEquals(aWelcome.playerId, a2Welcome.playerId)
        awaitTrue { rejoinCalls.isNotEmpty() }
        assertEquals(listOf(aWelcome.playerId), rejoinCalls)

        // хук может отправить снапшот вернувшемуся игроку
        lastSnapshotSend!!.invoke(JsonPrimitive("snapshot-42"))
        val snap = a2.await<ServerMsg.GameMsg>()
        assertEquals(JsonPrimitive("snapshot-42"), snap.payload)

        // игровой канал работает после реконнекта
        a2.send(ClientMsg.GameMsg(JsonPrimitive("pong")))
        val gm = host.await<ServerMsg.GameMsg>()
        assertEquals(JsonPrimitive("pong"), gm.payload)
        assertEquals(aWelcome.playerId, gm.fromPlayerId)
        Unit
    }

    @Test
    fun `rejoin unknown profile rejected`() = runBlocking {
        val port = newServer()
        lobbyOfTwo(port)
        val c = RawWsClient(port)
        clients += c
        c.connect()
        c.send(ClientMsg.Rejoin("no-such-profile"))
        assertEquals("unknown profile", c.await<ServerMsg.Error>().reason)
        withTimeout(5000) { c.closed.await() }
        Unit
    }

    @Test
    fun `game msg targeted and broadcast`() = runBlocking {
        val port = newServer()
        val host = join(port, "prof-host", "Host")
        val a = join(port, "prof-a", "Alice")
        val b = join(port, "prof-b", "Bob")
        startGame(host.first, a.first, b.first)

        // адресно: только B
        a.first.send(ClientMsg.GameMsg(JsonPrimitive("for-b"), b.second.playerId))
        val gmB = b.first.await<ServerMsg.GameMsg>()
        assertEquals(JsonPrimitive("for-b"), gmB.payload)
        assertEquals(a.second.playerId, gmB.fromPlayerId)
        assertNull(host.first.nextOrNull(400))

        // broadcast: host и B получают, отправитель — нет
        a.first.send(ClientMsg.GameMsg(JsonPrimitive("all")))
        assertEquals(JsonPrimitive("all"), host.first.await<ServerMsg.GameMsg>().payload)
        assertEquals(JsonPrimitive("all"), b.first.await<ServerMsg.GameMsg>().payload)
        assertNull(a.first.nextOrNull(400))
        Unit
    }

    @Test
    fun `host disconnect shuts down server`() = runBlocking {
        val port = newServer()
        val (hostP, _) = lobbyOfTwo(port)
        join(port, "prof-b", "Bob")
        val a = clients[1]
        val b = clients[2]

        hostP.first.close()
        a.await<ServerMsg.HostDisconnected>()
        b.await<ServerMsg.HostDisconnected>()
        awaitTrue { server?.isRunning == false }
        Unit
    }

    @Test
    fun `abrupt client loss does not crash server`() = runBlocking {
        val port = newServer()
        val (hostP, _) = lobbyOfTwo(port)
        join(port, "prof-b", "Bob")
        val host = hostP.first
        val a = clients[1]
        val b = clients[2]

        a.abort()
        host.awaitState { s -> !s.players.first { it.nick == "Alice" }.connected }

        // сервер жив, остальные продолжают получать состояние
        host.send(ClientMsg.SetReady(true))
        b.awaitState { s -> s.players.first { it.nick == "Host" }.ready }
        assertTrue(server!!.isRunning)
        Unit
    }

    @Test
    fun `return to lobby resets ready and phase`() = runBlocking {
        val port = newServer()
        val (hostP, aP) = lobbyOfTwo(port)
        val host = hostP.first
        val a = aP.first
        startGame(host, a)

        host.send(ClientMsg.ReturnToLobby)
        val st = host.awaitState { s -> s.players.isNotEmpty() && s.players.all { !it.ready } }
        assertEquals(2, st.players.size)
        a.awaitState { s -> s.players.all { !it.ready } }

        // снова лобби: кик разрешён
        host.send(ClientMsg.Kick(aP.second.playerId))
        assertEquals("kicked", a.await<ServerMsg.Error>().reason)
        host.awaitState { s -> s.players.size == 1 }
        Unit
    }

    @Test
    fun `kick in game rejected`() = runBlocking {
        val port = newServer()
        val (hostP, aP) = lobbyOfTwo(port)
        val host = hostP.first
        val a = aP.first
        startGame(host, a)

        host.send(ClientMsg.Kick(aP.second.playerId))
        assertEquals("not in lobby", host.await<ServerMsg.Error>().reason)

        // A по-прежнему в игре
        a.send(ClientMsg.GameMsg(JsonPrimitive("still-here")))
        assertEquals(JsonPrimitive("still-here"), host.await<ServerMsg.GameMsg>().payload)
        Unit
    }

    @Test
    fun `join rejected while game in progress`() = runBlocking {
        val port = newServer()
        val (hostP, aP) = lobbyOfTwo(port)
        startGame(hostP.first, aP.first)

        val c = RawWsClient(port)
        clients += c
        c.connect()
        c.send(ClientMsg.Join("prof-late", "Latecomer"))
        assertEquals("game in progress", c.await<ServerMsg.Error>().reason)
        withTimeout(5000) { c.closed.await() }
        Unit
    }

    @Test
    fun `game msg in lobby rejected`() = runBlocking {
        val port = newServer()
        val (hostP, _) = lobbyOfTwo(port)
        hostP.first.send(ClientMsg.GameMsg(JsonPrimitive("too-early")))
        assertEquals("not in game", hostP.first.await<ServerMsg.Error>().reason)
        Unit
    }
}
