# Plan 5 – UI-Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die App nach der Spec optisch neu aufbauen, also mit Bild-Köpfen, Foto-Karten, Zeitleiste, Tabs und Bottom-Sheets. Dazu kommen Routenkarten über OpenStreetMap/MapLibre statt Google Maps und Screenshot-Tests zur Abnahme.

**Architecture:** Gemeinsame Bausteine liegen in `app/.../ui/design/`, und jeder Screen wird aus ihnen neu zusammengesetzt. Datenfluss, ViewModels, Routen und Schreibpfade bleiben, wie sie sind. Karten liegen in `app/.../maps/`: Ein `RouteMapRenderer` erzeugt per MapLibre-Snapshotter Bitmaps und legt sie im Cache ab, eine `RegionMapView` ist eine interaktive MapView. Die Screenshot-Tests nutzen Roborazzi mit Robolectric.

**Tech Stack:** Compose BOM 2024.12.01 und Material 3, Coil 2.7.0. Neu kommen MapLibre Android SDK `org.maplibre.gl:android-sdk:11.8.0` und Roborazzi `io.github.takahirom.roborazzi` (Plugin, `roborazzi`, `roborazzi-compose`, `roborazzi-junit-rule`, Version 1.39.0) hinzu. Weg fallen `maps-compose` und `play-services-maps`.

**Spec:** `docs/superpowers/specs/2026-11-01-ui-redesign-design.md`. Funktionsumfang laut `docs/superpowers/specs/2026-10-31-wanderlog-plus-design.md`.

## Global Constraints

- Alle sichtbaren Texte bleiben auf Deutsch. Keine Funktion darf verloren gehen: Jede bisherige Aktion muss im neuen Layout erreichbar bleiben, also Bearbeiten, Anhänge, Links, Status, Vorschläge, Sortieren, Konflikte, Import, Einstellungen und Teilen-Ziel.
- Datenmodell, ViewModels, `AppContainer.edit`, Sync und Server bleiben unverändert. Erlaubt sind nur neue Parameter oder Callbacks an Composables und kleine reine Helfer.
- Buchungsdaten (Bestätigungsnummer, Reisende) erscheinen nur auf den Detailseiten, nie in Listen, Karten-Zusammenfassungen, Logs oder Notifications.
- Theme: ein warmes Orange-Schema, hell und dunkel. `dynamicColor` ist standardmäßig `false`, weil Android 12+ sonst die eigene Gestaltung überschreibt. Für Titel im Bild-Kopf gilt `FontFamily.Serif`.
- Bilder: `https://itin-dev.wanderlogstatic.com/freeImageMedium/<imageKey>` über Coil. Fehlt ein Bild oder schlägt das Laden fehl, gibt es einen Verlauf in Regionsfarbe bzw. Akzentfarbe, nie einen Fehler und nie ein leeres Loch.
- Karten: Stil `https://tiles.openfreemap.org/styles/liberty`, ohne Key, mit sichtbarer Attribution „© OpenStreetMap“. MapLibre-Code wird in Robolectric-Tests nie initialisiert; Tests sehen die stilisierte Ersatzgrafik.
- Bestehende Test-Tags bleiben erhalten, wo das Element weiter existiert. Wird ein Test durch das neue Layout obsolet, wird er angepasst und nicht gelöscht, außer das getestete Element entfällt laut Spec. Das wird im Report begründet.
- Tests laufen mit JUnit 4 und Robolectric, es gibt kein Gerät. `JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.17.10-hotspot"`. Abschluss jedes Tasks: `./gradlew test :app:assembleDebug` grün.
- Screenshots aus echten Daten (`testdata/private`) bleiben unter `app/build/` und werden nie committet. Committete Referenzbilder gibt es nicht; Roborazzi läuft im Modus „record“ nur auf Anfrage.

## Review Focus

