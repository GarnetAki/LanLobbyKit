# LanLobbyKit — дизайн итерации 0

Дата: 2026-10-13
Статус: на ревью

## Цель

Переиспользуемая библиотека LAN-лобби для кооперативных игр на 2–5+ игроков
(подключение по IP через LAN / Radmin VPN). Игры (CherniyBoomer на KorGE,
будущие проекты на Compose) подключают её как зависимость и получают:
профили игроков, лобби с цветами и ready-check, встроенный сервер у хоста,
авто-реконнект и канал игровых сообщений после старта.

## Не входит (non-goals)

- Игровая логика конкретных игр (BombBusters и др.) — только канал сообщений.
- Миграция хоста: смерть процесса хоста = конец сервера для всех.
- Интернет-матчмейкинг, NAT traversal, авторизация/аккаунты.
- Launcher-приложение со списком игр (закладываем SPI, сам лончер — позже).
- Android/JS/native-таргеты: только JVM desktop.

## Стек

- Kotlin 2.x, JVM 21.
- Ktor 3: `ktor-server-netty` + `ktor-server-websockets` (хост),
  `ktor-client-cio` + `ktor-client-websockets` (клиент).
- kotlinx-serialization-json.
- Compose Multiplatform (desktop) — только в модуле `lobby-ui-compose`.
- Gradle Kotlin DSL, `maven-publish` → mavenLocal; composite build
  (`includeBuild`) для одновременной разработки либы и игры.

## Структура проекта

```
LanLobbyKit/
  lobby-core/          # headless, без UI и без игровых движков
  lobby-ui-compose/    # окна профиля/меню/лобби на Compose Desktop
  demo/                # две тривиальные демо-игры + запускалка
```

### lobby-core

- `protocol/`
  - `ClientMsg` (sealed, `@Serializable`):
    `Join(profileId, nick, avatarPngBase64?)` — обязательно первое сообщение;
    `Rejoin(profileId)`;
    `SetColor(color)`, `SetReady(ready)`;
    `Kick(playerId)` — только хост, только в лобби;
    `SelectScenario(scenarioId)`, `UpdateSettings(json)` — только хост;
    `StartGame` — только хост;
    `ReturnToLobby` — только хост;
    `GameMsg(payload: JsonElement, toPlayerId: String? = null)` — игровые
    сообщения после старта (`null` = broadcast).
  - `ServerMsg` (sealed): `Welcome(playerId, isHost)`, `Error(reason)`,
    `LobbyState(players, hostId, scenarios, selectedScenarioId, settings, port)`,
    `GameStarted(settings)`, `GameMsg(fromPlayerId, payload)`,
    `HostDisconnected`, `PlayerSessionInfo` (системные события: игрок отвалился/вернулся).
  - `Protocol.kt` — единый `Json { ignoreUnknownKeys = true; encodeDefaults = true }`.
  - Нераспарсенные фреймы: лог + дроп (не молча, как в SRooms).
- `server/` — `LobbyServer` (Ktor Netty, `webSocket("/ws")`):
  - Состояние под одним `Mutex`; `LinkedHashMap<playerId, ClientSlot>`.
  - `ClientSlot` переживает обрыв сокета: `connected=false`, состояние сохраняется.
  - Первое сообщение сокета — `Join` или `Rejoin`, иначе `Error` + закрытие.
  - Уникальность ников (case-insensitive) и цветов — сервер-side.
  - Первый подключившийся — хост. Хост не передаётся; отключение хоста →
    всем `HostDisconnected` и shutdown.
  - Порт: запрошенный + перебор до +9 при занятости; фактический порт
    читается из bound-сокета и приходит в `LobbyState.port`.
  - Игровой режим: после `StartGame` сервер пересылает `GameMsg` между
    клиентами (broadcast и адресно), а на `Rejoin` во время игры вызывает
    колбэк игры `onPlayerRejoin(playerId, send)` — игра шлёт снапшот.
  - Любые сбои send → пометить игрока disconnected, не ронять сервер.
- `client/` — `LobbyClient` (Ktor CIO):
  - `StateFlow<ConnectionState>`: `Disconnected / Connecting / InLobby / InGame`.
  - `StateFlow<LobbyState?>`, `SharedFlow<LobbyEvent>` (ошибки, кик, старт).
  - Авто-реконнект с экспоненциальным backoff (до ~30 с), при восстановлении
    шлёт `Rejoin(profileId)`; работает и в лобби, и в игре.
  - `sendGame(payload)`, `incomingGame: SharedFlow<GameMsg>`.
- `profile/` — `ProfileStore` (JVM):
  - `profile.json` в `~/.lanlobbykit/` (или `~/.<appId>/`, appId задаёт игра):
    `profileId` (UUID, генерится один раз), `nick`, `lastHost`, `lastPort`.
  - `avatar.png` 128×128, при загрузке ресайз/проверка ≤ 64 КБ, base64 в `Join`.
