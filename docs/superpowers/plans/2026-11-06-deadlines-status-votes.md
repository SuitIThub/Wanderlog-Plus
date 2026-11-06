# Fristen, „Vorgemerkt“ und Abstimmen – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fristen für Orte und Logistik (aus Recherche oder von Hand) mit kurzer, richtiger Heute-Anzeige; Zustand „Vorgemerkt“; Abstimmen je Zugang mit automatischem Zustandswechsel.

**Architecture:** Fristen werden in `shared` als reine Logik berechnet (`ReservationDates`, `Deadlines`) und von Heute-Seite und Erinnerungen gemeinsam genutzt. Stimmen sind eigene Sync-Entitäten (`VOTE`), die Zustandsregel liegt in `shared` (`Votes`) und läuft in App (sofort) und Server (nach Push, korrigierend).

**Tech Stack:** Kotlin, kotlinx.serialization, Ktor-Server, SQLite-Store mit HLC-Feld-Sync, Jetpack Compose (Robolectric-Tests), JDK 21 (`export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.8.9-hotspot"`).

**Spec:** `docs/superpowers/specs/2026-11-06-deadlines-status-votes-design.md`

## Global Constraints

- Drei Teile, jeder auf eigenem Branch, gemergt mit eigener Version: Teil 1 → 0.44.0 (versionCode 55), Teil 2 → 0.45.0 (56), Teil 3 → 0.46.0 (57), jeweils eigener `## <version>`-Abschnitt in `CHANGELOG.md`.
- Texte in der App auf Deutsch; Datumsfelder als yyyy-MM-dd.
- Neue Modellfelder immer mit Default (`= null`, `= false`), damit alte Datensätze dekodieren.
- Heute-Fristen: Fenster heute … heute + 30 Tage, plus alle `OVERDUE` und `OPEN_NOW`; höchstens 4 Zeilen, Rest hinter „Alle Fristen (n)“.
- Ideen-Sammelzeile: Fristen in den nächsten 14 Tagen.
- Stimmen: Like = 1, Doppel-Like = 2, Dislike = −2; Schwelle 2.
- Erinnerungsabstände für „Reservieren bis“: 30, 14, 7, 2 Tage (wie `Reservations.REMINDER_OFFSETS`).

## Review Focus

1. Ort ohne geplanten Tag und ohne Region: keine geschätzte Frist, kein Absturz → `ReservationDates` gibt `null` (Test in Task 2).
2. Recherche liefert `need = NOT_NEEDED`, Notiz/Tag sagt trotzdem „reservieren“ → keine Reservierung nötig (Test in Task 1).
3. Frist erledigt, danach recherchiert die KI neu → `done`/`byHand` bleiben erhalten (Test in Task 3).
4. Fremde Stimme in einem Push (voter ≠ Token) → nur diese Änderung verworfen, der Rest gespeichert (Test in Task 9).
5. Geplanter Ort fällt unter 2 Punkte → Zustand bleibt `PLANNED`, Hinweis erscheint (Test in Task 10).

---

## Teil 1 – Fristen (Branch `feat/deadlines`, 0.44.0)

### Task 1: Reservierungsbedarf richtig erkennen

**Files:**
- Modify: `shared/src/main/kotlin/de/wanderlogplus/shared/model/Research.kt` (neue Typen, Feld im Dossier)
- Modify: `shared/src/main/kotlin/de/wanderlogplus/shared/query/Reservations.kt`
- Modify: `server/src/main/kotlin/de/wanderlogplus/server/ai/Assistant.kt` (`RESEARCH_JSON_SHAPE`, `parseResearch`)
- Modify: `server/src/main/kotlin/de/wanderlogplus/server/ai/Parallel.kt` (Schema um `reservation`)
- Test: `shared/src/test/kotlin/de/wanderlogplus/shared/query/ReservationsTest.kt`, `server/src/test/kotlin/de/wanderlogplus/server/ai/ResearchParseTest.kt` (neu oder bestehende Parse-Tests)

