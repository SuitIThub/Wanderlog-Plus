# Bewerten-Modus Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A „Bewerten“ mode in Orte that walks me through every open place I have not voted on yet, one compact card at a time, with 👎 / Neutral / 👍 / 👍👍 and distance to the stay.

**Architecture:** App only (`app/.../ui/places`). Pure functions in a new `PlaceRating.kt` (queue, distances, labels) plus a `PlaceRatingScreen` composable; votes go through the existing `PlacesViewModel.vote` → `voteEdits`. Neutral = a vote record with value 0 (already accepted and settled by the server).

**Tech Stack:** Kotlin, Jetpack Compose, Robolectric compose tests, JUnit4.

**Spec:** `docs/superpowers/specs/2026-11-16-place-rating-design.md`

## Global Constraints

- „Bewertet“ = a `Vote` with id `Votes.id(savedPlaceId, me)` exists in `snapshot.votes`, whatever its value.
- Queue statuses: `IDEA`, `SHORTLIST`, `PLANNED` only.
- Order: regions in `regionTileOrder(snapshot)`, then places without a known region; inside a region by place name (case-insensitive), then id.
- Distances are great-circle (`distanceKm`), places/stays at (0.0, 0.0) count as unknown.
- Copy (German, exact): button `Bewerten (n)`, chips `👎`, `Neutral`, `👍`, `👍👍`, `Überspringen`, `Mehr`, progress `i / n`, end `Alles bewertet 🎉`, `🏨 <d> zur Unterkunft (<Region>)`, `<d> vom Zentrum <Region>`.
- Distance format `<d>`: < 1 km → `850 m` (rounded to 10 m); < 10 km → `4,2 km`; else `38 km`.
- Heute card: only in `TodayState.Before` with `daysUntilStart >= 30` and n > 0.
- Version 0.75.0, versionCode 96, own CHANGELOG section.

## Review Focus

- `me == null` (name not loaded yet): no „Bewerten“ button, no crash → Task 4 test `noRateButtonWithoutMe`.
- A place in the frozen queue gets deleted or voted on from another device while rating → it is skipped, not shown → Task 3 test `skipsPlacesGoneOrRatedMeanwhile`.
- Region without stay (and day trip whose main region has none) → stay line omitted → Task 2 test `noStayNoLine`.
- Place without coordinates (0,0) → no distance lines → Task 2 test `noCoordinatesNoDistance`.
- Rotation / process recreation mid-run keeps the position → Task 3: queue and index in `rememberSaveable`.

---

### Task 1: Neutral in the VoteBar

**Files:**
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/ui/places/VoteBar.kt`
- Test: `app/src/test/kotlin/de/wanderlogplus/app/ui/places/VoteBarTest.kt`

**Interfaces:**
- Produces: `VoteBar` shows chip tag `vote-neutral` (label `Neutral`), selected when my vote exists with value 0; tapping it calls `onVote(0)`. Chip order: 👎, Neutral, 👍, 👍👍. Tapping my selected 👍/👍👍/👎 still calls `onVote(0)` (= neutral).

- [ ] **Step 1: Write failing test `neutralChipSelectedForZeroVote`**: content `VoteBar(listOf(vote("s1","daniel",0)), me = "daniel", onVote = { cast += it })`; assert `onNodeWithTag("vote-neutral").assertIsSelected()`, `vote-like` not selected; perform click on `vote-like` → `cast == [1]`; click `vote-neutral` → `cast == [1, 0]`. Second test `neutralNotSelectedWithoutVote`: `VoteBar(emptyList(), "daniel", {})` → `vote-neutral` not selected.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests "*VoteBarTest*"` → FAIL (no node `vote-neutral`).
- [ ] **Step 3: Implement**: `mine` becomes `Int?` (null = no record); neutral chip selected when `mine == 0`.
- [ ] **Step 4: Run** same command → PASS (all VoteBarTest).
- [ ] **Step 5: Commit** `feat(app): neutral vote in the vote bar`

### Task 2: Queue and distances (pure logic)

**Files:**
- Create: `app/src/main/kotlin/de/wanderlogplus/app/ui/places/PlaceRating.kt`
- Test: `app/src/test/kotlin/de/wanderlogplus/app/ui/places/PlaceRatingTest.kt` (plain JUnit, `Fixtures.trip()`)

**Interfaces:**
- Consumes: `regionTileOrder`, `isDayTrip`, `regionCentres`, `distanceKm` (PlacesScreen.kt), `Votes.id`.
- Produces:
  - `fun isRated(snapshot: TripSnapshot, savedPlaceId: String, me: String): Boolean`
  - `fun ratingQueue(snapshot: TripSnapshot, me: String): List<String>` (saved place ids)
  - `fun distanceLabel(km: Double): String`
  - `data class StayDistance(val km: Double, val regionName: String)`
  - `fun stayDistance(snapshot: TripSnapshot, saved: SavedPlace): StayDistance?` — stays of the place's region, or of `dayTripFromRegionId` for day-trip regions; nearest stay; `regionName` = name of the stay's region.
  - `fun centreDistance(snapshot: TripSnapshot, saved: SavedPlace): StayDistance?` — only for day-trip regions (`isDayTrip`), distance to `regionCentres()[regionId]`, `regionName` = own region name.

