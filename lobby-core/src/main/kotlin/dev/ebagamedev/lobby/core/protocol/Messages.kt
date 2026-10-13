package dev.ebagamedev.lobby.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Scenario(
    val id: String,
    val title: String,
    val description: String? = null,
)

@Serializable
data class PlayerInfo(
    val playerId: String,
    val nick: String,
    val avatarPngBase64: String? = null,
    val color: Long? = null,
    val ready: Boolean = false,
    val connected: Boolean = true,
)

@Serializable
data class LobbyState(
    val players: List<PlayerInfo>,
    val hostId: String,
    val scenarios: List<Scenario>,
    val selectedScenarioId: String? = null,
    val settings: JsonElement? = null,
    val port: Int,
)

@Serializable
enum class PlayerSessionEvent { DISCONNECTED, RECONNECTED }