1. **Sehr lange Namen** (z. B. „Hotel Gion Shinmonzen Kyoto Higashiyama Annex“), lange Notizen und viele Programmpunkte: Erwartet wird kein Überlauf, Text wird nach 2 Zeilen mit Ellipse gekürzt, und Listen scrollen. Abgedeckt durch Screenshot-Fixtures mit langen Texten (Task 1, 8).
2. **Dunkelmodus:** Text auf Bild-Köpfen bleibt lesbar (Verlauf), Karten heben sich ab. Abgedeckt durch Dark-Screenshots aller Screens (Task 8).
3. **Ohne Netz** (keine Fotos, keine Kacheln): Alle Karten zeigen Verläufe bzw. die stilisierte Routengrafik, das Layout springt nicht. Abgedeckt durch den Test `fallbacksWithoutImages` (Task 1) und `RouteMapFallbackTest` (Task 2).
4. **Tage ohne Programm, Regionen ohne Fotos, Fahrten ohne Koordinaten:** Es gibt sinnvolle Leerzustände statt leerer Flächen. Tests in den Tasks 3, 4 und 6.
5. **Rotation bzw. Prozess-Neustart im Tagesdetail:** Der gewählte Tab bleibt erhalten (`rememberSaveable`). Test in Task 4.

---

### Task 1: Theme, Design-Bausteine, Bild-Helfer, Roborazzi-Grundgerüst

**Files:**
- Modify: `app/.../ui/Theme.kt`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `build.gradle.kts` (Plugin, falls nötig)
- Create:
  - in `app/.../ui/design/`: `HeroHeader.kt`, `PhotoCard.kt`, `Timeline.kt`, `Bits.kt` (SectionLabel, Badge, ActionCircle, EmptyHint), `EditSheet.kt`, `Images.kt`
  - `app/src/test/.../ui/design/DesignGalleryScreenshotTest.kt`, `app/src/test/.../ui/design/ImagesTest.kt`, `app/src/test/.../testing/Fixtures.kt`

**Interfaces (Produces):**
- `WanderlogPlusTheme(darkTheme: Boolean = isSystemInDarkTheme(), dynamicColor: Boolean = false, content)`:
  - Warmes Schema hell: primary `0xFFA5520F`, onPrimary weiß, primaryContainer `0xFFFFDCC0`, onPrimaryContainer `0xFF341100`, secondary `0xFF755846`, tertiary `0xFF5F6135`, background und surface `0xFFFFF8F4`.
  - Dunkel: primary `0xFFFFB77C`, primaryContainer `0xFF7F3D00`, background und surface `0xFF1A120C`.
  - Typografie: Material-Standard. Dazu `val HeroTitle: TextStyle`, Serif, 28 sp.
- `fun placeImageUrl(imageKey: String): String` und `fun Place.heroImageUrl(): String?` (erster nicht leerer Key, sonst null).
- `fun dayHeroImage(snapshot: TripSnapshot, date: String): String?`: Foto des ersten PLACE-Eintrags im Programm des Tages, sonst null. `fun regionHeroImage(snapshot, regionId): String?`: Foto des bestbewerteten Orts der Region, sonst null.
- `@Composable fun HeroHeader(title: String, kicker: String?, imageUrl: String?, accent: Color, height: Dp = 170.dp, onBack: (() -> Unit)? = null, onEdit: (() -> Unit)? = null, chips: List<String> = emptyList(), modifier)`. Tags: `hero`, `hero-back`, `hero-edit`.
- `@Composable fun PhotoCard(title: String, subtitle: String?, imageUrl: String?, placeholderSymbol: String, accent: Color, style: PhotoCardStyle = PhotoCardStyle.Compact, badges: List<BadgeSpec> = emptyList(), onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null, modifier)`:
  - `enum PhotoCardStyle { Compact, Large }`. Compact hat ein 56 dp großes Thumbnail links, Large einen 140 dp hohen Bildstreifen oben.
  - Titel höchstens 2 Zeilen mit Ellipse, Untertitel 1 Zeile.
- `data class BadgeSpec(val text: String, val tone: BadgeTone)` mit `enum BadgeTone { Neutral, Positive, Warning, Accent }`.
- `@Composable fun Timeline(content: TimelineScope.() -> Unit)` mit `fun TimelineScope.item(marker: String, key: Any? = null, content: @Composable () -> Unit)`:
  - Linie mit Punkt in Akzentfarbe, Marke über dem Inhalt.
  - Implementierung ist eine `Column`, kein Lazy, weil die Zeitleisten kurz sind. Steht sie in einem scrollenden Elternteil, ist das in Ordnung.
