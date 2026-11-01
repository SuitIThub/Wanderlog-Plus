# Plan 2: Sync-Server – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ein Ktor-Server mit SQLite, der Feld-Änderungen von Geräten annimmt (Push), geänderte Objekte paginiert ausliefert (Pull), Dateien speichert, den Wanderlog-Import auslöst und als Docker-Image auf einem beliebigen Linux-Host läuft.

**Architecture:** Neues Gradle-Modul `server`. `SqliteStore` implementiert das bestehende `EntityStore`-Interface aus `shared` auf einer SQLite-Datei; jede geschriebene Feldzeile bekommt eine global monotone `seq`. `SyncService` serialisiert alle Schreibvorgänge (Pushes und Importe) über eine Sperre, validiert Pushes vollständig vor dem Anwenden und verwandelt Gerätewerte, die nur gegen den Importer verlieren, in Konflikte. Ktor-Routen sind dünn und delegieren an die Services.

**Tech Stack:** Kotlin 2.1.0 (JVM 17), Ktor 3.0.3 (Netty, Auth Bearer, ContentNegotiation, StatusPages), kotlinx-serialization 1.7.3, `org.xerial:sqlite-jdbc` 3.47.1.0 (reines JDBC, kein ORM), Logback 1.5.12, JUnit 5.11.3, `ktor-server-test-host`.

**Spec:** `docs/superpowers/specs/2026-10-31-wanderlog-plus-design.md` (§2, §3, §5 Ablauf, §6, §7, §8, §10, §11, §12 server). Zusätzlich verbindlich: `docs/superpowers/followups-from-plan1.md`, Abschnitt „Pflicht für Plan 2“. Teil 2 von 4.

## Global Constraints

- Paket-Root `de.wanderlogplus.server`; Protokoll-DTOs in `de.wanderlogplus.shared.sync`.
- `shared` bleibt frei von JVM-fremden Abhängigkeiten außer kotlinx-serialization (kein Ktor, kein JDBC in `shared`).
- Protokollversion `1` (`PROTOCOL_VERSION`). Header `X-Protocol-Version` auf jeder `/api/*`-Anfrage außer `/api/health`; fehlt er oder ist er ≠ 1 → `409` mit Body `{"error":"protocol_mismatch","serverProtocol":1}`, nichts wird gelesen oder geschrieben.
- Auth: `Authorization: Bearer <token>` auf allen `/api/*` außer `/api/health`; ungültig/fehlend → `401`. Token-Vergleich zeitkonstant (`MessageDigest.isEqual`). Der Token-Name ist der Urheber (`author`) gespeicherter Änderungen.
- Reihenfolge der Prüfungen: Auth (401) vor Protokoll (409).
- Konfiguration nur über Umgebungsvariablen:
  - `WLP_TOKENS` – `name:token,name:token` (Pflicht; jeder Token ≥ 16 Zeichen, Namen eindeutig, sonst Startabbruch mit Meldung),
  - `WLP_DATA_DIR` – Default `/data` (enthält `wanderlog-plus.db` und `files/`),
  - `WLP_PORT` – Default `8080`,
  - `WLP_MAX_FILE_MB` – Default `25`,
  - `WLP_TRIP_KEY` – optional; ohne ihn (und ohne `WLP_IMPORT_FILE`) antwortet `POST /api/import/wanderlog` mit `400 {"error":"import_not_configured"}`,
  - `WLP_IMPORT_FILE` – optional, Pfad zu einer Wanderlog-JSON-Datei statt Abruf (für Tests/Offline); hat Vorrang vor `WLP_TRIP_KEY`.
- Reservierte nodeIds: `importer`, `server`. Geräte-Pushes mit HLC-nodeId daraus → `400`.
- Maximaler Uhren-Vorlauf eingehender HLCs: 24 h (`MAX_CLOCK_DRIFT_MILLIS = 86_400_000`) gegenüber der Serverzeit → `400`.
- Fehlerantworten sind JSON `{"error":"<code>", "message":"<text>"}` (`message` optional). Keine Stacktraces in Antworten.
- Logs enthalten nie Feldwerte, Tokens oder Dateiinhalte (Buchungsdaten!). Erlaubt: Methode, Pfad, Status, Anzahlen, Autor.
- Echte Exporte nur unter `testdata/private/` (gitignored). Tests mit echten Daten werden übersprungen, wenn die Datei fehlt (`assumeTrue`).
- Kommandos aus dem Repo-Root; unter Git-Bash `./gradlew`.

## Review Focus

