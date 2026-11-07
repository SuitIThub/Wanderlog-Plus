# ÖPNV: robuste erste Suche, Reise ändern, Start/Ziel – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** First transit search survives dropped connections without paying twice; trains know the place before/after their stations; a whole train journey can be re-searched and replaced.

**Architecture:** Server de-duplicates in-flight searches and treats results ≤10 min old as fresh; the app retries on network errors. `Train.walkFrom/walkTo` carry the places, filled from `TransitResult.fromPlace/toPlace`. `LocalEditor.replaceJourney` reuses `replaceRide`/`addRidesAfter`.

**Tech Stack:** Kotlin, Ktor, Compose, Robolectric, JUnit.

**Spec:** `docs/superpowers/specs/2026-11-07-transit-robust-journey-change-design.md`

## Global Constraints

- Gradle with `JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.8.9-hotspot"`; `-q`; task-done commands end with `&& echo PASS`.
- Versions: Task 1 → 0.54.2 (71), Task 2 → 0.55.0 (72), Task 3 → 0.56.0 (73); German CHANGELOG sections.
- New model fields at the end of classes, with defaults (sync compatibility).

## Review Focus

1. Two identical searches at once must not both call Apify; a failing first call must not leave the second hanging → Task 1 test.
2. Retrying after NETWORK must not retry other error kinds (no_route, no credit) → Task 1 test.
3. A connection picked with only stations (no place) must not set walkFrom/walkTo → Task 2 test.
4. replaceJourney on a journey containing a flight must not be offered → Task 3 test.
5. replaceJourney with fewer new rides than old legs deletes the surplus, more new rides adds them, order stays → Task 3 test.

---

### Task 1: Robust first search (0.54.2)

**Files:** `server/.../TransitService.kt`, `app/.../ui/transit/TransitViewModel.kt`; tests `server/.../TransitServiceTest.kt`, `app/.../ui/transit/TransitRetryTest.kt`.

**Interfaces:** `TransitService` gets `private val inFlight = ConcurrentHashMap<String, CompletableFuture<TransitResult>>()`; `FRESH_WINDOW = Duration.ofMinutes(10)`; `TransitViewModel(..., retryDelaysMillis: List<Long> = listOf(5_000, 10_000))`.

- [ ] Tests: `sameSearchRunsOnce` (two threads, source blocks on a latch, both get the same result, `inputs.size == 1`); `failedSearchReleasesWaiters` (source throws `TransitUnavailable`, both callers get it); `freshUsesResultOfTheLastTenMinutes` (fresh=true twice within 5 min → 1 run; after 11 min → 2 runs); app `retriesAfterNetworkError` (MockEngine: first call throws IOException, second answers) → result set, message null; `noRetryForOtherErrors` (404 no_route → 1 call, message „Keine Verbindung …“); `givesUpAfterThreeNetworkErrors` → „Server nicht erreichbar.“.
- [ ] Run → FAIL; implement; run → PASS; full server + app suites green.
- [ ] 0.54.2 / 71, CHANGELOG, commit `fix: transit search survives a dropped first connection without a second paid search; 0.54.2`.

### Task 2: Start and goal around the stations (0.55.0)

**Files:** `shared/.../model/Models.kt` (`Train.walkFrom`, `Train.walkTo`), `shared/.../sync/TransitProtocol.kt` (`TransitResult.fromPlace`, `toPlace`), `app/.../transit/TransitViewModel.kt` (set them on the result: `r.copy(fromPlace = form.fromPoint?.takeIf { it.name.isNotBlank() }, toPlace = …)`), `app/.../logistics/LogisticsEditScreen.kt` (`MoveDraft.walkFrom/walkTo`, `draftFromTransit`, fields, `draftChanges`), `client/.../LocalEditor.kt` (`addRidesAfter` keeps given walkTo on the last ride), `app/.../logistics/WalkEnds.kt` (new: `WalkEndRow`, `walkEstimate`), `LogisticsDetailScreen.kt`, `JourneyScreen.kt`, `app/.../transit/TransitReplace.kt` (`replaceSeed` uses walkFrom/walkTo).

**Interfaces:** `fun walkEstimate(km: Double): String?` → "ca. N Min. zu Fuß" for km ≤ 3 (minutes = ceil(km×1000×1.3/80)), else null; `@Composable fun WalkEndRow(snapshot, place: Endpoint, station: Endpoint, before: Boolean, tag: String)` (uses `LocalLegRepository` walk when available, else `walkEstimate`, else "(4,2 km)").
- `draftFromTransit(d, r)`: `walkFrom = r.fromPlace?.toEndpoint() ?: d.walkFrom`; the last ride gets `walkTo = r.toPlace?.toEndpoint()`: with one ride on the draft (`walkTo`), else on `extraRides.last()`.

- [ ] Tests: `pickedPlacesBecomeWalkEnds` (result with fromPlace/toPlace, two rides → draft.walkFrom = Hotel, extraRides.last().walkTo = Ryokan, draft.walkTo null); `stationsOnlyNoWalkEnds` (Review Focus 3); `viewModelSetsPlacesOnResult`; `walkEstimates` (0.95 km → "ca. 16 Min. zu Fuß", 4.2 → null); `detailShowsWalkRows` (train with walkFrom/walkTo → `walk-from`, `walk-to`); `formEditsWalkEnds` (draftChanges writes walkFrom from draft); `replaceSeedUsesWalkEnds`.
- [ ] Run → FAIL; implement; run → PASS; full suites green.
- [ ] 0.55.0 / 72, CHANGELOG, commit `feat: trains know the place before and after their stations (hotel → station … station → ryokan); 0.55.0`.

### Task 3: Change a whole journey (0.56.0)

**Files:** `client/.../LocalEditor.kt` (`replaceJourney`), `app/.../more/MoreViewModel.kt`, `app/.../logistics/JourneyScreen.kt` (button), `app/.../transit/TransitReplace.kt` (`journeyChangeSeed`, route `JOURNEY_CHANGE_ROUTE = "logistik/journey-change/{id}"`), `AppNavigation.kt`.

**Interfaces:** `LocalEditor.replaceJourney(snapshot, journeyId: String, rides: List<Train>)`: only when every leg is a train and rides not empty; first old leg ← `replaceRide` semantics for rides[0] (with `walkFrom` of rides[0] or kept), other old legs deleted (`deleted = true`), rides[1..] added after the first via `addRidesAfter` (journey id kept); `fun journeyChangeSeed(j: Journey, snapshot): ReplaceSeed + date/time`; `fun canChangeJourney(j: Journey): Boolean`.

- [ ] Tests: `replaceThreeByTwo` (first keeps booking, legs 2–3 deleted, new second added, order), `replaceOneByThree`, `flightJourneyNotChangeable` (Review Focus 4: `canChangeJourney` false, button absent), `seedFromWalkEnds` (start = walkFrom of first, goal = walkTo of last, date/time = first departure).
- [ ] Run → FAIL; implement; run → PASS; full suites green.
- [ ] 0.56.0 / 73, CHANGELOG, commit `feat: change a whole train journey to another connection; 0.56.0`.