**Interfaces:**
- Produces: `enum class ReservationNeed { REQUIRED, RECOMMENDED, NOT_NEEDED, UNKNOWN }`; `@Serializable data class ReservationAdvice(val need: ReservationNeed = ReservationNeed.UNKNOWN, val opensDaysBefore: Int? = null, val opensOn: String? = null, val byDaysBefore: Int? = null, val note: String = "")`; `PlaceDossier.reservation: ReservationAdvice? = null`.

- [ ] **Step 1: Failing tests**
  - `ReservationsTest.notNeededWithFillerWords`: Dossier-hints `["Eine Reservierung ist nicht nötig."]` → `Reservations.reason(s) == null`.
  - `ReservationsTest.bookingNoteThatDeniesDoesNotCount`: criterion `booking` score 2 note „Reservierung ist nicht erforderlich, aber Andrang“ → `null`.
  - `ReservationsTest.adviceWins`: dossier `reservation = ReservationAdvice(NOT_NEEDED)` plus tag „⚠️ Nur mit Reservierung“ → `null`; `ReservationAdvice(REQUIRED, note = "Tickets online")` ohne Texte → `"Tickets online"`; `REQUIRED` mit leerer note → `"Reservierung nötig"`; `RECOMMENDED` → `"Reservierung empfohlen"` (wenn note leer).
  - `ResearchParseTest.reservationIsParsed`: `parseResearch` mit `"reservation": {"need":"REQUIRED","opensDaysBefore":30,"opensOn":null,"byDaysBefore":3,"note":"Online ab 30 Tage vorher."}` → Dossier-Feld gleich; unbekanntes `need` → `UNKNOWN`; fehlendes Objekt → `null`.
- [ ] **Step 2: Run, expect FAIL** — `./gradlew :shared:test :server:test --tests '*Reservations*' --tests '*ResearchParse*'` → Compile-Fehler/Assertion.
- [ ] **Step 3: Implement.** `Reservations.reason`: zuerst `s.dossier?.reservation` auswerten (`UNKNOWN` → weiter wie bisher), sonst bisherige Reihenfolge; `NOT_NEEDED`-Regex erweitern um `reservierung (ist |wird |muss )?(nicht|kaum|selten|nur selten) (nötig|notwendig|erforderlich)`, `nicht (nötig|notwendig|erforderlich),? (zu )?reservieren`, `not (required|necessary)`, `no need to (book|reserve)`; booking-Notiz nur zählen, wenn `!NOT_NEEDED.containsMatchIn(note)`. `RESEARCH_JSON_SHAPE` um `"reservation": {"need": "REQUIRED|RECOMMENDED|NOT_NEEDED|UNKNOWN", "opensDaysBefore": Zahl oder null, "opensOn": "yyyy-MM-dd oder null", "byDaysBefore": Zahl oder null, "note": "ein Satz, Deutsch"}` plus eine Zeile Erklärung („opensDaysBefore: Buchung öffnet so viele Tage vor dem Besuch; opensOn: feste Freischaltung; byDaysBefore: spätestens so viele Tage vorher“); Parallel-Schema gleich (Feld in `required`).
- [ ] **Step 4: Run, expect PASS** (gleicher Befehl, dazu ganze `:shared:test :server:test`).
- [ ] **Step 5: Commit** `fix: reservation need from explicit research advice; negations with filler words`.

### Task 2: Fristen berechnen (`ReservationWindow`, `ReservationDates`, `Deadlines`)

**Files:**
- Create: `shared/src/main/kotlin/de/wanderlogplus/shared/model/ReservationWindow.kt`
- Modify: `shared/src/main/kotlin/de/wanderlogplus/shared/model/Models.kt` (Felder an `Train`, `Flight`, `Stay`, `SavedPlace`)
- Create: `shared/src/main/kotlin/de/wanderlogplus/shared/query/Deadlines.kt`
- Test: `shared/src/test/kotlin/de/wanderlogplus/shared/query/DeadlinesTest.kt`

