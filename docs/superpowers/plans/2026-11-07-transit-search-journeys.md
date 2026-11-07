# ÖPNV-Suche mit Orten, Auswahl-Dialogen und Reisen – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Places/hotels as start and goal of the transit search, date/time pickers, connections found from Logistik saved as journeys (one train per ride), and replacing a ride by a later one.

**Architecture:** `TransitForm` gains points (`fromPoint`/`toPoint`) and the screen a suggestion list fed by a pure trip-place matcher plus `PlaceFinder`. A pure `TransitRides.ridesOf` turns a connection into trains; `LocalEditor.addRidesAfter` and `replaceRide` place them in journeys. Replacing reuses the transit screen on a new route.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3 DatePicker/TimePicker), kotlinx.serialization, Robolectric, JUnit.

**Spec:** `docs/superpowers/specs/2026-11-07-transit-search-journeys-design.md`

## Global Constraints

- Gradle: `JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.8.9-hotspot" ./gradlew …` (Bash). Use `-q`; read result XMLs for failures.
- Versions: Task 2 → 0.52.0 (67), Task 4 → 0.53.0 (68), Task 5 → 0.54.0 (69); each with its own German CHANGELOG section.
- All user-visible text German; Robolectric: no AlertDialog with text fields (OOM); merged-tree finders need `useUnmergedTree = true`.
- Japan data source unchanged (Apify/Yahoo); no server changes.

## Review Focus

1. A connection whose rides cross midnight (23:50 → 00:20) must give the second ride the next date → test in Task 3.
2. A route with walks only, or a single ride, must not create a journey → test in Task 3/4.
3. Picking a place then typing a station by hand: the request carries the typed station and still the point → test in Task 1.
4. Replacing a ride of a train that is not in a journey but gets two rides → a new journey with both, original fields kept → test in Task 5.
5. Seats entered for the old departure must be flagged after a replacement, not silently kept as valid → test in Task 5.

---

### Task 1: Von/Nach with places, hotels or stations

**Files:**
- Create: `app/src/main/kotlin/de/wanderlogplus/app/ui/transit/TransitEndpoints.kt`
- Modify: `app/.../ui/transit/TransitViewModel.kt`, `TransitScreen.kt`, `AppNavigation.kt` (transit VM factory + `TransitScreen` call), `LogisticsEditScreen.kt` (`TransitPanel`)
- Test: `app/src/test/kotlin/de/wanderlogplus/app/ui/transit/TransitEndpointsTest.kt`

