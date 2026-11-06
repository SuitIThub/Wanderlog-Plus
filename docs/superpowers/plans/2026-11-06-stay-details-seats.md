# Zimmer & Ausstattung, Sitzplätze & Sitzpläne – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Klasse/Sitzplätze an Flügen und Zügen mit selbst gezeichnetem Sitzplan, dazu Zimmer und Ausstattung der
Unterkünfte, befüllt aus Bestätigungen, Links und Websuche und von Hand korrigierbar.

**Architecture:** Shared: neue Felder an `Flight`/`Train`/`Stay`/`Attachment`, Sitzplan-Katalog `SeatMaps` (Daten +
`modelFor`/`locate`), `StayInfoMerge`. Server: KI-Auftrag `"stay"` (Gemini liest Anhänge, Parallel liest Links und sucht,
Google-Details, Sonnet schreibt), Endpunkt `POST /api/ai/stay`. App: Sitzkarte mit `Canvas`-Plan, Formularfelder,
Unterkunftskarten „Zimmer“/„Unterkunft“, Bearbeiten mit `editedByHand`.

**Tech Stack:** Kotlin, kotlinx.serialization, Ktor, SQLite, Jetpack Compose (Canvas), OpenRouter (Gemini, Sonnet), Parallel, Google Places.

**Spec:** `docs/superpowers/specs/2026-11-06-stay-details-seats-design.md`

## Global Constraints

- `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.8.9-hotspot"` vor jedem `./gradlew`.
- Neue Felder immer am Ende der Klasse mit Standardwert; vom Wanderlog-Import nicht überschrieben.
- Bestätigungen dürfen an Gemini; Bestätigungsnummer und Reisendennamen der Unterkunft erscheinen nie in Prompts an
  Sonnet/Parallel, nie im gespeicherten `StayInfo` und nie im Job-Protokoll.
- Texte auf Deutsch; Japanisch-Absicherung über `completeJson(..., GERMAN_PLEASE)`.
- Sitzpläne werden aus den Vorlagen in `.superpowers/seatmaps/` (git-ignoriert) abgeschrieben; die Bilder kommen nicht ins Repo.
- Commits mit `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` und `Claude-Session: https://claude.ai/code/session_01KFjuunE2K5BtAqzv536fJh`.
- Abschluss: Version + `CHANGELOG.md`, lokal in `main` mergen, nicht pushen.

## Review Focus

- Ein Platz, den es im Plan nicht gibt („43F“ im CI-A350, Tippfehler): Karte zeigt Klasse/Plätze ohne Markierung, kein Absturz → Test in Task 2.
- Flugnummer anders geschrieben („CI62“, „ci 62“, „CI0062“): wird trotzdem zugeordnet → Test in Task 2.
- Unterkunft ohne Anhänge und ohne Links: Auftrag läuft nur mit Websuche/Google und speichert nur Unterkunftsangaben, keine erfundenen Zimmerdaten → Test in Task 5.
- Gemini gibt doch die Buchungsnummer oder einen Namen zurück: wird vor dem Weitergeben entfernt → Test in Task 5.
- Recherche erneut gestartet, nachdem von Hand korrigiert wurde: Handfelder bleiben → Test in Task 1 (Merge) und Task 5 (Speichern).

---

### Task 1: Datenmodell (Sitze, StayInfo, Anhang-Label) und Merge

**Files:**
- Modify: `shared/src/main/kotlin/de/wanderlogplus/shared/model/Models.kt` (`Flight`, `Train`, `Stay`, `Attachment`)
- Create: `shared/src/main/kotlin/de/wanderlogplus/shared/model/StayInfo.kt`
- Test: `shared/src/test/kotlin/de/wanderlogplus/shared/model/StayInfoTest.kt`

**Interfaces:**
- Produces: `Seat(car: String? = null, seat: String, traveler: String? = null)`; `Flight`/`Train`: `travelClass: String? = null`, `seats: List<Seat> = emptyList()`, `seatMap: String? = null`; `Stay`: `info: StayInfo? = null`, `infoLinks: List<String> = emptyList()`; `Attachment.label: String? = null` (`Attachment.LABEL_ROOM = "room"`); `StayInfo`, `RoomInfo`, `PropertyInfo`, `AmenityGroup`, `Amenity` genau wie in der Spec; `object StayInfoMerge { fun merge(old: StayInfo?, new: StayInfo): StayInfo; fun edited(info: StayInfo, key: String): StayInfo }`.
  Schlüssel für `editedByHand`: `"room.type"`, `"room.description"`, `"room.sizeSqm"`, `"room.beds"`, `"room.bath"`, `"room.view"`, `"room.maxGuests"`, `"room.amenities.<Gruppe>"`, `"property.description"`, `"property.meals"`, `"property.houseRules"`, `"property.amenities.<Gruppe>"`.