**Interfaces:**
- Consumes: `ReservationAdvice` (Task 1), `Reservations.reason`.
- Produces:
  - `@Serializable data class ReservationWindow(val opens: String? = null, val by: String? = null, val done: Boolean = false, val byHand: Boolean = false)`; Felder `Train.reservation`, `Flight.reservation`, `Stay.reservation`, `SavedPlace.reservationWindow` (alle `ReservationWindow? = null`).
  - `object ReservationDates { fun visitDate(s: SavedPlace, days: List<Day>, items: List<DayItem>, stays: List<Stay>, tripDays: List<String>): Pair<String, Boolean>?` (Datum, geschätzt) `; fun windowFor(s: SavedPlace, visit: Pair<String, Boolean>?): ReservationWindow?` `}` — von Hand (`byHand`) gesetztes Fenster unverändert; sonst aus `dossier.reservation`: `opens = opensOn ?: visit - opensDaysBefore`, `by = visit - byDaysBefore`; ohne Besuchstag nur `opensOn`.
  - `enum class DeadlineKind { CANCEL, OPENS, BY, PLACE }`, `enum class DeadlineState { UPCOMING, OPEN_NOW, OVERDUE }`, `enum class DeadlineTarget { PLACE, STAY, TRAIN, FLIGHT }`.
  - `data class Deadline(val kind: DeadlineKind, val date: String, val title: String, val targetType: DeadlineTarget, val targetId: String, val state: DeadlineState, val estimated: Boolean = false, val placeStatus: PlaceStatus? = null)`.
  - `object Deadlines { fun list(s: DeadlineInput, today: String): List<Deadline> }` mit `data class DeadlineInput(val places: Map<String, Place>, val saved: List<SavedPlace>, val stays: List<Stay>, val trains: List<Train>, val flights: List<Flight>, val days: List<Day>, val items: List<DayItem>, val tripDays: List<String>)`. Sortierung: `OVERDUE` zuerst, dann nach Datum.

- [ ] **Step 1: Failing tests** in `DeadlinesTest` (heute = 2027-04-10, Reise ab 2027-05-01):
  - `visitDatePlannedOrEstimated`: geplanter Ort → (Tag, false); ungeplant mit Region r1, erste Unterkunft in r1 ab 2027-05-03 → ("2027-05-03", true); ohne Region und ohne Plan → `null`.
  - `windowFromAdvice`: Besuch 2027-05-10, `opensDaysBefore = 30, byDaysBefore = 3` → `opens = "2027-04-10", by = "2027-05-07"`; `opensOn = "2027-04-01"` gewinnt über Tage; `byHand`-Fenster bleibt unverändert.
  - `listStates`: Zug mit `reservation(opens = "2027-04-05", by = "2027-04-20")` (also `opens <= today`) → genau ein `BY`-Eintrag mit `state = OPEN_NOW`, kein `OPENS`; `opens` in der Zukunft → `OPENS` `UPCOMING` und `BY` `UPCOMING`; `by < today` und nicht `done` → `BY` `OVERDUE`; `done = true` → nichts; Fahrt schon vorbei (`depart.date < today`) → nichts.
  - `stayCancelAndPlace`: Unterkunft mit `cancellationDeadline = "2027-04-15"` → `CANCEL`; Ort mit Reservierungsbedarf, `reserved = false`, ohne Fenster, geplant 2027-05-10 → `PLACE` am 2027-05-10; `reserved = true` → nichts; Ort `DROPPED` → nichts; jeder Ort-Eintrag trägt `placeStatus`.
- [ ] **Step 2: Run, expect FAIL** — `./gradlew :shared:test --tests '*DeadlinesTest*'`.
- [ ] **Step 3: Implement** in den genannten Dateien. Ort: Fenster = `s.reservationWindow ?: ReservationDates.windowFor(s, visit)`; mit Fenster OPENS/BY wie Logistik (geschätzt = Besuchstag geschätzt), ohne Fenster aber mit `Reservations.reason != null` und Besuchstag → `PLACE`. Ereignis vorbei: Zug/Flug `depart.date < today`, Unterkunft `checkOut < today`, Ort Besuchstag `< today`.
- [ ] **Step 4: Run, expect PASS** (`:shared:test`).
- [ ] **Step 5: Commit** `feat(shared): reservation windows and one deadline list`.

