# Plan 4 – App-Ausbau Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die V1-Funktionen der Spec ergänzen, die nach Plan 3 fehlen: Dateianhänge (Hochladen, Offline-Download, Anzeige, Teilen-Menü), Navigation und externe Links, Bearbeiten von Tag und Ort, QR-Einrichtung, Karte, Reise-Zeitzone und Fristen-Erinnerungen.

**Architecture:** Netzwerk und reine Logik liegen in `client` (JVM, testbar ohne Android): Datei-API, `ServerUrl` mit Pfad-Präfix, `TripSnapshot.attachments`, neue `LocalEditor`-Operationen. Die App bekommt einen lokalen Dateispeicher (`filesDir/attachments/<sha256>`), eine Room-Tabelle für ausstehende Uploads (erste Migration 1→2) und einen `FileSyncWorker`, der nach jedem erfolgreichen Sync läuft. UI-Bausteine (Anhänge-Sektion, Link-Buttons) werden in die bestehenden Screens eingesetzt.

**Tech Stack:** Kotlin 2.1, Compose BOM 2024.12.01, Room 2.6.1, WorkManager 2.10.0, Ktor-Client 3.0.3, Coil 2.7.0; neu: `com.journeyapps:zxing-android-embedded:4.3.0`, `com.google.maps.android:maps-compose:6.2.1` (+ `play-services-maps:19.0.0`).

**Spec:** `docs/superpowers/specs/2026-10-31-wanderlog-plus-design.md` (§7 Dateien, §9 App, §10 Erweiterungspunkte, §11 Fehlerbehandlung, §12 Tests). Pflichtpunkte: `docs/superpowers/followups-from-plan3.md`, `followups-from-plan2.md`.

## Global Constraints

- Alle sichtbaren Texte auf Deutsch.
- Buchungsdaten (Namen, Bestätigungsnummern, Budget, Dateiinhalte, Dateinamen) erscheinen nie in Logs, Exception-Messages, Notifications oder Fehlermeldungen. Fehler werden nur als Klassenname oder fester Text weitergegeben.
- Jeder Schreibzugriff auf Entities läuft über `AppContainer.edit { editor, snapshot -> }`. Keine direkten `LocalEditor`-Aufrufe aus Composables.
- Jede Änderung am Room-Schema braucht eine `Migration` plus Migrationstest. `fallbackToDestructiveMigration` ist verboten.
- Jede Anfrage an `/api/*` außer Health trägt `Authorization: Bearer <token>` und `X-Protocol-Version: 1`. Das gilt auch für Datei-Up- und -Downloads; deshalb laufen sie ausschließlich über `SyncApi`.
- Größenlimit pro Datei in der App: 25 MB (`MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024`), wie der Server-Standard.
- Neue Bibliotheken werden mit fester Version in `gradle/libs.versions.toml` eingetragen.
- Tests: JUnit 5 in `client`, JUnit 4 + Robolectric in `app`. Es gibt kein Gerät und keinen Emulator. `JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.17.10-hotspot"`. Abschluss jedes Tasks: `./gradlew test :app:assembleDebug` grün.
- Echte Daten liegen nur in `testdata/private/` (gitignored) und werden nie committet.

## Review Focus

1. **Download liefert falsche oder abgeschnittene Bytes** (Verbindungsabbruch, Proxy-Fehlerseite): Die Datei darf nicht als verfügbar gelten. Erwartet: Prüfung des SHA-256 gegen den Hash, temporäre Datei wird verworfen. Test in Task 1 (`downloadRejectsHashMismatch`) und Task 3 (`partialDownloadLeavesNoFile`).
2. **Anhang wird offline hinzugefügt:** Der Datensatz synct, bevor die Datei hochgeladen ist. Das andere Gerät bekommt 404. Erwartet: Es zeigt „nicht offline verfügbar“ und versucht es später erneut, ohne Absturz und ohne Endlosschleife pro Sync. Test in Task 3 (`missingOnServerCountsAsFailedAndRetriesLater`).
3. **Upgrade einer installierten Version 1 der DB:** Es darf kein Absturz und kein Datenverlust auftreten. Test in Task 3 (`migrate1To2KeepsFieldsAndOutbox`).
4. **Teilen aus einer fremden App mit einer `content://`-URI ohne Größe oder Namen, oder über 25 MB:** Erwartet ist eine klare deutsche Meldung, und es entsteht kein halber Anhang. Test in Task 5 (`tooLargeShareCreatesNoAttachment`).
5. **Gerät steht in Japan auf mitteleuropäischer Zeit** (oder umgekehrt): „Heute“ muss den Reisetag in JST zeigen. Test in Task 2 (`todayUsesTripZone`).

---

### Task 1: client – Datei-API, Pfad-Präfix, Anhänge im Snapshot, neue Editor-Operationen

**Files:**
- Modify: `client/src/main/kotlin/de/wanderlogplus/client/SyncApi.kt`, `ServerUrl.kt`, `TripSnapshot.kt`, `LocalEditor.kt`
- Test: `client/src/test/kotlin/de/wanderlogplus/client/FileApiTest.kt` (MockEngine), `ServerUrlTest.kt`, `TripSnapshotTest.kt`, `LocalEditorTest.kt` (bestehende Dateien erweitern)