1. **Offline-Gerät gegen Import:** Gerät ändert offline `notes`, Server importiert inzwischen einen neuen Wanderlog-Wert, dann pusht das Gerät mit älterer HLC → Gerätewert gewinnt, `_conflict.notes` = Wanderlog-Wert. (Test in Task 4.)
2. **Wiederholter Push** (Netzabbruch nach Serverannahme, App sendet dieselben Änderungen erneut) → keine neue `seq`, kein Konflikt, `accepted = 0`. (Test in Task 4.)
3. **Pagination an Seitengrenzen:** 7 Objekte, `limit=3` → drei Seiten, jedes Objekt genau einmal, letzte Seite `hasMore=false`; ein danach geändertes Objekt erscheint bei `since=nextSeq` wieder. (Test in Task 3.)
4. **Teilweise ungültiger Push:** 1 gültige + 1 Änderung mit kaputter HLC → `400`, auch die gültige wird nicht gespeichert. (Test in Task 4.)
5. **Import bricht ab:** ungültiges Wanderlog-JSON → `502`/`422`, Store unverändert, Protokolleintrag `ok=false` mit Fehlertext. (Test in Task 6.)

---

## Dateistruktur

```
settings.gradle.kts                    + include("server")
gradle/libs.versions.toml              + ktor, sqlite-jdbc, logback
shared/src/main/kotlin/de/wanderlogplus/shared/sync/SyncProtocol.kt   DTOs + Konstanten
importer/src/main/kotlin/de/wanderlogplus/importer/WanderlogClient.kt  Retry 5xx, Redirects
importer/src/main/kotlin/de/wanderlogplus/importer/Cli.kt              saubere Fehlermeldungen
server/build.gradle.kts
server/src/main/kotlin/de/wanderlogplus/server/
  Main.kt               main(): Config lesen, Services bauen, Netty starten
  ServerConfig.kt       Env → ServerConfig
  Application.kt        Application.module(services): Plugins, Auth, Protokollprüfung, Routen
  SqliteStore.kt        EntityStore auf SQLite, seq, Transaktionen, Pull-Seiten, Import-Log
  SyncService.kt        Push-Validierung, Importer-Konflikte, Pull
  FileStore.kt          Dateien unter SHA-256
  ImportService.kt      TripSource, Import unter Sperre, Protokoll
  LiveStatusProvider.kt Erweiterungspunkt, leere Implementierung
server/src/main/resources/logback.xml
server/src/test/kotlin/de/wanderlogplus/server/…
server/Dockerfile, server/docker-compose.yml, server/Caddyfile.example, server/README.md
```

---

### Task 1: Protokoll-DTOs in `shared` und Importer-Härtung

**Files:**
- Create: `shared/src/main/kotlin/de/wanderlogplus/shared/sync/SyncProtocol.kt`
- Test: `shared/src/test/kotlin/de/wanderlogplus/shared/sync/SyncProtocolTest.kt`
- Modify: `importer/src/main/kotlin/de/wanderlogplus/importer/WanderlogClient.kt`, `importer/src/main/kotlin/de/wanderlogplus/importer/Cli.kt`
- Test: `importer/src/test/kotlin/de/wanderlogplus/importer/WanderlogClientTest.kt`

**Interfaces:**
- Produces (in `de.wanderlogplus.shared.sync`):
  - `const val PROTOCOL_VERSION = 1`, `const val PROTOCOL_HEADER = "X-Protocol-Version"`
  - `@Serializable data class PushRequest(val changes: List<FieldChange>)`
  - `@Serializable data class PushResponse(val accepted: Int, val serverSeq: Long)`
  - `@Serializable data class PullResponse(val records: List<EntityRecord>, val nextSeq: Long, val hasMore: Boolean)`
  - `@Serializable data class ErrorResponse(val error: String, val message: String? = null, val serverProtocol: Int? = null)`
- Produces (importer): `class WanderlogClient(private val send: (HttpRequest) -> HttpResponse<String> = default)` – Default nutzt `HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()`. `fetch(tripKey: String): String` unverändert in der Signatur.

