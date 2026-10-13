package dev.ebagamedev.lobby.core.server

import dev.ebagamedev.lobby.core.protocol.ClientMsg
import dev.ebagamedev.lobby.core.protocol.LobbyState
import dev.ebagamedev.lobby.core.protocol.PlayerInfo
import dev.ebagamedev.lobby.core.protocol.PlayerSessionEvent
import dev.ebagamedev.lobby.core.protocol.Scenario
import dev.ebagamedev.lobby.core.protocol.ServerMsg
import dev.ebagamedev.lobby.core.protocol.decodeClientMsg
import dev.ebagamedev.lobby.core.protocol.encodeToJson
import kotlinx.serialization.SerializationException
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import java.util.UUID

class LobbyServer(
    private val scenarios: List<Scenario>,
    private val requestedPort: Int,
    private val hooks: GameSessionHooks = GameSessionHooks.None,
) {

    private enum class Phase { Lobby, Game }

    private class ClientSlot(
        val playerId: String,
        val profileId: String,
        var nick: String,
        var avatarPngBase64: String?,
        var color: Long? = null,
        var ready: Boolean = false,
        var connected: Boolean = true,
        var session: DefaultWebSocketServerSession? = null,
    )

    private val logger = LoggerFactory.getLogger(LobbyServer::class.java)
    private val mutex = Mutex()
    private val slots = LinkedHashMap<String, ClientSlot>()
    private var phase = Phase.Lobby
    private var hostId: String? = null
    private var selectedScenarioId: String? = null
    private var settings: JsonElement? = null
    private var engine: EmbeddedServer<*, *>? = null
    private val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var actualPort: Int = 0
        private set

    val isRunning: Boolean get() = engine != null

    /** Поднимает сервер; requestedPort != 0 занят → перебор до +9. Возвращает фактический порт. */
    suspend fun start(): Int {
        val candidates = if (requestedPort == 0) listOf(0) else (requestedPort..requestedPort + 9).toList()
        var lastError: Throwable? = null
        for (port in candidates) {
            try {
                val srv = embeddedServer(Netty, port = port, host = "0.0.0.0") { module() }
                srv.start(wait = false)
                engine = srv
                actualPort = srv.engine.resolvedConnectors().first().port
                logger.info("LobbyServer started on port {}", actualPort)
                return actualPort
            } catch (e: Throwable) {
                lastError = e
                logger.warn("port {} unavailable: {}", port, e.message)
            }
        }
        throw IllegalStateException("no free port in range $requestedPort..${requestedPort + 9}", lastError)
    }

    suspend fun stop() {
        engine?.stop(1000, 2000)
        engine = null
        shutdownScope.cancel()
        logger.info("LobbyServer stopped")
    }

    private fun Application.module() {
        install(WebSockets)
        routing {
            webSocket("/ws") {
                val slot = handshake()
                if (slot == null) {
                    return@webSocket
                }
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            handleFrame(slot, frame.readText())
                        }
                    }
                } catch (e: Throwable) {
                    logger.debug("connection of {} ended: {}", slot.nick, e.message)
                } finally {
                    onSocketClosed(slot, this)
                }
            }
        }
    }

    /** Первое сообщение сокета — Join или Rejoin; иначе Error + закрытие. */
    private suspend fun DefaultWebSocketServerSession.handshake(): ClientSlot? {
        val firstText = runCatching {
            for (frame in incoming) {
                if (frame is Frame.Text) return@runCatching frame.readText()
            }
            null
        }.getOrNull() ?: return null

        val msg = try {
            firstText.decodeClientMsg()
        } catch (e: Exception) {
            logger.warn("unparseable first frame, closing: {}", e.message)
            sendErrorAndClose("first message must be join or rejoin")
            return null
        }
        return when (msg) {
            is ClientMsg.Join -> {
                val slot = ClientSlot(
                    playerId = UUID.randomUUID().toString(),
                    profileId = msg.profileId,
                    nick = msg.nick,
                    avatarPngBase64 = msg.avatarPngBase64,
                    session = this,
                )
                val error = mutex.withLock {
                    if (phase != Phase.Lobby) "game in progress"
                    else if (slots.values.any { it.nick.equals(msg.nick, ignoreCase = true) }) "nick taken"
                    else {
                        slots[slot.playerId] = slot
                        if (hostId == null) hostId = slot.playerId
                        null
                    }
                }
                if (error != null) {
                    sendErrorAndClose(error)
                    null
                } else {
                    sendTo(slot, ServerMsg.Welcome(slot.playerId, isHost = slot.playerId == hostId))
                    broadcastState()
                    slot
                }
            }
            is ClientMsg.Rejoin -> {
                val slot = mutex.withLock { slots.values.firstOrNull { it.profileId == msg.profileId } }
                if (slot == null) {
                    sendErrorAndClose("unknown profile")
                    null
                } else {
                    mutex.withLock {
                        slot.connected = true
                        slot.session = this
                    }
                    sendTo(slot, ServerMsg.Welcome(slot.playerId, isHost = slot.playerId == hostId))
                    broadcastSessionEvent(slot, PlayerSessionEvent.RECONNECTED)
                    broadcastState()
                    if (phase == Phase.Game) {
                        hooks.onPlayerRejoin(slot.playerId) { payload ->
                            sendTo(slot, ServerMsg.GameMsg(fromPlayerId = "server", payload = payload))
                        }
                    }
                    slot
                }
            }
            else -> {
                sendErrorAndClose("first message must be join or rejoin")
                null
            }
        }
    }

    private suspend fun DefaultWebSocketServerSession.handleFrame(slot: ClientSlot, text: String) {
        val msg = try {
            text.decodeClientMsg()
        } catch (e: SerializationException) {
            logger.warn("dropping unparseable frame from {}: {}", slot.nick, e.message)
            return
        } catch (e: IllegalArgumentException) {
            logger.warn("dropping unparseable frame from {}: {}", slot.nick, e.message)
            return
        }
        when (msg) {
            is ClientMsg.SetColor -> {
                val error = mutex.withLock {
                    if (phase != Phase.Lobby) "not in lobby"
                    else if (slots.values.any { it.playerId != slot.playerId && it.color == msg.color }) "color taken"
                    else {
                        slot.color = msg.color
                        null
                    }
                }
                if (error != null) sendTo(slot, ServerMsg.Error(error)) else broadcastState()
            }
            is ClientMsg.SetReady -> {
                val error = mutex.withLock {
                    if (phase != Phase.Lobby) "not in lobby"
                    else {
                        slot.ready = msg.ready
                        null
                    }
                }
                if (error != null) sendTo(slot, ServerMsg.Error(error)) else broadcastState()
            }
            is ClientMsg.Kick -> handleKick(slot, msg)
            is ClientMsg.SelectScenario -> {
                val error = mutex.withLock {
                    when {
                        slot.playerId != hostId -> "not host"
                        scenarios.none { it.id == msg.scenarioId } -> "unknown scenario"
                        else -> {
                            selectedScenarioId = msg.scenarioId
                            null
                        }
                    }
                }
                if (error != null) sendTo(slot, ServerMsg.Error(error)) else broadcastState()
            }
            is ClientMsg.UpdateSettings -> {
                val error = mutex.withLock {
                    if (slot.playerId != hostId) "not host"
                    else {
                        settings = msg.json
                        null
                    }
                }
                if (error != null) sendTo(slot, ServerMsg.Error(error)) else broadcastState()
            }
            is ClientMsg.StartGame -> handleStartGame(slot)
            is ClientMsg.ReturnToLobby -> handleReturnToLobby(slot)
            is ClientMsg.GameMsg -> handleGameMsg(slot, msg)
            is ClientMsg.Join, is ClientMsg.Rejoin -> sendTo(slot, ServerMsg.Error("already joined"))
        }
    }

    private suspend fun handleKick(kicker: ClientSlot, msg: ClientMsg.Kick) {
        val target = mutex.withLock {
            when {
                kicker.playerId != hostId -> return@withLock null to "not host"
                phase != Phase.Lobby -> return@withLock null to "not in lobby"
                else -> slots[msg.playerId] to null
            }
        }
        val (victim, error) = target
        if (error != null) {
            sendTo(kicker, ServerMsg.Error(error))
            return
        }
        if (victim == null) {
            sendTo(kicker, ServerMsg.Error("no such player"))
            return
        }
        sendTo(victim, ServerMsg.Error("kicked"))
        val session = mutex.withLock {
            slots.remove(victim.playerId)
            victim.session
        }
        session?.close()
        broadcastState()
    }

    private suspend fun handleStartGame(slot: ClientSlot) {
        val error = mutex.withLock {
            when {
                slot.playerId != hostId -> "not host"
                phase != Phase.Lobby -> "not in lobby"
                slots.values.any { it.connected && !it.ready } -> "not everyone ready"
                else -> {
                    phase = Phase.Game
                    null
                }
            }
        }
        if (error != null) {
            sendTo(slot, ServerMsg.Error(error))
            return
        }
        val snapshot = mutex.withLock { settings to slots.values.mapNotNull { it.session } }
        snapshot.second.forEach { s -> sendFrame(s, ServerMsg.GameStarted(snapshot.first).encodeToJson()) }
    }

    private suspend fun handleReturnToLobby(slot: ClientSlot) {
        val error = mutex.withLock {
            when {
                slot.playerId != hostId -> "not host"
                phase != Phase.Game -> "not in game"
                else -> {
                    phase = Phase.Lobby
                    slots.values.forEach { it.ready = false }
                    null
                }
            }
        }
        if (error != null) sendTo(slot, ServerMsg.Error(error)) else broadcastState()
    }

    private suspend fun handleGameMsg(slot: ClientSlot, msg: ClientMsg.GameMsg) {
        val targets = mutex.withLock {
            if (phase != Phase.Game) return@withLock null
            val out = ServerMsg.GameMsg(fromPlayerId = slot.playerId, payload = msg.payload)
            val sessions = if (msg.toPlayerId == null) {
                slots.values.filter { it.playerId != slot.playerId }.mapNotNull { it.session }
            } else {
                listOfNotNull(slots[msg.toPlayerId]?.session)
            }
            out.encodeToJson() to sessions
        }
        if (targets == null) {
            sendTo(slot, ServerMsg.Error("not in game"))
            return
        }
        targets.second.forEach { sendFrame(it, targets.first) }
    }

    private suspend fun onSocketClosed(slot: ClientSlot, closingSession: DefaultWebSocketServerSession) {
        val wasConnected = mutex.withLock {
            // слот мог быть удалён киком — тогда ничего не рассылаем
            if (!slots.containsKey(slot.playerId)) return@withLock false
            if (slot.session === closingSession) {
                slot.connected = false
                slot.session = null
                true
            } else {
                // старый сокет после Rejoin — слот уже перепривязан к новому
                false
            }
        }
        if (!wasConnected) return
        if (slot.playerId == hostId) {
            // смерть хоста фатальна: всем HostDisconnected и shutdown
            logger.info("host {} disconnected, shutting down", slot.nick)
            val sessions = mutex.withLock { slots.values.filter { it.connected }.mapNotNull { it.session } }
            val text = ServerMsg.HostDisconnected.encodeToJson()
            sessions.forEach { sendFrame(it, text) }
            shutdownScope.launch {
                delay(300)
                stop()
            }
            return
        }
        broadcastSessionEvent(slot, PlayerSessionEvent.DISCONNECTED)
        broadcastState()
    }

    private suspend fun DefaultWebSocketServerSession.sendErrorAndClose(reason: String) {
        runCatching { send(Frame.Text(ServerMsg.Error(reason).encodeToJson())) }
        runCatching { close() }
    }

    private suspend fun sendTo(slot: ClientSlot, msg: ServerMsg) {
        val session = mutex.withLock { slot.session } ?: return
        sendFrame(session, msg.encodeToJson(), owner = slot)
    }

    private suspend fun sendFrame(session: DefaultWebSocketServerSession, text: String, owner: ClientSlot? = null) {
        try {
            session.send(Frame.Text(text))
        } catch (e: Throwable) {
            val victim = owner ?: mutex.withLock { slots.values.firstOrNull { it.session == session } }
            logger.warn("send to {} failed: {}", victim?.nick, e.message)
            if (victim != null) {
                val changed = mutex.withLock {
                    if (slots.containsKey(victim.playerId) && victim.connected) {
                        victim.connected = false
                        victim.session = null
                        true
                    } else false
                }
                if (changed) {
                    broadcastSessionEvent(victim, PlayerSessionEvent.DISCONNECTED)
                    broadcastState()
                }
            }
        }
    }

    private suspend fun broadcastState() {
        val (state, sessions) = mutex.withLock {
            buildState() to slots.values.filter { it.connected }.mapNotNull { it.session }
        }
        val text = ServerMsg.LobbyStateMsg(state).encodeToJson()
        sessions.forEach { sendFrame(it, text) }
    }

    private suspend fun broadcastSessionEvent(slot: ClientSlot, event: PlayerSessionEvent) {
        val sessions = mutex.withLock { slots.values.filter { it.connected }.mapNotNull { it.session } }
        val text = ServerMsg.PlayerSessionInfo(slot.playerId, slot.nick, event).encodeToJson()
        sessions.forEach { sendFrame(it, text) }
    }

    private fun buildState(): LobbyState = LobbyState(
        players = slots.values.map {
            PlayerInfo(it.playerId, it.nick, it.avatarPngBase64, it.color, it.ready, it.connected)
        },
        hostId = hostId.orEmpty(),
        scenarios = scenarios,
        selectedScenarioId = selectedScenarioId,
        settings = settings,
        port = actualPort,
    )
}
