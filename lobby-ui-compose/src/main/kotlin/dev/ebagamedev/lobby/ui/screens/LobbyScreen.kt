package dev.ebagamedev.lobby.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.ebagamedev.lobby.core.client.ConnectionState
import dev.ebagamedev.lobby.core.client.LobbyClient
import dev.ebagamedev.lobby.core.protocol.LobbyState
import dev.ebagamedev.lobby.core.protocol.PlayerInfo
import dev.ebagamedev.lobby.ui.LobbyGameUi
import dev.ebagamedev.lobby.ui.PlayerColors

@Composable
fun LobbyScreen(game: LobbyGameUi, client: LobbyClient, hostedPort: Int?) {
    val lobby by client.lobbyState.collectAsState()
    val myId by client.myPlayerId.collectAsState()
    val isHost by client.isHost.collectAsState()
    val connection by client.connectionState.collectAsState()

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Лобби — ${game.gameName}", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(16.dp))
            when (connection) {
                ConnectionState.Connecting -> Text("Переподключение…", color = MaterialTheme.colorScheme.error)
                ConnectionState.Disconnected -> Text("Отключено", color = MaterialTheme.colorScheme.error)
                else -> {}
            }
            Spacer(Modifier.weight(1f))
            if (isHost && hostedPort != null) {
                Text("Порт: $hostedPort", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(16.dp))

        val state = lobby
        if (state == null) {
            Text("Подключение…")
            return@Column
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(state.players) { player ->
                PlayerRow(
                    player = player,
                    isHost = player.playerId == state.hostId,
                    canKick = isHost && player.playerId != myId,
                    onKick = { client.kick(player.playerId) },
                )
                HorizontalDivider()
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Цвет", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        ColorPalette(state, myId) { client.setColor(it) }
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            val me = state.players.firstOrNull { it.playerId == myId }
            Button(onClick = { client.setReady(!(me?.ready ?: false)) }) {
                Text(if (me?.ready == true) "Не готов" else "Готов")
            }
            Spacer(Modifier.width(16.dp))
            if (isHost) {
                ScenarioPicker(state, client)
                Spacer(Modifier.width(16.dp))
                val allReady = state.players.filter { it.connected }.all { it.ready }
                Button(enabled = allReady, onClick = { client.startGame() }) { Text("Старт") }
            }
        }

        if (isHost) {
            Spacer(Modifier.height(16.dp))
            game.SettingsContent(state.settings) { client.updateSettings(it) }
        }
    }
}

@Composable
private fun PlayerRow(player: PlayerInfo, isHost: Boolean, canKick: Boolean, onKick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).alpha(if (player.connected) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarImage(player.avatarPngBase64, 40.dp)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(16.dp).background(player.color?.let { PlayerColors.colorOf(it) } ?: Color.Gray, CircleShape))
        Spacer(Modifier.width(12.dp))
        Text(player.nick, style = MaterialTheme.typography.bodyLarge)
        if (isHost) {
            Spacer(Modifier.width(8.dp))
            Text("★ хост", color = MaterialTheme.colorScheme.primary)
        }
        if (!player.connected) {
            Spacer(Modifier.width(8.dp))
            Text("отключился", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.weight(1f))
        if (player.ready) Text("✓ готов", color = MaterialTheme.colorScheme.primary)
        if (canKick) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onKick) { Text("Кик") }
        }
    }
}

@Composable
private fun ColorPalette(state: LobbyState, myId: String?, onPick: (Long) -> Unit) {
    val taken = state.players.filter { it.playerId != myId }.mapNotNull { it.color }.toSet()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlayerColors.palette.forEach { color ->
            val isTaken = color in taken
            OutlinedButton(
                enabled = !isTaken,
                onClick = { onPick(color) },
                modifier = Modifier.size(36.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Box(Modifier.size(20.dp).background(PlayerColors.colorOf(color), CircleShape))
            }
        }
    }
}

@Composable
private fun ScenarioPicker(state: LobbyState, client: LobbyClient) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.scenarios.firstOrNull { it.id == state.selectedScenarioId }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(selected?.title ?: "Сценарий…")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.scenarios.forEach { scenario ->
                DropdownMenuItem(
                    text = { Text(scenario.title) },
                    onClick = {
                        client.selectScenario(scenario.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