- [ ] **Step 1: Tests schreiben**
  - `SyncProtocolTest.roundTrip`: `PushRequest` mit einer `FieldChange(EntityType.PLACE, "p1", "notes", JsonPrimitive("ü ⚠️"), "000000000000001-000000-dev")` → `Json.encodeToString` → `decodeFromString` ergibt Gleichheit; ebenso `PullResponse` mit einem `EntityRecord` mit zwei Feldern, `nextSeq = 42`, `hasMore = true`.
  - `SyncProtocolTest.errorResponseOmitsNulls`: `Json { explicitNulls = false }.encodeToString(ErrorResponse("protocol_mismatch", serverProtocol = 1))` == `{"error":"protocol_mismatch","serverProtocol":1}`.
  - `WanderlogClientTest` mit Fake-`send`, der eine Liste vorbereiteter Antworten/Exceptions abarbeitet (Fake-`HttpResponse` als kleine Testklasse):
    - `retriesOnceOn5xx`: 503 dann 200 `"{}"` → `fetch` liefert `"{}"`, 2 Aufrufe.
    - `retriesOnceOnIOException`: IOException dann 200 → Erfolg.
    - `failsAfterSecond5xx`: 500, 502 → `ImportFormatException`, Meldung enthält `HTTP 502`, genau 2 Aufrufe.
    - `doesNotRetry4xx`: 404 → `ImportFormatException` „HTTP 404“, 1 Aufruf.
- [ ] **Step 2:** `./gradlew :shared:test :importer:test` → neue Tests schlagen fehl (Kompilierfehler).
- [ ] **Step 3: Implementieren.** Retry-Regel: genau ein zweiter Versuch bei `IOException` oder Status ≥ 500; 4xx sofort Fehler.
  `Cli.kt`: Laden der State-Datei und Lesen von `--file` in eigene Fehlerbehandlung: fehlende Datei → `Import failed: file not found: <path>` (Exit 1), unlesbarer/kaputter State (`SerializationException`, `IllegalArgumentException`, `IOException`) → `Import failed: cannot read state <path>: <message>` (Exit 1). Kein Stacktrace.
- [ ] **Step 4:** `./gradlew test` → alles grün.
- [ ] **Step 5: Commit** `feat: sync protocol DTOs; importer retries 5xx and reports CLI errors cleanly`

---

### Task 2: Server-Modul, Konfiguration, Auth, Protokollprüfung, Health

**Files:**
- Modify: `settings.gradle.kts`, `gradle/libs.versions.toml`
- Create: `server/build.gradle.kts`, `server/src/main/kotlin/de/wanderlogplus/server/{Main,ServerConfig,Application}.kt`, `server/src/main/resources/logback.xml`
- Test: `server/src/test/kotlin/de/wanderlogplus/server/{ServerConfigTest,ApplicationTest}.kt`

**Interfaces:**
- Produces:
  - `data class ServerConfig(val tokens: Map<String, String> /* name→token */, val dataDir: Path, val port: Int, val maxFileBytes: Long, val tripKey: String?, val importFile: Path?)`, `companion fun fromEnv(env: Map<String, String>): ServerConfig` (wirft `IllegalArgumentException` mit verständlicher Meldung).
  - `class Services(val config: ServerConfig, ...)` – Container; in Task 2 nur `config`; spätere Tasks ergänzen `store`, `sync`, `files`, `imports` als Konstruktorparameter.
  - `fun Application.module(services: Services)` – installiert ContentNegotiation (Json mit `explicitNulls = false`, `ignoreUnknownKeys = true`), StatusPages (unerwartete Exception → `500 {"error":"internal"}`, `BadRequestException`/`IllegalArgumentException` → `400 {"error":"bad_request","message":…}`), Bearer-Auth (`"api"`) mit `UserIdPrincipal(name)`, Protokollprüfung für authentifizierte Routen, Route `GET /api/health` → `200 {"status":"ok","protocol":1}` ohne Auth/Header.
  - Hilfsfunktion `fun ApplicationCall.author(): String` (Principal-Name).
  - Testhilfe in `server/src/test/.../TestSupport.kt`: `fun testServices(dir: Path, ...): Services` und Konstanten `TOKEN_A = "token-alice-0123456789"` (Name `alice`), `TOKEN_B = "token-bob-0123456789ab"` (Name `bob`); `fun HttpRequestBuilder.authed(token: String = TOKEN_A)` setzt Bearer + Protokoll-Header.
- Dependencies (`server/build.gradle.kts`): `application` (mainClass `de.wanderlogplus.server.MainKt`), `implementation(project(":shared"))`, `implementation(project(":importer"))`, Ktor server core/netty/auth/content-negotiation/status-pages/call-logging, `ktor-serialization-kotlinx-json`, sqlite-jdbc, logback; test: `ktor-server-test-host`, `ktor-client-content-negotiation`, kotlin-test, junit.
- Für Task 2 eine geschützte Probe-Route `GET /api/whoami` → `{"name":"<author>"}`; sie bleibt bestehen (die App nutzt sie für den Verbindungstest).