- `@Composable fun SectionLabel(text: String)`, `Badge(spec: BadgeSpec)`, `ActionCircle(icon: ImageVector, label: String, onClick: () -> Unit, tag: String)` und `EmptyHint(text: String)`.
- `@Composable fun EditSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit)`: `ModalBottomSheet`, Tag `edit-sheet`.
- `Fixtures.kt` (Test): `object Fixtures { fun trip(longTexts: Boolean = false): TripSnapshot }`. Das ist eine anonymisierte Reise mit 3 Regionen, 6 Tagen, Orten mit imageKeys, Fahrten mit Endpunkt-Orten mit Koordinaten, Unterkünften und Notizen. Sie enthält keine echten Daten.
- `fun Fixtures.realOrNull(): TripSnapshot?` lädt `testdata/private/wanderlog-real.json` über den Importer, wenn die Datei vorhanden ist. Wie die bestehenden Real-Data-Tests das machen, steht in `client/src/test` (RealDataClientTest).

- [ ] **Step 1: Failing tests**
  - `ImagesTest`:
    - `placeImageUrlUsesMediumBase`.
    - `heroImageSkipsBlankKeys`.
    - `dayHeroImageUsesFirstPlaceItem`.
    - `regionHeroImageUsesBestRated`.
    - `nullWithoutImages`.
  - `DesignGalleryScreenshotTest`: rendert mit `captureRoboImage("build/outputs/roborazzi/design-gallery-{light,dark}.png")` eine Galerie mit HeroHeader (ohne Bild), PhotoCard (Compact und Large, langer Titel), Timeline mit 3 Einträgen, Badges, ActionCircles, SectionLabel und EmptyHint.
    - `@GraphicsMode(GraphicsMode.Mode.NATIVE)` und `@Config(qualifiers = "w411dp-h891dp-xxhdpi")`.
    - `fallbacksWithoutImages` prüft, dass bei `imageUrl = null` ein Node mit Tag `hero-fallback` bzw. `photo-fallback` existiert.
- [ ] **Step 2:** Tests ausführen; sie müssen fehlschlagen.
- [ ] **Step 3:** Implementieren.
  - Roborazzi einrichten: Plugin und Abhängigkeiten. `roborazzi.outputDir` zeigt auf `build/outputs/roborazzi`. Die Screenshot-Tests nehmen nur auf, sie vergleichen nicht; `captureRoboImage` schreibt immer.
  - Der Theme-Wechsel betrifft die ganze App. Hängen bestehende Tests an `dynamicColor`, werden sie angepasst.
- [ ] **Step 4:** `./gradlew test :app:assembleDebug` muss grün sein. Danach liegen die PNGs unter `app/build/outputs/roborazzi/`.
- [ ] **Step 5:** Commit `feat(app): warm theme, design components (hero, photo cards, timeline, sheets), screenshot setup`

### Task 2: Karten – MapLibre statt Google Maps, Routenkarten mit Cache

**Files:**
- Create: `app/.../maps/RouteGeometry.kt`, `app/.../maps/RouteMapRenderer.kt`, `app/.../maps/RouteMap.kt`, `app/.../maps/RegionMapView.kt`
- Modify:
  - `app/.../ui/places/RegionMap.kt` (Marker-Logik bleibt, die Anzeige läuft über `RegionMapView`) und `RegionPlacesScreen.kt` (Umschalter immer sichtbar, `mapAvailable` entfällt)
  - `app/build.gradle.kts` (Google-Maps-Abhängigkeiten, `MAPS_API_KEY`-Platzhalter und `HAS_MAPS_KEY` entfernen), `AndroidManifest.xml` (Geo-API-Key-Meta-Data entfernen), `libs.versions.toml`, `README.md` (Abschnitt „Karte“ neu)
- Test: `app/src/test/.../maps/RouteGeometryTest.kt`, `RouteMapFallbackTest.kt`, `RouteMapCacheTest.kt`; `PlacesScreenTest` anpassen

