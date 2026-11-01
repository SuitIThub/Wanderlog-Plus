# Plan 3: App-Grundgerüst – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eine native Android-App, die sich mit dem Sync-Server verbindet, alle Reisedaten lokal hält und offline nutzbar macht. Sie zeigt die Tage mit Region, Unterkunft, Programm und Vorschlägen, ermöglicht die wichtigsten Bearbeitungen (Vorschlag in Tag übernehmen, Programm sortieren/entfernen, Notiz und Status eines Ortes) und löst Konflikte und Importe aus.

**Architecture:** Neues reines JVM-Modul `client` mit der gesamten Sync- und Bearbeitungslogik (HTTP-API, SyncEngine, LocalEditor, Konflikt-Auflösung) gegen Interfaces (`EntityStore`, `Outbox`, `SyncStateStore`). Getestet wird mit Ktor-MockEngine und gegen den echten Server (`server`-Modul, `testApplication`). Das Android-Modul `app` (Compose, Material 3) implementiert diese Interfaces mit Room und DataStore, plant Syncs mit WorkManager und rendert die Oberfläche aus einem `TripSnapshot`, der bei jeder lokalen Änderung neu gebaut wird. Fachlogik für Ansichten (Heute, Vorschlagsfilter, Öffnungszeiten) liegt in `shared/query` und ist JVM-getestet.

**Tech Stack:** Kotlin 2.1.0, AGP 8.7.3, Gradle 8.11.1, compileSdk/targetSdk 35, minSdk 26, Java 17. Compose BOM 2024.12.01 (Material 3), activity-compose 1.9.3, lifecycle 2.8.7, navigation-compose 2.8.5, Room 2.6.1 mit KSP 2.1.0-1.0.29, WorkManager 2.10.0, DataStore Preferences 1.1.1, Ktor Client 3.0.3 (OkHttp-Engine in der App, MockEngine/Test-Host in Tests), Coil 2.7.0, Robolectric 4.14.1, JUnit 4.13.2 (app), JUnit 5 (JVM-Module), kotlinx-coroutines-test 1.9.0.

**Spec:** `docs/superpowers/specs/2026-10-31-wanderlog-plus-design.md` (§4 abgeleitete Werte, §6 Sync-Auslöser, §9 App, §11 Fehlerbehandlung, §12 app). Verbindlich zusätzlich: `docs/superpowers/followups-from-plan1.md` („Pflicht für Plan 3“) und `docs/superpowers/followups-from-plan2.md` („Pflicht für Plan 3“). Teil 3 von 4; Plan 4 (Ausbau): Dateien/Anhänge + Teilen-Menü + Offline-Download, Karte, Navigation/Website/Telefon/Tabelog-Links, QR-Einrichtung, Tag-Notiz/Regions-Override/Tags bearbeiten, Fristen-Erinnerungen, Live-Status-Anzeige.

## Global Constraints

- Paket-Roots: `de.wanderlogplus.client` (Modul `client`), `de.wanderlogplus.app` (Modul `app`, `applicationId = "de.wanderlogplus.app"`, App-Name „Wanderlog Plus“).
- `client` ist reines Kotlin/JVM (keine Android-Abhängigkeit), hängt von `shared` ab und nutzt nur `ktor-client-core`, `ktor-client-content-negotiation`, `ktor-serialization-kotlinx-json`, kotlinx-coroutines-core.
- UI-Texte auf Deutsch. Datumsformat in der UI: `Sa, 09.05.` (Wochentag kurz, Tag.Monat.), Zeiten `HH:mm`.
- Jede `/api/*`-Anfrage außer `/api/health` trägt `Authorization: Bearer <token>` und `X-Protocol-Version: 1`; Bodies mit `Content-Type: application/json`.
- Pull-Cursor ist ausschließlich `PullResponse.nextSeq`; Pull wird wiederholt, solange `hasMore`. Für jede gepullte HLC und für `PushResponse.maxHlc` wird `clock.receive` aufgerufen (mit Drift-Kappung, Task 1).
- nodeId: einmal pro Installation zufällig (`dev-` + 12 Hex-Zeichen), dauerhaft gespeichert. `importer`/`server` sind reserviert.
- Geräte schreiben nie `_source`, `_sourceId`, `_base.*` (Server lehnt ab). App-angelegte Objekte haben daher kein `_source`; der Re-Import fasst sie nicht an.
- Neue IDs aus der App: UUID v7 (`Ids.newId()`, Task 1). Day-IDs für App-angelegte Tage deterministisch: `Ids.dayId(tripId, date)` = `UUID.nameUUIDFromBytes("day:<tripId>:<date>")`; existiert für das Datum bereits ein nicht gelöschter Day, wird dieser benutzt.
- Gelöschte Objekte (`deleted == true`) erscheinen nirgends in der UI.
- Buchungsdaten (Namen, Bestätigungsnummern) werden angezeigt, aber nie geloggt (kein `Log.*` mit Feldwerten, keine Crash-Reporter).
- Token und Server-URL liegen nur im app-privaten DataStore. Cleartext-HTTP ist erlaubt (LAN-Server), aber außerhalb privater Netzbereiche wird gewarnt.
- Kommandos aus dem Repo-Root, Git-Bash: `./gradlew`. `local.properties` mit `sdk.dir=C\:\\Users\\bigma\\AppData\\Local\\Android\\Sdk` (gitignored) muss existieren.

## Review Focus

1. **Offline-Änderung überlebt Sync:** Gerät ändert offline eine Notiz, pullt danach einen älteren Serverstand → lokaler Wert bleibt, wird beim nächsten Push übertragen und kommt beim zweiten Gerät an. (Test in Task 4.)
2. **Neue Server-Datenbank (Epoch-Wechsel / `cursor_ahead`):** Client setzt `since = 0`, pusht seinen vollständigen Feldstand, pullt neu; keine lokale Änderung geht verloren. (Test in Task 4.)
3. **Erster Start ohne Daten:** Keine Server-Konfiguration bzw. leere DB → Einrichtung bzw. Hinweis „Noch keine Daten – jetzt synchronisieren“, kein Absturz; Stays mit leeren Daten brechen `TripQueries` nicht. (Tests in Task 1 und Task 6.)
4. **Abbruch mitten im Sync:** Ausgang wird erst nach erfolgreichem Push geleert; erneuter Push nach Abbruch ist ein No-op am Server. (Test in Task 4.)
5. **Server-URL-Eingaben:** `sync.example.org`, `https://sync.example.org/`, ` http://192.168.1.10:8080 ` → normalisiert (Schema ergänzt als `https://`, Leerzeichen und abschließender `/` entfernt); ungültige Eingaben → verständliche Meldung statt Absturz. (Test in Task 6.)

---

## Dateistruktur

