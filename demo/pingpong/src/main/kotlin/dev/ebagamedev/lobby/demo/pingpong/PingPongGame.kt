package dev.ebagamedev.lobby.demo.pingpong

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.ebagamedev.lobby.core.client.LobbyClient
import dev.ebagamedev.lobby.core.protocol.Scenario
import dev.ebagamedev.lobby.ui.LobbyGameUi
import dev.ebagamedev.lobby.ui.LobbyTheme
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Тривиальная демо-игра: общий счётчик, «Ping» рассылает его всем. Проверяет игровой канал и реконнект. */
class PingPongGame : LobbyGameUi {

    override val gameId = "demo-pingpong"
    override val gameName = "Ping Pong"
    override val appId = "lanlobbykit-demo-pingpong"

    @Volatile
    private var currentCount = 0

    override fun scenarios(): List<Scenario> = listOf(
        Scenario("save-1", "Сейв 1"),
        Scenario("save-2", "Сейв 2"),
    )

    @Composable
    override fun SettingsContent(settings: JsonElement?, onChange: (JsonElement) -> Unit) {
        val current = settings?.jsonObject?.get("startCounter")?.jsonPrimitive?.intOrNull ?: 0
        var text by remember(current) { mutableStateOf(current.toString()) }
        OutlinedTextField(
            value = text,
            onValueChange = { v ->
                text = v.filter(Char::isDigit)
                onChange(buildJsonObject { put("startCounter", text.toIntOrNull() ?: 0) })
            },
            label = { Text("Начальный счётчик") },
            singleLine = true,
        )
    }

    override fun onGameStarted(client: LobbyClient, settings: JsonElement?) {
        application {
            var count by remember { mutableIntStateOf(settings?.jsonObject?.get("startCounter")?.jsonPrimitive?.intOrNull ?: 0) }
            LaunchedEffect(Unit) {
                client.incomingGame.collect { msg ->
                    msg.payload.jsonObject["count"]?.jsonPrimitive?.intOrNull?.let {
                        count = it
                        currentCount = it
                    }
                }
            }
            Window(onCloseRequest = ::exitApplication, title = "Ping Pong") {
                LobbyTheme {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("$count", style = MaterialTheme.typography.displayLarge)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = {
                            count++
                            currentCount = count
                            client.sendGame(buildJsonObject { put("count", count) })
                        }) { Text("Ping") }
                    }
                }
            }
        }
    }

    override fun onPlayerRejoin(playerId: String, client: LobbyClient) {
        // снапшот вернувшемуся: текущий счётчик
        client.sendGame(buildJsonObject { put("count", currentCount) }, toPlayerId = playerId)
    }
}
