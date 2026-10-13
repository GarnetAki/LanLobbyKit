package dev.ebagamedev.lobby.core.protocol

import kotlinx.serialization.json.Json

val LobbyJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun ClientMsg.encodeToJson(): String = LobbyJson.encodeToString(ClientMsg.serializer(), this)

fun String.decodeClientMsg(): ClientMsg = LobbyJson.decodeFromString(ClientMsg.serializer(), this)

fun ServerMsg.encodeToJson(): String = LobbyJson.encodeToString(ServerMsg.serializer(), this)

fun String.decodeServerMsg(): ServerMsg = LobbyJson.decodeFromString(ServerMsg.serializer(), this)