**Interfaces:**
- Produces:
  - `@Serializable data class UploadedFile(val hash: String, val size: Long, val duplicate: Boolean)`
  - `suspend fun SyncApi.uploadFile(file: java.io.File, fileName: String, mimeType: String): UploadedFile`: Multipart, Part-Name `file`, mit Dateiname und Content-Type. Bei 413 kommt `ApiException(BAD_REQUEST, code = "file_too_large", status = 413)`.
  - `suspend fun SyncApi.downloadFile(hash: String, dest: java.io.File)`:
    - Streamt nach `dest.resolveSibling(dest.name + ".part")` und berechnet dabei den SHA-256.
    - Bei Gleichheit mit `hash` folgt ein atomares Rename auf `dest`, sonst wird `.part` gelöscht und `ApiException(SERVER, message = "Datei beschädigt")` geworfen.
    - 404 ergibt `ApiException(BAD_REQUEST, code = "not_found", status = 404)`. IOException ergibt NETWORK, die `.part`-Datei wird gelöscht.
    - Für Downloads gilt `requestTimeoutMillis` = 300_000.
  - `ServerUrl.normalize` lässt einen Pfad-Präfix zu: `https://host/wlp/` wird zu `https://host/wlp`. Query, Fragment und UserInfo werden weiter abgelehnt. `SyncApi` hängt `/api/...` an den Präfix an; das tut der bestehende `base`-Code bereits, ein Test belegt es.
  - `TripSnapshot.attachments: List<Attachment>` (nicht gelöschte) und `fun attachmentsFor(type: EntityType, id: String): List<Attachment>`, sortiert nach `fileName`, dann `id`.
  - `LocalEditor.addAttachment(targetType: EntityType, targetId: String, fileHash: String, fileName: String, mimeType: String, size: Long): String` (neue ID). Entfernen läuft über das bestehende `delete(EntityType.ATTACHMENT, id)`.
  - `LocalEditor.setDayFields(snapshot: TripSnapshot, date: String, values: Map<String, JsonElement>)`:
    - Erlaubte Keys sind nur `title`, `note` und `regionOverrideId`, sonst `IllegalArgumentException`.
    - Gibt es kein Day für `date` (`snapshot.queries()?.dayFor(date)`), wird es mit `Ids.dayId(trip.id, date)`, `date`, `deleted=false` und den Werten in einer Transaktion angelegt.
  - `LocalEditor.addNoteToDay(snapshot: TripSnapshot, date: String, text: String): String` ist wie `addSuggestionToDay`, aber mit `kind = NOTE` und `text`, ans Ende sortiert. Dabei wird kein SavedPlace-Status geändert.

- [ ] **Step 1: Write failing tests**
  - `FileApiTest.uploadSendsMultipartWithAuthHeaders`: Die MockEngine prüft `Authorization`, `X-Protocol-Version: 1`, Part-Name `file` und Dateiname. Antwort `{"hash":"ab..","size":3,"duplicate":false}`, decodiert zu `UploadedFile`.
  - `FileApiTest.uploadTooLargeMapsTo413Code`: 413 `{"error":"file_too_large"}` führt zu `kind == BAD_REQUEST`, `code == "file_too_large"`.
  - `FileApiTest.downloadWritesFileWhenHashMatches`: Bytes "abc" mit dem SHA-256 von "abc" (`ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad`). Danach existiert `dest`, `.part` nicht.
  - `FileApiTest.downloadRejectsHashMismatch`: Bytes "abd" mit demselben Hash ergeben ApiException mit `message == "Datei beschädigt"`. Danach gibt es weder `dest` noch `.part`.
  - `FileApiTest.download404IsNotFound` und `downloadSendsAuthHeaders`.
  - `ServerUrlTest.acceptsPathPrefix`: `"https://h.example/wlp/"` wird zu `"https://h.example/wlp"`. `rejectsQueryEvenWithPrefix`: `"https://h/wlp?x=1"` schlägt fehl. `FileApiTest.prefixIsUsedForRequests`: Die URL ist `https://h/wlp/api/health`.
  - `TripSnapshotTest.attachmentsForFiltersDeletedAndSorts`.
  - `LocalEditorTest.setDayFieldsCreatesDayOnce`:
    - Zwei Aufrufe für dasselbe Datum ergeben genau ein Day mit `Ids.dayId`.
    - Beim zweiten Aufruf schreibt das Outbox nur das geänderte Feld.
  - `LocalEditorTest.setDayFieldsRejectsOtherFields` (`date` ergibt IAE).
  - `LocalEditorTest.addNoteToDayAppendsNoteItem`.
- [ ] **Step 2:** `./gradlew :client:test`. Die neuen Tests müssen fehlschlagen.
- [ ] **Step 3:** Implementieren. SHA-256 über `java.security.MessageDigest`, hex klein geschrieben. `java.nio.file.Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`; scheitert ATOMIC_MOVE, folgt ein normales Move.
- [ ] **Step 4:** `./gradlew :client:test`. Alles muss grün sein.
- [ ] **Step 5:** Commit `feat(client): file upload/download with hash check, path prefix, attachments and day edits`

### Task 2: app – Reise-Zeitzone

**Files:**
- Modify: `app/.../data/SettingsStore.kt`, `app/.../ui/MainActivity.kt`, `app/.../ui/more/SettingsScreen.kt`, `app/.../ui/AppNavigation.kt`
- Test: `app/src/test/.../data/SettingsStoreTest.kt`, `app/src/test/.../ui/today/TripZoneTest.kt`

**Interfaces:**
- Produces:
  - `SettingsStore.tripZone: Flow<TripZone>` und `suspend fun setTripZone(z: TripZone)`, gespeichert im bestehenden DataStore unter dem Key `trip_zone`.
  - `enum class TripZone(val zoneId: java.time.ZoneId?) { JAPAN(ZoneId.of("Asia/Tokyo")), DEVICE(null) }`, Standard `JAPAN`.
  - `fun TripZone.now(): LocalDateTime` = `LocalDateTime.now(zoneId ?: ZoneId.systemDefault())`.
- Ruling: Standard ist JST. Die Reise und alle Uhrzeiten in den Daten sind japanische Ortszeit. Vor der Reise verschiebt das den Countdown höchstens um Stunden.