### Task 3: Recherche schreibt Fenster, ohne Handarbeit zu überschreiben

**Files:**
- Modify: `server/src/main/kotlin/de/wanderlogplus/server/ai/AiJobs.kt` (`writeDossier`)
- Test: `server/src/test/kotlin/de/wanderlogplus/server/ai/AiJobsTest.kt` (bestehende Research-Tests erweitern)

**Interfaces:** Consumes `ReservationWindow`, `ReservationAdvice`. Schreibt **kein** `reservationWindow`-Feld (das Fenster wird aus Dossier + Plan berechnet); die Task prüft nur, dass `reservationWindow` und `reserved` beim Schreiben eines neuen Dossiers unverändert bleiben.

- [ ] **Step 1: Failing test** `researchKeepsHandWindowAndReserved`: gespeicherter Ort mit `reservationWindow = ReservationWindow(opens = "2027-04-01", byHand = true)`, `reserved = true`; neues Dossier mit `reservation = REQUIRED` wird geschrieben → danach Feld `reservationWindow` und `reserved` unverändert, `dossier.reservation.need == REQUIRED`.
- [ ] **Step 2: Run** → FAIL nur falls `writeDossier` die Felder anfasst; schlägt der Test sofort grün an, ist das ein gültiger Befund (Ledger-Notiz), weiter.
- [ ] **Step 3: Implement** nichts außer ggf. Korrektur; sonst nur Test behalten.
- [ ] **Step 4/5:** `:server:test` grün; Commit `test(server): research keeps hand-set reservation data`.

### Task 4: Formulare und Detailseiten (Logistik + Ort)

**Files:**
- Create: `app/src/main/kotlin/de/wanderlogplus/app/ui/logistics/ReservationFields.kt` (gemeinsame Compose-Felder + Text)
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/ui/logistics/LogisticsEditScreen.kt` (`MoveDraft.reservation`, `draftOf`, `draftChanges`, `validateDraft`)
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/ui/logistics/StayEditScreen.kt` (`StayDraft.reservation`, `stayDraftOf`, `validateStay`, `stayToSave`)
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/ui/logistics/LogisticsDetailScreen.kt` (Zeile „Reservierung“)
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/ui/places/PlaceDetailScreen.kt` (Karte „Reservierung“: Fenster anzeigen + bearbeiten)
- Test: `app/src/test/kotlin/de/wanderlogplus/app/ui/logistics/ReservationFieldsTest.kt`

**Interfaces:**
- Produces: `data class ReservationDraft(val opens: String = "", val by: String = "", val done: Boolean = false)`; `fun ReservationDraft.toWindow(old: ReservationWindow?): ReservationWindow?` (leer + nicht done → null; sonst `byHand = true`); `fun ReservationWindow?.toDraft(): ReservationDraft`; `fun validateReservation(d: ReservationDraft): String?` („Reservierbar ab bitte als JJJJ-MM-TT“, „Reservieren bis bitte als JJJJ-MM-TT“, „„Reservierbar ab“ liegt nach „Reservieren bis““); `fun reservationText(w: ReservationWindow, estimated: Boolean): String` („Reservierbar ab Mi, 14.04. · Reservieren bis So, 20.04.“, „ (geschätzt)“, „ · erledigt“); `@Composable fun ReservationFields(d: ReservationDraft, onChange: (ReservationDraft) -> Unit)` mit Tags `res-opens`, `res-by`, `res-done`.
- Ort: Änderungen über bestehende Ort-Speicherung (`setChanged` auf `SAVED_PLACE`, Feld `reservationWindow`); Häkchen bleibt `reserved`.

