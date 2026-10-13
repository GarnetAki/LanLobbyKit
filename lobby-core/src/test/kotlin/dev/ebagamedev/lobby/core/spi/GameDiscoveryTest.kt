package dev.ebagamedev.lobby.core.spi

import dev.ebagamedev.lobby.core.client.LobbyClient
import dev.ebagamedev.lobby.core.protocol.Scenario
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertTrue

class TestGame : LobbyGame {
    override val gameId = "test-game"
    override val gameName = "Test Game"
    override val appId = "lanlobbykit-test"
    override fun scenarios(): List<Scenario> = listOf(Scenario("s1", "S1"))
    override fun onGameStarted(client: LobbyClient, settings: JsonElement?) {}
}

class GameDiscoveryTest {
    @Test
    fun `discovers service-loaded games`() {
        assertTrue(GameDiscovery.loadGames().any { it.gameId == "test-game" })
    }
}