- [ ] **Step 1: Failing tests**
  - `SettingsStoreTest.tripZoneDefaultsToJapanAndPersists`.
  - `TripZoneTest.todayUsesTripZone`:
    - Mit fester `Clock` 2027-05-19T20:00Z liefert `TripZone.JAPAN.now(clock)` das Datum 2027-05-20.
    - `DEVICE` mit Systemzone Europe/Berlin liefert 2027-05-19.
    - Dazu eine Überladung `fun TripZone.now(clock: java.time.Clock): LocalDateTime` (die parameterlose ruft sie mit `Clock.systemUTC()` auf).
  - `SettingsScreenTest.tripZoneToggleCallsCallback`: Ein Tipp auf „Gerätezeit verwenden“ ruft `onTripZone(DEVICE)` auf.
- [ ] **Step 2:** Tests ausführen; sie müssen fehlschlagen.
- [ ] **Step 3:** Implementieren.
  - `MainActivity` sammelt `tripZone` und übergibt `clock = { zone.now() }` an `AppNavigation`.
  - `SettingsScreen` bekommt den Abschnitt „Zeitzone“ mit zwei RadioButtons: „Japan (JST)“ und „Gerätezeit verwenden“. Neue Parameter sind `tripZone: TripZone` und `onTripZone: (TripZone) -> Unit` mit Defaults, damit bestehende Tests kompilieren.
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest` muss grün sein.
- [ ] **Step 5:** Commit `feat(app): trip time zone (JST by default) for Heute and opening hours`

### Task 3: app – lokaler Dateispeicher, Upload-Warteschlange (Migration 1→2), FileSyncWorker

**Files:**
- Create: `app/.../files/AttachmentFiles.kt`, `app/.../files/FileSyncRunner.kt`, `app/.../sync/FileSyncWorker.kt`, `app/.../data/PendingUploadEntity.kt`, `app/.../data/PendingUploadDao.kt`, `app/.../data/Migrations.kt`
- Modify: `AppDatabase.kt` (version 2, entity hinzu, `MIGRATION_1_2`), `AppContainer.kt`, `SyncScheduler.kt`, `SyncRunner.kt`, `app/build.gradle.kts` (`sourceSets["test"].assets.srcDir("$projectDir/schemas")`)
- Test: `app/src/test/.../data/MigrationTest.kt`, `app/src/test/.../files/AttachmentFilesTest.kt`, `app/src/test/.../files/FileSyncRunnerTest.kt`, `SyncSchedulerTest.kt`

**Interfaces:**
- Consumes: `SyncApi.uploadFile`, `SyncApi.downloadFile`, `TripSnapshot.attachments` (Task 1).
- Produces:
  - `@Entity("pending_upload") data class PendingUploadEntity(@PrimaryKey val hash: String, val fileName: String, val mimeType: String, val createdAt: Long)`. DAO: `insert(OnConflict IGNORE)`, `all()`, `delete(hash)`.
  - `class AttachmentFiles(dir: java.io.File)`, im Container als `AttachmentFiles(File(filesDir, "attachments"))`:
    - `fun fileFor(hash: String): java.io.File?`: nur wenn die Datei existiert und `hash` dem Muster `[0-9a-f]{64}` entspricht.
    - `fun target(hash: String): java.io.File`: Zielpfad, auch wenn die Datei noch nicht existiert.
    - `fun importStream(input: java.io.InputStream, maxBytes: Long = MAX_ATTACHMENT_BYTES): Imported(hash: String, size: Long)`:
      - Kopiert nach `tmp-<uuid>` und hasht dabei.
      - Über `maxBytes` wird abgebrochen: tmp löschen, dann `AttachmentTooLargeException`.
      - Danach folgt ein Rename auf den Hash; eine bestehende gleiche Datei wird wiederverwendet.
    - `val version: StateFlow<Int>`: zählt hoch, sobald eine Datei hinzukommt.
  - `const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024` in `AttachmentFiles.kt`.
  - `data class FileSyncResult(val uploaded: Int, val downloaded: Int, val failed: Int)`
  - `class FileSyncRunner(api: () -> SyncApi?, files: AttachmentFiles, uploads: PendingUploadDao, attachments: suspend () -> List<Attachment>)` mit `suspend fun run(): FileSyncResult`:
    - Zuerst werden alle ausstehenden Uploads hochgeladen. Bei Erfolg oder `duplicate` wird die Zeile gelöscht. Fehlt die lokale Datei, wird die Zeile ebenfalls gelöscht und als failed gezählt.
    - Danach wird jeder `fileHash` der Anhänge ohne lokale Datei heruntergeladen. Jeder Fehler zählt als failed, die Schleife läuft weiter.
    - Gibt es keinen Server, ist das Ergebnis `FileSyncResult(0,0,0)`.
  - `FileSyncWorker`: `Result.retry()`, wenn `failed > 0` und mindestens ein Fehler NETWORK oder SERVER war, sonst `success`. Ein 404 allein führt nicht zu retry; der nächste Sync löst ohnehin einen neuen Lauf aus.
  - `SyncScheduler.requestFiles()`: Unique Work `"files"`, `ExistingWorkPolicy.APPEND_OR_REPLACE`, Netzwerk-Constraint, Backoff 30 s. `SyncRunner.runOnce` ruft sie nach jedem `SyncResult` ohne `Failed` auf.
  - `AppContainer`: `attachmentFiles`, `fileSyncRunner` und `suspend fun addAttachment(targetType, targetId, input: InputStream, fileName: String, mimeType: String): String`:
    - Ablauf: import, dann `pending_upload` einfügen, dann `edit { e, _ -> e.addAttachment(...) }`, dann `requestFiles()`.
    - Größe kommt aus `Imported.size`.
- Ruling: Der Anhang-Datensatz wird sofort angelegt, auch offline. Die Datei folgt beim nächsten FileSync. Andere Geräte zeigen bis dahin „nicht offline verfügbar“ (Spec §11).

- [ ] **Step 1: Failing tests**
  - `MigrationTest.migrate1To2KeepsFieldsAndOutbox`:
    - Mit `MigrationTestHelper` wird DB v1 aus `schemas/.../1.json` erzeugt, mit je einer Zeile in `fields` und `outbox`.
    - Nach `runMigrationsAndValidate(2, MIGRATION_1_2)` sind die Zeilen noch da und `pending_upload` ist leer.
  - `AttachmentFilesTest`:
    - `importHashesAndStoresUnderHash` ("abc" ergibt den bekannten Hash).
    - `importSameContentTwiceReusesFile`.
    - `tooLargeThrowsAndLeavesNoTmp` (`maxBytes = 2`, Eingabe "abc").
    - `fileForRejectsInvalidHash` (`"../x"` ergibt null).
  - `FileSyncRunnerTest` mit MockEngine-`SyncApi`:
    - `uploadsPendingAndDeletesRow`.
    - `downloadsMissingAttachments`.
    - `missingOnServerCountsAsFailedAndRetriesLater`: 404 ergibt `failed == 1`, die Datei ist nicht vorhanden, ein zweiter Lauf versucht es erneut.
    - `partialDownloadLeavesNoFile`: Hash passt nicht, also `fileFor == null`.
    - `noServerDoesNothing`.
  - `SyncSchedulerTest.requestFilesEnqueuesUniqueWork`.
- [ ] **Step 2:** Tests ausführen; sie müssen fehlschlagen.
- [ ] **Step 3:** Implementieren.
  - `Room.databaseBuilder(...).addMigrations(MIGRATION_1_2)`.
  - Die Migration nutzt exakt das SQL aus dem exportierten Schema 2: erst bauen, dann `app/schemas/.../2.json` prüfen.
  - Schema 2 wird committet.
- [ ] **Step 4:** `./gradlew test :app:assembleDebug` muss grün sein.
- [ ] **Step 5:** Commit `feat(app): local attachment store, upload queue (DB v2 + migration), background file sync`

### Task 4: app – Anhänge-UI, Anzeige von Bildern und PDFs, Ticket-Schnellzugriff

**Files:**
- Create: `app/.../ui/attachments/AttachmentsSection.kt`, `AttachmentViewerScreen.kt`, `AttachmentsViewModel.kt`, `app/src/main/res/xml/file_paths.xml`
- Modify: `AndroidManifest.xml` (FileProvider `${applicationId}.files`, Pfad `attachments/`), `LogisticsScreen.kt`, `PlaceDetailScreen.kt`, `DayDetailScreen.kt`, `TodayScreen.kt`, `AppNavigation.kt`, `MainActivity.kt`
- Test: `app/src/test/.../ui/attachments/AttachmentsSectionTest.kt`, `OpenRoutingTest.kt`, `app/src/test/.../ui/today/TicketQuickAccessTest.kt`

**Interfaces:**
- Consumes: `TripSnapshot.attachmentsFor`, `AppContainer.addAttachment`, `AttachmentFiles.fileFor` und `version` (Tasks 1 und 3).
- Produces:
  - `sealed interface OpenTarget { data class Image(val file: File); data class Pdf(val file: File); data class External(val file: File, val mimeType: String); object Unavailable }`
  - `fun openTargetFor(a: Attachment, files: AttachmentFiles): OpenTarget`:
    - `image/*` ergibt Image, `application/pdf` ergibt Pdf, alles andere External.
    - Ohne lokale Datei ergibt sich Unavailable.
  - `@Composable fun AttachmentsSection(attachments: List<Attachment>, available: (String) -> Boolean, onAdd: () -> Unit, onOpen: (Attachment) -> Unit, onDelete: (Attachment) -> Unit, modifier: Modifier = Modifier)`:
    - Überschrift „Anhänge“, pro Anhang Dateiname, Größe (`formatBytes`: „1,2 MB“, „340 KB“) und bei fehlender Datei der Hinweis „nicht offline verfügbar“.
    - Knopf „Anhang hinzufügen“.
    - Löschen per Menü mit Bestätigungsdialog „Anhang löschen?“.
    - Test-Tags `attachment-<id>` und `attachment-add`.
  - Route `ATTACHMENT_ROUTE = "attachment/{attachmentId}"`. `AttachmentViewerScreen` zeigt ein Bild mit Coil aus der Datei. Ein PDF zeigt es mit `android.graphics.pdf.PdfRenderer`: jede Seite als Bitmap in einer LazyColumn, Breite gleich Bildschirmbreite, Seiten werden beim Sichtbarwerden gerendert.
  - External: `Intent.ACTION_VIEW` mit FileProvider-URI und `FLAG_GRANT_READ_URI_PERMISSION`. Bei `ActivityNotFoundException` erscheint die Snackbar „Keine App zum Öffnen gefunden“.
  - Hinzufügen: `ActivityResultContracts.OpenDocument` (`arrayOf("*/*")`).
    - Name und MIME kommen vom `ContentResolver` (`OpenableColumns.DISPLAY_NAME`, Fallback „Anhang“; `getType`, Fallback `application/octet-stream`).
    - Ist die Datei zu groß, erscheint die Snackbar „Datei zu groß (max. 25 MB)“.
    - Die Arbeit läuft im `AttachmentsViewModel` auf IO im Write-Scope.
  - Eingebaut:
    - in Logistik in jede Karte (Flug, Zug, Unterkunft),
    - in Ortsdetail (Ziel `SAVED_PLACE`, savedPlaceId),
    - in Tagesdetail (Ziel `DAY`). Existiert der Day noch nicht, wird er vor dem Anhängen über `setDayFields(snapshot, date, mapOf("title" to <aktueller Titel>))` angelegt, mit leerem String. Ruling: lieber ein angelegtes Day mit leerem Titel als ein Anhang ohne Ziel.
  - Heute (Ticket-Schnellzugriff): Unter jeder Fahrt des Tages stehen ihre Anhänge als `AssistChip` (Tag `ticket-<attachmentId>`). Ein Tipp ruft `onOpenAttachment(attachment)` auf.

