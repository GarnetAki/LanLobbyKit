package dev.ebagamedev.lobby.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ebagamedev.lobby.ui.LobbyController
import kotlinx.coroutines.launch

@Composable
fun MenuScreen(controller: LobbyController, onConnected: () -> Unit, onError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var hostPort by remember { mutableStateOf("25565") }
    var joinHost by remember { mutableStateOf(controller.profile.lastHost ?: "127.0.0.1") }
    var joinPort by remember { mutableStateOf(controller.profile.lastPort?.toString() ?: "25565") }
    var busy by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(Modifier.width(280.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Хост", style = MaterialTheme.typography.titleLarge)
                Text(controller.game.gameName, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = hostPort,
                    onValueChange = { hostPort = it.filter(Char::isDigit) },
                    label = { Text("Порт") },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = !busy && hostPort.toIntOrNull() != null,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                controller.host(hostPort.toInt())
                                onConnected()
                            } catch (e: Exception) {
                                onError("Не удалось поднять сервер: ${e.message}")
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("Создать лобби") }
            }
        }

        Spacer(Modifier.width(32.dp))

        Card(Modifier.width(280.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Присоединиться", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = joinHost, onValueChange = { joinHost = it }, label = { Text("IP хоста") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = joinPort,
                    onValueChange = { joinPort = it.filter(Char::isDigit) },
                    label = { Text("Порт") },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = !busy && joinHost.isNotBlank() && joinPort.toIntOrNull() != null,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                controller.join(joinHost.trim(), joinPort.toInt())
                                onConnected()
                            } catch (e: Exception) {
                                onError(e.message ?: "Ошибка подключения")
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("Подключиться") }
            }
        }
    }
}