- [ ] **Step 1: Failing tests** `StayInfoTest`: Codec-Roundtrip von `Stay(info = …, infoLinks = …)`, `Train(seats = listOf(Seat("12","111"), Seat("12","114")), travelClass = "2. Klasse")`, `Attachment(label = "room")`; `merge keeps hand edits` (old `room.type = "Twin (von Hand)"` mit `editedByHand = {"room.type", "property.amenities.Bad"}`, new mit anderem Typ und anderer Bad-Gruppe → Typ und Bad-Gruppe aus old, Rest aus new, `editedByHand` bleibt, `sources`/`researchedAt` aus new); `merge without old takes new`; `edited adds the key once`.
- [ ] **Step 2: Run** `./gradlew :shared:test --tests '*StayInfoTest'` → FAIL (Klassen fehlen).
- [ ] **Step 3: Implement** Modelle und `StayInfoMerge`.
- [ ] **Step 4: Run** `./gradlew :shared:test :client:test :server:test :app:compileDebugKotlin` → PASS.
- [ ] **Step 5: Commit** `feat(model): seats and class on rides, room and amenities on stays`

### Task 2: Sitzplan-Katalog mit den fünf Modellen

**Files:**
- Create: `shared/src/main/kotlin/de/wanderlogplus/shared/seatmap/SeatMaps.kt`, `shared/src/main/kotlin/de/wanderlogplus/shared/seatmap/SeatMapCatalog.kt`
- Test: `shared/src/test/kotlin/de/wanderlogplus/shared/seatmap/SeatMapsTest.kt`

**Interfaces:**
- Consumes: `Flight`, `Train`, `Seat` (Task 1).
- Produces:
  ```kotlin
  enum class SeatMapKind { TRAIN_CAR, AIRCRAFT }
  enum class Facility { TOILET, GALLEY, EXIT, LUGGAGE, BABY, WHEELCHAIR, TABLE }
  sealed interface Cell { data class SeatCell(val code: String, val window: Boolean = false) : Cell; data object Aisle : Cell; data object Gap : Cell; data class FacilityCell(val facility: Facility) : Cell }
  data class SeatRow(val label: String, val cells: List<Cell>)
  data class SeatSection(val name: String, val rows: List<SeatRow>)
  data class SeatMapModel(val key: String, val name: String, val kind: SeatMapKind, val matches: List<String>, val sections: List<SeatSection>)
  enum class SeatPosition { WINDOW, MIDDLE, AISLE }
  data class SeatSpot(val section: SeatSection, val row: SeatRow, val position: SeatPosition, val neighbours: List<String>)
  object SeatMaps {
      val all: List<SeatMapModel>                       // aus SeatMapCatalog
      fun byKey(key: String?): SeatMapModel?
      fun modelFor(flight: Flight): SeatMapModel?       // seatMap von Hand, sonst matches über "<airlineCode> <number>"
      fun modelFor(train: Train): SeatMapModel?         // seatMap von Hand, sonst matches über number
      fun locate(model: SeatMapModel, seat: String): SeatSpot?
  }
  ```
  Nummern-Normalisierung: Großbuchstaben, Leerzeichen weg, führende Nullen der Ziffern weg („CI0062“ = „CI62“ = „ci 62“; „ICE571“ = „ICE 571“). Fenster = `SeatCell.window`; Gang = direkt neben `Aisle`; sonst Mitte. Nachbarn = Sitze derselben Reihe zwischen denselben Gängen.
  Katalog (`SeatMapCatalog.kt`): `db-ice4-2kl` (matches `ICE 571`, `ICE 1272`), `ci-a359` (`CI 61`, `CI 62`, `CI 100`), `ci-a21n` (`CI 105`), `nh-a21n` (`NH 1861`), `nh-789` (`NH 468`), Reihen/Sitze/Gänge/Einrichtungen abgeschrieben aus `.superpowers/seatmaps/*`.