- [ ] **Step 1: Failing tests**
  - `OpenRoutingTest`: jpeg ergibt Image, pdf ergibt Pdf, zip ergibt External, eine fehlende Datei ergibt Unavailable.
  - `AttachmentsSectionTest`:
    - `showsUnavailableHint`.
    - `deleteAsksForConfirmation`: Erst nach „Löschen“ im Dialog wird `onDelete` aufgerufen.
    - `formatBytesGerman` (1_234_567 ergibt „1,2 MB“, 340_000 ergibt „332 KB“; Basis 1024, eine Nachkommastelle ab MB).
  - `TicketQuickAccessTest.tappingTicketOfTodaysTrainOpensIt` (Spec §12, UI-Test „Ticket öffnen“):
    - Snapshot mit einem Zug am 2027-05-20 und einem Anhang an diesem Zug, `now` = 2027-05-20T08:00.
    - Ein Klick auf `ticket-<id>` ruft den Callback mit genau diesem Anhang auf.
- [ ] **Step 2:** Tests ausführen; sie müssen fehlschlagen.
- [ ] **Step 3:** Implementieren. `PdfRenderer` und `ParcelFileDescriptor` werden in `DisposableEffect` geschlossen.
- [ ] **Step 4:** `./gradlew test :app:assembleDebug` muss grün sein.
- [ ] **Step 5:** Commit `feat(app): attachments on logistics, places and days; image/PDF viewer; ticket quick access`

