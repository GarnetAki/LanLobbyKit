# LanLobbyKit — Итерация 0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Собрать итерацию 0 переиспользуемого LAN-лобби-кита: модули `lobby-core` (протокол, сервер, клиент с реконнектом, профили, SPI), `lobby-ui-compose` (окна профиль/меню/лобби), `demo` (две мини-игры + запускалка) + тесты из раздела «Тестирование» спеки.

**Architecture:** Мульти-модульный Gradle-проект (JVM 21). Хост поднимает встроенный Ktor Netty WebSocket-сервер и сам подключается loopback-клиентом — единая кодовая база для хоста и клиента. Идентичность игрока = persistent `profileId` (UUID) из `~/.<appId>/profile.json`; сервер не удаляет слот при обрыве, `Rejoin(profileId)` перепривязывает сокет. Игры подключаются через SPI (`LobbyGame` в core, `LobbyGameUi` в ui, ServiceLoader).

**Tech Stack:** Kotlin 2.1.21, JVM 21, Gradle 8.14.3, Ktor 3.1.3 (server-netty/server-websockets/client-cio/client-websockets), kotlinx-serialization-json 1.8.1, kotlinx-coroutines 1.10.2, Compose Multiplatform 1.8.2 (только ui-модуль и demo).

**Spec:** `docs/superpowers/specs/2026-10-13-lanlobbykit-design.md`

## Global Constraints

- Kotlin `2.1.21`, `jvmToolchain(21)`, Gradle wrapper `8.14.3` (скопировать wrapper из `C:/Users/akira/Desktop/EbaGamedev/SRooms/gradle/wrapper` + `gradlew`, `gradlew.bat`).
- Версии зависимостей (как в SRooms, проверено в работе): Ktor `3.1.3`, kotlinx-serialization-json `1.8.1`, kotlinx-coroutines `1.10.2`, Compose plugin `1.8.2`, slf4j-simple `2.0.17`.
- `group = "dev.ebagamedev"`, `version = "0.1.0"` для публикуемых модулей; артефакты `lobby-core`, `lobby-ui-compose` через `maven-publish` → mavenLocal.
- Только JVM desktop. `lobby-core` НЕ зависит от Compose.
- Сигнатуры `LobbyGame` / `LobbyGameUi` / `LobbyClient` — контракт для параллельно разрабатываемой CherniyBoomer, не менять (см. Task 2, 6, 7, 8).
- Протокол: единый `Json { ignoreUnknownKeys = true; encodeDefaults = true }`; нераспарсенные фреймы — лог + дроп.
- Компиляция: `freeCompilerArgs.add("-Xjvm-default=all")` (как в SRooms) — чтобы default-методы интерфейсов SPI были бинарно-совместимы.
- Тесты: `kotlin("test")` на JUnit5, `kotlinx-coroutines-test:1.10.2`; сетевые тесты — `runBlocking` с реальными сокетами и таймаутами (не `runTest` с виртуальным временем).
- Слф4j: `slf4j-simple` только в runtime demo и в testRuntime.

## Review Focus

Пять сценариев, которые спека подразумевает, но которые легко уронить (каждый закреплён тестом в owning-таске):

1. Первое сообщение сокета — не `Join`/`Rejoin` (например `SetReady`) → `Error` + закрытие сокета, сервер жив (тест в Task 4).
2. Битый фрейм (не-JSON или невалидное сообщение) → лог + дроп, соединение и сервер продолжают работать (тест в Task 4).
3. Сбой send одному клиенту (мёртвый сокет) → игрок помечается disconnected, сервер и остальные клиенты не падают (тест в Task 5).
4. `Rejoin` с неизвестным `profileId` → `Error("unknown profile")` + закрытие (спека молчит; разумное поведение) (тест в Task 5).
5. Смерть хоста → всем остальным `HostDisconnected`, сервер останавливается, слоты клиентов не течь (тест в Task 5).

---

