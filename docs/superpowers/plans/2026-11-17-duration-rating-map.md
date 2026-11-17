# Aufenthaltsdauer, Karte beim Bewerten, einklappbare Kartenliste – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dauer am Programmpunkt, Ortskarte im Bewerten-Screen, Liste der Vollbild-Ortskarte einklappbar.

**Architecture:** Nur App-Änderungen an vorhandenen Composables (`DayTimeline`, `PlaceRatingScreen`, `PlaceMapScreen`); Dauer aus `DayScheduleView`.

**Tech Stack:** Kotlin, Jetpack Compose, Robolectric-Tests.

**Spec:** `docs/superpowers/specs/2026-11-17-duration-rating-map-design.md`

## Global Constraints

- Dauertext: „45 Min“, „2 Std“, „1 Std 30 Min“ (Funktion `durationText(minutes: Int): String`).
- Version 0.77.0, versionCode 98, CHANGELOG-Abschnitt.
- Build: JDK 21 Adoptium, `./gradlew :app:testDebugUnitTest`.

## Review Focus

1. Ort ohne Koordinaten im Bewerten-Screen → keine Karte, kein Leerraum (Test in Task 2).
2. Vollbild-Karte mit nur einem Punkt (keine Liste) → keine Einklapp-Leiste (Test in Task 3).
3. Notiz in der Zeitleiste → keine Dauer (Test in Task 1).

---

### Task 1: Dauer am Programmpunkt

**Files:** Modify `app/src/main/kotlin/de/wanderlogplus/app/ui/design/DayTimeline.kt`; Test `app/src/test/kotlin/de/wanderlogplus/app/ui/design/DayTimelineTest.kt`

**Interfaces:** Produces `fun durationText(minutes: Int): String` (DayTimeline.kt).

- [ ] Step 1: Tests `durationTexts` (`45`→„45 Min“, `120`→„2 Std“, `90`→„1 Std 30 Min“) und `programCardShowsStayDuration`: Fixture 2027-04-02, Item i-3 mit `stayMinutes = 90` → Text „1 Std 30 Min“ (substring) unter `program-row`/`day-item-i-3`; eine Notiz hat keinen „Min“-/„Std“-Text.
- [ ] Step 2: `./gradlew :app:testDebugUnitTest --tests '*DayTimelineTest*'` → FAIL.
- [ ] Step 3: Untertitel der PHOTO-Karte um `durationText(stop.end - stop.start)` ergänzen (in der `listOfNotNull(...)`-Kette, nach Kategorie/Check-in).
- [ ] Step 4: gleicher Lauf → PASS. Step 5: Commit `feat(app): show the planned stay on program cards`.

### Task 2: Karte im Bewerten-Screen

**Files:** Modify `app/.../ui/places/PlaceRatingScreen.kt` (Param `onOpenMap: (savedPlaceId: String) -> Unit = {}`), `app/.../ui/AppNavigation.kt` (`onOpenMap = { id -> nav.navigate(placeMapRoute(id)) }`); Test `PlaceRatingScreenTest.kt`

- [ ] Step 1: Tests `mapUnderTheTextOpensTheFullMap`: Ort mit Koordinaten → `place-map-card` existiert, liegt unter dem Beschreibungstext; Klick auf `place-map-fullscreen` ruft `onOpenMap(savedId)`. `noMapWithoutCoordinates`: Ort mit lat/lng 0 → `place-map-card` existiert nicht.
- [ ] Step 2: Lauf `--tests '*PlaceRatingScreenTest*'` → FAIL.
- [ ] Step 3: In `RatingCard` nach KI-Text: `PlaceMapCard(remember(place, saved.dossier) { placeMapPoints(place, saved.dossier) }, accent, { onOpenMap(saved.id) })`; Akzent = Regionsfarbe wie in `PlaceMapScreen`.
- [ ] Step 4: PASS. Step 5: Commit `feat(app): map of the place and research spots when rating`.

### Task 3: Einklappbare Liste der Vollbild-Ortskarte; Version

**Files:** Modify `app/.../ui/places/PlaceMap.kt` (`PlaceMapScreen`), `app/build.gradle.kts`, `CHANGELOG.md`; Test `PlaceMapTest.kt`

**Interfaces:** Tags `place-map-list-toggle` (Leiste, Text „n Punkte“), Liste weiter `place-map-points`.

- [ ] Step 1: Tests `listCollapsesAndExpands`: Toggle-Klick → `place-map-points` weg, Toggle bleibt; nochmal → wieder da. `noToggleForASinglePoint`: Ort ohne Recherche-Punkte → kein `place-map-list-toggle`.
- [ ] Step 2: Lauf `--tests '*PlaceMapTest*'` → FAIL.
- [ ] Step 3: `var listOpen by rememberSaveable(savedPlaceId) { mutableStateOf(true) }`; Leiste (Row, clickable, Icon ExpandMore/ExpandLess) über der Liste, schmal unten mit Gewicht 0.38 nur wenn offen (sonst Karte weight 1 + Leiste); breit rechts: Spalte mit Leiste oben, Liste darunter nur wenn offen, Spalte zu → nur schmale Leiste (Breite wrap) neben der Karte.
- [ ] Step 4: PASS; Version 0.77.0/98 + CHANGELOG; Gesamtlauf `:app:testDebugUnitTest` → PASS. Step 5: Commit `feat(app): collapsible point list on the place map; 0.77.0`.
