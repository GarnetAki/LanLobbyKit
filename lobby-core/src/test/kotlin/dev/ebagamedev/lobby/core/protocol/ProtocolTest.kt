package dev.ebagamedev.lobby.core.protocol

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProtocolTest {

    @Test
    fun clientMsgRoundTrip() {
        val msgs: List<ClientMsg> = listOf(
            ClientMsg.Join("p1", "Nick", "aGVsbG8="),
            ClientMsg.Join("p1", "Nick"),
            ClientMsg.Rejoin("p1"),
            ClientMsg.SetColor(0xFFFF5722),
            ClientMsg.SetReady(true),
            ClientMsg.SetReady(false),
            ClientMsg.Kick("p2"),
            ClientMsg.SelectScenario("m1"),
            ClientMsg.UpdateSettings(buildJsonObject { put("x", 1) }),
            ClientMsg.StartGame,
            ClientMsg.ReturnToLobby,
            ClientMsg.GameMsg(JsonPrimitive("hi"), "p3"),
            ClientMsg.GameMsg(JsonPrimitive("hi")),
        )
        msgs.forEach { assertEquals(it, it.encodeToJson().decodeClientMsg()) }
    }

    @Test
    fun serverMsgRoundTrip() {
        val state = LobbyState(
            players = listOf(
                PlayerInfo("p1", "Host", "aGVsbG8=", 0xFFFF5722, ready = true, connected = true),
                PlayerInfo("p2", "Guest"),
            ),
            hostId = "p1",
            scenarios = listOf(Scenario("m1", "Mission 1"), Scenario("m2", "Mission 2", "desc")),
            selectedScenarioId = "m1",
            settings = buildJsonObject { put("x", 1) },
            port = 25565,
        )
        val msgs: List<ServerMsg> = listOf(
            ServerMsg.Welcome("p1", isHost = true),
            ServerMsg.Error("nick taken"),
            ServerMsg.LobbyStateMsg(state),
            ServerMsg.GameStarted(buildJsonObject { put("x", 1) }),
            ServerMsg.GameStarted(null),
            ServerMsg.GameMsg("p2", JsonPrimitive("ping")),
            ServerMsg.HostDisconnected,
            ServerMsg.PlayerSessionInfo("p2", "Guest", PlayerSessionEvent.DISCONNECTED),
            ServerMsg.PlayerSessionInfo("p2", "Guest", PlayerSessionEvent.RECONNECTED),
        )
        msgs.forEach { assertEquals(it, it.encodeToJson().decodeServerMsg()) }
    }

    @Test
    fun unknownKeysIgnored() {
        assertEquals(ClientMsg.StartGame, """{"type":"start_game","futureField":42}""".decodeClientMsg())
        assertEquals(ServerMsg.HostDisconnected, """{"type":"host_disconnected","futureField":{}}""".decodeServerMsg())
    }

    @Test
    fun defaultsEncoded() {
        assertTrue(ClientMsg.Join("p", "n").encodeToJson().contains("avatarPngBase64"))
    }

    @Test
    fun lobbyStateDefaultsRoundTrip() {
        val state = LobbyState(
            players = emptyList(),
            hostId = "p1",
            scenarios = emptyList(),
            port = 9000,
        )
        assertEquals(state, LobbyJson.decodeFromString(LobbyState.serializer(), LobbyJson.encodeToString(LobbyState.serializer(), state)))
    }
}