**Interfaces (Produces):**
- `data class LatLngD(val lat: Double, val lng: Double)`
- `data class RouteGeometry(val from: LatLngD, val to: LatLngD, val arc: Boolean)`:
  - `fun points(): List<LatLngD>`: Für `arc = false` sind es die 2 Punkte. Für `arc = true` sind es 32 Punkte auf einem quadratischen Bézier-Bogen, mit Kontrollpunkt am Mittelpunkt, senkrecht um 20 % der Distanz versetzt.
  - `fun cacheKey(widthPx: Int, heightPx: Int): String`: SHA-256 hex aus den auf 5 Nachkommastellen gerundeten Koordinaten, `arc`, Breite und Höhe.
- `fun routeFor(flight: Flight, snapshot: TripSnapshot): RouteGeometry?` und `fun routeFor(train: Train, snapshot: TripSnapshot): RouteGeometry?`:
  - Die Koordinaten kommen aus `snapshot.places[endpoint.placeId]`.
  - Fehlen sie oder sind sie (0,0), ist das Ergebnis null.
  - Flug ergibt `arc = true`, Zug `arc = false`.
- `class RouteMapRenderer(context: Context, cacheDir: File = File(context.cacheDir, "route-maps"))` mit `suspend fun render(route: RouteGeometry, widthPx: Int, heightPx: Int): File?`:
  - Ist die Datei schon im Cache, wird sie direkt zurückgegeben.
  - Sonst rendert `org.maplibre.android.snapshotter.MapSnapshotter` mit dem Stil-URL, der Kamera auf die Bounds mit Padding 48 px und einer `LineLayer` bzw. `GeoJsonSource` für Route, Start- und Zielpunkt. Die PNG wird atomar gespeichert (tmp, dann rename).
  - Jeder Fehler ergibt null (kein Netz o. Ä.), ohne Log von Koordinaten.
  - `MapLibre.getInstance(context)` wird erst hier lazy aufgerufen, nie im `Application.onCreate`.
- `@Composable fun RouteMap(route: RouteGeometry?, accent: Color, height: Dp = 120.dp, renderer: RouteMapRenderer? = LocalRouteMapRenderer.current, modifier)`:
  - Zeigt die gecachte bzw. gerenderte PNG über Coil aus der Datei.
  - Solange sie fehlt, ohne Renderer (Tests) oder bei null zeigt sie `RouteMapFallback`: Verlauf in der Akzentfarbe, zwei Punkte und eine Linie bzw. ein Bogen auf einem Canvas. Tag `route-fallback`.
  - Für `route == null` wird nichts gerendert.
- `val LocalRouteMapRenderer = staticCompositionLocalOf<RouteMapRenderer?> { null }`. `MainActivity` stellt einen Renderer bereit, Tests nicht.
- `@Composable fun RegionMapView(markers: List<MapMarker>, onOpenPlace: (String) -> Unit, modifier)`:
  - `AndroidView` mit `org.maplibre.android.maps.MapView` und Lifecycle-Weiterleitung über `LocalLifecycleOwner`.
  - Marker als Symbole bzw. Kreise in Statusfarben, ein Tipp auf einen Marker ruft `onOpenPlace` auf.
  - Die Kamera passt auf die Bounds.
  - Wird nur komponiert, wenn `LocalMapsEnabled.current == true`. Das ist true in `MainActivity` und false als Default, damit Tests nie MapLibre laden. Ohne Maps zeigt der Kartenmodus den Hinweis „Karte nicht verfügbar“.
- Ruling: Google Maps fällt ganz weg (Spec §5), ein Key wird nicht mehr gebraucht.

- [ ] **Step 1: Failing tests**
  - `RouteGeometryTest`:
    - `trainIsStraight`.
    - `flightArcHas32PointsAndBulges`: Der Mittelpunkt des Bogens weicht von der Geraden ab.
    - `cacheKeyStableAndSizeSensitive`.
    - `routeForMissingCoordsIsNull`.
    - `routeForUsesEndpointPlaces`.
  - `RouteMapFallbackTest.showsFallbackWithoutRenderer`: Tag `route-fallback` existiert, und bei `route = null` wird nichts gerendert.
  - `RouteMapCacheTest.cachedFileIsReturnedWithoutRendering`: Eine vorab in `cacheDir` gelegte Datei mit passendem Key wird zurückgegeben. Der Renderer ist so geschnitten, dass das ohne MapLibre-Initialisierung testbar ist; der Cache-Lookup steht vor dem Initialisieren.
  - `PlacesScreenTest`: Der Umschalter `view-map` ist immer sichtbar. Ein Klick darauf zeigt in Tests „Karte nicht verfügbar“.