```
settings.gradle.kts            + pluginManagement (google, mavenCentral, gradlePluginPortal), google() in dependencyResolution, include("client","app")
build.gradle.kts               + android/compose/ksp Plugins (apply false)
gradle/libs.versions.toml      + Versionen aus Tech Stack
shared/…/sync/Hlc.kt           receive mit Drift-Kappung
shared/…/model/Ids.kt          newId() (UUID v7), dayId()
shared/…/query/TripQueries.kt  tolerant gegenüber leeren/ungültigen Stay-Daten
shared/…/query/PlaceQueries.kt Öffnungszeiten, Vorschlags-Sortierung/-Filter
shared/…/query/TodayQueries.kt Heute-Ansicht (vor/während/nach der Reise), Fristen, Fahrten eines Tages
importer/…/ReimportMerger.kt   Referenzprüfung vor Soft-Delete von Places
client/build.gradle.kts
client/src/main/kotlin/de/wanderlogplus/client/
  ServerUrl.kt         Normalisierung + Warnung „unsicher“
  SyncApi.kt           HTTP-Aufrufe, Fehlerklassifikation
  Outbox.kt            Interface Outbox, SyncStateStore + In-Memory-Implementierungen
  SyncEngine.kt        Push/Pull/Epoch/Fehler → SyncResult
  LocalEditor.kt       lokale Änderungen (Feld setzen, Objekt anlegen/löschen) + Tag-Programm-Operationen
  Conflicts.kt         Konflikte finden und auflösen
  TripSnapshot.kt      Records → typisierte Listen (ohne gelöschte), TripQueries-Fabrik
client/src/test/kotlin/…   inkl. Tests gegen den echten Server (testImplementation(project(":server")))
app/build.gradle.kts, app/src/main/AndroidManifest.xml
app/src/main/kotlin/de/wanderlogplus/app/
  WanderlogPlusApp.kt  Application + AppContainer (manuelle DI)
  data/                Room (FieldEntity, OutboxEntity, Dao, AppDatabase), RoomEntityStore, RoomOutbox, SettingsStore (DataStore), SnapshotRepository
  sync/                SyncWorker, SyncScheduler
  ui/                  MainActivity, Theme, Navigation, Format (Datum), Markdown
  ui/setup/            Einrichtung
  ui/today/ ui/trip/ ui/places/ ui/logistics/ ui/more/
app/src/test/kotlin/…  Robolectric-Tests
```

---

### Task 1: `shared`/`importer`-Follow-ups und Ansichtslogik

**Files:**
- Modify: `shared/src/main/kotlin/de/wanderlogplus/shared/sync/Hlc.kt`, `shared/src/main/kotlin/de/wanderlogplus/shared/query/TripQueries.kt`, `importer/src/main/kotlin/de/wanderlogplus/importer/ReimportMerger.kt`
- Create: `shared/src/main/kotlin/de/wanderlogplus/shared/model/Ids.kt`, `shared/src/main/kotlin/de/wanderlogplus/shared/query/PlaceQueries.kt`, `shared/src/main/kotlin/de/wanderlogplus/shared/query/TodayQueries.kt`
- Test: `shared/src/test/kotlin/de/wanderlogplus/shared/{sync/HlcTest,model/IdsTest,query/TripQueriesTest,query/PlaceQueriesTest,query/TodayQueriesTest}.kt`, `importer/src/test/kotlin/de/wanderlogplus/importer/ReimportMergerTest.kt`

**Interfaces:**
- Produces:
  - `HlcClock(nodeId, now, maxDriftMillis: Long = 86_400_000)`: `receive(remote)` behandelt `remote.wallMillis > now() + maxDriftMillis` wie eine Remote-Zeit von `now() + maxDriftMillis` (Zähler der Remote-HLC wird dann ignoriert) – die Uhr springt also höchstens 24 h vor.
  - `object Ids { fun newId(now: Long = System.currentTimeMillis(), random: java.util.Random = SecureRandom()): String /* UUID v7, RFC 9562 */; fun dayId(tripId: String, date: String): String }`
  - `TripQueries`: Stays mit leerem oder unparsbarem `checkIn`/`checkOut` werden ignoriert; `days()` liefert `emptyList()`, wenn `trip.startDate`/`endDate` leer oder unparsbar sind.
  - `PlaceQueries` (in `de.wanderlogplus.shared.query`):
    - `fun isOpenOn(periods: List<OpeningPeriod>, dayOfWeek: Int /* 0 = So */): Boolean?` – `null`, wenn keine Perioden bekannt.
    - `fun isOpenAt(periods: List<OpeningPeriod>, dayOfWeek: Int, time: String /* HH:mm */): Boolean?` – berücksichtigt Perioden über Mitternacht (`closeDay != openDay`) und „24 h“ (eine Periode ohne `closeTime`); `null` ohne Perioden.
    - `enum class TagRank { STAR, NEUTRAL, WARNING }`; `fun tagRank(tags: List<String>): TagRank` – enthält ein Tag `⭐` oder `🌟` → STAR; sonst enthält eines `⚠️` oder `⚠` → WARNING; sonst NEUTRAL.
    - `data class Suggestion(val saved: SavedPlace, val place: Place?)`
    - `fun sortSuggestions(list: List<Suggestion>): List<Suggestion>` – STAR vor NEUTRAL vor WARNING, dann `place.rating` absteigend (fehlend zuletzt), dann Name.
    - `fun filterSuggestions(list: List<Suggestion>, category: PlaceCategory?, openOnDayOfWeek: Int?): List<Suggestion>` – Orte mit unbekannten Öffnungszeiten bleiben beim Wochentagsfilter enthalten.
  - `TodayQueries(queries: TripQueries, trip: Trip, stays: List<Stay>, flights: List<Flight>, trains: List<Train>)`:
    - `sealed interface TodayState { data class Before(val daysUntilStart: Long, val deadlines: List<Deadline>) ; data class During(val day: DayView, val movements: List<Movement>) ; data object After ; data object Unknown }` (Unknown bei fehlenden Reisedaten)
    - `data class Deadline(val date: String, val stay: Stay)` – Stays mit `cancellationDeadline` (ISO-Datum oder ISO-Datum+Zeit; nur der Datumsteil zählt) ≥ heute, aufsteigend.
    - `sealed interface Movement { val date: String; val time: String? }` mit `data class FlightMove(val flight: Flight)` und `data class TrainMove(val train: Train)`; `fun movementsOn(date: String): List<Movement>` – nach `depart.date == date`, sortiert nach `depart.time` (fehlend zuletzt).
    - `fun state(today: java.time.LocalDate): TodayState`
- `ReimportMerger`: Ein PLACE, der in Wanderlog fehlt, wird nicht auf `deleted = true` gesetzt, solange ein nicht gelöschtes Record vom Typ SAVED_PLACE, STAY oder DAY_ITEM, das in diesem Lauf nicht selbst gelöscht wird, im Feld `placeId` auf ihn zeigt; stattdessen `_removedInSource = true` (zählt als `flaggedRemoved`).

