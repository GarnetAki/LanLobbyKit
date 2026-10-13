package dev.ebagamedev.lobby.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
fun ProfileScreen(controller: LobbyController, onDone: () -> Unit) {
    var nick by remember { mutableStateOf(controller.profile.nick) }
    var avatarVersion by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Профиль", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        // avatarVersion заставляет перечитать avatar.png после смены
        @Suppress("UNUSED_EXPRESSION") avatarVersion
        AvatarImage(controller.avatarBase64(), 128.dp)
        Spacer(Modifier.height(8.dp))

        Button(onClick = {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { pickImageFile() }
                if (bytes != null) {
                    try {
                        controller.saveAvatar(bytes)
                        avatarVersion++
                        error = null
                    } catch (e: IllegalArgumentException) {
                        error = "Аватар слишком большой (макс. 64 КБ после сжатия)"
                    }
                }
            }
        }) { Text("Выбрать аватар") }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(value = nick, onValueChange = { nick = it }, label = { Text("Ник") }, singleLine = true)
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))
        Button(
            enabled = nick.isNotBlank(),
            onClick = {
                controller.saveProfile(nick.trim())
                onDone()
            },
        ) { Text("Продолжить") }
    }
}

private fun pickImageFile(): ByteArray? {
    val dialog = FileDialog(null as Frame?, "Выбор аватара", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") }
    dialog.isVisible = true
    val file = dialog.file ?: return null
    return File(dialog.directory, file).readBytes()
}