- [ ] **Step 1: Failing tests:** `toWindowAndBack` (leer → null, Daten → byHand = true, done ohne Daten → `ReservationWindow(done = true, byHand = true)`); `validateOrder` (opens > by → Meldung); `trainDraftSavesReservation` (über `draftChanges` mit Draft `reservation = ReservationDraft("2027-04-01","2027-04-20")` → `after["reservation"]` dekodiert gleich); `stayDraftSavesReservation` analog über `stayToSave`; Compose: `StayEditScreen` zeigt `res-opens`, Eingabe landet im gespeicherten Draft (bestehendes Muster aus StayEdit-Tests).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*Reservation*' --tests '*StayEdit*' --tests '*LogisticsEdit*'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(app): reservation windows editable for rides, stays and places`.

### Task 5: Heute-Seite „Fristen“

**Files:**
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/ui/today/TodayWidgets.kt` (`deadlineRows`, `DeadlinesWidget`)
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/ui/today/TodayScreen.kt` (beide Aufrufe; `RESERVATION_DAYS_DURING` entfernen; Klick auf Unterkunft/Zug/Flug öffnet Detail)
- Test: `app/src/test/kotlin/de/wanderlogplus/app/ui/today/DeadlineRowsTest.kt` (neu) + bestehende Today-Tests anpassen

**Interfaces:**
- Consumes: `Deadlines.list`.
- Produces: `internal fun deadlineRows(snapshot: TripSnapshot, today: String): List<DeadlineRow>` (Fenster 30 Tage, `OVERDUE`/`OPEN_NOW` immer; Check-out heute bleibt erste Zeile); `DeadlineRow` erhält `state: DeadlineState`, `targetType`, `targetId`; `DeadlinesWidget(rows, accent, onOpen: ((DeadlineTarget, String) -> Unit)?)`, zeigt 4 Zeilen, dann TextButton „Alle Fristen (n)“ (Tag `deadlines-more`) zum Aufklappen; `OVERDUE` in `colorScheme.error` mit Text „überfällig seit …“.
- Zeilentexte: `CANCEL` „Stornofrist bis …“, `OPENS` „Reservierbar ab …“, `BY` (UPCOMING) „Reservieren bis …“, `BY`/`PLACE` (OPEN_NOW) „Jetzt reservierbar · bis …“, `PLACE` „Reservierung nötig · eingeplant …“; `estimated` hängt „ (geschätzt)“ an.

- [ ] **Step 1: Failing tests:** `onlyNext30DaysPlusOverdue` (Frist in 40 Tagen fehlt; überfällige vorne); `widgetFoldsAfterFour` (6 Zeilen → 4 sichtbar, `deadlines-more` zeigt „Alle Fristen (6)“, nach Klick 6 sichtbar); `sameRuleDuringTrip`.
- [ ] **Step 2: Run** `--tests '*DeadlineRows*' --tests '*Today*'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(today): deadlines of the next 30 days, overdue first, folded after four`.

### Task 6: Erinnerungen + Release 0.44.0