- [ ] **Step 1: Tests schreiben**
  - `HlcTest.receiveCapsFarFutureRemote`: now = 1_000; remote wall = 1_000 + 3 Tage → Ergebnis wall = 1_000 + 86_400_000; nachfolgendes `tick()` > Ergebnis. `receiveWithinDriftUnchanged`: remote 1_000 + 1 h, counter 5 → wall = remote, counter 6.
  - `IdsTest`: `newId` hat Version 7 (`UUID.version() == 7`), Variante 2, die ersten 48 Bit = `now`; zwei IDs mit steigendem `now` sortieren als Strings aufsteigend; `dayId("t", "2027-05-10")` ist stabil und ≠ `dayId("t", "2027-05-11")`.
  - `TripQueriesTest.emptyStayDatesIgnored`: Stay mit `checkIn = ""` plus ein gültiger Stay → `regionFor` nutzt den gültigen, keine Exception. `emptyTripDates`: `days()` == leer.
  - `PlaceQueriesTest`: Periode Mo 09:00–17:00 → `isOpenAt(.., 1, "10:00") == true`, `"18:00" == false`, Di `false`; Periode Fr 18:00 → Sa 02:00: `isOpenAt(.., 6, "01:00") == true`; Periode So 00:00 ohne closeTime → immer offen; leere Liste → `null`. `tagRank(listOf("🌟"))==STAR`, `listOf("⚠️")==WARNING`, `listOf("⭐","⚠️")==STAR`. Sortierung: [WARNING r=4.9, NEUTRAL r=4.0, STAR r=3.0, NEUTRAL r=null] → STAR, NEUTRAL 4.0, NEUTRAL null, WARNING. Filter: Kategorie FOOD lässt nur FOOD; Wochentagsfilter lässt geschlossene raus, unbekannte drin.
  - `TodayQueriesTest`: Reise 2027-05-08..2027-05-29; `state(2027-05-01)` → `Before(7, …)`, Deadlines nur ≥ heute, sortiert; `state(2027-05-10)` → `During` mit Datum 2027-05-10 und Fahrten des Tages sortiert nach Zeit; `state(2027-05-30)` → `After`; leere Trip-Daten → `Unknown`.
  - `ReimportMergerTest.placeReferencedByAppDataIsFlaggedNotDeleted`: Place P (wanderlog) + App-angelegtes DayItem (ohne `_source`) mit `placeId = P`; Snapshot ohne P → P `deleted` bleibt false, `_removedInSource == true`. `unreferencedPlaceIsDeleted`: ohne Referenz → `deleted == true` (bisheriges Verhalten).
- [ ] **Step 2:** `./gradlew :shared:test :importer:test` → neue Tests schlagen fehl.
- [ ] **Step 3:** Implementieren.
- [ ] **Step 4:** `./gradlew test` → grün.
- [ ] **Step 5: Commit** `feat(shared): HLC drift cap, UUIDv7 ids, tolerant trip queries, place/today queries; importer keeps referenced places`

---

### Task 2: Modul `client` – Server-URL, SyncApi, Ausgang und Zustand

**Files:**
- Modify: `settings.gradle.kts` (include `client`), `gradle/libs.versions.toml`
- Create: `client/build.gradle.kts`, `client/src/main/kotlin/de/wanderlogplus/client/{ServerUrl,SyncApi,Outbox}.kt`
- Test: `client/src/test/kotlin/de/wanderlogplus/client/{ServerUrlTest,SyncApiTest}.kt`

