package dev.ebagamedev.lobby.demo.roulette

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

/** Вторая мини-игра-заглушка: «Spin» рассылает всем случайного победителя из лобби. */
class RouletteGame : LobbyGameUi {

    override val gameId = "demo-roulette"
    override val gameName = "Roulette"
    override val appId = "lanlobbykit-demo-roulette"

    override fun scenarios(): List<Scenario> = listOf(Scenario("default", "Обычная"))

    @Composable
    override fun SettingsContent(settings: JsonElement?, onChange: (JsonElement) -> Unit) {
        Text("Настроек нет", style = MaterialTheme.typography.bodyMedium)
    }

    override fun onGameStarted(client: LobbyClient, settings: JsonElement?) {
        application {
            var winner by remember { mutableStateOf<String?>(null) }
            val lobby by client.lobbyState.collectAsState()
            LaunchedEffect(Unit) {
                client.incomingGame.collect { msg ->
                    winner = msg.payload.jsonPrimitive.content
                }
            }
            Window(onCloseRequest = ::exitApplication, title = "Roulette") {
                LobbyTheme {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            winner?.let { "Победитель: $it" } ?: "Ещё не крутили",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = {
                            val players = lobby?.players?.filter { it.connected } ?: return@Button
                            val pick = players[Random.nextInt(players.size)].nick
                            winner = pick
                            client.sendGame(JsonPrimitive(pick))
                        }) { Text("Spin") }
                    }
                }
            }
        }
    }
}