### Task 1: Gradle-каркас мульти-модульного проекта

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `.gitignore`
- Create: `lobby-core/build.gradle.kts`, `lobby-ui-compose/build.gradle.kts`
- Create: `demo/pingpong/build.gradle.kts`, `demo/roulette/build.gradle.kts`, `demo/app/build.gradle.kts`
- Copy: wrapper из `C:/Users/akira/Desktop/EbaGamedev/SRooms` (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`)
- Test: `lobby-core/src/test/kotlin/dev/ebagamedev/lobby/core/SanityTest.kt`

**Interfaces:**
- Produces: модули `:lobby-core`, `:lobby-ui-compose`, `:demo:pingpong`, `:demo:roulette`, `:demo:app`; координаты `dev.ebagamedev:lobby-core:0.1.0`, `dev.ebagamedev:lobby-ui-compose:0.1.0`.

- [ ] **Step 1: Скопировать wrapper и создать файлы каркаса**

`settings.gradle.kts`: `rootProject.name = "LanLobbyKit"`, `include("lobby-core", "lobby-ui-compose", "demo:pingpong", "demo:roulette", "demo:app")`. `gradle.properties`: как у SRooms (`org.gradle.jvmargs=-Xmx3g`, caching, parallel, `kotlin.code.style=official`). `.gitignore`: `.gradle/`, `build/`, `out/`, `.idea/`, `*.iml`, `local.properties`.

Корневой `build.gradle.kts` — только объявление версий-констант через `extra` или повторяемые литералы в модулях (каталог версий не заводим — YAGNI): kotlin `2.1.21`, ktor `3.1.3`, serialization `1.8.1`, coroutines `1.10.2`, compose `1.8.2`.

- [ ] **Step 2: `lobby-core/build.gradle.kts`**

Plugins: `kotlin("jvm")` 2.1.21, `kotlin("plugin.serialization")` 2.1.21, `maven-publish`. `jvmToolchain(21)`, `-Xjvm-default=all`, `group`/`version` из Global Constraints. Deps: `api` ktor-server-netty, ktor-server-websockets, ktor-client-cio, ktor-client-websockets, kotlinx-serialization-json, kotlinx-coroutines-core; `testImplementation` kotlin("test"), kotlinx-coroutines-test, ktor-client-cio (для тестовых фейк-клиентов), testRuntime slf4j-simple. Publishing: `MavenPublication` from `components["java"]`.

- [ ] **Step 3: `lobby-ui-compose/build.gradle.kts`**

Плюс plugins `org.jetbrains.compose` 1.8.2 и `org.jetbrains.kotlin.plugin.compose` 2.1.21. Deps: `api(project(":lobby-core"))`, compose.desktop.currentOs, compose.material3, compose.materialIconsExtended, kotlinx-coroutines-swing. Publishing аналогично. Repositories во всех модулях: mavenCentral, google, `https://maven.pkg.jetbrains.space/public/p/compose/dev` (в `settings.gradle.kts` через `dependencyResolutionManagement`).

- [ ] **Step 4: demo-модули**

`demo/pingpong` и `demo/roulette`: `kotlin("jvm")` + compose plugins, deps `api(project(":lobby-ui-compose"))` (нужен Compose для `LobbyGameUi`), compose.desktop.currentOs, material3. `demo/app`: то же + `implementation(project(":demo:pingpong"))`, `implementation(project(":demo:roulette"))`, compose desktop application с `mainClass = "dev.ebagamedev.lobby.demo.app.MainKt"`.

- [ ] **Step 5: Sanity-тест и проверка сборки**

`SanityTest.kt`: один тривиальный `@Test fun sanity() { assertEquals(4, 2 + 2) }`.

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, sanity-тест зелёный.

Run: `./gradlew :lobby-core:publishToMavenLocal :lobby-ui-compose:publishToMavenLocal`
Expected: артефакты в `~/.m2/repository/dev/ebagamedev/`.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "chore: gradle multi-module scaffold (lobby-core, lobby-ui-compose, demo)"
```

---

### Task 2: Протокол — сообщения и (де)сериализация

**Files:**
- Create: `lobby-core/src/main/kotlin/dev/ebagamedev/lobby/core/protocol/Messages.kt`
- Create: `lobby-core/src/main/kotlin/dev/ebagamedev/lobby/core/protocol/ClientMsg.kt`
- Create: `lobby-core/src/main/kotlin/dev/ebagamedev/lobby/core/protocol/ServerMsg.kt`
- Create: `lobby-core/src/main/kotlin/dev/ebagamedev/lobby/core/protocol/Protocol.kt`
- Test: `lobby-core/src/test/kotlin/dev/ebagamedev/lobby/core/protocol/ProtocolTest.kt`

**Interfaces:**
- Produces (контракт для всех остальных тасков и для CherniyBoomer):

```kotlin
// Messages.kt
@Serializable data class Scenario(val id: String, val title: String, val description: String? = null)
@Serializable data class PlayerInfo(
    val playerId: String, val nick: String, val avatarPngBase64: String? = null,
    val color: Long? = null, val ready: Boolean = false, val connected: Boolean = true,
)
@Serializable data class LobbyState(
    val players: List<PlayerInfo>, val hostId: String, val scenarios: List<Scenario>,
    val selectedScenarioId: String? = null, val settings: JsonElement? = null, val port: Int,
)
@Serializable enum class PlayerSessionEvent { DISCONNECTED, RECONNECTED }

// ClientMsg.kt — @Serializable sealed interface ClientMsg, @SerialName snake_case:
Join(profileId: String, nick: String, avatarPngBase64: String? = null)      // "join"
Rejoin(profileId: String)                                                   // "rejoin"
SetColor(color: Long)                                                       // "set_color" (0xAARRGGBB)
SetReady(ready: Boolean)                                                    // "set_ready"
Kick(playerId: String)                                                      // "kick"
SelectScenario(scenarioId: String)                                          // "select_scenario"
UpdateSettings(json: JsonElement)                                           // "update_settings"
StartGame                                                                   // "start_game"
ReturnToLobby                                                               // "return_to_lobby"
GameMsg(payload: JsonElement, toPlayerId: String? = null)                   // "game_msg"

// ServerMsg.kt — @Serializable sealed interface ServerMsg:
Welcome(playerId: String, isHost: Boolean)                                  // "welcome"
Error(reason: String)                                                       // "error"
LobbyStateMsg(state: LobbyState)                                            // "lobby_state"
GameStarted(settings: JsonElement?)                                         // "game_started"
GameMsg(fromPlayerId: String, payload: JsonElement)                         // "game_msg"
HostDisconnected                                                            // "host_disconnected"
PlayerSessionInfo(playerId: String, nick: String, event: PlayerSessionEvent)// "player_session"

// Protocol.kt
val LobbyJson: Json  // ignoreUnknownKeys = true; encodeDefaults = true
fun ClientMsg.encodeToJson(): String
fun String.decodeClientMsg(): ClientMsg
fun ServerMsg.encodeToJson(): String
fun String.decodeServerMsg(): ServerMsg
```

- [ ] **Step 1: Написать failing-тест round-trip**

```kotlin
@Test fun clientMsgRoundTrip() {
    val msgs: List<ClientMsg> = listOf(
        ClientMsg.Join("p1", "Nick", "aGVsbG8="), ClientMsg.Rejoin("p1"),
        ClientMsg.SetColor(0xFFFF5722), ClientMsg.SetReady(true),
        ClientMsg.Kick("p2"), ClientMsg.SelectScenario("m1"),
        ClientMsg.UpdateSettings(buildJsonObject { put("x", 1) }),
        ClientMsg.StartGame, ClientMsg.ReturnToLobby,
        ClientMsg.GameMsg(JsonPrimitive("hi"), "p3"), ClientMsg.GameMsg(JsonPrimitive("hi")),
    )
    msgs.forEach { assertEquals(it, it.encodeToJson().decodeClientMsg()) }
}
@Test fun serverMsgRoundTrip() { /* то же для всех 7 ServerMsg */ }
@Test fun unknownKeysIgnored() {
    assertEquals(ClientMsg.StartGame, """{"type":"start_game","futureField":42}""".decodeClientMsg())
}
@Test fun defaultsEncoded() {
    assertTrue(ClientMsg.Join("p", "n").encodeToJson().contains("avatarPngBase64"))
}
```

Serial name дискриминатора — `"type"` (дефолт kotlinx; проверить в тесте round-trip, не навязывать classDiscriminator без нужды).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :lobby-core:test --tests "*ProtocolTest*"`
Expected: FAIL (compile error — нет protocol-пакета).

- [ ] **Step 3: Реализовать протокол**

Четыре файла по интерфейсам выше; `@Serializable` на всём, object-сообщения — `@Serializable data object`. `decode*` бросают `SerializationException` — вызывающий код (server/client) ловит и дропает.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :lobby-core:test --tests "*ProtocolTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lobby-core
git commit -m "feat(core): wire protocol ClientMsg/ServerMsg + LobbyJson"
```

---

### Task 3: ProfileStore — профиль игрока и аватар

**Files:**
- Create: `lobby-core/src/main/kotlin/dev/ebagamedev/lobby/core/profile/PlayerProfile.kt`
- Create: `lobby-core/src/main/kotlin/dev/ebagamedev/lobby/core/profile/ProfileStore.kt`
- Test: `lobby-core/src/test/kotlin/dev/ebagamedev/lobby/core/profile/ProfileStoreTest.kt`

**Interfaces:**
- Produces:

```kotlin
@Serializable data class PlayerProfile(
    val profileId: String, val nick: String,
    val lastHost: String? = null, val lastPort: Int? = null,
)

class ProfileStore(appId: String, dir: Path = Path.of(System.getProperty("user.home"), ".$appId")) {
    fun loadOrCreate(): PlayerProfile          // генерит UUID при первом запуске, дефолтный ник "Player"
    fun save(profile: PlayerProfile)
    fun loadAvatarPng(): ByteArray?            // null, если avatar.png нет
    fun saveAvatarPng(source: ByteArray)       // ресайз до 128x128 PNG; IllegalArgumentException если результат > 64 КБ
    fun avatarBase64(): String?                // base64 avatar.png для Join
}
```

- [ ] **Step 1: Failing-тесты**

Во временной директории (`@TempDir`):

```kotlin
@Test fun `first load creates persistent profileId`() // loadOrCreate дважды → тот же profileId, файл существует
@Test fun `save and load roundtrip`()                  // save(nick="X", lastHost="1.2.3.4", lastPort=9000) → load совпадает
@Test fun `avatar saved resized to 128 and readable`() // сгенерить BufferedImage 500x300 → saveAvatarPng → loadAvatarPng: PNG 128x128
@Test fun `avatar over 64kb rejected`()                // шумовая картинка 512x512 (random pixels) → IllegalArgumentException
@Test fun `avatarBase64 is null without avatar`()
```

- [ ] **Step 2: Run to verify fail** — `./gradlew :lobby-core:test --tests "*ProfileStoreTest*"` → compile error.

- [ ] **Step 3: Реализовать**

`profile.json` через `LobbyJson`. Аватар: `ImageIO.read` → масштаб в 128×128 (`getScaledInstance`/Graphics2D) → `ImageIO.write` PNG → проверка размера ≤ 64*1024 → запись `avatar.png`. Base64 — `java.util.Base64`.

- [ ] **Step 4: Run to verify pass** — та же команда, PASS.

- [ ] **Step 5: Commit** — `feat(core): ProfileStore with avatar handling`

---

### Task 4: LobbyServer — фаза лобби

**Files:**
- Create: `lobby-core/src/main/kotlin/dev/ebagamedev/lobby/core/server/LobbyServer.kt`
- Create: `lobby-core/src/main/kotlin/dev/ebagamedev/lobby/core/server/GameSessionHooks.kt`
- Test: `lobby-core/src/test/kotlin/dev/ebagamedev/lobby/core/server/LobbyServerLobbyTest.kt`
- Test: `lobby-core/src/test/kotlin/dev/ebagamedev/lobby/core/server/TestClients.kt` (хелпер)

**Interfaces:**
- Consumes: протокол из Task 2.
- Produces:

```kotlin
fun interface GameSessionHooks {                       // для Task 5 (реконнект в игре)
    suspend fun onPlayerRejoin(playerId: String, send: suspend (JsonElement) -> Unit)
    companion object { val None: GameSessionHooks = GameSessionHooks { _, _ -> } }
}

class LobbyServer(
    private val scenarios: List<Scenario>,
    private val requestedPort: Int,                    // 0 = ephemeral, без перебора
    private val hooks: GameSessionHooks = GameSessionHooks.None,
) {
    val actualPort: Int                                // доступен после start()
    suspend fun start(): Int                           // возвращает actualPort
    suspend fun stop()
}
```

`TestClients.kt` — хелпер для всех серверных тестов: `class RawWsClient` на ktor-client-cio (`webSocketSession("ws://127.0.0.1:$port/ws")`), методы `suspend fun send(msg: ClientMsg)`, `suspend fun sendRaw(text: String)`, `suspend fun receive(): ServerMsg` (с таймаутом 5 c), `fun close()`.

Правила сервера (из спеки): первое сообщение сокета — `Join` или `Rejoin`, иначе `Error` + закрытие; ник уникален case-insensitive → `Error("nick taken")`; цвет уникален → `Error("color taken")`; первый подключившийся — хост (host не передаётся); `Kick/SelectScenario/UpdateSettings/StartGame/ReturnToLobby` только от хоста, иначе `Error("not host")`; `Kick` только в лобби; `SetColor(null)`-сброса нет — цвет опционален только до выбора; `LobbyState` шлётся всем после каждого изменения; `StartGame` требует, чтобы все connected-игроки (кроме, возможно, самого хоста — хост тоже должен быть ready; правило: все connected ready) → `GameStarted(settings)` всем; порт: `requestedPort` занят → перебор +1..+9, иначе `IllegalStateException`.

- [ ] **Step 1: Failing-тесты фазы лобби (спека, сценарии 1, 4, 5, 6 + Review Focus 1–2)**

```kotlin
@Test fun `join lobby color ready start`()        // host+A: join обоих → LobbyState с 2 игроками, hostId=host;
                                                  // A: SetColor → LobbyState.color у A; A SetReady(true); host SetReady(true);
                                                  // host StartGame → оба получают GameStarted
@Test fun `duplicate nick rejected`()             // "Bob" затем "bob" → второму Error, он не в players
@Test fun `duplicate color rejected`()            // A SetColor(1), B SetColor(1) → B Error("color taken"), у B color==null
@Test fun `host-only commands rejected`()         // A StartGame → Error("not host")
@Test fun `kick in lobby`()                       // host Kick(A) → A получает Error("kicked") и сокет закрывается; LobbyState без A
@Test fun `occupied port probes next`()           // ServerSocket(0) занимает порт P; LobbyServer(requestedPort=P).start() == P+1;
                                                  // LobbyState.port == P+1
@Test fun `first message not join or rejoin rejected`()  // send SetReady первым → Error + socket closed; сервер жив (host ещё может join)
@Test fun `garbage frame logged and dropped`()    // после Join отправить "not json{{{" → соединение живо, следующий SetReady работает
```

- [ ] **Step 2: Run to verify fail** — `./gradlew :lobby-core:test --tests "*LobbyServerLobbyTest*"` → compile error.

- [ ] **Step 3: Реализовать `LobbyServer`**

`embeddedServer(Netty, port = requestedPort)` в `application { install(WebSockets); routing { webSocket("/ws") {...} } }`. Состояние под одним `Mutex`: `LinkedHashMap<String, ClientSlot>`; `ClientSlot(playerId, profileId, nick, avatar, color, ready, connected, session: DefaultWebSocketServerSession?)`. Фаза: `enum Phase { Lobby, Game }` начиная с `Lobby`. Обработка сообщений — `when` по `ClientMsg` с проверками фазы/прав → `Error` или мутация + `broadcastLobbyState()`. Send каждому через `session.send(Frame.Text(...))` в try/catch — при сбое пометить `connected=false`, разослать `PlayerSessionInfo(DISCONNECTED)` (полная логика обрывов — Task 5, здесь минимум, чтобы не падало). Порт: `actualPort` из `server.engine.resolvedConnectors().first().port`; при `BindException` — рекурсия/цикл по +1..+9.

- [ ] **Step 4: Run to verify pass** — та же команда, PASS (8 тестов).

- [ ] **Step 5: Commit** — `feat(core): LobbyServer lobby phase (join/colors/ready/host rights/port probing)`

---

### Task 5: LobbyServer — реконнект, игровой режим, смерть хоста

**Files:**
- Modify: `lobby-core/src/main/kotlin/dev/ebagamedev/lobby/core/server/LobbyServer.kt`
- Test: `lobby-core/src/test/kotlin/dev/ebagamedev/lobby/core/server/LobbyServerRejoinTest.kt`

**Interfaces:**
- Consumes: `LobbyServer`, `GameSessionHooks`, `RawWsClient` из Task 4.
- Produces: поведение — сигнатур новых нет.

Правила: обрыв сокета → слот остаётся (`connected=false`), всем `PlayerSessionInfo(DISCONNECTED)` + `LobbyState`. `Rejoin(profileId)`: неизвестный profileId → `Error("unknown profile")` + close; известный → перепривязка session, `connected=true`, `Welcome(playerId, isHost)`, всем `PlayerSessionInfo(RECONNECTED)` + `LobbyState`; в фазе Game дополнительно `hooks.onPlayerRejoin(playerId, send)`. `GameMsg` от клиента — только в фазе Game (иначе `Error("not in game")`); `toPlayerId == null` → broadcast остальным, иначе адресно; форма на проводе — `ServerMsg.GameMsg(fromPlayerId, payload)`. `StartGame` переводит фазу в Game; `ReturnToLobby` (только хост) → фаза Lobby, всем `ready=false`, broadcast `LobbyState`. Отключение хоста → всем остальным `HostDisconnected` → `stop()`. Любой сбой send → `connected=false`, сервер не ронять.

- [ ] **Step 1: Failing-тесты (спека, сценарии 2, 3 + Review Focus 3–5)**

```kotlin
@Test fun `rejoin in lobby restores state`()      // A join, SetColor, SetReady; A.close(); B видит DISCONNECTED;
                                                  // новый сокет Rejoin(A.profileId) → Welcome с тем же playerId,
                                                  // LobbyState: цвет/ready сохранены, connected=true
@Test fun `rejoin in game calls hook and game msgs flow`()  // host+A в игре (hooks записывают вызовы);
                                                  // A.close(); A Rejoin → hooks.onPlayerRejoin(playerId=A) вызван ровно раз,
                                                  // через send можно отправить снапшот → A получает ServerMsg.GameMsg;
                                                  // после этого A.send GameMsg → host получает его
@Test fun `rejoin unknown profile rejected`()     // Rejoin("no-such-id") → Error("unknown profile") + close
@Test fun `game msg targeted and broadcast`()     // host,A,B в игре: A GameMsg(payload, to=B) → только B получает;
                                                  // A GameMsg(payload, null) → host и B получают, A — нет
@Test fun `host disconnect shuts down server`()   // host.close() → A и B получают HostDisconnected; новый connect к порту падает
@Test fun `send failure does not crash server`()  // A закрывает сокет без close-frame (abort); сервер продолжает:
                                                  // host шлёт SetReady → LobbyState приходит B
@Test fun `return to lobby resets ready and phase`() // host+A в игре; host ReturnToLobby → оба получают LobbyState
                                                  // с ready=false у всех; Kick снова разрешён
```

- [ ] **Step 2: Run to verify fail** — `./gradlew :lobby-core:test --tests "*LobbyServerRejoinTest*"` → FAIL.

- [ ] **Step 3: Реализовать**

Дописать `LobbyServer`: обработка `finally`/cancellation в ws-хендлере → детач session от слота (не удалять слот); ветка `Rejoin`; ретранслятор `GameMsg`; `ReturnToLobby`; детект хоста в детаче → `HostDisconnected` + `stop()` в отдельной корутине (нельзя stop() из своего же потока обработчика — запустить в `CoroutineScope(Dispatchers.IO)`). Abort без close-frame ловится тем же `try/finally` вокруг `incoming` consume.

- [ ] **Step 4: Run to verify pass** — обе серверные тест-суиты зелёные: `./gradlew :lobby-core:test`.

- [ ] **Step 5: Commit** — `feat(core): reconnect via Rejoin, game relay, host-death shutdown`

---

### Task 6: LobbyClient — состояние, события, авто-реконнект

**Files:**
- Create: `lobby-core/src/main/kotlin/dev/ebagamedev/lobby/core/client/ConnectionState.kt`
- Create: `lobby-core/src/main/kotlin/dev/ebagamedev/lobby/core/client/LobbyEvent.kt`
- Create: `lobby-core/src/main/kotlin/dev/ebagamedev/lobby/core/client/LobbyClient.kt`
- Test: `lobby-core/src/test/kotlin/dev/ebagamedev/lobby/core/client/LobbyClientTest.kt`

**Interfaces:**
- Consumes: протокол (Task 2), `PlayerProfile` (Task 3), `LobbyServer` (Task 4–5, в тестах).
- Produces (контракт для CherniyBoomer — не менять):

```kotlin
enum class ConnectionState { Disconnected, Connecting, InLobby, InGame }

sealed interface LobbyEvent {
    data class ErrorReceived(val reason: String) : LobbyEvent
    data object Kicked : LobbyEvent
    data class GameStartedEvent(val settings: JsonElement?) : LobbyEvent
    data object HostDisconnectedEvent : LobbyEvent
    data class PlayerSession(val playerId: String, val nick: String, val event: PlayerSessionEvent) : LobbyEvent
    data object Reconnected : LobbyEvent
}

data class IncomingGameMsg(val fromPlayerId: String, val payload: JsonElement)

class LobbyClient(private val scope: CoroutineScope) {
    val connectionState: StateFlow<ConnectionState>
    val lobbyState: StateFlow<LobbyState?>
    val myPlayerId: StateFlow<String?>
    val isHost: StateFlow<Boolean>
    val events: SharedFlow<LobbyEvent>
    val incomingGame: SharedFlow<IncomingGameMsg>
    fun connect(host: String, port: Int, profile: PlayerProfile, avatarPngBase64: String? = null)
    fun setColor(color: Long)
    fun setReady(ready: Boolean)
    fun kick(playerId: String)
    fun selectScenario(scenarioId: String)
    fun updateSettings(settings: JsonElement)
    fun startGame()
    fun returnToLobby()
    fun sendGame(payload: JsonElement, toPlayerId: String? = null)
    fun disconnect()                                // намеренный выход: без авто-реконнекта
}
```

Поведение: `connect` запускает supervisor-корутину в `scope`: подключение → `Join` (первый раз) / `Rejoin` (после обрыва) → consume-цикл → при обрыве и `disconnected != намеренный` → backoff 0.5 c ×2 до капа 30 c → повтор. `Kicked` (Error("kicked")+закрытие) и `HostDisconnected` — без реконнекта, состояние `Disconnected`. `InLobby`/`InGame` выставляются по `Welcome`/фазе и `GameStarted`/`LobbyState` после игры.

- [ ] **Step 1: Failing-тесты (end-to-end против реального `LobbyServer` на ephemeral-порту)**

```kotlin
@Test fun `connect reaches InLobby and receives lobby state`() // connect → withTimeout: connectionState==InLobby,
                                                  // myPlayerId != null, lobbyState.players содержит ник
@Test fun `two clients see each other and game msgs flow`()    // hostClient и clientA: оба InLobby;
                                                  // host: setReady+startGame (через второго клиента ready тоже);
                                                  // clientA.sendGame(payload) → hostClient.incomingGame.first() == payload
@Test fun `auto reconnect after socket drop`()    // клиент InLobby; убить его сокет на стороне сервера (close сессии слота
                                                  // — хелпер в тесте дёргает сервер) → connectionState уходит в Connecting,
                                                  // затем снова InLobby; events содержит Reconnected; nick/color сохранены
@Test fun `kicked client does not reconnect`()    // host kick(clientA) → у A events: Kicked, connectionState==Disconnected,
                                                  // спустя ~2 c всё ещё Disconnected
@Test fun `host disconnect ends session`()        // host-клиент disconnect() → у A HostDisconnectedEvent, Disconnected
@Test fun `reconnect in game keeps game channel`()// оба InGame; дроп A → A возвращается (Reconnected),
                                                  // hooks.onPlayerRejoin вызван на сервере; A.sendGame → host получает
```

Backoff в тестах: чтобы не ждать — конструктор принимает опциональный `initialBackoffMs: Long = 500` (в тестах 50 мс). Не часть публичного контракта CherniyBoomer, но и не ломает его.

- [ ] **Step 2: Run to verify fail** — `./gradlew :lobby-core:test --tests "*LobbyClientTest*"` → compile error.

- [ ] **Step 3: Реализовать `LobbyClient`**

Один `HttpClient(CIO) { install(WebSockets) }` на клиента. Supervisor-job в `scope`; send-методы шлют в текущую сессию через `Mutex`-гардируемую ссылку (не-соединённый вызов — молча дроп + лог). Маппинг ServerMsg → flow'ы; `Error("kicked")` перед закрытием → `LobbyEvent.Kicked` и флаг запрета реконнекта. При восстановлении соединения шлёт `Rejoin(profile.profileId)`; успешный `Welcome` → `LobbyEvent.Reconnected` (только если это не первое подключение).

- [ ] **Step 4: Run to verify pass** — `./gradlew :lobby-core:test` — все сьюты зелёные.

- [ ] **Step 5: Commit** — `feat(core): LobbyClient with StateFlow/SharedFlow API and auto-reconnect`

---

### Task 7: SPI — LobbyGame и ServiceLoader-discovery

**Files:**
- Create: `lobby-core/src/main/kotlin/dev/ebagamedev/lobby/core/spi/LobbyGame.kt`
- Create: `lobby-core/src/main/kotlin/dev/ebagamedev/lobby/core/spi/GameDiscovery.kt`
- Test: `lobby-core/src/test/kotlin/dev/ebagamedev/lobby/core/spi/GameDiscoveryTest.kt`
- Test: `lobby-core/src/test/resources/META-INF/services/dev.ebagamedev.lobby.core.spi.LobbyGame`

**Interfaces:**
- Consumes: `Scenario`, `LobbyClient`.
- Produces (контракт из спеки — не менять; `onPlayerRejoin` добавлен с default-телом, см. примечание ниже):

```kotlin
interface LobbyGame {
    val gameId: String
    val gameName: String
    val appId: String
    fun scenarios(): List<Scenario>
    fun onGameStarted(client: LobbyClient, settings: JsonElement?)
    /** Хост: игрок playerId вернулся в игру после обрыва — отправить ему снапшот
     *  через client.sendGame(payload, toPlayerId = playerId). Вызывается только на хосте. */
    fun onPlayerRejoin(playerId: String, client: LobbyClient) {}
}

object GameDiscovery {
    fun loadGames(): List<LobbyGame>   // ServiceLoader.load(LobbyGame::class.java)
}
```

> Спека в разделе «сервер» требует колбэк `onPlayerRejoin(playerId, send)`, но в SPI-блоке его нет. Решение плана: default-метод на `LobbyGame` (пустая реализация = игра без снапшотов). Бинарно-совместимо благодаря `-Xjvm-default=all`, сигнатуры существующих членов не тронуты. `LobbyServer` при хостинге получает `GameSessionHooks`, который `lobby-ui-compose` строит из `game.onPlayerRejoin` (Task 8).

- [ ] **Step 1: Failing-тест**

Тестовая реализация `TestGame : LobbyGame` (gameId="test-game") + регистрация в `src/test/resources/META-INF/services/...`:

```kotlin
@Test fun `discovers service-loaded games`() {
    assertTrue(GameDiscovery.loadGames().any { it.gameId == "test-game" })
}
```

- [ ] **Step 2: Run to verify fail** → compile error.

- [ ] **Step 3: Реализовать** два файла по интерфейсам.

- [ ] **Step 4: Run to verify pass.**

- [ ] **Step 5: Commit** — `feat(core): LobbyGame SPI + ServiceLoader discovery`

---

### Task 8: lobby-ui-compose — экраны и точка входа LanLobby

**Files:**
- Create: `lobby-ui-compose/src/main/kotlin/dev/ebagamedev/lobby/ui/LobbyGameUi.kt`
- Create: `lobby-ui-compose/src/main/kotlin/dev/ebagamedev/lobby/ui/LanLobby.kt`
- Create: `lobby-ui-compose/src/main/kotlin/dev/ebagamedev/lobby/ui/LobbyController.kt`
- Create: `lobby-ui-compose/src/main/kotlin/dev/ebagamedev/lobby/ui/PlayerColors.kt`
- Create: `lobby-ui-compose/src/main/kotlin/dev/ebagamedev/lobby/ui/screens/ProfileScreen.kt`
- Create: `lobby-ui-compose/src/main/kotlin/dev/ebagamedev/lobby/ui/screens/MenuScreen.kt`
- Create: `lobby-ui-compose/src/main/kotlin/dev/ebagamedev/lobby/ui/screens/LobbyScreen.kt`

**Interfaces:**
- Consumes: `LobbyClient`, `LobbyServer`, `ProfileStore`, `LobbyGame`, `GameSessionHooks`.
- Produces (контракт из спеки — не менять):

```kotlin
interface LobbyGameUi : LobbyGame {
    @Composable fun SettingsContent(settings: JsonElement?, onChange: (JsonElement) -> Unit)
}

object LanLobby {
    fun run(game: LobbyGameUi)   // блокирующий запуск Compose-приложения; по GameStarted окно лобби закрывается,
                                 // вызывается game.onGameStarted(client, settings)
}
```

`LobbyController` (внутренний): держит `ProfileStore(game.appId)`, `LobbyClient`, для хоста — `LobbyServer` (scenarios = `game.scenarios()`, hooks = `{ playerId, send -> game.onPlayerRejoin(playerId, client-обёртка над send) }` — обёртка: `GameSessionHooks` шлёт только этому игроку, а `LobbyGame.onPlayerRejoin` получает полный `LobbyClient`; хост-контроллер использует свой loopback-`LobbyClient` и шлёт `client.sendGame(payload, toPlayerId = playerId)` через метод игры). Хост-флоу: `server.start()` → `client.connect("127.0.0.1", server.actualPort, profile)` — отображаемый порт для гостей = `actualPort`.

`PlayerColors`: фиксированная палитра 8 `Long` (0xFFF44336 red, 0xFF2196F3 blue, 0xFF4CAF50 green, 0xFFFFEB3B yellow, 0xFF9C27B0 purple, 0xFFFF9800 orange, 0xFF00BCD4 cyan, 0xFFE91E63 pink).

- [ ] **Step 1: ProfileScreen**

Поля: ник (prefill из профиля), кнопка «Выбрать аватар» (AWT `FileDialog`, фильтр png/jpg), превью 128×128, сохранение через `ProfileStore` → переход в меню. Ошибки сохранения аватара — snackbar.

- [ ] **Step 2: MenuScreen**

Две карточки: «Хост» (имя игры из `game.gameName`, поле порта, дефолт `25565`) и «Присоединиться» (IP + порт, prefill `lastHost`/`lastPort` из профиля). Успешный connect → сохранить `lastHost/lastPort` в профиль → LobbyScreen.

- [ ] **Step 3: LobbyScreen**

Список игроков из `lobbyState` (аватар из base64, ник, кружок цвета, галка ready, корона у `hostId`, серая плашка при `connected=false`); палитра `PlayerColors` — занятые цвета disabled; кнопка Ready (toggle); у хоста: dropdown сценариев (`selectScenario`), `game.SettingsContent(settings) { client.updateSettings(it) }`, «Старт» enabled только когда все connected `ready`, «Кик» у каждого не-хоста. Snackbar на `LobbyEvent` (Error/PlayerSession/Reconnecting по `connectionState==Connecting`). `HostDisconnectedEvent`/`Kicked` → назад в меню с сообщением.

- [ ] **Step 4: LanLobby.run**

`application { Window(...) { ... } }` + `remember` контроллера; коллекция `events`: `GameStartedEvent` → `exitApplication` и вызов `game.onGameStarted` (окно лобби закрывается до вызова). Навигация — простой `var screen by remember` enum.

- [ ] **Step 5: Проверка компиляции**

Run: `./gradlew :lobby-ui-compose:build`
Expected: BUILD SUCCESSFUL (UI-тестов нет — ручная проверка в Task 9).

- [ ] **Step 6: Commit** — `feat(ui): Compose profile/menu/lobby screens + LanLobby entry point`

---

### Task 9: demo — pingpong, roulette, запускалка

**Files:**
- Create: `demo/pingpong/src/main/kotlin/dev/ebagamedev/lobby/demo/pingpong/PingPongGame.kt`
- Create: `demo/pingpong/src/main/resources/META-INF/services/dev.ebagamedev.lobby.core.spi.LobbyGame`
- Create: `demo/roulette/src/main/kotlin/dev/ebagamedev/lobby/demo/roulette/RouletteGame.kt`
- Create: `demo/roulette/src/main/resources/META-INF/services/dev.ebagamedev.lobby.core.spi.LobbyGame`
- Create: `demo/app/src/main/kotlin/dev/ebagamedev/lobby/demo/app/Main.kt`

**Interfaces:**
- Consumes: `LobbyGameUi`, `LanLobby.run`, `GameDiscovery.loadGames()`.

`PingPongGame`: gameId `"demo-pingpong"`, gameName `"Ping Pong"`, appId `"lanlobbykit-demo-pingpong"`; `scenarios()` — 2 заглушки; `SettingsContent` — одно поле «startCounter» (Int, пишет в settings json); `onGameStarted` — маленькое Compose-окно: счётчик, кнопка «Ping» шлёт `sendGame(buildJsonObject{put("count", n+1)})`, входящие `incomingGame` обновляют счётчик; `onPlayerRejoin` — шлёт текущий счётчик адресно вернувшемуся.

`RouletteGame`: gameId `"demo-roulette"`, gameName `"Roulette"`; окно со списком ников из `lobbyState` и кнопкой «Spin» — рассылает всем случайного «победителя» через `GameMsg`; `SettingsContent` — пустышка-текст.

`Main.kt` (app): `GameDiscovery.loadGames().filterIsInstance<LobbyGameUi>()`; если >1 — println-список + `readLine()` выбор (или `--game=demo-roulette` аргумент); `LanLobby.run(chosen)`.

- [ ] **Step 1: Реализовать pingpong + service-файл, собрать**

Run: `./gradlew :demo:pingpong:build` → SUCCESS.

- [ ] **Step 2: Реализовать roulette + service-файл, собрать**

Run: `./gradlew :demo:roulette:build` → SUCCESS.

- [ ] **Step 3: Реализовать app, собрать всё**

Run: `./gradlew build` → SUCCESS, все unit-тесты зелёные.

- [ ] **Step 4: Ручная smoke-проверка (отметить в коммит-месседже)**

Два запуска `./gradlew :demo:app:run` (или два `java -jar`): первый — Хост (порт 25565), второй — Join на 127.0.0.1:25565 → оба в лобби; цвет, ready, старт → оба окна pingpong; Ping ходит туда-сюда; закрыть окно гостя в игре и перезапустить → реконнект, снапшот счётчика пришёл.

- [ ] **Step 5: Commit** — `feat(demo): pingpong + roulette demo games and launcher app`

---

### Task 10: Публикация в mavenLocal и GitHub

**Files:**
- Modify: `README.md` (создать: что это, модули, `publishToMavenLocal`, подключение `dev.ebagamedev:lobby-core:0.1.0`, `includeBuild` для разработки, ссылка на спеку)

- [ ] **Step 1: Финальный прогон**

Run: `./gradlew clean build` → SUCCESS (все тесты).
Run: `./gradlew :lobby-core:publishToMavenLocal :lobby-ui-compose:publishToMavenLocal`
Expected: `~/.m2/repository/dev/ebagamedev/lobby-core/0.1.0/` и `.../lobby-ui-compose/0.1.0/` содержат jar + pom + module.

- [ ] **Step 2: README + commit** — `docs: readme with usage and publish instructions`.

- [ ] **Step 3: GitHub**

С пользователем: `gh auth status` → `gh repo create <owner>/LanLobbyKit --public --source . --push` (имя/владельца уточнить у пользователя в момент выполнения). Проверка: `gh repo view --web`-URL открывается, история коммитов на месте.

---

## Self-review notes (заполнено при написании)

- Покрытие спеки: протокол (T2), профили (T3), сервер лобби+игра+реконнект+порт (T4–T5), клиент+backoff (T6), SPI+ServiceLoader (T7), UI-экраны+LanLobby.run (T8), demo (T9), публикация (T10). Non-goals не реализуются.
- Все 6 сценариев раздела «Тестирование» распределены: 1,4,5,6 → T4; 2,3 → T5; плюс e2e клиентские → T6.
- Review Focus 1–5 закрыт тестами в T4/T5 (указано в каждой строке).
- Отклонение от спеки зафиксировано одно: `LobbyGame.onPlayerRejoin` как default-метод (T7) — иначе колбэк из раздела «сервер» некуда повесить.
