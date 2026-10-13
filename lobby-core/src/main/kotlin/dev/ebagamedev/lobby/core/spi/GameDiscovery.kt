package dev.ebagamedev.lobby.core.spi

import java.util.ServiceLoader

/** Discovery игр на classpath через ServiceLoader (задел под launcher). */
object GameDiscovery {
    fun loadGames(): List<LobbyGame> =
        ServiceLoader.load(LobbyGame::class.java).toList()
}
