package dev.ebagamedev.lobby.core.client

import dev.ebagamedev.lobby.core.profile.PlayerProfile
import dev.ebagamedev.lobby.core.protocol.ClientMsg
import dev.ebagamedev.lobby.core.protocol.ServerMsg
import dev.ebagamedev.lobby.core.protocol.decodeServerMsg
import dev.ebagamedev.lobby.core.protocol.encodeToJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory

class LobbyClient(
    private val scope: CoroutineScope,
    private val initialBackoffMs: Long = 500,
    private val maxBackoffMs: Long = 30_000,
) {

    private val logger = LoggerFactory.getLogger(LobbyClient::class.java)
    private val http = HttpClient(CIO) { install(WebSockets) }

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _lobbyState = MutableStateFlow<dev.ebagamedev.lobby.core.protocol.LobbyState?>(null)
    val lobbyState: StateFlow<dev.ebagamedev.lobby.core.protocol.LobbyState?> = _lobbyState.asStateFlow()

    private val _myPlayerId = MutableStateFlow<String?>(null)
    val myPlayerId: StateFlow<String?> = _myPlayerId.asStateFlow()

    private val _isHost = MutableStateFlow(false)
    val isHost: StateFlow<Boolean> = _isHost.asStateFlow()

    private val _events = MutableSharedFlow<LobbyEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<LobbyEvent> = _events.asSharedFlow()

    private val _incomingGame = MutableSharedFlow<IncomingGameMsg>(extraBufferCapacity = 64)
    val incomingGame: SharedFlow<IncomingGameMsg> = _incomingGame.asSharedFlow()

    private val sendMutex = Mutex()
    private var session: DefaultClientWebSocketSession? = null
    private var job: Job? = null

    private var host: String = ""
    private var port: Int = 0
    private var profile: PlayerProfile? = null
    private var avatarPngBase64: String? = null

    @Volatile private var intentionalDisconnect = false
    @Volatile private var sessionOver = false // kicked или host disconnected — реконнект запрещён
    @Volatile private var everConnected = false

    fun connect(host: String, port: Int, profile: PlayerProfile, avatarPngBase64: String? = null) {
        job?.cancel()
        this.host = host
        this.port = port
        this.profile = profile
        this.avatarPngBase64 = avatarPngBase64
        intentionalDisconnect = false
        sessionOver = false
        job = scope.launch { runLoop() }
    }

    private suspend fun runLoop() {
        var backoff = initialBackoffMs
        while (kotlinx.coroutines.currentCoroutineContext().isActive && !intentionalDisconnect && !sessionOver) {
            _connectionState.value = ConnectionState.Connecting
            try {
                val s = http.webSocketSession("ws://$host:$port/ws")
                sendMutex.withLock { session = s }
                val first: ClientMsg = if (everConnected) {
                    ClientMsg.Rejoin(profile!!.profileId)
                } else {
                    ClientMsg.Join(profile!!.profileId, profile!!.nick, avatarPngBase64)
                }
                s.send(Frame.Text(first.encodeToJson()))
                consume(s)
            } catch (e: Throwable) {
                logger.debug("connection attempt/consume ended: {}", e.message)
            } finally {
                sendMutex.withLock { session = null }
            }
            if (intentionalDisconnect || sessionOver) break
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(maxBackoffMs)
        }
        _connectionState.value = ConnectionState.Disconnected
    }

    private suspend fun consume(s: DefaultClientWebSocketSession) {
        for (frame in s.incoming) {
            if (frame !is Frame.Text) continue
            val msg = try {
                frame.readText().decodeServerMsg()
            } catch (e: Exception) {
                logger.warn("dropping unparseable server frame: {}", e.message)
                continue
            }
            when (msg) {
                is ServerMsg.Welcome -> {
                    _myPlayerId.value = msg.playerId
                    _isHost.value = msg.isHost
                    _connectionState.value = ConnectionState.InLobby
                    if (everConnected) {
                        _events.emit(LobbyEvent.Reconnected)
                    }
                    everConnected = true
                }
                is ServerMsg.LobbyStateMsg -> _lobbyState.value = msg.state
                is ServerMsg.GameStarted -> {
                    _connectionState.value = ConnectionState.InGame
                    _events.emit(LobbyEvent.GameStartedEvent(msg.settings))
                }
                is ServerMsg.GameMsg -> _incomingGame.emit(IncomingGameMsg(msg.fromPlayerId, msg.payload))
                is ServerMsg.PlayerSessionInfo ->
                    _events.emit(LobbyEvent.PlayerSession(msg.playerId, msg.nick, msg.event))
                is ServerMsg.Error -> {
                    if (msg.reason == "kicked") {
                        sessionOver = true
                        _events.emit(LobbyEvent.Kicked)
                    } else {
                        _events.emit(LobbyEvent.ErrorReceived(msg.reason))
                    }
                }
                ServerMsg.HostDisconnected -> {
                    sessionOver = true
                    _events.emit(LobbyEvent.HostDisconnectedEvent)
                }
            }
        }
    }

    private fun send(msg: ClientMsg) {
        val s = session
        if (s == null) {
            logger.debug("send while offline, dropped: {}", msg::class.simpleName)
            return
        }
        scope.launch {
            sendMutex.withLock {
                try {
                    s.send(Frame.Text(msg.encodeToJson()))
                } catch (e: Throwable) {
                    logger.debug("send failed (will reconnect): {}", e.message)
                }
            }
        }
    }

    fun setColor(color: Long) = send(ClientMsg.SetColor(color))
    fun setReady(ready: Boolean) = send(ClientMsg.SetReady(ready))
    fun kick(playerId: String) = send(ClientMsg.Kick(playerId))
    fun selectScenario(scenarioId: String) = send(ClientMsg.SelectScenario(scenarioId))
    fun updateSettings(settings: JsonElement) = send(ClientMsg.UpdateSettings(settings))
    fun startGame() = send(ClientMsg.StartGame)
    fun returnToLobby() = send(ClientMsg.ReturnToLobby)
    fun sendGame(payload: JsonElement, toPlayerId: String? = null) = send(ClientMsg.GameMsg(payload, toPlayerId))

    /** Намеренный выход: сокет закрывается, авто-реконнект не выполняется. Клиент можно connect() снова. */
    fun disconnect() {
        intentionalDisconnect = true
        val s = session
        scope.launch {
            runCatching { s?.close() }
            job?.join()
        }
    }

    /** Полная остановка: disconnect + закрытие http-движка. После shutdown клиент не переиспользуется. */
    fun shutdown() {
        intentionalDisconnect = true
        sessionOver = true
        val s = session
        scope.launch {
            runCatching { s?.close() }
            job?.join()
            http.close()
        }
    }
}