**Files:**
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/reminders/Reminders.kt`, `DeadlineWorker.kt`, `ReservationDoneReceiver.kt`
- Test: `app/src/test/kotlin/de/wanderlogplus/app/reminders/RemindersTest.kt`
- Modify: `app/build.gradle.kts` (55 / "0.44.0"), `CHANGELOG.md`

**Interfaces:** Produces `fun dueWindowReminders(deadlines: List<Deadline>, today: LocalDate, alreadySent: Set<String>): List<Reminder>` — `BY` mit Abständen 30/14/7/2 (kleinster fälliger, Schlüssel `"<type>:<id>|<date>|<offset>"`), `OPENS` am Tag selbst (Schlüssel `"<type>:<id>|opens|<date>"`); nur Logistik und Orte mit Status `SHORTLIST`/`PLANNED` (bis Teil 2 nur `PLANNED`). Der „Erledigt“-Knopf: Orte → `reserved = true` (wie bisher), Logistik → `reservation.done = true`.

- [ ] **Step 1: Failing tests:** `byRemindersUseOffsets`, `opensOnTheDay`, `doneLogisticsGetsNoReminder`.
- [ ] **Step 2–4:** Run FAIL → implement → PASS (`:app:testDebugUnitTest --tests '*Reminders*'`).
- [ ] **Step 5:** Ganze Suite `:app:testDebugUnitTest :server:test :shared:test :client:test` grün; Version + CHANGELOG 0.44.0; Commit `feat: reminders for reservation windows; 0.44.0`; Merge `feat/deadlines` → main.

## Teil 2 – „Vorgemerkt“ (Branch `feat/shortlist`, 0.45.0)

### Task 7: Zustand `SHORTLIST`

**Files:**
- Modify: `shared/src/main/kotlin/de/wanderlogplus/shared/model/Models.kt` (`PlaceStatus { IDEA, SHORTLIST, PLANNED, VISITED, DROPPED }`)
- Modify: alle `when (PlaceStatus)`-Stellen: `app/.../ui/Format.kt` (`statusLabel` → „Vorgemerkt“), `app/.../maps/RegionMapView.kt` (Farbe `#8E24AA`), Statusauswahl/Filter in `app/.../ui/places/*`, Chat-Kontext `server/.../ai/ChatAgent.kt` (Statustexte), Export/Import falls `when` vorhanden (Compiler zeigt sie).
- Test: bestehende Format-/Places-Tests erweitern; `shared` Codec-Test.

- [ ] **Step 1: Failing tests:** `statusLabel(SHORTLIST) == "Vorgemerkt"`; Codec dekodiert `"SHORTLIST"`; Places-Filter-Chip „Vorgemerkt“ filtert.
- [ ] **Step 2–4:** FAIL → implement (Compiler-Fehler aller `when` beheben) → PASS.
- [ ] **Step 5:** Commit `feat: place status Vorgemerkt`.

### Task 8: Fristen nur für Vorgemerkt/Geplant + Ideen-Sammelzeile + Release 0.45.0

**Files:** `app/.../ui/today/TodayWidgets.kt`, `app/.../reminders/Reminders.kt`, Tests wie Task 5/6; Version 56 / "0.45.0", CHANGELOG.

**Interfaces:** `deadlineRows` filtert Ort-Fristen auf `placeStatus in setOf(SHORTLIST, PLANNED)`; `internal fun ideaDeadlines(all: List<Deadline>, today: String): List<Deadline>` = Ideen mit Datum ≤ heute + 14 oder `OPEN_NOW`; Widget zeigt bei nicht-leerer Liste eine Zeile „n Ideen mit Fristen“ (Tag `deadlines-ideas`), aufklappbar zu Einzelzeilen (öffnen den Ort). Erinnerungen: Orte nur `SHORTLIST`/`PLANNED`.

- [ ] **Step 1: Failing tests:** `ideasAreSummarised` (Idee mit Frist in 5 Tagen → keine Einzelzeile, Sammelzeile „1 Idee mit Fristen“; in 20 Tagen → nichts); `shortlistShowsDeadlines`; `ideaGetsNoReminder`.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5:** Ganze Suite grün; 0.45.0; Commit; Merge `feat/shortlist`.

## Teil 3 – Abstimmen (Branch `feat/votes`, 0.46.0)

### Task 9: `VOTE`-Entität, Regel, Server-Prüfung

**Files:**
- Modify: `shared/src/main/kotlin/de/wanderlogplus/shared/sync/EntityRecord.kt` (`EntityType.VOTE`)
- Create: `shared/src/main/kotlin/de/wanderlogplus/shared/model/Votes.kt`
- Modify: `client/src/main/kotlin/de/wanderlogplus/client/TripSnapshot.kt` (`votes: List<Vote> = emptyList()`)
- Modify: `server/src/main/kotlin/de/wanderlogplus/server/SyncService.kt` (`push`: fremde Stimmen verwerfen; danach Zustände prüfen)
- Test: `shared/src/test/.../model/VotesTest.kt`, `server/src/test/.../SyncServiceVotesTest.kt`