- [ ] **Step 1: Write failing tests**
  - `queueFollowsRegionOrderAndName`: `ratingQueue(Fixtures.trip(), "daniel") == listOf("s-tempel", "s-garten", "s-nudel", "s-ufer", "s-markt")`.
  - `queueSkipsRatedAndClosed`: snapshot `.copy(votes = listOf(Vote(Votes.id("s-garten","daniel"),"s-garten","daniel",0), Vote(Votes.id("s-nudel","selina"),"s-nudel","selina",1)), savedPlaces = … s-markt with status VISITED)` → `listOf("s-tempel", "s-nudel", "s-ufer")`; `isRated(snap, "s-garten", "daniel")` true.
  - `distanceLabelFormats`: `0.853 → "850 m"`, `4.24 → "4,2 km"`, `37.6 → "38 km"`.
  - `dayTripUsesMainRegionStay`: for `s-ufer` (region r-see, day trip from r-berg): `stayDistance` regionName `"Bergstadt"`, km in 30.0..38.0; `centreDistance` regionName `"Seenland"`, km < 1.0 (only place in its region).
  - `ownRegionStayAndNoCentre`: for `s-tempel`: `stayDistance` regionName `"Bergstadt"`, km < 3.0; `centreDistance == null`.
  - `noStayNoLine`: snapshot with `stays = emptyList()` → `stayDistance(s-tempel) == null`.
  - `noCoordinatesNoDistance`: places with `p-ufer` at lat/lng 0.0 → both functions null for `s-ufer`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests "*PlaceRatingTest*"` → FAIL (unresolved references).
- [ ] **Step 3: Implement** the functions above in `PlaceRating.kt` (`Locale.GERMAN` for the decimal comma).
- [ ] **Step 4: Run** same → PASS.
- [ ] **Step 5: Commit** `feat(app): rating queue and stay distances`

### Task 3: PlaceRatingScreen

**Files:**
- Create: `app/src/main/kotlin/de/wanderlogplus/app/ui/places/PlaceRatingScreen.kt`
- Test: `app/src/test/kotlin/de/wanderlogplus/app/ui/places/PlaceRatingScreenTest.kt` (Robolectric, like VoteBarTest)

**Interfaces:**
- Consumes: Task 2 functions, `PlacePhotosRow(place)`, `RatingStrip(place, saved)`, `voteNames`, `regionColor`.
- Produces: `const val PLACE_RATING_ROUTE = "orte/bewerten"` and
  ```kotlin
  @Composable fun PlaceRatingScreen(snapshot: TripSnapshot, me: String, onVote: (savedPlaceId: String, value: Int) -> Unit,
      onOpenPlace: (String) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier)
  ```
- Behaviour: queue = `ratingQueue(snapshot, me)` frozen at first composition (`rememberSaveable`), index in `rememberSaveable`. Current = first entry from index on that still exists in `snapshot.savedPlaces` and `!isRated(snapshot, id, me)`. Card (scrollable, tag `rating-card-<id>`): photos, name, region · category, RatingStrip, `🏨 …` line (tag `rating-stay`), centre line (tag `rating-centre`), description, `worthReason`, `voteNames` of others, `Mehr` (tag `rating-more` → `onOpenPlace`). Bottom bar fixed: chips tags `rate-dislike|rate-neutral|rate-like|rate-double` → `onVote(id, -2|0|1|2)` then index advances; `Überspringen` (tag `rate-skip`) advances only; progress `i / n` (tag `rate-progress`, i = position+1, n = queue size). After the last: `Alles bewertet 🎉` (tag `rate-done`) and a button `Zurück zu den Orten` → `onDone`.

- [ ] **Step 1: Write failing tests**
  - `votesAndAdvances`: content with `Fixtures.trip()`, me `"daniel"`; `rating-card-s-tempel` shown, `rate-progress` text `1 / 5`; click `rate-like` → recorded `("s-tempel", 1)`, now `rating-card-s-garten`, `2 / 5`; click `rate-neutral` → `("s-garten", 0)`.
  - `skipDoesNotVote`: click `rate-skip` → no vote recorded, `rating-card-s-garten` shown.
  - `showsStayDistanceForDayTrip`: advance to s-ufer (skip ×3) → `rating-stay` text starts with `🏨 ` and ends with `zur Unterkunft (Bergstadt)`; `rating-centre` exists; on s-tempel `rating-centre` does not exist.
  - `skipsPlacesGoneOrRatedMeanwhile`: hold snapshot in `mutableStateOf`; after composition set it to a copy where `s-garten` is removed and daniel has voted on `s-nudel`; click `rate-like` on s-tempel → `rating-card-s-ufer` shown.
  - `endsWithDone`: 5 × `rate-skip` → `rate-done` exists; click `Zurück zu den Orten` → `onDone` called.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests "*PlaceRatingScreenTest*"` → FAIL.
