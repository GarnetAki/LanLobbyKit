# LanLobbyKit

Переиспользуемый LAN-лобби-кит для кооперативных игр (JVM desktop): профили игроков
(UUID/ник/аватар), лобби с цветами и ready-check, встроенный сервер у хоста,
авто-реконнект через `Rejoin(profileId)`, канал игровых сообщений после старта,
SPI для игр через ServiceLoader.

Стек: Kotlin 2.1.21, JVM 21, Ktor 3 (Netty server + CIO client, WebSocket),
kotlinx-serialization-json, Compose Multiplatform (desktop).

## Модули

- `lobby-core` — headless: протокол, `LobbyServer`, `LobbyClient`, `ProfileStore`, SPI (`LobbyGame`).
- `lobby-ui-compose` — окна профиль/меню/лобби на Compose Desktop, точка входа `LanLobby.run(game)`, SPI `LobbyGameUi`.
- `demo` — две мини-игры (`pingpong`, `roulette`) и запускалка `demo:app`.

## Использование в игре

```kotlin
// build.gradle.kts
repositories { mavenLocal() }
dependencies { implementation("dev.ebagamedev:lobby-ui-compose:0.1.0") }

// игра реализует LobbyGameUi и регистрируется через
// META-INF/services/dev.ebagamedev.lobby.core.spi.LobbyGame
fun main() = LanLobby.run(MyGame())
```

В активной разработке вместо mavenLocal — composite build: `includeBuild("../LanLobbyKit")`.

Демо: `./gradlew :demo:app:run` (два процесса на одной машине: один «Хост», второй «Присоединиться» на 127.0.0.1).

## Разработка

```bash
./gradlew build                                            # сборка + все тесты
./gradlew :lobby-core:publishToMavenLocal \
          :lobby-ui-compose:publishToMavenLocal            # публикация в ~/.m2
```

Дизайн-спека: `docs/superpowers/specs/2026-10-13-lanlobbykit-design.md`.
План итерации 0: `docs/superpowers/plans/2026-10-13-lanlobbykit-iteration-0.md`.