**Interfaces:**
- Produces:
  - `object ServerUrl { fun normalize(input: String): Result<String>; fun isInsecure(url: String): Boolean }` – `normalize`: trim, ohne Schema → `https://` voranstellen, abschließende `/` entfernen, nur `http`/`https`, Host Pflicht, kein Pfad außer leer (sonst Failure mit deutscher Meldung, z. B. „Ungültige Server-Adresse“). `isInsecure`: `http://` und Host nicht in `localhost`, `127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `*.local`.
  - `class SyncApi(private val http: HttpClient, private val baseUrl: String, private val token: String)` (der Aufrufer installiert ContentNegotiation nicht; `SyncApi` erzeugt intern einen Client via `http.config { install(ContentNegotiation) { json(...) }; expectSuccess = false }`):
    - `suspend fun health(): HealthInfo` (`@Serializable data class HealthInfo(val status: String, val protocol: Int, val epoch: String, val serverTime: Long)`), ohne Auth.
    - `suspend fun whoami(): String`
    - `suspend fun push(changes: List<FieldChange>): PushResponse`
    - `suspend fun pull(since: Long, limit: Int = 500): PullResponse`
    - `suspend fun triggerImport(): ImportLogInfo`, `suspend fun importStatus(limit: Int = 20): List<ImportLogInfo>` (`@Serializable data class ImportLogInfo(startedAt, finishedAt, ok, created, updated, conflicts, removed, flaggedRemoved, error: String? = null)` – gleiche Feldnamen wie `ImportLogEntry` des Servers).
    - Fehler: wirft `ApiException(val kind: ApiErrorKind, val code: String?, val status: Int?)` mit `enum class ApiErrorKind { NETWORK, UNAUTHORIZED, PROTOCOL, CURSOR_AHEAD, BAD_REQUEST, CLOCK_DRIFT, NOT_CONFIGURED, SERVER }`. Zuordnung: IOException/Timeout → NETWORK; 401 → UNAUTHORIZED; 409 `protocol_mismatch` → PROTOCOL; 409 `cursor_ahead` → CURSOR_AHEAD; 400 `clock_drift` → CLOCK_DRIFT; 400 `import_not_configured` → NOT_CONFIGURED; sonstige 4xx → BAD_REQUEST (mit `code` und `message` aus `ErrorResponse`, Feld `val message: String?` zusätzlich); 5xx/502 → SERVER. Timeouts: Connect 10 s, Request 60 s (über `HttpTimeout`).
  - `data class OutboxEntry(val seq: Long, val change: FieldChange)`
  - `interface Outbox { fun add(changes: List<FieldChange>); fun peek(limit: Int): List<OutboxEntry>; fun remove(seqs: Collection<Long>); fun size(): Int }`
  - `interface SyncStateStore { var nodeId: String?; var since: Long; var epoch: String?; var lastSyncAt: Long?; var lastError: String? }`
  - `class InMemoryOutbox : Outbox`, `class InMemorySyncState : SyncStateStore` (für Tests und als Referenz).
- Dependencies `client/build.gradle.kts`: `kotlin("jvm")`, `kotlin("plugin.serialization")`, toolchain 17, `api(project(":shared"))`, ktor-client-core, ktor-client-content-negotiation, ktor-serialization-kotlinx-json, kotlinx-coroutines-core 1.9.0; test: ktor-client-mock, kotlinx-coroutines-test, junit5, kotlin-test, `testImplementation(project(":server"))`, ktor-server-test-host.

- [ ] **Step 1: Tests schreiben**
  - `ServerUrlTest` (Review Focus 5): `"sync.example.org"` → `https://sync.example.org`; `"https://sync.example.org/"` → ohne `/`; `"  http://192.168.1.10:8080 "` → `http://192.168.1.10:8080`; `"ftp://x"`, `""`, `"http://"`, `"https://host/pfad"` → Failure. `isInsecure("http://192.168.1.10:8080") == false`, `isInsecure("http://sync.example.org") == true`, `isInsecure("https://sync.example.org") == false`, `isInsecure("http://nas.local") == false`, `isInsecure("http://172.20.0.5") == false`, `isInsecure("http://172.32.0.5") == true`.
  - `SyncApiTest` mit `MockEngine`: Header `Authorization`, `X-Protocol-Version`, `Content-Type` beim Push vorhanden; `health` ohne Authorization; Zuordnung aller Fehlerarten (401, 409 protocol_mismatch, 409 cursor_ahead, 400 clock_drift, 400 invalid_hlc mit message „change #2: invalid HLC“ → BAD_REQUEST mit code/message, 502 → SERVER, geworfene `java.io.IOException` → NETWORK).
- [ ] **Step 2–4:** FAIL → implementieren → `./gradlew :client:test` grün.
- [ ] **Step 5: Commit** `feat(client): server url handling, sync api with error classification, outbox/state interfaces`

---

### Task 3: `LocalEditor`, `TripSnapshot`, Konflikte

**Files:**
- Create: `client/src/main/kotlin/de/wanderlogplus/client/{LocalEditor,TripSnapshot,Conflicts}.kt`
- Test: `client/src/test/kotlin/de/wanderlogplus/client/{LocalEditorTest,TripSnapshotTest,ConflictsTest}.kt`

**Interfaces:**
- Consumes: `EntityStore`, `applyChanges`, `HlcClock`, `EntityCodec`, `Ids`, `SortKey`, Models, `TripQueries`.
- Produces:
  - `fun interface Transactor { fun <T> run(block: () -> T): T }` mit `val NoTransaction: Transactor`.
  - `class LocalEditor(private val store: EntityStore, private val outbox: Outbox, private val clock: HlcClock, private val tx: Transactor = NoTransaction, private val onChange: () -> Unit = {})`
    - `fun set(type: EntityType, id: String, values: Map<String, JsonElement>)` – je Feld `clock.change(...)`, in einer Transaktion `store.applyChanges` + `outbox.add` (nur angenommene), danach `onChange()`. Wirft `IllegalArgumentException` für Felder `_source`, `_sourceId`, `_base.*`.
    - `fun create(type: EntityType, values: Map<String, JsonElement>, id: String = Ids.newId()): String` – setzt zusätzlich `deleted = false`.
    - `fun delete(type: EntityType, id: String)` – `deleted = true`.
    - `fun addSuggestionToDay(snapshot: TripSnapshot, date: String, savedPlaceId: String): String` – Day finden (nicht gelöscht, `date` gleich) oder mit `Ids.dayId(trip.id, date)` anlegen (`date`); DayItem anlegen (`dayId`, `kind = PLACE`, `placeId` = SavedPlace.placeId, `sortKey = SortKey.between(letzter sortKey des Tages, null)`); SavedPlace `status = PLANNED`. Gibt die DayItem-ID zurück.
    - `fun moveDayItem(snapshot: TripSnapshot, itemId: String, up: Boolean)` – neuer `sortKey` zwischen den Nachbarn (an Rändern No-op).
    - `fun removeDayItem(snapshot: TripSnapshot, itemId: String)` – DayItem `deleted = true`; war es ein PLACE und gibt es einen SavedPlace mit diesem `placeId` und Status PLANNED, und hat dieser Ort kein anderes nicht gelöschtes DayItem, dann SavedPlace `status = IDEA`.
  - `data class TripSnapshot(val trip: Trip?, val regions: List<Region>, val places: Map<String, Place>, val savedPlaces: List<SavedPlace>, val stays: List<Stay>, val flights: List<Flight>, val trains: List<Train>, val days: List<Day>, val dayItems: List<DayItem>, val notes: List<Note>, val conflicts: List<Conflict>)`; `companion fun from(records: List<EntityRecord>): TripSnapshot` (nur nicht gelöschte, Trip = erster nicht gelöschter nach id); `fun queries(): TripQueries?` (null ohne Trip); `fun dayItemsFor(dayId: String): List<DayItem>` (nach `sortKey`); `val isEmpty: Boolean` (kein Trip).
  - `data class Conflict(val type: EntityType, val id: String, val field: String, val appValue: JsonElement, val wanderlogValue: JsonElement, val label: String)` – aus allen nicht gelöschten Records mit `_conflict.<f>` ≠ JsonNull; `label` = Name/Titel des Objekts (Place-Name über `placeId` für SavedPlace/Stay, sonst `title`/`name`/Typname).
  - `fun LocalEditor.keepApp(c: Conflict)` → `_conflict.<f> = null`; `fun LocalEditor.takeWanderlog(c: Conflict)` → `<f> = wanderlogValue`, `_conflict.<f> = null`.

- [ ] **Step 1: Tests schreiben** (In-Memory-Store/Outbox, `HlcClock("dev-a") { fester Zeitwert }`)
  - `LocalEditorTest`: `set` schreibt Store + Outbox und ruft `onChange`; reservierte Felder → Exception, nichts geschrieben; `create` setzt `deleted=false` und eine UUIDv7-ID; `addSuggestionToDay` ohne vorhandenen Day legt Day mit `Ids.dayId` an, DayItem mit sortKey nach dem letzten, SavedPlace → PLANNED; mit vorhandenem (importiertem) Day nutzt es dessen ID; zweimaliges Hinzufügen für dasselbe Datum erzeugt keinen zweiten Day; `moveDayItem` tauscht die Reihenfolge (über `dayItemsFor`); `removeDayItem` setzt SavedPlace zurück auf IDEA, aber nicht, wenn der Ort noch in einem anderen Tag eingeplant ist.
  - `TripSnapshotTest`: gelöschte Records fehlen; leere Records → `isEmpty`, `queries() == null`; Konflikt wird mit Label gefunden.
  - `ConflictsTest`: `keepApp` → kein Konflikt mehr, Wert unverändert; `takeWanderlog` → Wert = Wanderlog-Wert, kein Konflikt; beide erzeugen Outbox-Einträge.
- [ ] **Step 2–4:** FAIL → implementieren → `./gradlew :client:test` grün.
- [ ] **Step 5: Commit** `feat(client): local editor, trip snapshot, conflict resolution`

---

### Task 4: `SyncEngine` (inkl. Tests gegen den echten Server)

**Files:**
- Create: `client/src/main/kotlin/de/wanderlogplus/client/SyncEngine.kt`
- Test: `client/src/test/kotlin/de/wanderlogplus/client/{SyncEngineTest,SyncEngineServerTest}.kt`

**Interfaces:**
- Consumes: `SyncApi`, `Outbox`, `SyncStateStore`, `EntityStore`, `HlcClock`, `Transactor`.
- Produces:
  - `sealed interface SyncResult { data class Ok(val pushed: Int, val pulled: Int) : SyncResult; data class Failed(val kind: ApiErrorKind, val message: String) : SyncResult }` – `message` deutsch, für die UI (z. B. „Server nicht erreichbar“, „Token ungültig“, „App-Version passt nicht zum Server“, „Uhrzeit des Geräts weicht stark ab“).
  - `class SyncEngine(private val api: SyncApi, private val store: EntityStore, private val outbox: Outbox, private val state: SyncStateStore, private val clock: HlcClock, private val tx: Transactor = NoTransaction, private val now: () -> Long = System::currentTimeMillis)`; `suspend fun sync(): SyncResult` (nicht reentrant: ein `Mutex`).
  - `fun resetLocalCursor()` – dieselbe Reset-Logik wie beim Epoch-Wechsel (`since = 0`, vollständiger lokaler Feldstand in den Ausgang), aber `epoch` bleibt; für „Sync-Zustand zurücksetzen“ nach einem eingespielten Backup.

**Ablauf `sync()` (verbindlich):**
1. `health()`; ist `state.epoch` null → speichern; ist sie anders → **Reset**: `since = 0`, gesamten lokalen Feldstand (`store.all()`, alle Felder außer `_source`, `_sourceId`, `_base.*`, mit ihren gespeicherten HLCs) in den Ausgang legen, `epoch` speichern.
2. **Push** in Blöcken à 500 aus `outbox.peek`: Erfolg → `clock.receive(maxHlc)` (falls nicht null), Einträge entfernen. `BAD_REQUEST` mit Code `invalid_change`/`invalid_hlc`/`reserved_field`/`reserved_node` und Meldung `change #n…` → nur den n-ten Eintrag des Blocks entfernen und den Block erneut senden (höchstens so oft wie der Block Einträge hat). Andere Fehler → Abbruch mit `Failed`, Ausgang unverändert.
3. **Pull** ab `state.since` mit `limit = 500`, solange `hasMore`: jede Seite in einer Transaktion anwenden (je Feld `FieldChange` → `store.applyChanges`, `clock.receive` je HLC), danach `since = nextSeq`. `CURSOR_AHEAD` → Reset wie in 1. und einmal von vorn (Push + Pull); ein zweites `CURSOR_AHEAD` → `Failed`.
4. Erfolg: `lastSyncAt = now()`, `lastError = null`. Fehler: `lastError = message`.

- [ ] **Step 1: Tests schreiben**
  - `SyncEngineTest` (MockEngine, In-Memory-Implementierungen): Push leert den Ausgang erst nach 200; bei 502 bleibt er vollständig (Review Focus 4); 400 `change #2: invalid HLC` entfernt nur Eintrag 2 und sendet den Rest; Pull über 3 Seiten (`hasMore`) setzt `since` auf das letzte `nextSeq`; `maxHlc` wird an `clock.receive` gegeben (nächstes `tick()` > maxHlc); Epoch-Wechsel → `since=0`, Ausgang enthält den vollständigen Feldstand ohne `_base.*`; `cursor_ahead` einmal → Reset + Erfolg; zweimal → `Failed(CURSOR_AHEAD)`; 401 → `Failed(UNAUTHORIZED)` und `lastError` gesetzt.
  - `SyncEngineServerTest` (echter Server via `testApplication { application { module(Services(...)) } }` aus `server`-Hauptklassen: `SqliteStore`, `SyncService`, `FileStore`, `ImportService` mit einer `TripSource` auf `importer/src/test/resources/wanderlog-mini.json`; `@TempDir`; `SyncApi` mit `createClient { }` des Test-Hosts, Basis-URL `""`/`http://localhost`):
    - `twoDevicesConverge`: Import über `triggerImport()`, Gerät A und B syncen → beide haben dieselben Records wie `store.all()`.
    - `offlineEditSurvivesAndPropagates` (Review Focus 1): A ändert offline `note` eines SavedPlace (LocalEditor), B ändert nichts, A pullt zuerst nur (simuliert durch Sync mit leerem Ausgang vor der Änderung, dann Änderung), A sync → B sync → B hat A's Wert; zweiter Import ändert ihn nicht.
    - `resetLocalCursorRepushes`: nach `resetLocalCursor()` enthält der Ausgang den vollständigen Feldstand, `since == 0`, Epoch unverändert.
    - `newServerDatabaseTriggersFullRepush` (Review Focus 2): A und B synchronisiert, A hat eine lokale Änderung gepusht; neuer Server mit frischer DB (neue Epoch) → A sync → Server enthält A's Feldstand inkl. der Änderung; B sync (Epoch-Wechsel) → B hat A's Änderung, keine lokale Änderung von B verloren.
    - `conflictRoundTrip`: A ändert offline `note` eines importierten SavedPlace; Server importiert eine geänderte Fassung (Fixture-Kopie mit anderem Notiztext für denselben Ort, im Test erzeugt) → A sync → A sieht `Conflict`; `takeWanderlog` + sync → Server-Wert = Wanderlog-Wert, kein `_conflict` mehr.
- [ ] **Step 2–4:** FAIL → implementieren → `./gradlew :client:test` grün.
- [ ] **Step 5: Commit** `feat(client): sync engine with epoch reset, indexed rejects, paged pull; tests against real server`

---

### Task 5: Android-Modul: Gerüst, Theme, Navigation, Room-Datenhaltung

**Files:**
- Modify: `settings.gradle.kts` (pluginManagement-Repos `google()`, `mavenCentral()`, `gradlePluginPortal()`; `google()` in dependencyResolution; include `app`), `build.gradle.kts`, `gradle/libs.versions.toml`, `.gitignore` (nichts Neues nötig, `local.properties` ist drin)
- Create: `local.properties` (nicht committen), `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/{strings,themes}.xml`, Launcher-Icon als einfaches Vector-Adaptive-Icon, `app/src/main/kotlin/de/wanderlogplus/app/{WanderlogPlusApp,AppContainer}.kt`, `app/src/main/kotlin/de/wanderlogplus/app/data/{AppDatabase,FieldEntity,OutboxEntity,FieldDao,RoomEntityStore,RoomOutbox,SettingsStore,SnapshotRepository}.kt`, `app/src/main/kotlin/de/wanderlogplus/app/ui/{MainActivity,Theme,AppNavigation}.kt`
- Test: `app/src/test/kotlin/de/wanderlogplus/app/{data/RoomEntityStoreTest,data/RoomOutboxTest,data/SettingsStoreTest,ui/NavigationTest}.kt`

**Interfaces:**
- `app/build.gradle.kts`: Plugins `com.android.application`, `org.jetbrains.kotlin.android`, `org.jetbrains.kotlin.plugin.compose`, `org.jetbrains.kotlin.plugin.serialization`, `com.google.devtools.ksp`; `namespace = applicationId = "de.wanderlogplus.app"`, compileSdk 35, minSdk 26, targetSdk 35, Java/Kotlin 17, `buildFeatures.compose = true`, `testOptions.unitTests.isIncludeAndroidResources = true`; `implementation(project(":client"))`; Ktor `ktor-client-okhttp`; Room (runtime, ktx, compiler via ksp); Robolectric/JUnit4/compose-ui-test-junit4/`ui-test-manifest` (debugImplementation)/room-testing/work-testing/coroutines-test als Test-Abhängigkeiten. Die JVM-Module behalten JUnit 5; `app` nutzt JUnit 4 (Robolectric).
- Manifest: `INTERNET`, `ACCESS_NETWORK_STATE`; `android:usesCleartextTraffic="true"`; `MainActivity` als Launcher; `android:name=".WanderlogPlusApp"`.
- Room: `@Entity(tableName = "fields", primaryKeys = ["type","id","field"]) data class FieldEntity(type: String, id: String, field: String, value: String /* JSON */, hlc: String)`; `@Entity(tableName = "outbox") data class OutboxEntity(@PrimaryKey(autoGenerate = true) val seq: Long = 0, type, id, field, value, hlc)`; `FieldDao` mit blockierenden Methoden (`get(type,id)`, `allOfType`, `all`, `upsert(list)`, Outbox `insert/peek/delete/count`) und `@Query("SELECT COUNT(*) FROM fields") fun observeFieldCount(): Flow<Int>` sowie einer `InvalidationTracker`-basierten `fun changes(): Flow<Unit>` in `SnapshotRepository`.
- `class RoomEntityStore(db: AppDatabase) : EntityStore`, `class RoomOutbox(db) : Outbox`, `val roomTransactor: Transactor` (`db.runInTransaction`).
- `class SettingsStore(context)`: DataStore Preferences; `data class ServerSettings(val url: String, val token: String)`; `val server: Flow<ServerSettings?>`; `suspend fun saveServer(s)`; implementiert zusätzlich `SyncStateStore` über eine **synchron** lesbare Ebene (`SharedPreferences` `"sync_state"` für nodeId/since/epoch/lastSyncAt/lastError ist erlaubt und einfacher – Entscheidung: Server-Einstellungen in DataStore, Sync-Zustand in SharedPreferences). `nodeId` wird beim ersten Zugriff erzeugt (`"dev-" + 12 Hex`).
- `class SnapshotRepository(store, db, scope)`: `val snapshot: StateFlow<TripSnapshot?>` – baut bei jeder Tabellenänderung `TripSnapshot.from(store.all())` auf `Dispatchers.Default` neu (null nur bis zum ersten Laden).
- `class AppContainer(context)`: hält `db`, `store`, `outbox`, `settings`, `clock` (`HlcClock(nodeId)`, beim Start `receive` auf die höchste gespeicherte HLC), `editor` (`LocalEditor(..., onChange = { syncScheduler.requestSoon() })` – `syncScheduler` kommt in Task 6; bis dahin No-op-Lambda), `snapshots`.
- UI-Gerüst: `MainActivity` (Compose, `enableEdgeToEdge`), `WanderlogPlusTheme` (Material 3, dynamische Farben ab Android 12, sonst feste Palette; Hell/Dunkel nach System), `AppNavigation`: `NavigationBar` mit 5 Tabs „Heute“, „Reise“, „Orte“, „Logistik“, „Mehr“ (Material-Icons: Today, CalendarMonth, Place, Train, MoreHoriz – `material-icons-extended` erlaubt); jeder Tab zunächst ein Platzhalter-Screen mit dem Tab-Namen als Titel.

- [ ] **Step 1: Tests schreiben** (Robolectric, `@RunWith(RobolectricTestRunner::class)`, `@Config(sdk = [34])`)
  - `RoomEntityStoreTest`: put/get/all-Roundtrip mit JsonNull und Emoji; `applyChanges` aus `shared` funktioniert über den Store (ältere HLC verworfen); In-Memory-DB (`Room.inMemoryDatabaseBuilder(...).allowMainThreadQueries()` nur im Test).
  - `RoomOutboxTest`: add → peek in Reihenfolge, remove einzelner seqs, size.
  - `SettingsStoreTest`: nodeId stabil über zwei Instanzen, beginnt mit `dev-`, 16 Zeichen; since/epoch Roundtrip; saveServer → Flow liefert den Wert.
  - `NavigationTest` (compose-ui-test): Alle 5 Tab-Labels sichtbar; Klick auf „Logistik“ zeigt den Logistik-Platzhalter.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest` → FAIL.
- [ ] **Step 3:** Implementieren.
- [ ] **Step 4:** `./gradlew test :app:assembleDebug` → grün, APK unter `app/build/outputs/apk/debug/`.
- [ ] **Step 5: Commit** `feat(app): android module with room storage, settings, theme and tab navigation`

---

### Task 6: Einrichtung, Sync-Planung, Sync-Status

**Files:**
- Create: `app/src/main/kotlin/de/wanderlogplus/app/sync/{SyncWorker,SyncScheduler,SyncRunner}.kt`, `app/src/main/kotlin/de/wanderlogplus/app/ui/setup/{SetupScreen,SetupViewModel}.kt`, `app/src/main/kotlin/de/wanderlogplus/app/ui/SyncStatusBar.kt`
- Modify: `AppContainer.kt`, `AppNavigation.kt`, `MainActivity.kt`
- Test: `app/src/test/kotlin/de/wanderlogplus/app/{sync/SyncSchedulerTest,ui/setup/SetupScreenTest,ui/EmptyStateTest}.kt`

**Interfaces:**
- `class SyncRunner(container)`: `suspend fun runOnce(): SyncResult?` – null ohne Server-Einstellungen; baut `SyncApi(HttpClient(OkHttp), url, token)` und `SyncEngine(..., tx = roomTransactor)` und ruft `sync()`; `val status: StateFlow<SyncStatus>` (`data class SyncStatus(val running: Boolean, val lastSyncAt: Long?, val lastError: String?, val pending: Int)`).
- `class SyncWorker : CoroutineWorker` → `SyncRunner.runOnce()`; `Failed` mit Kind NETWORK/SERVER → `Result.retry()` (Backoff exponentiell, 30 s Start), sonst `Result.success()` (Fehler steht im Status).
- `class SyncScheduler(context)`: `fun schedulePeriodic()` (unique periodic work `"sync-periodic"`, 15 min, Constraint `CONNECTED`, KEEP), `fun requestSoon()` (unique one-time `"sync-soon"`, `REPLACE`, Verzögerung 5 s, CONNECTED), `fun requestNow()` (unique one-time `"sync-now"`, REPLACE, ohne Verzögerung). App-Start (`WanderlogPlusApp.onCreate`) → `schedulePeriodic()` + `requestNow()`, falls Server konfiguriert.
- Einrichtung (`SetupScreen`): Felder „Server-Adresse“ und „Token“, Button „Verbindung testen“ (normalisiert URL via `ServerUrl.normalize`; Fehlermeldung inline; dann `health()` + `whoami()` → „Verbunden als <name>“ oder verständlicher Fehler je `ApiErrorKind`), Warnhinweis bei `ServerUrl.isInsecure` („Unverschlüsselte Verbindung über das Internet – Token und Buchungsdaten sind lesbar.“), Button „Speichern“ erst nach erfolgreichem Test aktiv → `saveServer`, `requestNow()`, Navigation zu „Heute“. Wird beim Start gezeigt, wenn keine Server-Einstellungen existieren; später über „Mehr“ erreichbar (Task 9).
- `SyncStatusBar`: kleine Zeile oben in jedem Tab: „Synchronisiere …“ / „Zuletzt synchronisiert vor 3 Min.“ / Fehlertext (rot) / „N Änderungen warten“; Pull-to-Refresh (`PullToRefreshBox`) in den Tab-Screens ruft `requestNow()`.
- Leerzustand: Ist `snapshot.isEmpty`, zeigen Heute/Reise/Orte/Logistik „Noch keine Daten – jetzt synchronisieren“ mit Button (→ `requestNow()`).

- [ ] **Step 1: Tests schreiben**
  - `SyncSchedulerTest` (work-testing, `WorkManagerTestInitHelper`): `requestSoon()` zweimal → genau eine eingereihte `"sync-soon"`-Arbeit; `schedulePeriodic()` → periodische Arbeit mit 15 min und Netz-Constraint.
  - `SetupScreenTest` (compose-ui-test mit Fake-`SetupViewModel`-Abhängigkeit, d. h. der Verbindungs-Test ist eine injizierbare suspend-Funktion): ungültige URL → Fehlermeldung, Speichern deaktiviert; `http://sync.example.org` → Warnhinweis sichtbar; erfolgreicher Test → „Verbunden als alice“, Speichern aktiv.
  - `EmptyStateTest`: leerer Snapshot → „Noch keine Daten“ im Heute-Tab sichtbar, kein Absturz (Review Focus 3).
- [ ] **Step 2–4:** FAIL → implementieren → `./gradlew test :app:assembleDebug` grün.
- [ ] **Step 5: Commit** `feat(app): setup screen, workmanager sync scheduling, sync status`

---

### Task 7: Reise-Tab: Tagesliste, Tagesdetail, Vorschläge, Markdown

**Files:**
- Create: `app/src/main/kotlin/de/wanderlogplus/app/ui/Format.kt`, `app/src/main/kotlin/de/wanderlogplus/app/ui/Markdown.kt`, `app/src/main/kotlin/de/wanderlogplus/app/ui/trip/{TripScreen,DayDetailScreen,TripViewModel}.kt`
- Test: `app/src/test/kotlin/de/wanderlogplus/app/ui/{FormatTest,MarkdownTest}.kt`, `app/src/test/kotlin/de/wanderlogplus/app/ui/trip/DayDetailTest.kt`

**Interfaces:**
- `Format`: `fun dayLabel(date: String): String` → `"Sa, 09.05."` (Locale.GERMAN), `fun regionColor(hex: String?): Color` (Fallback neutral), `fun relativeAgo(millis: Long, now: Long): String` („gerade eben“, „vor 3 Min.“, „vor 2 Std.“, „vor 1 Tag“/„vor N Tagen“).
- `Markdown`: `fun parseMarkdown(text: String): List<MdBlock>` (`sealed interface MdBlock`: `Paragraph(spans)`, `Bullet(spans)`, `Numbered(n, spans)`; Spans mit **fett**, *kursiv*, `[Text](url)`); **weicher Zeilenumbruch** (einzelnes `\n`) wird als Zeilenumbruch dargestellt; Leerzeile trennt Absätze. `@Composable fun MarkdownText(text: String)` rendert über `AnnotatedString`, Links mit `LinkAnnotation.Url`.
- Reise-Tab: `LazyColumn` aller `DayView`s: `dayLabel`, Tages-Titel, Regions-Chip (Name, Farbe), Unterkunftsname, Kennzeichnung „Reisetag“; Tipp → `DayDetailScreen(date)`.
- Tagesdetail: Kopf (Datum, Titel, Region, Unterkunft), Tages-Notiz (Markdown), **Programm** (`dayItemsFor(day.id)`: Ort-Name bzw. Notiztext, Zeit; Aktionen „nach oben“, „nach unten“, „entfernen“ → `LocalEditor`), **Vorschläge** (`suggestionsFor(date)` → `Suggestion`s → `filterSuggestions` mit Kategorie-Chips „Alle/Sehenswürdigkeiten/Essen/Shopping/Nightlife/Sonstiges“ und Schalter „an diesem Wochentag geöffnet“ → `sortSuggestions`; je Eintrag Name, Tags, Bewertung, Kategorie; Button „Zum Tag hinzufügen“ → `addSuggestionToDay`). Reisetag ohne Region → Hinweis „Reisetag – keine Vorschläge“.
- `TripViewModel(snapshots, editor)`: alle Schreibzugriffe auf `Dispatchers.IO`.

- [ ] **Step 1: Tests schreiben**
  - `FormatTest`: `dayLabel("2027-05-09") == "So, 09.05."`; `relativeAgo` Grenzfälle.
  - `MarkdownTest`: `"**Alternative:** Bus"` → Paragraph mit fettem Span „Alternative:“ und normalem „ Bus“; `"- a\n- b"` → zwei Bullets; `"Zeile 1\nZeile 2"` → ein Paragraph mit Zeilenumbruch; `"1. x"` → Numbered; Link-Span mit URL; Emoji/Umlaute unverändert.
  - `DayDetailTest` (compose-ui-test, Snapshot aus In-Memory-Records mit einem Tag, zwei Vorschlägen (eine ⭐, eine ⚠️) und einem Programmpunkt): Reihenfolge der Vorschläge ⭐ vor ⚠️; „Zum Tag hinzufügen“ → Vorschlag verschwindet aus der Liste und erscheint im Programm; „entfernen“ → zurück in den Vorschlägen; Reisetag zeigt den Hinweis.
- [ ] **Step 2–4:** FAIL → implementieren → `./gradlew test :app:assembleDebug` grün.
- [ ] **Step 5: Commit** `feat(app): trip tab with day list, day detail, suggestions and markdown`

---

### Task 8: Heute- und Orte-Tab

**Files:**
- Create: `app/src/main/kotlin/de/wanderlogplus/app/ui/today/{TodayScreen,TodayViewModel}.kt`, `app/src/main/kotlin/de/wanderlogplus/app/ui/places/{PlacesScreen,RegionPlacesScreen,PlaceDetailScreen,PlacesViewModel}.kt`
- Test: `app/src/test/kotlin/de/wanderlogplus/app/ui/{today/TodayScreenTest,places/PlaceDetailTest}.kt`

**Interfaces:**
- Heute: `TodayQueries.state(LocalDate.now())` (Datum injizierbar für Tests):
  - `Before`: „Noch N Tage“ + Liste „Fristen“ (Datum, Unterkunftsname, „Stornofrist“).
  - `During`: Tageskopf (Datum, Titel, Region, Unterkunft mit Check-in-/Check-out-Hinweis, wenn heute), „Fahrten heute“ (Flug/Zug mit Zeit und Strecke), „Programm“ (wie Tagesdetail, nur lesend, Tipp → Tagesdetail), „Jetzt geöffnet in der Nähe“: Vorschläge des Tages, gefiltert auf `isOpenAt(..., heute, jetzt) != false`, sortiert, max. 5.
  - `After`: „Willkommen zurück“ + Link zur Reise. `Unknown`: Leerzustand.
- Orte: Liste der Regionen (Name, Farbe, Anzahl Orte) + Eintrag „Ohne Region“ → `RegionPlacesScreen`: SavedPlaces der Region, gruppiert nach Kategorie, sortiert per `sortSuggestions`; Status als kleines Label.
- Ortsdetail (`PlaceDetailScreen(savedPlaceId)`): Bild (erstes `imageKey` → `https://itin-dev.wanderlogstatic.com/freeImageMedium/<key>` via Coil `AsyncImage`; Fehler/fehlend → kein Bild, kein Fehler), Name, Bewertung (★ 4.5 (1.234)), Kategorie, Tags, Adresse, Dauer (`minMinutes`–`maxMinutes` Min.), Öffnungszeiten je Wochentag (Mo–So, „geschlossen“/„unbekannt“), Beschreibung, **Notiz** (Markdown-Anzeige + „Bearbeiten“ → Textfeld → Speichern → `editor.set(SAVED_PLACE, id, note)`), **Status** (SegmentedButtons Idee/Geplant/Besucht/Verworfen → `editor.set(..., status)`).

- [ ] **Step 1: Tests schreiben**
  - `TodayScreenTest`: Datum vor der Reise → „Noch 7 Tage“ und eine Frist; Datum während der Reise → Region und „Fahrten heute“ mit einem Zug; leerer Snapshot → Leerzustand.
  - `PlaceDetailTest`: Notiz bearbeiten und speichern → `LocalEditor` hat Outbox-Eintrag `note`, Anzeige zeigt neuen Text; Status „Besucht“ → Outbox-Eintrag `status = "VISITED"`; Ort ohne Öffnungszeiten zeigt „unbekannt“.
- [ ] **Step 2–4:** FAIL → implementieren → `./gradlew test :app:assembleDebug` grün.
- [ ] **Step 5: Commit** `feat(app): today tab and places tab with editable note and status`

---

### Task 9: Logistik- und Mehr-Tab (Notizen, Import, Konflikte, Einstellungen)

**Files:**
- Create: `app/src/main/kotlin/de/wanderlogplus/app/ui/logistics/LogisticsScreen.kt`, `app/src/main/kotlin/de/wanderlogplus/app/ui/more/{MoreScreen,NotesScreen,ImportScreen,ConflictsScreen,SettingsScreen,MoreViewModel}.kt`
- Test: `app/src/test/kotlin/de/wanderlogplus/app/ui/{logistics/LogisticsScreenTest,more/ConflictsScreenTest,more/ImportScreenTest}.kt`

**Interfaces:**
- Logistik: eine chronologische Liste aus Flügen, Zügen und Unterkünften (Sortierschlüssel: Abfahrts-/Check-in-Datum + Zeit). Flug: Airline + Nummer, Abflug/Ankunft (Name, Code, Datum, Zeit), Bestätigungsnummer, Reisende. Zug: Anbieter, Strecke, Zeiten, Bestätigungsnummer. Unterkunft: Name, Check-in/-out, Bestätigungsnummer, Stornofrist. Je Eintrag ein Platzhalter-Label „Live-Status: –“ (Erweiterungspunkt, Plan 4). Notiz als Markdown, wenn vorhanden.
- Mehr: Einträge „Notizen“, „Import“, „Konflikte (N)“, „Einstellungen“.
  - Notizen: Liste nach `sortKey`, Detail mit Markdown.
  - Import: Button „Aus Wanderlog importieren“ → `SyncApi.triggerImport()` → danach `requestNow()`; Liste `importStatus()` (Zeit, ok/Fehler, neu/geändert/Konflikte/entfernt); `NOT_CONFIGURED` → „Auf dem Server ist kein Wanderlog-Trip konfiguriert.“; offline → Fehlermeldung.
  - Konflikte: Liste aus `snapshot.conflicts` (Label, Feldname, „App:“-Wert, „Wanderlog:“-Wert, Strings ohne Anführungszeichen, lange Texte gekürzt mit „mehr“); Buttons „App behalten“/„Wanderlog übernehmen“ → `keepApp`/`takeWanderlog`.
  - Einstellungen: Server-Adresse + Token (maskiert) ändern → Einrichtungs-Screen; Sync-Status (zuletzt, Fehler, wartende Änderungen, nodeId); „Jetzt synchronisieren“; „Sync-Zustand zurücksetzen“ (Bestätigungsdialog) → `SyncEngine.resetLocalCursor()` (Task 4), dann `requestNow()`.

- [ ] **Step 1: Tests schreiben**
  - `LogisticsScreenTest`: Flug 05-08, Unterkunft 05-09, Zug 05-12 erscheinen in dieser Reihenfolge; Bestätigungsnummer sichtbar.
  - `ConflictsScreenTest`: ein Konflikt sichtbar mit beiden Werten; „Wanderlog übernehmen“ → Liste leer, Outbox-Einträge für Feld und `_conflict.<f>`.
  - `ImportScreenTest` (SyncApi über MockEngine injiziert): Erfolg zeigt neuen Protokolleintrag; 400 `import_not_configured` zeigt den Hinweistext.
- [ ] **Step 2–4:** FAIL → implementieren → `./gradlew test :app:assembleDebug` grün.
- [ ] **Step 5: Commit** `feat(app): logistics tab and more tab with notes, import, conflicts and settings`

---

### Task 10: Ende-zu-Ende mit echten Daten und Abschluss

**Files:**
- Create: `client/src/test/kotlin/de/wanderlogplus/client/RealDataClientTest.kt`
- Modify: `README.md` im Repo-Root anlegen oder ergänzen (kurz, deutsch: Module, Build, App installieren per `./gradlew :app:installDebug`, Server siehe `server/README.md`)

**Interfaces:**
- `RealDataClientTest` (übersprungen ohne `testdata/private/wanderlog-real.json`): echter Server mit dieser Datei als `TripSource`; Import; zwei Clients syncen → je 533 Records; `TripSnapshot.from(...)` → `queries()!!.days()` hat 22 Tage (2027-05-08 bis 2027-05-29); `suggestionsFor("2027-05-20")` enthält SavedPlaces mit Regionen Kyoto **und** Nara (Namensprüfung über `regions`); Client A fügt einen Vorschlag zum 2027-05-20 hinzu → nach Sync von B ist das DayItem bei B und der Vorschlag nicht mehr in B's Vorschlägen. Keine Feldwerte ausgeben.

- [ ] **Step 1:** Test schreiben, ausführen (mit echter Datei: muss laufen, nicht übersprungen).
- [ ] **Step 2:** `./gradlew test :app:assembleDebug` → grün; APK-Größe notieren.
- [ ] **Step 3: Commit** `test(client): end-to-end sync with real trip data; repo readme`