### Task 5: app – Teilen-Menü (Datei teilen → Ziel wählen)

**Files:**
- Create: `app/.../ui/share/ShareActivity.kt`, `ShareTargetScreen.kt`, `ShareTargets.kt`
- Modify: `AndroidManifest.xml` (`ShareActivity`, exported, Intent-Filter `SEND` und `SEND_MULTIPLE`, `mimeType="*/*"`, Theme wie die App)
- Test: `app/src/test/.../ui/share/ShareTargetsTest.kt`, `ShareTargetScreenTest.kt`, `ShareImportTest.kt`

**Interfaces:**
- Consumes: `AppContainer.addAttachment` (Task 3).
- Produces:
  - `enum class ShareGroup(val label: String) { FLIGHT("Flüge"), TRAIN("Züge"), STAY("Unterkünfte"), DAY("Tage"), PLACE("Orte") }`
  - `data class ShareTarget(val type: EntityType, val id: String, val label: String, val subtitle: String?, val group: ShareGroup)`
  - `fun shareTargets(snapshot: TripSnapshot): List<ShareTarget>`:
    - Flüge, Züge und Unterkünfte chronologisch.
    - Tage: jedes Datum der Reise mit der ID aus `dayFor(date)?.id`. Fehlt sie, ist die ID `"date:<yyyy-mm-dd>"`; das Day wird beim Anhängen über `setDayFields` angelegt.
    - Orte: SavedPlaces alphabetisch nach Ortsname.
    - Labels kommen aus derselben Logik wie Logistik und Orte. Bestätigungsnummern stehen nicht im Label.
  - `fun filterTargets(targets: List<ShareTarget>, query: String): List<ShareTarget>`: ohne Beachtung von Groß- und Kleinschreibung, auf `label` und `subtitle`.
  - `ShareActivity` liest `EXTRA_STREAM`; bei `SEND_MULTIPLE` ist es eine Liste.
    - Ohne Server oder mit leerem Snapshot zeigt sie „Erst Server einrichten und synchronisieren“.
    - Nach der Wahl wird für jede URI `addAttachment` aufgerufen. Danach folgen der Toast „Angehängt“ (bzw. „<n> Dateien angehängt“) und `finish()`.
    - Ist eine Datei zu groß, wird sie übersprungen und gemeldet: „<n> Datei(en) zu groß (max. 25 MB)“. Es entsteht kein Anhang für sie.

- [ ] **Step 1: Failing tests**
  - `ShareTargetsTest`:
    - `groupsAndOrders`.
    - `missingDayGetsDateId`.
    - `labelsNeverContainConfirmationNumbers`: Der Flug hat die Bestätigungsnummer „ABC123“, und kein Label oder Subtitle enthält sie.
  - `ShareTargetScreenTest.searchFilters`.
  - `ShareImportTest.tooLargeShareCreatesNoAttachment`:
    - Über eine Funktion `suspend fun importShared(container-ähnliches Interface, uris, target)` aufrufen, die als testbare Einheit aus `ShareActivity` herausgezogen ist.
    - Eine URI über 25 MB ergibt 0 Anhänge in der Outbox und `skippedTooLarge == 1`.
- [ ] **Step 2:** Tests ausführen; sie müssen fehlschlagen.
- [ ] **Step 3:** Implementieren. Die `content://`-Berechtigung gilt nur, solange die Activity lebt. Deshalb wird der Stream noch vor `finish()` vollständig kopiert, also synchron im ViewModel und nicht per Worker.
- [ ] **Step 4:** `./gradlew test :app:assembleDebug` muss grün sein.
- [ ] **Step 5:** Commit `feat(app): share sheet target picker attaches shared files`