- [ ] **Step 1: Failing tests** `SeatMapsTest`:
  - `rides find their model by number` – Flight(airlineCode „CI“, number „62“) → `ci-a359`; „CI0062“ und „ci 62“ ebenso; `seatMap = "nh-789"` von Hand gewinnt; unbekannte Nummer → null; Train(number „ICE 571“) → `db-ice4-2kl`.
  - `seats are placed with their position` – `ci-a359`: 43E MIDDLE, 43G AISLE, Nachbarn von 43E enthalten 43G und 43D; 34A WINDOW, 34C AISLE (Premium Economy); `ci-a21n` 43K WINDOW, 43J MIDDLE; `db-ice4-2kl` 111 und 114 gefunden, eines davon WINDOW; „43F“ im `ci-a359` → null.
  - `catalog is consistent` – je Modell keine doppelten Sitzcodes, jede Reihe mit mindestens einem Sitz, jeder `matches`-Eintrag nur in einem Modell.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** Typen, Logik, Katalog (Bilder ansehen und Reihe für Reihe übertragen).
- [ ] **Step 4: Run** `./gradlew :shared:test` → PASS.
- [ ] **Step 5: Commit** `feat(seats): seat map catalog with ICE 4, China Airlines and ANA cabins`

### Task 3: Klasse & Sitze bearbeiten und anzeigen (Text)

**Files:**
- Modify: `app/.../logistics/LogisticsEditScreen.kt` (`MoveDraft` + `draftOf`/`draftChanges` + Felder), `app/.../logistics/LogisticsDetailScreen.kt` (Details-Zeilen), `app/.../today/TodayWidgets.kt` (Nächste Fahrt), `server/.../ai/ChatAgent.kt` (`ChatContextText.logistics`)
- Create: `shared/src/main/kotlin/de/wanderlogplus/shared/seatmap/SeatText.kt`
- Test: `shared/.../seatmap/SeatTextTest.kt`, `app/src/test/.../logistics/LogisticsManageTest.kt` (erweitern), `server/.../ai/ChatAgentTest.kt` (erweitern)

**Interfaces:**
- Produces: `fun seatLine(travelClass: String?, seats: List<Seat>): String?` → z. B. `"2. Klasse · Wagen 12, 111 + 114"`, `"Economy · 43E + 43G"`, null ohne beides; `MoveDraft.travelClass: String = ""`, `MoveDraft.seats: List<Seat> = emptyList()`, `MoveDraft.seatMap: String? = null`.

- [ ] **Step 1: Failing tests**: `seatLine` (Wagen gleich → einmal genannt; verschiedene Wagen → „Wagen 3, 12A + Wagen 4, 1A“; leer → null); `LogisticsManageTest`: Draft mit Klasse und zwei Sitzen → `draftChanges` enthält `travelClass`, `seats`, `seatMap`; `ChatAgentTest`: Logistik-Zeile endet mit `" · 2. Klasse · Wagen 12, 111 + 114"`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement**: Formular (Klasse als Textfeld mit Vorschlagschips aus der Spec, Sitzliste mit Wagen/Platz/Person, Sitzplan-Auswahl „Automatisch“ + `SeatMaps.all` des passenden `kind`), Anzeige der `seatLine` in Details, „Nächste Fahrt“ und Chat.
- [ ] **Step 4: Run** `./gradlew :shared:test :server:test :app:testDebugUnitTest` → PASS.
- [ ] **Step 5: Commit** `feat(seats): class and seats on trains and flights`

### Task 4: Gezeichneter Sitzplan

**Files:**
- Create: `app/src/main/kotlin/de/wanderlogplus/app/ui/logistics/SeatMapView.kt`
- Modify: `app/.../logistics/LogisticsDetailScreen.kt` (Karte „Sitzplätze“)
- Test: `app/src/test/.../logistics/SeatMapViewTest.kt`

**Interfaces:**
- Consumes: `SeatMaps`, `SeatSpot`, `seatLine` (Tasks 2–3).
- Produces: `@Composable fun SeatCard(model: SeatMapModel?, travelClass: String?, seats: List<Seat>)`; `@Composable fun SeatMapCanvas(model: SeatMapModel, highlight: Set<String>, modifier: Modifier)` – Flugzeug längs (vorn oben), Zug-Wagen quer; Sitze als abgerundete Rechtecke mit Code, eigene Plätze in `primary`, Gänge leer, Einrichtungen als kleine Symbole; Semantik `contentDescription` je hervorgehobenem Sitz („43E, Mitte“) für Tests/Barrierefreiheit; Vollbild-Dialog beim Tippen.