- [ ] **Step 2:** Tests ausführen; sie müssen fehlschlagen.
- [ ] **Step 3:** Implementieren.
  - MapLibre-Version 11.8.0. Löst sie nicht auf, wird die nächstliegende 11.x genommen und im Report vermerkt.
  - Die Größenänderung der APK wird im Report vermerkt.
- [ ] **Step 4:** `./gradlew test :app:assembleDebug` muss grün sein.
- [ ] **Step 5:** Commit `feat(app): OpenStreetMap maps via MapLibre (route snapshots with cache, region map); drop Google Maps`

### Task 3: Heute

**Files:** Modify `app/.../ui/today/TodayScreen.kt`. Test: `TodayScreenTest.kt`, `TicketQuickAccessTest.kt` (anpassen), `app/src/test/.../ui/today/TodayScreenshotTest.kt`.

**Interfaces:**
- Consumes: Bausteine aus Task 1, `RouteMap` und `routeFor` aus Task 2.
- Die Signatur von `TodayScreen` bleibt. Alle bisherigen Callbacks sind erlaubt, neue nur mit Defaults.

**Layout:**
- **Während der Reise:**
  - `HeroHeader`: Titel ist der Regionsname oder der Tagestitel, Kicker „Do · 20. Mai · Tag 13“, Bild `dayHeroImage`, Akzent die Regionsfarbe.
  - Abschnitt „Unterkunft“ als `PhotoCard` Compact mit Hotelfoto, „Check-in/Check-out …“ und Navigations-Aktion als trailing.
  - Abschnitt „Tagesablauf“ als `Timeline`:
    - Fahrten zuerst nach Uhrzeit, mit Marke = Uhrzeit, Titel „Nara → Kyoto“, Unterzeile „09:12 – 09:58 · Carrier“, `RouteMap` (120 dp) und Ticket-Chips wie bisher (Tags `ticket-<id>` bleiben).
    - Dann die Programmpunkte mit Marke = Nummer: Orte als `PhotoCard` Large (Bildstreifen) mit Badge „offen“ (Positive), wenn jetzt geöffnet; Notizen als Textkarte.
    - Zuletzt die Unterkunft als Marke „Nacht“.
  - Abschnitt „Jetzt geöffnet“ mit kompakten `PhotoCard`s, höchstens 5.
  - Leerzustand ohne Programm: `EmptyHint("Für heute ist noch nichts geplant")` mit Knopf „Tag öffnen“.
- **Vor der Reise:** Kopf (Verlauf, kein Bild) mit großer Zahl „n Tage“ und Text „bis Japan“. Darunter „Anstehende Fristen“ als Karten mit Warning-Badge.
- Unknown bzw. Empty bleibt wie bisher.

- [ ] **Step 1: Failing tests**
  - `TodayScreenTest`: Die bestehenden Assertions werden auf das neue Layout angepasst, Inhalte und Callbacks bleiben. Neu: `timelineOrdersMovesThenPlaces`, `emptyProgramShowsHint`, `beforeTripShowsCountdownAndDeadlines`.
  - `TodayScreenshotTest`: Fixture während der Reise, vor der Reise und mit langen Texten, jeweils hell und dunkel. Ist echte Daten vorhanden, kommt `today-real-{light,dark}.png` für den 2027-05-20 dazu.
- [ ] **Step 2:** Tests ausführen; sie müssen fehlschlagen.
- [ ] **Step 3:** Implementieren.
- [ ] **Step 4:** Grün.
- [ ] **Step 5:** Commit `feat(app): redesigned Heute (hero, timeline with route maps and photos)`

### Task 4: Reise-Liste und Tagesdetail mit Tabs