- [ ] **Step 1: Tests schreiben**
  - `ServerConfigTest`: `fromEnv(mapOf("WLP_TOKENS" to "alice:$TOKEN_A,bob:$TOKEN_B"))` → zwei Tokens, `dataDir == Path("/data")`, `port == 8080`, `maxFileBytes == 25L*1024*1024`, `tripKey == null`. Fehlerfälle werfen: fehlendes `WLP_TOKENS`; Token < 16 Zeichen; doppelter Name; Eintrag ohne `:`; `WLP_PORT=abc`.
  - `ApplicationTest` (`testApplication { application { module(testServices(tmp)) } }`):
    - `healthWithoutAuth`: `GET /api/health` → 200, Body enthält `"protocol":1`.
    - `missingToken401`: `GET /api/whoami` mit Protokoll-Header, ohne Token → 401.
    - `wrongToken401`.
    - `wrongProtocol409`: gültiger Token, Header `2` → 409, Body `{"error":"protocol_mismatch","serverProtocol":1}`.
    - `missingProtocol409`.
    - `whoamiReturnsTokenName`: `TOKEN_B` → `{"name":"bob"}`.
- [ ] **Step 2:** `./gradlew :server:test` → FAIL.
- [ ] **Step 3: Implementieren.** `Main.kt`: `ServerConfig.fromEnv(System.getenv())`, bei `IllegalArgumentException` Meldung auf stderr und Exit 2; `embeddedServer(Netty, port) { module(services) }.start(wait = true)`. CallLogging nur mit Methode, Pfad, Status.
- [ ] **Step 4:** `./gradlew test` → grün.
- [ ] **Step 5: Commit** `feat(server): ktor skeleton with env config, bearer auth, protocol check, health`

---

### Task 3: `SqliteStore` mit Sequenzen, Transaktionen, Pull-Seiten und Import-Log

**Files:**
- Create: `server/src/main/kotlin/de/wanderlogplus/server/SqliteStore.kt`
- Test: `server/src/test/kotlin/de/wanderlogplus/server/SqliteStoreTest.kt`

**Interfaces:**
- Consumes: `EntityStore`, `EntityRecord`, `FieldValue`, `EntityType` aus `shared`.
- Produces:
  - `class SqliteStore(dbFile: Path) : EntityStore, AutoCloseable`
    - Schema (beim Öffnen angelegt, `PRAGMA journal_mode=WAL`):
      `fields(type TEXT NOT NULL, id TEXT NOT NULL, field TEXT NOT NULL, value TEXT NOT NULL /* JSON */, hlc TEXT NOT NULL, seq INTEGER NOT NULL, author TEXT NOT NULL, PRIMARY KEY(type,id,field))`, Index auf `seq`;
      `import_log(id INTEGER PRIMARY KEY AUTOINCREMENT, started_at TEXT, finished_at TEXT, ok INTEGER, created INTEGER, updated INTEGER, conflicts INTEGER, removed INTEGER, flagged_removed INTEGER, error TEXT)`.
    - `var currentAuthor: String` – Autor für folgende `put`s (Default `"server"`); nur innerhalb von `transaction` setzen.
    - `put(record)`: schreibt nur Felder, deren `(value, hlc)` vom gespeicherten Stand abweicht; jede geschriebene Zeile bekommt `seq = maxSeq + 1` (fortlaufend) und `author = currentAuthor`. Keine Prüfung der HLC-Reihenfolge (das macht `EntityRecord.apply`).
    - `get`, `all(type)`, `all()` rekonstruieren `EntityRecord`s; Reihenfolge von `all()` stabil nach (type, id).
    - `fun <T> transaction(author: String, block: () -> T): T` – eine JDBC-Transaktion, setzt `currentAuthor`; bei Exception Rollback und erneutes Werfen. Verschachtelung nicht nötig.
    - `fun maxSeq(): Long` (0 bei leerer DB).
    - `fun pull(since: Long, limit: Int): PullResponse` – Objekte, deren höchste Feld-`seq` > `since` ist, sortiert nach dieser höchsten `seq`, höchstens `limit` Stück, jeweils mit **allen** Feldern; `nextSeq` = höchste `seq` des letzten gelieferten Objekts (bei leerer Seite `since`), `hasMore` = weitere Objekte existieren.
    - `fun appendImportLog(entry: ImportLogEntry)`, `fun importLog(limit: Int): List<ImportLogEntry>` (neueste zuerst).
  - `@Serializable data class ImportLogEntry(val startedAt: String, val finishedAt: String, val ok: Boolean, val created: Int = 0, val updated: Int = 0, val conflicts: Int = 0, val removed: Int = 0, val flaggedRemoved: Int = 0, val error: String? = null)` (Zeiten ISO-8601 UTC).