- [ ] **Step 1: Failing test** `SeatMapViewTest` (Robolectric): `SeatCard` mit `ci-a359` und Sitzen 43E/43G zeigt Text „43E · Mitte“, „43G · Gang“, „nebeneinander“ und Knoten mit `contentDescription` „43E, Mitte“; ohne Modell nur `seatLine` und Hinweis „Sitzplan wählen“.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** Canvas + Karte (scrollt horizontal/vertikal zum Abschnitt der Plätze).
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest` → PASS.
- [ ] **Step 5: Commit** `feat(seats): drawn seat map with your seats`

### Task 5: Server-Auftrag „Unterkunft recherchieren“

**Files:**
- Create: `server/src/main/kotlin/de/wanderlogplus/server/ai/StayResearch.kt`
- Modify: `server/.../ai/AiJobs.kt` (Art `"stay"`, `startStay`), `server/.../ai/OpenRouter.kt` (`AiPart.Pdf(bytes)` als `file`-Teil), `server/.../Application.kt` (`POST /api/ai/stay`), `server/.../Main.kt`, `shared/.../sync/AiProtocol.kt` (`StayResearchRequest(stayId, links, fresh = false)`, `AiJobInfo.stayId: String? = null`), `client/.../SyncApi.kt` (`startStayResearch`)
- Test: `server/src/test/kotlin/de/wanderlogplus/server/ai/StayResearchTest.kt`

**Interfaces:**
- Consumes: `FactSource`, `completeJson`, `GERMAN_PLEASE`, `factLines`, `GooglePlaceDetails` (vorhanden), `StayInfoMerge` (Task 1), `FileStore` (Anhänge lesen).
- Produces:
  ```kotlin
  class StayResearch(media: ChatModel, writer: ChatModel, facts: FactSource?, pages: PageReader?, google: (String) -> List<Fact>)
  fun interface PageReader { fun read(url: String, say: (String) -> Unit): Facts? }   // Parallel; Schnittstelle bei Umsetzung prüfen
  fun research(stay: Stay, place: Place?, confirmations: List<Pair<String, ByteArray>> /* mime, bytes */, links: List<String>, say: (String) -> Unit): StayInfo?
  fun AiJobs.startStay(req: StayResearchRequest): AiJobInfo   // schreibt Stay.info über StayInfoMerge (fresh: editedByHand leeren) und Stay.infoLinks
  ```
  Entfernen von Buchungsdaten: jede Zeile der Gemini-Antwort, die `stay.confirmationNumber` oder einen `stay.travelerNames`-Eintrag enthält, wird verworfen, bevor sie weitergeht.

- [ ] **Step 1: Failing tests** `StayResearchTest` (Fakes für Modelle, PageReader, FactSource):
  - `confirmation, link and web facts make the stay info` – Gemini-Fake liefert Zimmertyp „Japanese-style room with open-air bath“; Link-Fakten; Websuche-Fakten; Sonnet-Fake liefert StayInfo-JSON → `room.type` gesetzt, `property.amenities` gesetzt, `sources` enthält Link.
  - `booking data never leaves gemini` – Gemini-Antwort enthält „Buchung 21315092583975“ und „Christian Dieckmann“ → beides nicht im Sonnet-Prompt, nicht im Ergebnis, nicht in `say`-Zeilen.
  - `a broken link does not stop the research` – PageReader wirft `AiFailed` für Link 1 → Ergebnis da, `say` meldet den Link.
  - `without confirmations and links only the property is filled` – keine Anhänge/Links → `room.type` leer, Sonnet-Prompt enthält „Keine Angaben zum gebuchten Zimmer“.
  - `the job keeps hand edits` (über `AiJobs` + `SyncService`) – Stay mit `info.editedByHand = {"room.type"}` → nach Auftrag ist `room.type` unverändert; `fresh = true` ersetzt ihn.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** (Gemini bekommt PDFs/Bilder als Teile; Zimmerbilder aus Links: optional, Seite per HTTP holen, `og:image`/`<img>` mit Zimmertyp im `alt`, höchstens 8, als `Attachment(kind = "photo", label = "room")` über `FileStore` + `ServerEdit(create = true)`; schlägt das fehl, nur Protokollzeile).
- [ ] **Step 4: Run** `./gradlew :server:test :client:test` → PASS.
- [ ] **Step 5: Commit** `feat(stays): research room and amenities from confirmations, links and the web`

### Task 6: App – Zimmer- und Unterkunftskarten, Recherche starten

**Files:**
- Create: `app/src/main/kotlin/de/wanderlogplus/app/ui/logistics/StayInfoCards.kt`
- Modify: `app/.../logistics/LogisticsDetailScreen.kt` (Unterkunft), Navigation/ViewModel für den Start (`SyncApi.startStayResearch`) und das Beobachten des Auftrags (Muster wie Ortsrecherche)
- Test: `app/src/test/.../logistics/StayInfoCardsTest.kt`

**Interfaces:**
- Produces: `@Composable fun StayInfoCards(stay: Stay, photos: List<Attachment>, files: AttachmentFiles?, onResearch: ((links: List<String>, fresh: Boolean) -> Unit)?, onEdit: (() -> Unit)?, running: AiJobInfo?)`.

- [ ] **Step 1: Failing test**: Karten zeigen Typ, Betten, Gruppen und Einträge („Bad: Rotenburo – privat“), Quellen; leer → „Zimmer & Ausstattung recherchieren“; Recherche-Dialog übernimmt gespeicherte `infoLinks` und liefert eingegebene Links an `onResearch`; „Alles neu recherchieren“ fragt nach und setzt `fresh = true`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement**. **Step 4: Run** `./gradlew :app:testDebugUnitTest` → PASS.
- [ ] **Step 5: Commit** `feat(app): room and amenities of stays`

### Task 7: App – Zimmer & Ausstattung bearbeiten, Fotos als „Zimmer“

**Files:**
- Modify: `app/.../logistics/StayEditScreen.kt` (`StayDraft.info`, Bereiche „Zimmer“/„Ausstattung“), `app/.../attachments/*` (Foto als Zimmer markieren), `client/.../LocalEditor.kt` falls nötig
- Test: `app/src/test/.../logistics/StayEditInfoTest.kt`

**Interfaces:**
- Consumes: `StayInfoMerge.edited` (Task 1).
- Produces: `fun stayInfoEdit(old: StayInfo?, field: String, update: (StayInfo) -> StayInfo): StayInfo` (setzt Feld und `edited`).

- [ ] **Step 1: Failing tests**: Ändern von Zimmertyp setzt `"room.type"` in `editedByHand`; Eintrag in Gruppe „Bad“ hinzufügen/löschen setzt `"room.amenities.Bad"`; `stayToSave` schreibt `info`; Foto-Menü „Als Zimmerfoto markieren“ setzt `label = "room"`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement**. **Step 4: Run** `./gradlew :app:testDebugUnitTest` → PASS.
- [ ] **Step 5: Commit** `feat(app): edit room and amenities, mark room photos`

### Task 8: Chat-Kontext und Abschluss

**Files:**
- Modify: `server/.../ai/ChatAgent.kt` (`ChatContextText.stay`: Zimmertyp, Betten, Bad; Unterkunfts-Ausstattung als eine Zeile je Gruppe, gekürzt), `CHANGELOG.md`, `app/build.gradle.kts`, `server/README.md` (`/api/ai/stay`)
- Test: `server/.../ai/ChatAgentTest.kt`

- [ ] **Step 1: Failing test**: feste Übersicht enthält bei der Unterkunft „Zimmer: Japanese-style room …“ und „Ausstattung: Onsen & Spa: Rotenburo; Service: Wäscherei“; ohne `info` unverändert; nie Bestätigungsnummer.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement**; Version `0.41.0`/`versionCode 50`, Changelog-Abschnitt.
- [ ] **Step 4: Run** `./gradlew :shared:test :client:test :server:test :app:testDebugUnitTest :app:assembleDebug` → alles grün.
- [ ] **Step 5: Commit**, lokal in `main` mergen (`--no-ff`), nicht pushen; manuell: Sitzpläne gegen Vorlagen, eine Unterkunft echt recherchieren.