### Task 6: app – ExternalLinkProvider (Navigation, ÖPNV, Website, Telefon, Tabelog)

**Files:**
- Create: `app/.../links/ExternalLinks.kt`, `app/.../ui/LinkButtons.kt`
- Modify: `PlaceDetailScreen.kt`, `LogisticsScreen.kt` (Unterkunft), `TodayScreen.kt` (Unterkunft des Tages)
- Test: `app/src/test/.../links/ExternalLinksTest.kt`, `PlaceDetailTest.kt` (erweitern)

**Interfaces:**
- Produces (Spec §10 `ExternalLinkProvider`):
  - `enum class LinkKind { NAVIGATE, TRANSIT, WEBSITE, PHONE, TABELOG }`
  - `data class ExternalLink(val kind: LinkKind, val label: String, val uri: String)`
  - `interface ExternalLinkProvider { fun linksFor(place: Place, category: PlaceCategory?): List<ExternalLink> }`
  - `object GoogleExternalLinks : ExternalLinkProvider` mit exakt diesen URIs. Parameter sind mit `URLEncoder.encode(v, "UTF-8")` codiert, `+` wird durch `%20` ersetzt.
    - NAVIGATE, „Navigation“: `https://www.google.com/maps/dir/?api=1&destination=<lat>,<lng>&destination_place_id=<googlePlaceId>`. `destination_place_id` entfällt, wenn sie leer ist. Bei `lat == 0.0 && lng == 0.0` wird `destination=<name + address>` statt Koordinaten verwendet.
    - TRANSIT, „ÖPNV“: wie NAVIGATE plus `&travelmode=transit`.
    - WEBSITE, „Website“: nur wenn `website` mit `http://` oder `https://` beginnt.
    - PHONE, „Anrufen“: `tel:` plus die Telefonnummer, auf die Zeichen `+0-9` reduziert, nur wenn danach mindestens 3 Ziffern übrig sind.
    - TABELOG, „Tabelog“: nur für `category == FOOD`, `https://tabelog.com/rstLst/?vs=1&sw=<name>`.
  - `@Composable fun LinkButtons(links: List<ExternalLink>, onOpen: (ExternalLink) -> Unit)`: FlowRow mit OutlinedButtons, Tag `link-<kind>`.
  - Öffnen mit `Intent(ACTION_VIEW, Uri.parse(uri))` bzw. `ACTION_DIAL` für `tel:`. Bei `ActivityNotFoundException` erscheint die Snackbar „Keine App zum Öffnen gefunden“.

- [ ] **Step 1: Failing tests**
  - `ExternalLinksTest`:
    - `navigateUsesCoordsAndPlaceId`: lat 34.9671, lng 135.7727, id `ChIJ123` ergibt exakt `https://www.google.com/maps/dir/?api=1&destination=34.9671,135.7727&destination_place_id=ChIJ123`.
    - `transitAddsTravelmode`.
    - `websiteOnlyHttp` (`javascript:x` ergibt keinen Link).
    - `phoneSanitized` (`"+81 75-641-7331"` ergibt `tel:+81756417331`).
    - `tabelogOnlyForFood`.
    - `tabelogEncodesName`: „一蘭 京都河原町店“ wird zu `%E4%B8%80%E8%98%AD%20%E4%BA%AC...`.
    - `zeroCoordsFallBackToName`.
  - `PlaceDetailTest.showsNavigationButtons`: `link-NAVIGATE` und `link-TRANSIT` existieren.
- [ ] **Step 2:** Tests ausführen; sie müssen fehlschlagen.
- [ ] **Step 3:** Implementieren.
- [ ] **Step 4:** `./gradlew test :app:assembleDebug` muss grün sein.
- [ ] **Step 5:** Commit `feat(app): external links for navigation, transit, website, phone and Tabelog`

### Task 7: app – Tag und Ort bearbeiten (Titel, Notiz, Regions-Override, Tags, Region, Kategorie, Notiz im Programm)

**Files:**
- Modify: `DayDetailScreen.kt`, `TripViewModel.kt`, `PlaceDetailScreen.kt`, `PlacesViewModel.kt`, `AppNavigation.kt`
- Test: `app/src/test/.../ui/trip/DayDetailEditTest.kt`, `app/src/test/.../ui/places/PlaceEditTest.kt`

**Interfaces:**
- Consumes: `LocalEditor.setDayFields`, `addNoteToDay` (Task 1).
- Produces:
  - `TripViewModel.setDayTitle(date, title)`, `setDayNote(date, note)` und `setRegionOverride(date, regionId: String?)`. Bei `null` wird `JsonNull` geschrieben, das heißt „Automatisch“. Dazu `addNote(date, text)`.
  - `PlacesViewModel.setTags(savedPlaceId, tags: List<String>)`, `setRegion(savedPlaceId, regionId: String?)` und `setCategory(savedPlaceId, PlaceCategory)`.
  - DayDetail:
    - Titel ist inline editierbar (Stift-Icon, Tag `day-title-edit`).
    - Abschnitt „Notiz“ mit Markdown-Anzeige und „Bearbeiten“, im gleichen Muster wie die Ortsnotiz inklusive `pendingNote`.
    - Abschnitt „Region“: Dropdown (`ExposedDropdownMenuBox`) mit „Automatisch (<abgeleitete Region>)“ und allen Regionen.
    - Knopf „Notiz zum Programm hinzufügen“ öffnet einen Dialog mit Textfeld.
    - Programm-Einträge mit `kind == NOTE` zeigen ihren Text und sind wie Orte verschieb- und entfernbar.
  - Ortsdetail:
    - FilterChips „⭐“ und „⚠️“ schalten diese Tags um, andere vorhandene Tags bleiben unverändert und in Reihenfolge erhalten.
    - Dropdowns „Region“ (inklusive „Keine Region“) und „Kategorie“.