- Alle Methoden `@Synchronized` (eine Connection); JSON-Werte via `Json.encodeToString(JsonElement)`.

- [ ] **Step 1: Tests schreiben** (`@TempDir`)
  - `putAndGetRoundTrip`: Record mit Feldern `name` (`"妻籠 ⚠️"`), `rating` (4.5), `deleted` (false), `notes` (JsonNull) → nach Schließen und Neuöffnen identisch.
  - `seqIncreasesPerWrittenField`: zwei Felder schreiben → `maxSeq() == 2`; dasselbe Record erneut `put` → `maxSeq()` bleibt 2; ein Feld ändern → 3.
  - `applyChangesWorksThroughStore`: `store.applyChanges(...)` (aus `shared`) mit älterer HLC wird verworfen, neuere angenommen.
  - `pullPagination` (Review Focus 3): 7 Records → `pull(0,3)`, `pull(next,3)`, `pull(next,3)`: 3+3+1 Objekte, alle IDs genau einmal, `hasMore` true/true/false; danach Record #2 ändern → `pull(lastNext, 3)` liefert genau #2 mit allen Feldern.
  - `transactionRollsBack`: Exception im Block → kein Feld gespeichert, `maxSeq()` unverändert.
  - `authorIsStored`: in `transaction("bob")` geschrieben → Spalte `author` = `bob` (Test darf per JDBC nachsehen oder über eine interne Hilfsfunktion `authorOf(type,id,field)`).
  - `importLogNewestFirst`.
- [ ] **Step 2:** FAIL. **Step 3:** implementieren. **Step 4:** `./gradlew :server:test` grün.
- [ ] **Step 5: Commit** `feat(server): SQLite entity store with sequence numbers and paged pull`

---

### Task 4: `SyncService` und Sync-Endpoints

**Files:**
- Create: `server/src/main/kotlin/de/wanderlogplus/server/SyncService.kt`
- Modify: `Application.kt` (Routen), `Services` (Feld `sync`)
- Test: `server/src/test/kotlin/de/wanderlogplus/server/{SyncServiceTest,SyncRoutesTest,ServerConvergenceTest}.kt`

**Interfaces:**
- Consumes: `SqliteStore`, `PushRequest/PushResponse/PullResponse`, `Hlc`, `HlcClock`, `applyChanges`.
- Produces:
  - `class SyncService(private val store: SqliteStore, private val now: () -> Long = System::currentTimeMillis)`
    - `val lock: Any` – gemeinsame Sperre; `ImportService` (Task 6) nutzt `sync.withWriteLock { }`.
    - `fun <T> withWriteLock(block: () -> T): T`
    - `fun push(author: String, changes: List<FieldChange>): PushResponse` – wirft `PushRejected(code: String, message: String)` (→ HTTP 400 `{"error":code,"message":…}`).
    - `fun pull(since: Long, limit: Int): PullResponse`
  - Routen: `POST /api/sync/push` (Body `PushRequest`) → 200 `PushResponse`; `GET /api/sync/pull?since=<Long ≥ 0>&limit=<1..1000, Default 500>` → 200 `PullResponse`; ungültige Query → 400.

**Push-Algorithmus (verbindlich):**
1. **Validierung des ganzen Batches vor jedem Schreiben** (sonst `PushRejected`, nichts gespeichert): höchstens 5000 Änderungen (`too_many_changes`); `id` und `field` nicht leer (`invalid_change`); `hlc` parsebar (`invalid_hlc`); `hlc.wallMillis ≤ now() + MAX_CLOCK_DRIFT_MILLIS` (`clock_drift`); `hlc.nodeId` nicht in `{"importer","server"}` (`reserved_node`). Unbekannter `EntityType` scheitert schon beim Deserialisieren → 400.
2. Unter `withWriteLock` und in `store.transaction(author)`: für jede Änderung `c` in Reihenfolge:
   - Gespeichertes Feld `s` = `store.get(c.type, c.id)?.fields[c.field]`.
   - **Importer-Verlust → Konflikt:** Wenn `s != null`, `Hlc.parse(c.hlc) < Hlc.parse(s.hlc)`, `Hlc.parse(s.hlc).nodeId == "importer"`, `c.field` nicht mit `_` beginnt und ≠ `deleted`, das Record ein Feld `_base.<c.field>` besitzt und `c.value != s.value`: dann schreibe mit der Server-Uhr (`HlcClock("server")`, vorher `receive(Hlc.parse(s.hlc))`) zwei Änderungen: `c.field` = `c.value` und `_conflict.<c.field>` = `s.value`. `_base` bleibt unverändert.
   - Sonst normal über `store.applyChanges(listOf(c))`.
   - **Bearbeitet, aber vom Import gelöscht:** Wurde für ein Record in diesem Push mindestens ein Feld ohne `_`-Präfix und ≠ `deleted` angenommen, und ist danach `deleted == true` mit HLC-nodeId `importer`, dann schreibe mit der Server-Uhr `deleted = false` und `_removedInSource = true` (Spec §5: „Objekt fehlt in Wanderlog, in App geändert → bleibt, Markierung“).
