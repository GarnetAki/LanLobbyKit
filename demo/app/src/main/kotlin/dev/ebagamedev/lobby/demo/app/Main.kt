package dev.ebagamedev.lobby.demo.app

import dev.ebagamedev.lobby.core.spi.GameDiscovery
import dev.ebagamedev.lobby.ui.LanLobby
import dev.ebagamedev.lobby.ui.LobbyGameUi

fun main(args: Array<String>) {
    val games = GameDiscovery.loadGames().filterIsInstance<LobbyGameUi>()
    if (games.isEmpty()) {
        println("Не найдено ни одной игры на classpath (META-INF/services)")
        return
    }
    val byArg = args.firstOrNull { it.startsWith("--game=") }?.removePrefix("--game=")
    val chosen = when {
        byArg != null -> games.firstOrNull { it.gameId == byArg }
            ?: error("игра '$byArg' не найдена; доступны: ${games.joinToString { it.gameId }}")
        games.size == 1 -> games.first()
        else -> {
            println("Доступные игры:")
            games.forEachIndexed { i, g -> println("  ${i + 1}. ${g.gameName} (${g.gameId})") }
            print("Выбери номер: ")
            val idx = (readLine()?.toIntOrNull() ?: 1) - 1
            games.getOrElse(idx) { games.first() }
        }
    }
    println("Запуск: ${chosen.gameName}")
    LanLobby.run(chosen)
}
