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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    /** Буфер 256; при переполнении (никто не читает) самые старые выбрасываются — клиент не встаёт. */
    private val _events = MutableSharedFlow<LobbyEvent>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<LobbyEvent> = _events.asSharedFlow()

    /** Игровые сообщения. Буфер 256, при переполнении старые дропаются (лог error) — собирайте promptly. */
    private val _incomingGame = MutableSharedFlow<IncomingGameMsg>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val incomingGame: SharedFlow<IncomingGameMsg> = _incomingGame.asSharedFlow()

    /** Outbox-актор: гарантирует порядок отправки; сообщения переживают разрыв и уходят после реконнекта. */
    private val outbox = Channel<ClientMsg>(Channel.UNLIMITED)

    @Volatile private var session: DefaultClientWebSocketSession? = null
    private var job: Job? = null

    private var host: String = ""
    private var port: Int = 0
    private var profile: PlayerProfile? = null
    private var avatarPngBase64: String? = null

    @Volatile private var intentionalDisconnect = false
    @Volatile private var sessionOver = false // kicked/host dead/отказ в handshake — реконнект запрещён
    @Volatile private var everConnected = false
    @Volatile private var rejoinPending = false // ждём GameStarted (игра) или LobbyState (лобби) после Rejoin

    fun connect(host: String, port: Int, profile: PlayerProfile, avatarPngBase64: String? = null) {
        job?.cancel()
        session?.let { old -> scope.launch { runCatching { old.close() } } }
        session = null
        while (outbox.tryReceive().isSuccess) { /* старые сообщения не переносим между серверами */ }
        this.host = host
        this.port = port
        this.profile = profile
        this.avatarPngBase64 = avatarPngBase64
        intentionalDisconnect = false
        sessionOver = false
        everConnected = false
        rejoinPending = false
        _lobbyState.value = null
        _myPlayerId.value = null
        _isHost.value = false
        job = scope.launch { runLoop() }
    }

    private suspend fun runLoop() {
        var backoff = initialBackoffMs
        while (currentCoroutineContext().isActive && !intentionalDisconnect && !sessionOver) {
            _connectionState.value = ConnectionState.Connecting
            try {
                val s = http.webSocketSession("ws://$host:$port/ws")
                session = s
                val first: ClientMsg = if (everConnected) {
                    rejoinPending = true
                    ClientMsg.Rejoin(profile!!.profileId)
                } else {
                    ClientMsg.Join(profile!!.profileId, profile!!.nick, avatarPngBase64)
                }
                s.send(Frame.Text(first.encodeToJson()))
                val sender = launchSender(s)
                try {
                    consume(s)
                } finally {
                    // иначе старый сендер на мёртвом сокете конкурирует за outbox с новым
                    sender.cancel()
                }
            } catch (e: Throwable) {
                logger.debug("connection attempt/consume ended: {}", e.message)
            } finally {
                session = null
            }
            if (intentionalDisconnect || sessionOver) break
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(maxBackoffMs)
        }
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun launchSender(s: DefaultClientWebSocketSession): Job =
        scope.launch {
            for (msg in outbox) {
                try {
                    s.send(Frame.Text(msg.encodeToJson()))
                    logger.debug("sent {}", msg::class.simpleName)
                } catch (e: Throwable) {
                    logger.debug("send failed (will reconnect): {}", e.message)
                    break
                }
            }
        }

    private suspend fun consume(s: DefaultClientWebSocketSession) {
        var welcomed = false
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
                    welcomed = true
                    _myPlayerId.value = msg.playerId
                    _isHost.value = msg.isHost
                    if (rejoinPending) {
                        // ждём GameStarted/LobbyState, чтобы выставить фазу
                        emitEvent(LobbyEvent.Reconnected)
                    } else {
                        _connectionState.value = ConnectionState.InLobby
                    }
                    everConnected = true
                }
                is ServerMsg.LobbyStateMsg -> {
                    _lobbyState.value = msg.state
                    if (rejoinPending) {
                        rejoinPending = false
                        _connectionState.value = ConnectionState.InLobby
                    }
                }
                is ServerMsg.GameStarted -> {
                    _connectionState.value = ConnectionState.InGame
                    if (rejoinPending) {
                        // восстановление после реконнекта — не новый старт игры
                        rejoinPending = false
                    } else {
                        emitEvent(LobbyEvent.GameStartedEvent(msg.settings))
                    }
                }
                is ServerMsg.GameMsg ->
                    if (!_incomingGame.tryEmit(IncomingGameMsg(msg.fromPlayerId, msg.payload))) {
                        logger.error("incomingGame buffer overflow, message dropped (collect incomingGame!)")
                    }
                is ServerMsg.PlayerSessionInfo ->
                    emitEvent(LobbyEvent.PlayerSession(msg.playerId, msg.nick, msg.event))
                is ServerMsg.Error -> {
                    if (msg.reason == "kicked") {
                        sessionOver = true
                        emitEvent(LobbyEvent.Kicked)
                    } else {
                        if (!welcomed) sessionOver = true // терминальный отказ в handshake — не ретраим
                        emitEvent(LobbyEvent.ErrorReceived(msg.reason))
                    }
                }
                ServerMsg.HostDisconnected -> {
                    sessionOver = true
                    emitEvent(LobbyEvent.HostDisconnectedEvent)
                }
            }
        }
    }

    private fun emitEvent(event: LobbyEvent) {
        if (!_events.tryEmit(event)) {
            logger.error("events buffer overflow, dropped: {}", event)
        }
    }

    /** Ставит сообщение в outbox; порядок отправок сохраняется. В офлайне копится и уйдёт после реконнекта. */
    private fun send(msg: ClientMsg) {
        outbox.trySend(msg)
    }

    fun setColor(color: Long) = send(ClientMsg.SetColor(color))
    fun setReady(ready: Boolean) = send(ClientMsg.SetReady(ready))
    fun kick(playerId: String) = send(ClientMsg.Kick(playerId))
    fun selectScenario(scenarioId: String) = send(ClientMsg.SelectScenario(scenarioId))
    fun updateSettings(settings: JsonElement) = send(ClientMsg.UpdateSettings(settings))
    fun startGame() = send(ClientMsg.StartGame)
    fun returnToLobby() = send(ClientMsg.ReturnToLobby)
    /** Игровое сообщение. Отправки упорядочены (outbox-актор); в офлайне копятся и уходят после реконнекта. */
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