3. `accepted` = Anzahl der Geräteänderungen, die (direkt oder als Konflikt-Gewinner) geschrieben wurden; `serverSeq = store.maxSeq()`.

- [ ] **Step 1: Tests schreiben** (`SyncServiceTest`, echte SQLite-Datei in `@TempDir`; HLC-Strings mit Hilfsfunktion `hlc(wall, node, counter = 0)`)
  - `acceptsNewerRejectsOlder`.
  - `duplicatePushIsNoop` (Review Focus 2): gleicher Push zweimal → zweites Mal `accepted == 0`, `maxSeq` unverändert, kein `_conflict`.
  - `invalidBatchWritesNothing` (Review Focus 4): eine gültige + eine mit `hlc = "kaputt"` → `PushRejected("invalid_hlc")`, `maxSeq() == 0`.
  - `rejectsFutureHlc`: wall = now + 25 h → `clock_drift`; now + 23 h → angenommen.
  - `rejectsReservedNode`: nodeId `importer` → `reserved_node`.
  - `deviceLosingToImporterBecomesConflict` (Review Focus 1): Store enthält Place `p` mit `notes`=`"W1"`/`_base.notes`=`"W1"` (HLC wall 100, `importer`), danach Import-Wert `notes`=`"W2"`, `_base.notes`=`"W2"` (wall 300, `importer`); Gerät pusht `notes`=`"Gerät"` mit wall 200 (`dev-a`) → gespeichert `notes == "Gerät"`, `_conflict.notes == "W2"`, `_base.notes == "W2"`, `accepted == 1`.
  - `deviceLosingToImporterSameValueIsQuiet`: Gerätewert == Importer-Wert → kein `_conflict`, `accepted == 0`.
  - `deviceLosingToImporterOnMasterFieldIsDropped`: Feld ohne `_base.<f>` (z. B. `rating`) → Gerät verliert still, kein Konflikt.
  - `deviceLosingToOtherDeviceIsPlainLww`: Verlust gegen `dev-b` → kein Konflikt.
  - `editRevivesImporterDeletedRecord`: Record mit `deleted=true` (importer, wall 300), Gerät pusht `notes` (wall 200, Feld hat ältere HLC) → `deleted == false`, `_removedInSource == true`.
  - `SyncRoutesTest`: Push über HTTP mit `TOKEN_B` → 200, danach Pull `since=0` enthält das Record; Autor in DB = `bob`; `since=-1` → 400; `limit=0` → 400; Body mit unbekanntem `type` → 400.
  - `ServerConvergenceTest` (Spec §12): zwei Geräte (`InMemoryEntityStore` + `HlcClock("dev-a")`/`("dev-b")`) und der Server; 200 Runden mit festem `Random(42)`: jedes Gerät ändert zufällige Felder (`notes`, `title`, `deleted`) an 5 festen IDs lokal, pusht zu zufälligen Zeitpunkten seinen Ausgang, pullt zu zufälligen Zeitpunkten (`since` je Gerät gemerkt, Records per Feld mit `EntityRecord.apply` gemischt); zusätzlich alle 50 Runden ein Server-Import-Schreibvorgang mit `HlcClock("importer")` direkt über `sync.withWriteLock { store.transaction("importer") { store.applyChanges(...) } }`. Am Ende: alle pushen und pullen; beide Geräte-Stores enthalten exakt dieselben Records wie `store.all()`.
- [ ] **Step 2:** FAIL. **Step 3:** implementieren. **Step 4:** `./gradlew test` grün.
- [ ] **Step 5: Commit** `feat(server): sync push/pull with batch validation and importer conflict rule`

---

### Task 5: Datei-API

**Files:**
- Create: `server/src/main/kotlin/de/wanderlogplus/server/FileStore.kt`
- Modify: `Application.kt`, `Services` (Feld `files`)
- Test: `server/src/test/kotlin/de/wanderlogplus/server/FileRoutesTest.kt`