**Files:** Modify `app/.../ui/trip/TripScreen.kt`, `DayDetailScreen.kt` (darf in `DayProgramTab.kt`, `DaySuggestionsTab.kt`, `DayNotesTab.kt` aufgeteilt werden), `AppNavigation.kt` (nur Parameter). Test: `DayDetailTest.kt`, `DayDetailEditTest.kt` (anpassen), `TripScreenshotTest.kt`.

**Layout:**
- **Reise:** Ein Tag ist eine Karte mit 6 dp Regionsfarbstreifen links, Datum („Do 20.05.“), Titel bzw. Regionsname, rechts einem 56 dp Thumbnail (`dayHeroImage`) und der Unterzeile „3 geplant · 🚄“ (Fahrten des Tages als Symbol). Der heutige Tag ist hervorgehoben (primaryContainer).
- **Tagesdetail:**
  - `HeroHeader` (140 dp) mit ✎. Das öffnet ein `EditSheet` „Tag bearbeiten“ mit Titel-Textfeld, Regions-Dropdown (wie bisher) und Speichern. Die bisherigen Tags `day-title-edit` und Region-Dropdown-Tags wandern in das Sheet.
  - Darunter eine `TabRow` mit den Tabs „Programm“, „Vorschläge (n)“ und „Notizen & Dateien“. Tags: `tab-program`, `tab-suggestions`, `tab-notes`. Der gewählte Tab steckt in `rememberSaveable(date)`.
  - **Programm:**
    - `Timeline` aus den Fahrten des Tages (Marke Uhrzeit, mit RouteMap), den Programmpunkten (Marke Nummer, Orte als `PhotoCard` Large bzw. Notizen als Textkarte) und der Unterkunft (Marke „Nacht“).
    - Jeder Programmpunkt hat ein ⋮-`DropdownMenu` mit „Nach oben“, „Nach unten“ und „Entfernen“, das die bestehenden Callbacks aufruft. Die bisherigen Tags der Move- und Remove-Buttons werden zu den Menüeinträgen; jeder Menüeintrag trägt den Tag, den vorher der entsprechende Button hatte.
    - FAB „＋ Hinzufügen“ (Tag `day-add`) öffnet ein Sheet mit „Ort aus Vorschlägen“ (wechselt zum Tab Vorschläge) und „Notiz“ (bisheriges Inline-Notizfeld im Sheet).
    - Leerzustand: „Noch nichts geplant – füge Orte aus den Vorschlägen hinzu.“
  - **Vorschläge:** Filter-Chips wie bisher (Kategorie, Wochentag), darunter kompakte `PhotoCard`s mit Bewertung und „geöffnet“-Badge und einem ＋-IconButton als trailing (bisheriger Callback und Tag).
  - **Notizen & Dateien:** Tagesnotiz (Markdown, Bearbeiten wie bisher), darunter `AttachmentsSection`.

- [ ] **Step 1: Failing tests**
  - `DayDetailTest` und `DayDetailEditTest` auf Tabs und Sheets anpassen: erst Tab bzw. Sheet öffnen, dann wie bisher prüfen.
  - Neu:
    - `tabsSwitchContent`.
    - `selectedTabSurvivesRecreation`: `StateRestorationTester`.
    - `itemMenuMovesAndRemoves`.
    - `fabOffersPlaceAndNote`.
    - `emptyProgramHint`.
  - `TripScreenshotTest`: Reise-Liste und Tagesdetail mit allen drei Tabs, hell und dunkel, mit Fixture und optional echten Daten (2027-05-20).
- [ ] **Step 2:** Tests ausführen; sie müssen fehlschlagen.
- [ ] **Step 3:** Implementieren.
- [ ] **Step 4:** Grün.
- [ ] **Step 5:** Commit `feat(app): redesigned trip list and tabbed day detail`

### Task 5: Orte, Region und Ortsdetail

**Files:** Modify `app/.../ui/places/PlacesScreen.kt`, `RegionPlacesScreen.kt`, `PlaceDetailScreen.kt`. Test: `PlacesScreenTest.kt`, `PlaceDetailTest.kt`, `PlaceEditTest.kt` (anpassen), `PlacesScreenshotTest.kt`.

