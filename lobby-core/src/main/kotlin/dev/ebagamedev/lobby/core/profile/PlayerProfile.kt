package dev.ebagamedev.lobby.core.profile

import kotlinx.serialization.Serializable

@Serializable
data class PlayerProfile(
    val profileId: String,
    val nick: String,
    val lastHost: String? = null,
    val lastPort: Int? = null,
)