**Interfaces:**
- Produces:
  - `class FileStore(private val dir: Path, private val maxBytes: Long)`; `fun save(input: InputStream): StoredFile` (streamt in eine Temp-Datei im selben Verzeichnis, berechnet SHA-256 dabei, bricht bei > `maxBytes` mit `FileTooLarge` ab und löscht die Temp-Datei, verschiebt atomar nach `<dir>/<hash>`; existiert der Hash schon, wird die Temp-Datei verworfen und `duplicate = true`); `fun open(hash: String): Path?`.
  - `@Serializable data class StoredFile(val hash: String, val size: Long, val duplicate: Boolean)`.
  - Routen: `POST /api/files` (Multipart, genau ein Datei-Part namens `file`) → 200 `StoredFile`; fehlt der Part → 400; zu groß → 413 `{"error":"file_too_large"}`. `GET /api/files/{hash}`: `hash` muss `^[0-9a-f]{64}$` sein (sonst 400), unbekannt → 404, sonst 200 `application/octet-stream` mit Datei-Inhalt.
  - Ktor-Multipart-Limit (`receiveMultipart(formFieldLimit = maxBytes + 1)`) so setzen, dass das eigene Limit greift.

- [ ] **Step 1: Tests schreiben**
  - `uploadAndDownload`: 3 KB Zufallsbytes hochladen → Hash = SHA-256 der Bytes, Download liefert identische Bytes.
  - `duplicateDetected`: zweiter Upload gleicher Bytes → `duplicate == true`, nur eine Datei im Verzeichnis.
  - `tooLarge413`: Services mit `maxFileBytes = 1024`, Upload 2048 Bytes → 413, Verzeichnis enthält keine Datei (auch keine Temp-Datei).
  - `invalidHash400` (`GET /api/files/..%2Fsecret` und `GET /api/files/ABC`), `unknownHash404`.
  - `filesNeedAuth401`.
- [ ] **Step 2–4:** FAIL → implementieren → `./gradlew :server:test` grün.
- [ ] **Step 5: Commit** `feat(server): content-addressed file upload and download`

---

### Task 6: Import-Auslösung, Import-Status, Export

**Files:**
- Create: `server/src/main/kotlin/de/wanderlogplus/server/ImportService.kt`
- Modify: `Application.kt`, `Services` (Feld `imports`), `Main.kt` (TripSource aus Config)
- Test: `server/src/test/kotlin/de/wanderlogplus/server/{ImportRoutesTest,RealDataServerTest}.kt`

**Interfaces:**
- Consumes: `ImportRunner.run(json, store, clock): ImportResult` und `ImportFormatException` aus `importer`; `WanderlogClient.fetch`; `SyncService.withWriteLock`; `SqliteStore.transaction`, `appendImportLog`, `importLog`.
- Produces:
  - `fun interface TripSource { fun fetch(): String }`; `fun tripSourceFrom(config: ServerConfig): TripSource?` (`importFile` → Datei lesen; sonst `tripKey` → `WanderlogClient().fetch(key)`; sonst `null`).
  - `class ImportService(store: SqliteStore, sync: SyncService, source: TripSource?, now: () -> Instant = Instant::now)`; `fun runImport(): ImportLogEntry` – Abruf **außerhalb** der Sperre, dann `sync.withWriteLock { store.transaction("importer") { ImportRunner.run(json, store, HlcClock("importer")) } }`; Erfolg und Fehler werden ins Import-Log geschrieben. Gleichzeitige Aufrufe laufen nacheinander (zweiter wartet).
  - Routen:
    - `POST /api/import/wanderlog` → 200 `ImportLogEntry` (ok=true); nicht konfiguriert → 400 `import_not_configured`; `ImportFormatException` → 502 `{"error":"import_failed","message":…}` (Log-Eintrag ok=false, `error` = Meldung).
    - `GET /api/import/status?limit=<1..100, Default 20>` → 200 `List<ImportLogEntry>`.
    - `GET /api/export` → 200 `List<EntityRecord>` (alle Records inkl. reservierter Felder), Header `Content-Disposition: attachment; filename="wanderlog-plus-export.json"`.