- [ ] **Step 3: Implement** `PlaceRatingScreen`.
- [ ] **Step 4: Run** same → PASS.
- [ ] **Step 5: Commit** `feat(app): rating screen walks through unrated places`

### Task 4: Entry button, navigation, version

**Files:**
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/ui/places/PlacesScreen.kt` (params + button in the label row)
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/ui/AppNavigation.kt` (Tab.ORTE route set ~l.152, `PlacesScreen(...)` call ~l.786, new `composable(PLACE_RATING_ROUTE)` next to `PLACE_ROUTE`)
- Modify: `app/build.gradle.kts` (versionCode 96, versionName "0.75.0"), `CHANGELOG.md`
- Test: `app/src/test/kotlin/de/wanderlogplus/app/ui/places/PlacesScreenTest.kt`

**Interfaces:**
- Consumes: `PLACE_RATING_ROUTE`, `PlaceRatingScreen`, `ratingQueue`, `PlacesViewModel.me`, `PlacesViewModel.vote`.
- Produces: `PlacesScreen(..., me: String? = null, onRate: (() -> Unit)? = null)`; button tag `places-rate`, text `Bewerten (n)` with n = `ratingQueue(snapshot, me).size`; shown only when `me != null && onRate != null && n > 0`.

- [ ] **Step 1: Write failing tests** in PlacesScreenTest: `rateButtonShowsCount` (me `"daniel"`, Fixtures → text `Bewerten (5)`, click → callback); `noRateButtonWithoutMe` (me null → `places-rate` does not exist); `noRateButtonWhenAllRated` (votes for all five → does not exist).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests "*PlacesScreenTest*"` → FAIL.
- [ ] **Step 3: Implement** button; wire navigation: ORTE screen passes `me = vm.me.collectAsState().value`, `onRate = { nav.navigate(PLACE_RATING_ROUTE) }`; the rating composable uses the ORTE-scoped `PlacesViewModel` (same pattern as `PLACE_ROUTE`), `onVote = vm::vote`, `onOpenPlace = { nav.navigate(placeRoute(it)) }`, `onDone = { nav.popBackStack() }`; add `PLACE_RATING_ROUTE` to the ORTE route set. Version 0.75.0 / 96, CHANGELOG `## 0.75.0` (Bewerten-Modus, Neutral-Stimme, Entfernung zur Unterkunft).
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest` → all PASS; `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `feat(app): Bewerten button and route; 0.75.0`

### Task 5: Prominent card on Heute (before the trip)

**Files:**
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/ui/places/PlaceRating.kt` (summary function)
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/ui/today/TodayScreen.kt` (`TodayScreen` params, `Before` shows the card first under the countdown hero)
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/ui/AppNavigation.kt` (new param `myName: (suspend () -> String?)? = null`; Heute: `produceState` for me; `onRate = { nav.navigateToTab(Tab.ORTE); nav.navigate(PLACE_RATING_ROUTE) }`), `app/src/main/kotlin/de/wanderlogplus/app/ui/MainActivity.kt` (pass the same loader as the PlacesViewModel's `myName`, ~l.390)
- Test: `PlaceRatingTest.kt`, `app/src/test/kotlin/de/wanderlogplus/app/ui/today/TodayScreenTest.kt`

**Interfaces:**
- Consumes: `ratingQueue`, `isRated`, `regionTileOrder`, `PLACE_RATING_ROUTE`.
- Produces:
  - `data class RatingSummary(val open: Int, val total: Int, val byRegion: List<Pair<String, Int>>)` — total = places with a queue status; byRegion = region name → open count, in region order, only > 0, „Ohne Region“ last.
  - `fun ratingSummary(snapshot: TripSnapshot, me: String): RatingSummary`
  - `TodayScreen(..., me: String? = null, onRate: (() -> Unit)? = null)`
- Card (tag `today-rate`): title `Orte bewerten`, line `<open> von <total> Orten noch offen` (tag `today-rate-count`), progress bar (rated/total), up to 3 regions `Bergstadt 3 · Seenland 1 · Küstenort 1`, big `Button` `Jetzt bewerten` (tag `today-rate-start`) → `onRate`.

- [ ] **Step 1: Write failing tests**
  - PlaceRatingTest `summaryCountsPerRegion`: Fixtures, me `daniel`, vote on `s-garten` → `RatingSummary(4, 5, listOf("Bergstadt" to 2, "Seenland" to 1, "Küstenort" to 1))`.
  - TodayScreenTest `rateCardBeforeTripFarAway`: Fixtures, `now` = 60 days before `Fixtures.START`, me `daniel` → `today-rate-count` text `5 von 5 Orten noch offen`; click `today-rate-start` → callback.
  - `noRateCardCloseToTrip`: now = 29 days before → `today-rate` does not exist.
  - `noRateCardWithoutMeOrAllRated`: me null → absent; all five voted → absent.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests "*PlaceRatingTest*" --tests "*TodayScreenTest*"` → FAIL.
- [ ] **Step 3: Implement** summary, card and wiring.
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest` → all PASS; `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL. CHANGELOG 0.75.0 mentions the Heute card.
- [ ] **Step 5: Commit** `feat(app): rating card on Heute until a month before the trip`