**Layout:**
- **Orte:**
  - Regionen als 2-spaltiges Raster großer Bildkacheln (Höhe 140 dp, `regionHeroImage`, sonst Regionsfarb-Verlauf).
  - Auf der Kachel: Name (Serif) und „n Orte“.
  - Tagesausflugs-Regionen stehen unter ihrer Hauptregion mit dem Hinweis „Tagesausflug“.
  - Orte ohne Region stehen in einer Kachel „Ohne Region“.
- **Region:**
  - `HeroHeader` (140 dp).
  - Kategorie-Filter-Chips („Alle“, Sehenswürdigkeiten, Essen, Shopping, Nightlife, Sonstiges) und Umschalter „Liste | Karte“.
  - Liste aus kompakten `PhotoCard`s mit Status-Punkt (Farbe nach Status) und Bewertung.
- **Ortsdetail:**
  - `HeroHeader` (Foto) mit ✎.
  - Darunter Bewertung, Kategorie und Status-Chip-Reihe (FilterChips wie bisher).
  - Reihe aus `ActionCircle`s (bisherige Links, Tags `link-<KIND>` bleiben).
  - Tabs „Info“ (Beschreibung, Öffnungszeiten als kompakte Tabelle mit hervorgehobenem heutigem Wochentag, Dauer, Adresse), „Notiz“ und „Dateien“.
  - ✎ öffnet ein `EditSheet` mit Tags ⭐/⚠️, Region und Kategorie (bisherige Logik und Tags).

- [ ] **Step 1: Failing tests**
  - Bestehende Tests anpassen.
  - Neu:
    - `regionTilesShowCounts`.
    - `categoryFilterNarrowsList`.
    - `placeDetailTabs`.
    - `editSheetHoldsTagsRegionCategory`.
    - `todayHighlightedInHours`.
  - `PlacesScreenshotTest`: Orte, Region und Ortsdetail (Info-Tab), hell und dunkel, mit Fixture und optional echten Daten.
- [ ] **Step 2:** Tests ausführen; sie müssen fehlschlagen.
- [ ] **Step 3:** Implementieren.
- [ ] **Step 4:** Grün.
- [ ] **Step 5:** Commit `feat(app): redesigned places (region tiles, filters, tabbed place detail)`

### Task 6: Logistik mit Tabs und Detailseite

**Files:**
- Modify: `app/.../ui/logistics/LogisticsScreen.kt`, `AppNavigation.kt` (neue Route `LOGISTICS_DETAIL_ROUTE = "logistik/{type}/{id}"` unter Tab LOGISTIK)
- Create: `app/.../ui/logistics/LogisticsDetailScreen.kt`
- Test: `LogisticsScreenTest.kt` (anpassen), `LogisticsDetailTest.kt`, `LogisticsScreenshotTest.kt`

**Layout:**
- Tabs „Fahrten“ und „Unterkünfte“ (Tags `tab-moves`, `tab-stays`).
- **Fahrten:**
  - Gruppiert nach Datum mit Überschriften („Fr 08.05.“).
  - Pro Fahrt eine Karte mit `RouteMap` (100 dp) oben, Titel („Flug LH 716“ bzw. „Zug JR Nara Line“), „Frankfurt → Tokio Haneda“, Zeiten und Badge „📎 n“.
  - Keine Bestätigungsnummer, keine Reisenden.
- **Unterkünfte:** `PhotoCard` Large mit Hotelfoto, „3 Nächte · 17.–20.05.“ und Warning-Badge „Storno bis 10.05.“, wenn die Frist in der Zukunft liegt.
- Tipp öffnet `LogisticsDetailScreen`:
  - `HeroHeader` (Flug bzw. Zug: Verlauf mit Routenkarte darunter; Unterkunft: Foto).
  - Abschnitte „Details“ (Endpunkte, Zeiten, Bestätigungsnummer, Reisende, Stornofrist und -bedingungen), „Notiz“ (Markdown), „Navigation“ (nur Unterkunft: ActionCircles) und „Anhänge“ (`AttachmentsSection`).
  - Live-Status-Platzhalter „Live-Status: –“ bleibt.
- Leerzustände je Tab: „Keine Fahrten.“ und „Keine Unterkünfte.“