- `spi/` — `LobbyGameModule` (см. ниже).

### SPI для игр

Два интерфейса, разнесённые по модулям, чтобы `lobby-core` не тянул Compose:

```kotlin
// lobby-core, без UI-зависимостей
interface LobbyGame {
    val gameId: String                 // "cherniyboomer"
    val gameName: String
    val appId: String                  // для ~/.<appId>/ профиля
    fun scenarios(): List<Scenario>    // миссии/сэйвы/уровни
    /** Вызывается при GameStarted: игра забирает LobbyClient и открывает своё окно. */
    fun onGameStarted(client: LobbyClient, settings: JsonElement?)
}

// lobby-ui-compose
interface LobbyGameUi : LobbyGame {
    /** Compose-панель настроек в лобби (рендерится только у хоста). */
    @Composable fun SettingsContent(settings: JsonElement?, onChange: (JsonElement) -> Unit)
}
```

Регистрация через `META-INF/services` (ServiceLoader) — задел под будущий
launcher, который найдёт все игры на classpath без обратной зависимости.
Настройки хоста (`UpdateSettings`) реплицируются всем в `LobbyState.settings`.

### lobby-ui-compose

Последовательные окна в одном процессе: Compose-окно лобби открывается первым,
после `GameStarted` закрывается, управление переходит игре (KorGE открывает
своё окно). Экраны:

1. **ProfileScreen** — ник, выбор/обрезка аватара (файл-диалог, превью).
2. **MenuScreen** — «Хост» (выбор игры из SPI-модулей на classpath, порт) /
   «Присоединиться» (IP хоста, порт; последние значения из профиля).
3. **LobbyScreen** — список игроков (аватар, ник, цвет, ready, метка хоста),
   выбор цвета из палитры (занятые недоступны), ready-кнопка;
   у хоста: выбор сценария + панель настроек модуля + кнопки «Старт»
   (активна, когда все ready) и «Кик»; у всех: индикаторы
   «игрок X отключился / вернулся».
4. Ошибки/события — snackbar; `HostDisconnected` → возврат в меню с сообщением.

### demo

- `demo-pingpong` — тривиальная «игра»: после старта игроки шлют друг другу
  `GameMsg` с счётчиком; проверяет игровой канал и реконнект в игре.
- `demo-roulette` — вторая мини-игра-заглушка, чтобы проверить выбор игры
  из SPI-списка.
- Запуск двух экземпляров на одной машине (host + join на 127.0.0.1).

## Реконнект — ключевые решения

- Идентичность = persistent `profileId` (UUID) из профиля, не ник.
- Сервер не удаляет слот при обрыве; `Rejoin(profileId)` перепривязывает сокет,
  клиент получает актуальный `LobbyState` или (в игре) снапшот через колбэк игры.
- Клиент реконнектится автоматически с backoff; UI показывает статус.
- Смерть хоста фатальна: `HostDisconnected`, клиенты в меню.

## Тестирование

- Unit-тесты `lobby-core`: сериализация протокола (round-trip), логика
  уникальности ников/цветов, ready-флаги, права хоста.
- Headless smoke-тест (`gradlew :lobby-core:test`): поднимаем `LobbyServer`
  на случайном порту + 2–3 фейк-клиента на `LobbyClient`:
  1. join → лобби, смена цвета, ready, старт;
  2. обрыв клиента в лобби → `Rejoin` → состояние совпадает;
  3. обрыв клиента в игре → `Rejoin` → колбэк `onPlayerRejoin` вызван,
     `GameMsg` ходят после реконнекта;
  4. дубликат ника/цвета → `Error`;
  5. кик в лобби; попытка кика в игре → `Error`;
  6. занятый порт → перебор, `LobbyState.port` == фактическому.
- UI и demo — ручная проверка (два окна на localhost).

## Публикация и использование в играх

- `gradlew publishToMavenLocal` → игры: `implementation("dev.ebagamedev:lobby-core:0.1.0")`,
  при желании `lobby-ui-compose`.
- В активной разработке игры подключают `includeBuild("../LanLobbyKit")`.
- Игра вызывает одну точку входа: `LanLobby.run(game: LobbyGameUi)` —
  показывает Compose-лобби и по старту дергает `game.onGameStarted`.

## Риски

- Compose-окно → KorGE-окно в одном процессе: проверить на demo + KorGE-игре
  рано (первый интеграционный шаг в CherniyBoomer). Фолбэк: отдельный
  процесс-лобби с передачей `host:port` + `Rejoin` после запуска игры —
  механизм реконнекта это покрывает, но в v1 не реализуем.
- Полный `LobbyState` при каждом изменении — для лобби ок; игровые дельты —
  ответственность игры через `GameMsg`.