**Interfaces:**
- Produces: `@Serializable data class Vote(val id: String, val savedPlaceId: String = "", val voter: String = "", val value: Int = 0)`; `object Votes { const val LIKE = 1; const val DOUBLE = 2; const val DISLIKE = -2; const val THRESHOLD = 2; fun id(savedPlaceId: String, voter: String) = "$savedPlaceId|$voter"; fun score(votes: List<Vote>, savedPlaceId: String): Int; fun nextStatus(current: PlaceStatus, score: Int): PlaceStatus; fun planDoubt(current: PlaceStatus, score: Int): Boolean }` — `nextStatus`: IDEA & ≥2 → SHORTLIST; SHORTLIST & <2 → IDEA; sonst unverändert. `planDoubt`: PLANNED & score < 2 & mindestens eine Stimme vorhanden (sonst würde jeder ungestimmte geplante Ort einen Hinweis bekommen).
- Server: In `push(author, changes)` Änderungen mit `type == VOTE` verwerfen, wenn `field == "voter"` und Wert ≠ author **oder** die id nicht auf `"|$author"` endet. Nach dem Anwenden: für jede betroffene `savedPlaceId` Score berechnen, `nextStatus` anwenden und bei Änderung `writeAsServer` mit Feld `status`.

- [ ] **Step 1: Failing tests:** `VotesTest.scoreAndRule` (like+like → 2 → SHORTLIST; double+dislike → 0 → IDEA; PLANNED bleibt; `planDoubt` true bei PLANNED/0 mit Stimme, false ohne Stimmen); `SyncServiceVotesTest.foreignVoteDropped` (Push von „daniel“ mit Stimme `s1|selina` und einer Notizänderung → Stimme fehlt, Notiz gespeichert); `serverCorrectsStatus` (zwei Pushes je +1 → Ort `SHORTLIST`; danach Dislike → `IDEA`).
- [ ] **Step 2–4:** FAIL → implement → PASS (`:shared:test :server:test`).
- [ ] **Step 5:** Commit `feat: votes per access token with automatic shortlist`.

### Task 10: Abstimmen in der App + Hinweise + Release 0.46.0

**Files:**
- Create: `app/src/main/kotlin/de/wanderlogplus/app/ui/places/VoteBar.kt`
- Modify: `PlaceDetailScreen.kt` (VoteBar), Ortslisten (Abzeichen Punktzahl), `TodayScreen.kt`/`TodayWidgets.kt` (Hinweis), `DayDetailScreen.kt` (Hinweis am Ort des Tags), `MainActivity.kt`/`AppContainer` (eigener Name: nach `whoami` in den Einstellungen speichern, beim Start nachladen, wenn leer)
- Test: `app/src/test/.../places/VoteBarTest.kt`, Today-/Day-Test erweitern
- Modify: Version 57 / "0.46.0", CHANGELOG

**Interfaces:**
- Produces: `fun voteEdits(snapshot: TripSnapshot, savedPlaceId: String, me: String, value: Int): (LocalEditor) -> Unit` — setzt/erstellt `VOTE` `Votes.id(...)` (value 0 = zurücknehmen) und wendet `Votes.nextStatus` auf den Ort an (nur wenn geändert); `@Composable fun VoteBar(votes: List<Vote>, me: String?, onVote: (Int) -> Unit)` mit Knöpfen Tags `vote-like`, `vote-double`, `vote-dislike` (nochmal tippen = zurücknehmen) und Zeile „Daniel 👍👍, Selina 👎“ (Tag `vote-names`); ohne `me` keine Knöpfe. Hinweistext: „Kaum noch Zustimmung: <Namen mit Symbol>“ (Tag `plan-doubt-<savedPlaceId>`).

- [ ] **Step 1: Failing tests:** `voteEditsMoveToShortlist` (zweite Like-Stimme → Status SHORTLIST in Snapshot); `voteBarShowsNamesAndToggles`; `todayShowsDoubtForPlannedPlace`; `dayShowsDoubt`.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5:** Ganze Suite grün; 0.46.0; Commit; Merge `feat/votes`.
