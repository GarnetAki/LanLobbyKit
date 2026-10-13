# Kickoff-промпт для нового чата LanLobbyKit

Вставь это первым сообщением в новом чате проекта LanLobbyKit:

---

Делаем итерацию 0 библиотеки LanLobbyKit — переиспользуемого LAN-лобби-кита
(Ktor WebSocket, хост = встроенный сервер + loopback-клиент, профили
UUID/ник/аватар, цвета, ready-check, реконнект через Rejoin(profileId),
SPI для игр). Дизайн-спека уже написана, отревьюлена и лежит в
`docs/superpowers/specs/2026-10-13-lanlobbykit-design.md` — она источник
истины, читай её первой.

Спека одобрена, brainstorming-фаза пройдена. Сразу переходи к написанию
плана реализации (writing-plans), затем реализуй итерацию 0:
модули `lobby-core`, `lobby-ui-compose`, `demo` + тесты из раздела
«Тестирование» спеки.

Контекст из соседнего проекта: паттерн лобби заимствован из SRooms
(C:/Users/akira/Desktop/EbaGamedev/SRooms) — там Compose Desktop +
Ktor WS, можно подсматривать подходы, но код не копируем (там нет
реконнекта, ready-check и SPI). Игра-потребитель — CherniyBoomer
(C:/Users/akira/Desktop/EbaGamedev/CherniyBoomer, KorGE), делается
параллельно в другом чате против интерфейсов из спеки — сигнатуры
`LobbyGame`/`LobbyGameUi`/`LobbyClient` не менять без необходимости.

По завершении итерации: публикация в mavenLocal + пуш в новый публичный
репозиторий на GitHub (репо создадим вместе).
---