- [ ] **Step 1: Failing tests**
  - `DayDetailEditTest`:
    - `editTitleCallsSetDayTitle`.
    - `regionDropdownOffersAutomaticAndRegions`.
    - `noteItemRendersText`.
  - `TripViewModelTest.setRegionOverrideNullWritesJsonNull`: über ein Fake-`EditFn` mit echtem `LocalEditor` auf In-Memory-Store.
  - `PlaceEditTest`:
    - `starToggleKeepsOtherTags`: `["🍜","⭐"]`, dann ⭐ aus, ergibt `["🍜"]`, dann ⭐ an, ergibt `["🍜","⭐"]`.
    - `categoryDropdownCallsSetCategory`.
- [ ] **Step 2:** Tests ausführen; sie müssen fehlschlagen.
- [ ] **Step 3:** Implementieren.
- [ ] **Step 4:** `./gradlew test :app:assembleDebug` muss grün sein.
- [ ] **Step 5:** Commit `feat(app): edit day title/note/region and place tags/region/category; notes in day program`

### Task 8: app – QR-Einrichtung und Setup-Deep-Link

**Files:**
- Create: `app/.../ui/setup/SetupLink.kt`
- Modify: `SetupScreen.kt`, `SetupViewModel.kt`, `MainActivity.kt`, `AndroidManifest.xml` (Intent-Filter `VIEW`, scheme `wlp`, host `setup`, `BROWSABLE`), `libs.versions.toml`, `app/build.gradle.kts`, `server/README.md` (Abschnitt „QR-Code für die Einrichtung“)
- Test: `app/src/test/.../ui/setup/SetupLinkTest.kt`, `SetupScreenTest.kt` (erweitern)

**Interfaces:**
- Produces:
  - Format: `wlp://setup?url=<url-encoded>&token=<url-encoded>`.
  - `object SetupLink { fun parse(raw: String): ServerSettings?; fun build(s: ServerSettings): String }`:
    - `parse` liefert null, wenn das Schema oder der Host nicht passt oder ein Parameter fehlt bzw. leer ist.
    - Die URL läuft durch `ServerUrl.normalize`; schlägt das fehl, ist das Ergebnis null.
  - `SetupViewModel.prefill(s: ServerSettings)` setzt URL und Token in die Felder. Es speichert nicht und testet nicht automatisch; der Nutzer tippt „Verbindung testen“ bzw. „Speichern“.
  - SetupScreen: Knopf „QR-Code scannen“ (Tag `setup-scan`) startet `ScanContract()` aus zxing-android-embedded mit `ScanOptions().setDesiredBarcodeFormats(QR_CODE).setBeepEnabled(false).setPrompt("QR-Code der Einrichtung scannen")`. Ein ungültiger Code ergibt die Meldung „Kein gültiger Einrichtungs-Code“.
  - Deep-Link: `MainActivity` erkennt `wlp://setup...` in `onCreate` und `onNewIntent`, navigiert zu SETUP und ruft `prefill` auf.
  - README: Beispiel `qrencode -t ansiutf8 'wlp://setup?url=https%3A%2F%2Fwlp.example.org&token=…'` mit dem Hinweis, dass der Code das Token enthält und wie ein Passwort zu behandeln ist.

- [ ] **Step 1: Failing tests**
  - `SetupLinkTest`:
    - `roundTrip`.
    - `rejectsOtherScheme`.
    - `rejectsMissingToken`.
    - `rejectsInvalidUrl`.
    - `decodesEncodedToken`: Token `a+b/c=` wird korrekt decodiert.
  - `SetupScreenTest.prefillFillsFieldsWithoutSaving`.
- [ ] **Step 2:** Tests ausführen; sie müssen fehlschlagen.
- [ ] **Step 3:** Implementieren. Die Abhängigkeit ist `com.journeyapps:zxing-android-embedded:4.3.0`. Die Kamera-Berechtigung kommt aus deren Manifest.
- [ ] **Step 4:** `./gradlew test :app:assembleDebug` muss grün sein.
- [ ] **Step 5:** Commit `feat(app): QR code and deep link setup`

### Task 9: app – Karte pro Region (Google Maps, optional per API-Key)

**Files:**
- Create: `app/.../ui/places/RegionMap.kt`
- Modify: `RegionPlacesScreen.kt`, `app/build.gradle.kts` (`buildFeatures.buildConfig = true`, `manifestPlaceholders["MAPS_API_KEY"]` und `buildConfigField("boolean", "HAS_MAPS_KEY", ...)` aus `local.properties` `MAPS_API_KEY`, Standard leer), `AndroidManifest.xml` (`<meta-data android:name="com.google.android.geo.API_KEY" android:value="${MAPS_API_KEY}"/>`), `libs.versions.toml`, `README.md` (Abschnitt „Karte“)
- Test: `app/src/test/.../ui/places/RegionMapTest.kt`, `RegionPlacesScreenTest.kt`

**Interfaces:**
- Produces:
  - `data class MapMarker(val savedPlaceId: String, val name: String, val lat: Double, val lng: Double, val status: PlaceStatus)`
  - `fun mapMarkers(snapshot: TripSnapshot, regionKey: String): List<MapMarker>`: dieselbe Auswahl wie die Liste der Region, ohne Orte mit `lat == 0.0 && lng == 0.0`.
  - `RegionPlacesScreen(..., mapAvailable: Boolean = BuildConfig.HAS_MAPS_KEY)`:
    - Bei `mapAvailable` gibt es einen SegmentedButton „Liste | Karte“ (Tags `view-list` und `view-map`).
    - Die Karte zeigt die Marker mit Kamera auf die Bounds der Marker. Farbe: IDEA azure, PLANNED green, VISITED violet, DROPPED rose.
    - Ein Tipp auf das Info-Fenster ruft `onOpenPlace(savedPlaceId)` auf.
  - Ohne Key gibt es keinen Umschalter. Die Karte (`GoogleMap`) wird in Tests nie komponiert.
