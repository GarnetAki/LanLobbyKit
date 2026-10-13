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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ebagamedev.lobby.ui.LobbyController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Меню: слева профиль (редактируется в любой момент до входа в лобби), справа хост/подключение. */
@Composable
fun MenuScreen(controller: LobbyController, onConnected: () -> Unit, onError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var hostPort by remember { mutableStateOf("25565") }
    var joinHost by remember { mutableStateOf(controller.profile.lastHost ?: "127.0.0.1") }
    var joinPort by remember { mutableStateOf(controller.profile.lastPort?.toString() ?: "25565") }
    var busy by remember { mutableStateOf(false) }

    var nick by remember { mutableStateOf(controller.profile.nick) }
    var avatarVersion by remember { mutableIntStateOf(0) }
    var avatarError by remember { mutableStateOf<String?>(null) }

    Row(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // левая часть: профиль
        Card(Modifier.width(260.dp)) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Профиль", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                @Suppress("UNUSED_EXPRESSION") avatarVersion // инвалидирует превью после смены аватара
                AvatarImage(controller.avatarBase64(), 128.dp)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    scope.launch {
                        val bytes = withContext(Dispatchers.IO) { pickImageFile() }
                        if (bytes != null) {
                            try {
                                controller.saveAvatar(bytes)
                                avatarVersion++
                                avatarError = null
                            } catch (e: IllegalArgumentException) {
                                avatarError = "Аватар слишком большой (макс. 64 КБ)"
                            }
                        }
                    }
                }) { Text("Сменить аватар") }
                avatarError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = nick,
                    onValueChange = {
                        nick = it
                        if (it.isNotBlank()) controller.saveProfile(it.trim())
                    },
                    label = { Text("Ник") },
                    singleLine = true,
                )
            }
        }

        Spacer(Modifier.width(32.dp))

        // правая часть: хост / присоединиться
        Column {
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
                        enabled = !busy && nick.isNotBlank() && hostPort.toIntOrNull() != null,
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

            Spacer(Modifier.height(16.dp))

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
                        enabled = !busy && nick.isNotBlank() && joinHost.isNotBlank() && joinPort.toIntOrNull() != null,
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
}

private fun pickImageFile(): ByteArray? {
    val dialog = FileDialog(null as Frame?, "Выбор аватара", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name ->
        val lower = name.lowercase()
        lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
    }
    dialog.isVisible = true
    val file = dialog.file ?: return null
    return File(dialog.directory, file).readBytes()
}
