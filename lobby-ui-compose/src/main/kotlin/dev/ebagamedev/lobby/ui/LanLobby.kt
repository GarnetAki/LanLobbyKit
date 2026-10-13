package dev.ebagamedev.lobby.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.ebagamedev.lobby.core.client.LobbyClient
import dev.ebagamedev.lobby.core.client.LobbyEvent
import dev.ebagamedev.lobby.core.protocol.PlayerSessionEvent
import dev.ebagamedev.lobby.ui.screens.LobbyScreen
import dev.ebagamedev.lobby.ui.screens.MenuScreen
import dev.ebagamedev.lobby.ui.screens.ProfileScreen
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement

object LanLobby {

    private enum class Screen { Profile, Menu, Lobby }

    /** Показывает Compose-лобби; после GameStarted окно закрывается и вызывается game.onGameStarted. */
    fun run(game: LobbyGameUi) {
        val controller = LobbyController(game)
        var startedWith: Pair<LobbyClient, JsonElement?>? = null

        application {
            var screen by remember { mutableStateOf(Screen.Profile) }
            val snackbar = remember { SnackbarHostState() }
            val uiScope = androidx.compose.runtime.rememberCoroutineScope()
            val client = controller.client

            LaunchedEffect(client) {
                client?.events?.collect { event ->
                    when (event) {
                        is LobbyEvent.GameStartedEvent -> {
                            startedWith = client to event.settings
                            exitApplication()
                        }
                        is LobbyEvent.Kicked -> {
                            controller.leave()
                            screen = Screen.Menu
                            snackbar.showSnackbar("Вас кикнули из лобби")
                        }
                        is LobbyEvent.HostDisconnectedEvent -> {
                            controller.leave()
                            screen = Screen.Menu
                            snackbar.showSnackbar("Хост отключился")
                        }
                        is LobbyEvent.ErrorReceived -> snackbar.showSnackbar(event.reason)
                        is LobbyEvent.PlayerSession -> snackbar.showSnackbar(
                            if (event.event == PlayerSessionEvent.DISCONNECTED) "${event.nick} отключился"
                            else "${event.nick} вернулся",
                        )
                        LobbyEvent.Reconnected -> snackbar.showSnackbar("Переподключено")
                    }
                }
            }

            Window(onCloseRequest = ::exitApplication, title = game.gameName) {
                MaterialTheme {
                    Scaffold(
                        snackbarHost = { SnackbarHost(snackbar) },
                    ) { padding ->
                        val modifier = Modifier.fillMaxSize().padding(padding)
                        when (screen) {
                            Screen.Profile -> Box_(modifier) { ProfileScreen(controller) { screen = Screen.Menu } }
                            Screen.Menu -> Box_(modifier) {
                                MenuScreen(
                                    controller,
                                    onConnected = { screen = Screen.Lobby },
                                    onError = { msg -> uiScope.launch { snackbar.showSnackbar(msg) } },
                                )
                            }
                            Screen.Lobby -> Box_(modifier) {
                                val c = controller.client
                                if (c != null) LobbyScreen(game, c, controller.hostedPort)
                            }
                        }
                    }
                }
            }
        }

        val started = startedWith
        if (started != null) {
            game.onGameStarted(started.first, started.second)
        } else {
            runBlocking { controller.shutdown() }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Box_(modifier: Modifier, content: @androidx.compose.runtime.Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(modifier) { content() }
}