- [ ] **Step 1: Tests schreiben**
  - `ImportRoutesTest` mit `TripSource`, die `importer/src/test/resources/wanderlog-mini.json` liest (Pfad über `System.getProperty("repoRoot")`, in `server/build.gradle.kts` wie beim Importer setzen):
    - `importCreatesRecordsAndLogs`: POST → 200, `ok == true`, `created > 0`; Pull `since=0` liefert Records; `GET /api/import/status` → ein Eintrag.
    - `secondImportIsNoop`: zweites POST → `created == 0 && updated == 0`, `maxSeq` unverändert.
    - `invalidJsonAbortsBeforeWrite` (Review Focus 5): Source liefert `{"foo":1}` → 502, `maxSeq() == 0`, Status-Eintrag `ok == false`, `error` nicht leer.
    - `notConfigured400`.
    - `importDoesNotOverwriteOfflineEdit`: Import, Gerät pusht `notes` eines importierten Place mit **neuer** HLC, zweiter Import mit derselben Datei → Gerätewert bleibt (normaler 3-Wege-Merge, `conflicts == 0`).
    - `exportContainsAllRecords`: nach Import liefert `/api/export` so viele Records wie `store.all()`; ohne Token 401.
  - `RealDataServerTest` (übersprungen ohne `testdata/private/wanderlog-real.json`): Import über die Route → `created == 533`; alle Pull-Seiten mit `limit=100` einsammeln → 533 verschiedene Records; zweiter Import `created == 0 && updated == 0`. Keine Feldwerte ausgeben.
- [ ] **Step 2–4:** FAIL → implementieren → `./gradlew test` grün.
- [ ] **Step 5: Commit** `feat(server): trigger wanderlog import, import log, full export`

---

### Task 7: Docker-Image, Compose, Caddy, Betriebsdoku, LiveStatus-Erweiterungspunkt

**Files:**
- Create: `server/src/main/kotlin/de/wanderlogplus/server/LiveStatusProvider.kt`, `server/Dockerfile`, `server/docker-compose.yml`, `server/Caddyfile.example`, `server/README.md`, `.dockerignore` (Repo-Root)
- Modify: `.gitignore` (`server/.env`)

**Interfaces:**
- Produces: `interface LiveStatusProvider { fun statusFor(type: EntityType, id: String): LiveStatus? }`, `@Serializable data class LiveStatus(val summary: String, val delayMinutes: Int? = null, val updatedAt: String)`, `object NoLiveStatus : LiveStatusProvider` (liefert immer `null`). Noch keine Route (Spec §10: V1 leer).
- `Dockerfile` (Build-Kontext = Repo-Root, `docker build -f server/Dockerfile .`): Stage 1 `gradle:8.11.1-jdk17` → `gradle :server:installDist --no-daemon`; Stage 2 `eclipse-temurin:17-jre` → kopiert `server/build/install/server`, Nutzer ohne Root (`uid 10001`), `VOLUME /data`, `EXPOSE 8080`, `HEALTHCHECK` per `curl -fs http://localhost:8080/api/health` (curl im Image installieren) , `ENTRYPOINT ["/app/bin/server"]`.
- `.dockerignore`: `.git`, `**/build`, `.gradle`, `testdata`, `.remember`, `.superpowers`, `.idea`.
- `docker-compose.yml`: Dienst `server` (Build wie oben, `env_file: .env`, Volume `wlp-data:/data`, nur `127.0.0.1:8080:8080` veröffentlicht) und Dienst `caddy` (`caddy:2`, Ports 80/443, mountet `Caddyfile`).
- `Caddyfile.example`: `{$WLP_DOMAIN} { reverse_proxy server:8080 }`.
- `README.md` (Deutsch, knapp): Zweck; Umgebungsvariablen (Tabelle wie in Global Constraints); Tokens erzeugen (`openssl rand -hex 24`); Start mit Compose; Backup = Volume `wlp-data` (DB + `files/`) bzw. `GET /api/export`; Hinweis, dass `WLP_TRIP_KEY` Zugriff auf Buchungsdaten gibt und nie committet wird; `.env` steht in `.gitignore`.

- [ ] **Step 1:** Dateien anlegen.
- [ ] **Step 2: Verifizieren:** `./gradlew test` grün. Dann `docker build -f server/Dockerfile -t wanderlog-plus-server .` und `docker run --rm -d -p 18080:8080 -e WLP_TOKENS=alice:token-alice-0123456789 --name wlp-smoke wanderlog-plus-server`, danach `curl -fs http://localhost:18080/api/health` → `{"status":"ok","protocol":1}` und `curl -s -o /dev/null -w "%{http_code}" -H "X-Protocol-Version: 1" http://localhost:18080/api/whoami` → `401`; `docker stop wlp-smoke`. Ist Docker im Ausführungsumfeld nicht verfügbar (Daemon läuft nicht), Schritt als nicht ausgeführt melden statt zu raten.
- [ ] **Step 3: Commit** `feat(server): docker image, compose with caddy, ops readme, live status extension point`