**Interfaces:**
- Produces: `data class EndpointHit(val name: String, val detail: String, val lat: Double, val lng: Double, val fromTrip: Boolean)`; `fun tripEndpointHits(snapshot: TripSnapshot, query: String, limit: Int = 8): List<EndpointHit>` (saved places + their region name as detail, stays with detail „Unterkunft“, places on any day's program; match: name contains query ignoring case and diacritics (`java.text.Normalizer` NFD minus marks); one per place id; blank query → empty); `typealias EndpointSearch = suspend (String) -> List<EndpointHit>` (online hits, `fromTrip = false`).
- `TransitForm` gains `fromPoint: TransitPoint? = null`, `toPoint: TransitPoint? = null`. `TransitViewModel(api, from, to, date, time, chosen, searchType)` keeps its constructor; `from`/`to` become the initial `fromPoint`/`toPoint`. New VM functions: `pickFrom(hit: EndpointHit)`, `pickTo(hit: EndpointHit)` (set point, clear station text, reload near stations for that side), `clearFrom()`, `clearTo()` (point null, near list empty). `requestFor(f)` (no longer takes from/to) uses `f.fromPoint`/`f.toPoint`.
- Messages: missing start → „Start eintragen (Ort, Hotel oder Bahnhof).“, missing goal → „Ziel eintragen (Ort, Hotel oder Bahnhof).“
- `TransitScreen` gains `suggest: (suspend (String) -> List<EndpointHit>)? = null` (null: no list). The list merges `tripEndpointHits` (passed in by the caller inside `suggest`) and online hits; trip hits first, max 8, online ones only from 3 characters after 600 ms, deduplicated by name+rounded coords. Tags: `transit-from-hit-<i>`, `transit-to-hit-<i>`, `transit-from-place`, `transit-from-place-clear` (and `to`).
- Callers build `suggest = { q -> tripEndpointHits(snap, q) + (if (q.trim().length >= 3) placeFinder.search(q, near, null).places.map { EndpointHit(it.name, it.detail, it.lat, it.lng, false) } else emptyList()) }`; the 600 ms debounce lives in the screen.

- [ ] **Step 1: Failing tests** (`TransitEndpointsTest`, Fixtures):
  - `tripHitsMatchPlacesAndStays`: query of a fixture place name fragment (lower case) → hit with `fromTrip = true`, detail = its region name; stay name fragment → detail „Unterkunft“; `tripEndpointHits(s, "")` empty; a place on a day and in the collection appears once.
  - `pickPlaceSetsPointAndClearsStation`: VM with a fake api (MockEngine answering `/api/transit/stations` with `{"stations":[]}`), `edit { it.copy(fromStation = "京都") }`, `pickFrom(EndpointHit("Hotel Gion", "", 35.0, 135.77, true))` → `form.fromPoint == TransitPoint(35.0, 135.77, "Hotel Gion")`, `form.fromStation == ""`.
  - `requestKeepsTypedStationAndPoint` (Review Focus 3): form with `fromPoint` set and `fromStation = "祇園四条"` → `requestFor(f).from` is the point, `fromStation == "祇園四条"`.
  - `clearRemovesPoint`: `clearFrom()` → `fromPoint == null`; `search()` without station → message „Start eintragen (Ort, Hotel oder Bahnhof).“
  - `screenShowsSuggestionsAndPicks`: `TransitScreen` with `suggest = { listOf(EndpointHit("Hotel Gion", "Unterkunft", 35.0, 135.77, true)) }`, type „Ho“ into `transit-from` → `transit-from-hit-0` exists; click → `onPickFrom` receives it (screen gains `onPickFrom/onPickTo/onClearFrom/onClearTo` callbacks).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*TransitEndpoints*' -q` → FAIL (unresolved).
- [ ] **Step 3: Implement** per Interfaces; adapt existing transit tests to `requestFor(f)`.
- [ ] **Step 4: Run** the same → PASS; `--tests '*Transit*'` green.
- [ ] **Step 5: Commit** `feat(app): transit search from and to places, hotels or stations`.

### Task 2: Date and time pickers (0.52.0)

**Files:**
- Modify: `app/.../ui/transit/TransitScreen.kt`, `TransitViewModel.kt` (helpers)
- Test: `app/src/test/kotlin/de/wanderlogplus/app/ui/transit/TransitPickersTest.kt`

**Interfaces:**
- Produces: `fun pickedTime(hour: Int, minute: Int): String` („08:05“); `fun dateLabel(iso: String): String` („Do., 20. Mai 2027“, `Locale.GERMAN`, `EEE, d. MMMM yyyy`; invalid → iso as is); `fun pickedDate(utcMillis: Long): String` (ISO date of the picker's UTC midnight); `fun dateMillis(iso: String): Long?` (UTC midnight, for the picker's initial value).
- Fields read-only (`readOnly = true`, click via `Modifier.clickable` on a transparent overlay box with tags `transit-date`/`transit-time`); dialogs: `DatePickerDialog` with `DatePicker` (confirm `transit-date-ok`), time in an `AlertDialog`-free `Dialog` holding `TimePicker(is24Hour = true)` (confirm `transit-time-ok`). Keyboard type of time no longer relevant.

- [ ] **Step 1: Failing tests**: `pickedTime(8, 5) == "08:05"`, `pickedTime(23, 59) == "23:59"`; `dateLabel("2027-05-20") == "Do., 20. Mai 2027"`; `pickedDate(dateMillis("2027-05-20")!!) == "2027-05-20"`; `dateLabel("x") == "x"`; screen test: click `transit-time` → `transit-time-ok` exists; click it → `onEdit` produced a form whose `time` matches `\d{2}:\d{2}`.
- [ ] **Step 2: Run** `--tests '*TransitPickers*'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS; full `:app:testDebugUnitTest` green.
- [ ] **Step 5: 0.52.0 / 67, CHANGELOG (Task 1 + 2), commit** `feat(app): date and time pickers in the transit search; 0.52.0`.

### Task 3: Connection → rides (shared)

**Files:**
- Create: `shared/src/main/kotlin/de/wanderlogplus/shared/query/TransitRides.kt`
- Test: `shared/src/test/kotlin/de/wanderlogplus/shared/query/TransitRidesTest.kt`

**Interfaces:**
- Produces: `object TransitRides { fun ridesOf(result: TransitResult): List<Train>; fun carrierOf(line: String): String? }`. Trains have `id = ""` (caller assigns), `carrier = carrierOf(leg.line.ifBlank { leg.lineJa })`, `number = leg.line.ifBlank { leg.lineJa }`, `depart/arrive = Endpoint(name = st.name.ifBlank { st.nameJa }, lat = st.lat, lng = st.lng, date, time = HH:mm or null)`, `transit = result.copy(routes = listOf(route.copy(legs = listOf(leg), fareYen = if (first) route.fareYen else 0, baseFareYen/expressFareYen/fees likewise, transfers = 0, departure = leg.departure, arrival = leg.arrival)))`. Uses the first route only; walks skipped.
- Dates: start `route.date.ifBlank { result.datetime.take(10) }`; running `prev` time; a departure or arrival earlier than the previous time → +1 day (arrival compared to its own departure as well).
- `carrierOf`: first match of prefixes in order: „JR“ → "JR", „Tokyo Metro“, „Toei“, „Ōsaka Metro“, „Kyōto U-Bahn“, „Nagoya U-Bahn“, „Kintetsu“, „Keihan“, „Hankyū“/„Hankyu“, „Hanshin“, „Meitetsu“, „Odakyū“, „Keiō“, „Seibu“, „Tōbu“, „Keisei“, „Nankai“; else null.

- [ ] **Step 1: Failing tests:**
  - `walksAreSkipped`: route legs walk, ride A (Shinkansen Tokyo→Nagoya 08:00–09:40), walk, ride B (Nagoya→Nakatsugawa 09:55–11:10) → 2 trains, B departs „Nagoya“ at 09:55, date = search date.
  - `crossesMidnight` (Review Focus 1): ride 23:50–23:58, ride 00:05–00:30 → second train date = next day (depart and arrive); a ride 23:40–00:10 → arrive date next day.
  - `fareOnlyOnFirst`: route fareYen 6380 → first train's transit route fareYen 6380, second 0.
  - `singleRideAndWalksOnly` (Review Focus 2): one ride → 1 train; only walks → empty.
  - `carriers`: "JR Shinkansen Nozomi 15" → "JR", "Tokyo Metro Ginza-Linie" → "Tokyo Metro", "Kintetsu Nara-Linie" → "Kintetsu", "Bus 205" → null.
- [ ] **Step 2: Run** `./gradlew :shared:test --tests '*TransitRides*' -q` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(shared): turn a transit connection into one train per ride`.

### Task 4: Save a connection from Logistik as a journey (0.53.0)

**Files:**
- Modify: `client/.../LocalEditor.kt` (`addRidesAfter`), `app/.../logistics/LogisticsEditScreen.kt` (`draftFromTransit`, hint, auto-open search), `app/.../more/MoreViewModel.kt` (`saveFields(..., extraRides)`), `app/.../logistics/LogisticsScreen.kt` (menu item), `AppNavigation.kt`
- Test: `client/src/test/kotlin/de/wanderlogplus/client/TransitJourneyTest.kt`, `app/src/test/kotlin/de/wanderlogplus/app/ui/logistics/TransitJourneyUiTest.kt`

**Interfaces:**
- Consumes: `TransitRides.ridesOf` (Task 3).
- Produces: `LocalEditor.addRidesAfter(snapshot: TripSnapshot, anchorId: String, anchorJourney: String?, rides: List<Train>, prev: Pair<EntityType, String>? = null, next: Pair<EntityType, String>? = null): String` — creates trains (new ids) for `rides`, all in one tx, and the journey order: the journey of the anchor (`anchorJourney`, else a new id) with the anchor at its place (existing position, or between `prev`/`next` for a new leg) and the new rides directly after it; writes `journeyId` and `journeyOrder` for all legs of the journey; returns the journey id. `rides` empty → no change, returns `anchorJourney.orEmpty()`.
- `draftFromTransit(d, r)`: fills the draft from the FIRST ride only (`TransitRides.ridesOf(r).first()`: carrier→operator, number, depart, arrive, via empty) and keeps `transit = r` (whole connection); with one ride identical to today's single-ride result. `fun extraRides(d: MoveDraft): List<Train>` = `ridesOf(d.transit).drop(1)` (empty without transit).
- Before saving, the draft's own `transit` is reduced to the first ride (`ridesOf(r).first().transit`), the rest goes as `extraRides`.
- `MoreViewModel.saveFields(type, id, before, after, extraRides: List<Train> = emptyList())`: after create/update, if `extraRides` not empty → `addRidesAfter(s, savedId, journeyOf(saved) ?: parseNewLeg(id)?.first, extraRides, prev, next)`.
- Form hint: `transit-as-journey` „Wird als Reise mit n Teilstrecken gespeichert“ when `extraRides(draft).isNotEmpty()`.
- Logistik menu: `logistics-add-transit` „ÖPNV-Verbindung suchen“ → `logisticsEditRoute(TRAIN, NEW_ID)` with query `?search=1`; `LogisticsEditScreen(..., openSearch: Boolean = false)` starts with the transit panel open.

- [ ] **Step 1: Failing tests:**
  - `TransitJourneyTest.newTrainWithThreeRides`: editor creates anchor train "t1" (no journey), `addRidesAfter(s, "t1", null, listOf(r2, r3))` → 3 trains share one journeyId, journeyOrder t1=0, r2=1, r3=2.
  - `insertsAfterAnchorInExistingJourney`: journey J with legs a(0), b(1); `addRidesAfter(s, "a", "J", listOf(x))` → order a, x, b.
  - `newLegBetweenNeighbours`: journey J a, c; new anchor "n" created with journeyId J, `addRidesAfter(s, "n", "J", listOf(y), prev = TRAIN to "a", next = TRAIN to "c")` → a, n, y, c.
  - `noRidesNoChange`: empty list → no journey written.
  - `TransitJourneyUiTest.draftTakesFirstRideAndHints` (Review Focus 2 for one ride): draftFromTransit with a 2-ride result → draft depart/arrive = ride 1, `extraRides(draft).size == 1`; edit screen shows `transit-as-journey`; with 1 ride → no hint, `extraRides` empty.
  - `editedTrainKeepsBooking`: saving an existing train with booking "ABC" and a 2-ride draft → train keeps "ABC", second train has none, both in one journey (via MoreViewModel with an in-memory editor).
  - `menuOffersTransitSearch`: LogisticsScreen with `onAdd` → open `logistics-add`, `logistics-add-transit` exists.
- [ ] **Step 2: Run** `./gradlew :client:test --tests '*TransitJourney*' :app:testDebugUnitTest --tests '*TransitJourneyUi*' -q` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS; `:client:test :app:testDebugUnitTest` green (existing `draftFromTransit` tests updated: multi-ride results no longer become one train with via stops).
- [ ] **Step 5: 0.53.0 / 68, CHANGELOG, commit** `feat: transit connections found in Logistik become journeys, one train per ride; 0.53.0`.

### Task 5: Replace a ride by another one (0.54.0)

**Files:**
- Modify: `shared/.../model/Models.kt` (`Train.seatsForDeparture: String? = null` at the end), `client/.../LocalEditor.kt` (`replaceRide`), `app/.../logistics/LogisticsDetailScreen.kt` (button, stale-seat hint), `app/.../logistics/JourneyScreen.kt` (button per train leg), `app/.../today/TodayWidgets.kt` + `TodayScreen.kt` (button), `app/.../transit/TransitReplace.kt` (new: route + seeding), `MoreViewModel.kt`, `AppNavigation.kt`
- Test: `client/src/test/kotlin/de/wanderlogplus/client/ReplaceRideTest.kt`, `app/src/test/kotlin/de/wanderlogplus/app/ui/transit/TransitReplaceTest.kt`

**Interfaces:**
- Consumes: `TransitRides.ridesOf`, `LocalEditor.addRidesAfter`.
- Produces: `LocalEditor.replaceRide(snapshot: TripSnapshot, trainId: String, result: TransitResult)`: rides = `ridesOf(result)`; empty → no change; first ride sets `carrier`, `number`, `depart`, `arrive`, `via = []`, `transit`; when the train has seats and `seatsForDeparture == null`, sets `seatsForDeparture` = old departure "yyyy-MM-ddTHH:mm" (keeps an existing value); more rides → `addRidesAfter(snapshot, trainId, train.journeyId, rest)`.
- `fun seatsStale(t: Train): Boolean` = seats not empty and `seatsForDeparture != null` and != current departure; detail shows `train-seats-stale` „Sitzplätze galten für die alte Fahrt (<old time>)“ with „Passen noch“ (`train-seats-ok`, sets `seatsForDeparture = null`); saving seats in the edit form also sets it to null.
- `const val TRANSIT_REPLACE_ROUTE = "logistik/replace/{id}"`, `fun transitReplaceRoute(trainId: String)`; `fun replaceStart(t: Train, now: java.time.LocalDateTime): Pair<String, String>` (date, time): start = max(departure + 1 min, now) as date-time (departure date fallback: today; without a time: now); returns its date and "HH:mm".
- Seeding: `TransitViewModel(api, from = point of depart (lat/lng) or null, to = arrive point, date, time, chosen = null, searchType = "depart")` with `fromStation`/`toStation` = the train's `transit` route leg Japanese names (`legs.first().from.nameJa` / `legs.last().to.nameJa`) when present, else blank. `onChoose` → `MoreViewModel.replaceRide(id, result)` and back.
- Buttons: detail of a train `train-replace` „Andere Fahrt suchen“; journey leg (train) `journey-replace-<id>`; Today next-trip card `today-replace` „Verpasst? Spätere Fahrt“ when the next movement is a train (new `onReplaceTrain: ((String) -> Unit)?` param, null hides).

- [ ] **Step 1: Failing tests:**
  - `ReplaceRideTest.replacesTimesKeepsBooking`: train t (booking "ABC", 09:55 Nagoya→Nakatsugawa, seats [5A]) replaced by a 1-ride result 10:25–11:40 → depart time 10:25, arrive 11:40, booking "ABC", seats kept, `seatsForDeparture == "<date>T09:55"`.
  - `twoRidesWithoutJourney` (Review Focus 4): train without journey, 2-ride result → second train exists, both share a new journey, order t then new.
  - `insideJourneyRestUnchanged`: journey A(Shinkansen), t, C → replace t with 1 ride → order A, t, C, A and C fields unchanged.
  - `seatsStaleFlag` (Review Focus 5): after replace `seatsStale(t) == true`; with `seatsForDeparture` equal to the current departure → false; no seats → false.
  - `TransitReplaceTest.replaceStartTimes`: planned 09:55 on 2027-05-20, now 2027-05-20T10:10 → ("2027-05-20", "10:10"); now 2027-05-19T22:00 → ("2027-05-20", "09:56"); planned 2027-05-20 23:59, now earlier → ("2027-05-21", "00:00") (crossing midnight moves the date).
  - `detailShowsReplaceAndStaleHint`: LogisticsDetailScreen for a train with stale seats and `onReplace` set → `train-replace` and `train-seats-stale` exist.
  - `todayOffersReplace`: TodayScreen fixture whose next movement is a train, `onReplaceTrain` set → `today-replace` exists.
- [ ] **Step 2: Run** `./gradlew :client:test --tests '*ReplaceRide*' :app:testDebugUnitTest --tests '*TransitReplace*' -q` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS; `:shared:test :client:test :app:testDebugUnitTest` green.
- [ ] **Step 5: 0.54.0 / 69, CHANGELOG, commit** `feat: replace a ride by another connection (missed train); 0.54.0`.