- Ruling: Die Spec nennt das Google Maps SDK. Ohne Key bleibt die App vollständig nutzbar, weil die Navigation über Links aus Task 6 läuft. Den Key trägt der Nutzer selbst ein; die Konditionen bleiben ein offener Punkt der Spec.

- [ ] **Step 1: Failing tests**
  - `RegionMapTest.markersSkipZeroCoords`.
  - `RegionMapTest.markersMatchListSelection`.
  - `RegionPlacesScreenTest.noToggleWithoutKey` (`mapAvailable=false`, also kein `view-map`).
  - `RegionPlacesScreenTest.toggleShownWithKey`: nur das Vorhandensein prüfen, nicht anklicken.
- [ ] **Step 2:** Tests ausführen; sie müssen fehlschlagen.
- [ ] **Step 3:** Implementieren. Abhängigkeiten sind `maps-compose:6.2.1` und `play-services-maps:19.0.0`. Löst eine Version nicht auf, wird die nächstliegende vorhandene 6.x bzw. 19.x genommen und das im Report vermerkt.
- [ ] **Step 4:** `./gradlew test :app:assembleDebug` muss grün sein, ohne `MAPS_API_KEY`.
- [ ] **Step 5:** Commit `feat(app): optional Google map per region`

### Task 10: app – Erinnerungen an Stornofristen

**Files:**
- Create: `app/.../reminders/Reminders.kt`, `app/.../reminders/DeadlineWorker.kt`
- Modify: `SyncScheduler.kt` (`scheduleReminders()`, täglich, Unique `"reminders"`, KEEP), `WanderlogPlusApp.kt`, `SettingsStore.kt` (`remindersEnabled: Flow<Boolean>`, Standard true), `SettingsScreen.kt` (Schalter „Erinnerungen an Fristen“), `MainActivity.kt` (POST_NOTIFICATIONS ab API 33 einmalig anfragen, wenn aktiviert), `AndroidManifest.xml`
- Test: `app/src/test/.../reminders/RemindersTest.kt`, `DeadlineWorkerTest.kt`

**Interfaces:**
- Consumes: `TodayQueries` und `Deadline(date, stay)` aus `shared`; `TripZone.now()` (Task 2).
- Produces:
  - `val REMINDER_OFFSETS = listOf(7, 3, 1, 0)` (Tage vor der Frist).
  - `data class Reminder(val key: String, val stayId: String, val title: String, val text: String)`, mit `key = "<stayId>|<deadlineDate>|<offset>"`.
  - `fun dueReminders(stays: List<Stay>, places: Map<String, Place>, today: LocalDate, alreadySent: Set<String>): List<Reminder>`:
    - Je Stay mit `cancellationDeadline` und jeden Offset `o`, für den `today >= deadline - o` und `today <= deadline` gilt, entsteht genau eine Erinnerung für den kleinsten passenden Offset, der noch nicht gesendet wurde.
    - Ältere Offsets werden als erledigt behandelt und nicht nachgeholt.
    - Titel „Stornofrist: <Unterkunftsname>“, Text „Noch <n> Tage (bis <dayLabel>)“, „Morgen“ bzw. „Heute“.
    - Keine Bestätigungsnummer und keine Namen von Reisenden.
  - `DeadlineWorker`:
    - Läuft mit Snapshot aus dem Container und `TripZone`.
    - Postet pro Reminder eine Notification im Channel `deadlines` („Fristen“) mit der ID `key.hashCode()`.
    - Speichert gesendete Keys in SharedPreferences `reminders` (StringSet).
    - Ist er deaktiviert oder fehlt die Berechtigung, endet er mit `success` ohne Notification.
    - Ein Tipp öffnet MainActivity.

- [ ] **Step 1: Failing tests**
  - `RemindersTest`:
    - `sevenDaysBefore`.
    - `onlySmallestDueOffset`: 2 Tage vor der Frist ergibt nur Offset 3, nicht 7.
    - `notResentWhenAlreadySent`.
    - `nothingAfterDeadline`.
    - `textHasNoConfirmationNumber`.
    - `todayAndTomorrowWording`.
  - `DeadlineWorkerTest.disabledPostsNothing`: `TestListenableWorkerBuilder` mit Robolectric-`NotificationManager`-Shadow.
  - `DeadlineWorkerTest.postsAndRemembers`.
- [ ] **Step 2:** Tests ausführen; sie müssen fehlschlagen.
- [ ] **Step 3:** Implementieren.
- [ ] **Step 4:** `./gradlew test :app:assembleDebug` muss grün sein.
- [ ] **Step 5:** Commit `feat(app): reminders for cancellation deadlines`

### Task 11: Doku und Version

**Files:**
- Modify: `README.md` (Funktionsliste: Anhänge, Teilen, Karte mit Key, QR, Zeitzone, Erinnerungen), `app/build.gradle.kts` (`versionCode = 2`, `versionName = "0.2.0"`)
- Create: `docs/superpowers/followups-from-plan4.md`. Den Inhalt liefert der Controller aus den Reviews; der Implementer legt nur die Gliederung „Pflicht für den nächsten Plan / Bekannte Grenzen / Offen“ an.

- [ ] **Step 1:** Änderungen vornehmen.
- [ ] **Step 2:** `./gradlew test :app:assembleDebug` muss grün sein.
- [ ] **Step 3:** Commit `docs: plan 4 features, version 0.2.0`