- [ ] **Step 1: Failing tests**
  - `LogisticsScreenTest`:
    - `tabsSeparateMovesAndStays`.
    - `listHidesConfirmationAndTravelers`: Die Fixture hat die Bestätigungsnummer „ABC123“ und Reisende „Max Muster“; beide kommen in der Liste nicht vor.
    - `groupedByDate`.
  - `LogisticsDetailTest`: `detailShowsConfirmationAndTravelers` und `detailShowsAttachmentsAndLinks`.
  - Bestehende Tests anpassen.
  - `LogisticsScreenshotTest`: beide Tabs und eine Detailseite, hell und dunkel.
- [ ] **Step 2:** Tests ausführen; sie müssen fehlschlagen.
- [ ] **Step 3:** Implementieren.
- [ ] **Step 4:** Grün.
- [ ] **Step 5:** Commit `feat(app): logistics split into trips and stays with detail page`

### Task 7: Mehr, Notizen, Einstellungen, Import, Konflikte, Setup, Teilen-Ziel

**Files:** Modify `app/.../ui/more/*.kt`, `app/.../ui/setup/SetupScreen.kt`, `app/.../ui/share/ShareTargetScreen.kt`, `app/.../ui/SyncStatusBar.kt`. Test: anpassen, plus `MoreScreenshotTest.kt`.

**Layout:**
- **Mehr:** `ListItem`s mit Icon-Kachel (Notizen, Import, Konflikte mit Badge-Zahl, Einstellungen) in Karten-Gruppen.
- **Notizen:** Karten mit Titel und 2-Zeilen-Vorschau.
- **Einstellungen:** Karten-Abschnitte „Server“, „Synchronisation“, „Zeitzone“ und „Erinnerungen“, Inhalte wie bisher.
- **Import:** Karte „Letzter Import“ mit Zahlen als kleine Kennzahl-Kacheln (neu, geändert, Konflikte, entfernt), darunter das Protokoll als Liste.
- **Konflikte:** pro Konflikt eine Karte mit „App“ und „Wanderlog“ nebeneinander und zwei Knöpfen.
- **Setup:** zentrierte Karte mit App-Name in Serif, Feldern und QR-Knopf, Warnung wie bisher.
- **Teilen-Ziel:** Gruppen als SectionLabel, Einträge als kompakte `PhotoCard`s (Orte und Unterkünfte mit Foto).
- **Sync-Statusleiste:** dezent als Chip statt Textzeile, Inhalte bleiben gleich.

- [ ] **Step 1: Failing tests:** Bestehende Tests anpassen. `MoreScreenshotTest` rendert Mehr, Einstellungen, Import, Konflikte, Setup und Teilen-Ziel, jeweils hell und dunkel.
- [ ] **Step 2:** Tests ausführen; sie müssen fehlschlagen.
- [ ] **Step 3:** Implementieren.
- [ ] **Step 4:** Grün.
- [ ] **Step 5:** Commit `feat(app): redesigned more, settings, import, conflicts, setup and share target`

### Task 8: Screenshot-Gesamtlauf, Feinschliff, Doku

**Files:** `app/src/test/.../ui/AllScreensScreenshotTest.kt` (optional als Sammel-Test), `README.md`, `docs/superpowers/followups-from-plan5.md`, `app/build.gradle.kts` (`versionCode = 3`, `versionName = "0.3.0"`).

- [ ] **Step 1:** Alle Screenshot-Tests laufen lassen und jedes PNG unter `app/build/outputs/roborazzi/` ansehen (Read-Tool). Prüfen auf Überlauf, schlechten Kontrast im Dunkelmodus, abgeschnittene Texte und uneinheitliche Abstände. Gefundene Probleme beheben.
- [ ] **Step 2:** Die README um einen Abschnitt „Screenshots erzeugen“ ergänzen: `./gradlew :app:testDebugUnitTest --tests '*Screenshot*'` legt PNGs nach `app/build/outputs/roborazzi/`; mit echten Daten enthalten sie Buchungsdaten und werden nicht geteilt.
- [ ] **Step 3:** `./gradlew test :app:assembleDebug` muss grün sein.
- [ ] **Step 4:** Commit `chore(app): screenshot pass fixes, docs, version 0.3.0`
