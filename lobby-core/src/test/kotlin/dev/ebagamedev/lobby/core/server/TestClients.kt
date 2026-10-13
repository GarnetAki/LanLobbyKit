package dev.ebagamedev.lobby.core.server

import dev.ebagamedev.lobby.core.protocol.ClientMsg
import dev.ebagamedev.lobby.core.protocol.ServerMsg
import dev.ebagamedev.lobby.core.protocol.decodeServerMsg
import dev.ebagamedev.lobby.core.protocol.encodeToJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Минимальный WS-клиент для серверных тестов: очередь входящих ServerMsg + await по типу. */
class RawWsClient(private val port: Int) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val http = HttpClient(CIO) { install(WebSockets) }
    private lateinit var session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession

    @PublishedApi
    internal val queue = Channel<ServerMsg>(Channel.UNLIMITED)
    val closed = CompletableDeferred<Unit>()

    suspend fun connect() {
        session = http.webSocketSession("ws://127.0.0.1:$port/ws")
        scope.launch {
            try {
                for (frame in session.incoming) {
                    if (frame is Frame.Text) {
                        try {
                            queue.send(frame.readText().decodeServerMsg())
                        } catch (_: Exception) {
                            // недекодируемое для тестов не интересно
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                closed.complete(Unit)
            }
        }
    }

    suspend fun send(msg: ClientMsg) = session.send(Frame.Text(msg.encodeToJson()))

    suspend fun sendRaw(text: String) = session.send(Frame.Text(text))

    suspend inline fun <reified T : ServerMsg> await(timeoutMs: Long = 5000): T =
        withTimeout(timeoutMs) {
            while (true) {
                val msg = queue.receive()
                if (msg is T) return@withTimeout msg
            }
            error("unreachable")
        }

    suspend fun awaitState(timeoutMs: Long = 5000, pred: (dev.ebagamedev.lobby.core.protocol.LobbyState) -> Boolean): dev.ebagamedev.lobby.core.protocol.LobbyState =
        withTimeout(timeoutMs) {
            while (true) {
                val msg = queue.receive()
                if (msg is ServerMsg.LobbyStateMsg && pred(msg.state)) return@withTimeout msg.state
            }
            error("unreachable")
        }

    fun close() {
        runCatching { kotlinx.coroutines.runBlocking { session.close() } }
        http.close()
        scope.cancel()
    }
}
