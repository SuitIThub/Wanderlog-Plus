# Google-Suche, Online-Fotos und Recherche-Warteschlange – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Place search and linking via Google Places, web photo search wherever photos are added, a serial research queue with higher per-job parallelism, and a region picker for full research from the places overview.

**Architecture:** One server building block (`GooglePlacesSearch`, Text Search with a full field mask and a 24 h cache) serves `/api/places/search` and the Google half of `/api/photos/search`. The app gets a `PlaceSearch` with Photon fallback, a `GoogleLinkRow`, a `WebPhotoSearchSheet` and an ordered research queue in `PlacesViewModel`. The server runs research jobs on their own single-thread lane.

**Tech Stack:** Kotlin, Ktor (server + client), kotlinx.serialization, Jetpack Compose, Robolectric, JUnit.

**Spec:** `docs/superpowers/specs/2026-11-06-google-search-photos-research-queue-design.md`

## Global Constraints

- Gradle: `JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.8.9-hotspot" ./gradlew …` (Bash).
- Versions: Task 2 → 0.47.0 (61), Task 3 → 0.48.0 (62), Task 5 → 0.49.0 (63), Task 7 → 0.50.0 (64), Task 8 → 0.51.0 (65); each with its own CHANGELOG section (German, like the existing ones). Server-only tasks are described in the next app version's section.
- All user-visible text German, fixed strings (never raw server data in error messages).
- Google key: `WLP_GOOGLE_PLACES_KEY` (falls back to `WLP_GOOGLE_ROUTES_KEY`, as `ServerConfig` already does).
- New endpoints are under `/api/…` with the existing `auth()`.
- Robolectric: no AlertDialog with text fields (OOM) → `EditSheet`; merged-tree finders need `useUnmergedTree = true`.
- Never print secrets from `server/.env`.

## Review Focus

1. Wikimedia media proxy must never fetch hosts other than `upload.wikimedia.org` (also via redirects or `..` in the file name) → test in Task 4.
2. Google answering 429/5xx or garbage JSON on search must give `502 places_search_unavailable`, never 500 → test in Task 1.
3. Two research jobs started a second apart: the app status line must show only the first, never alternate → test in Task 7.
4. A research queued for places that are already in a waiting job must not create a second job → test in Task 7.
5. The web photo search with one failing source (Wikimedia down) still returns the Google photos → test in Task 4.

---

### Task 1: Server – Google Text Search building block and `/api/places/search`

**Files:**
- Create: `server/src/main/kotlin/de/wanderlogplus/server/GooglePlacesSearch.kt`
- Create: `shared/src/main/kotlin/de/wanderlogplus/shared/sync/PlaceSearchProtocol.kt`
- Modify: `server/src/main/kotlin/de/wanderlogplus/server/ai/GooglePlacesGeocoder.kt` (`HttpPlacesSearchTransport(apiKey, fieldMask = <current mask>)`)
- Modify: `server/src/main/kotlin/de/wanderlogplus/server/Application.kt` (service + route + exception mapping)
- Test: `server/src/test/kotlin/de/wanderlogplus/server/GooglePlacesSearchTest.kt`, route test in `ApplicationTest.kt`

**Interfaces:**
- Produces (shared): `@Serializable data class GooglePlaceHit(val googlePlaceId: String, val name: String, val address: String = "", val lat: Double, val lng: Double, val types: List<String> = emptyList(), val rating: Double? = null, val ratingCount: Int? = null, val photos: List<PlacePhoto> = emptyList(), val category: PlaceCategory = PlaceCategory.OTHER)`; `@Serializable data class PlaceSearchHits(val hits: List<GooglePlaceHit>)`.
- Produces (shared): `object PlaceCategories { fun fromGoogle(types: List<String>): PlaceCategory }` in `shared/.../model/` (FOOD: restaurant, cafe, bakery, meal_takeaway, food, *_restaurant; NIGHTLIFE: bar, night_club, pub; SHOPPING: store, shopping_mall, market, *_store; SIGHT: tourist_attraction, museum, park, place_of_worship, hindu_temple, buddhist_temple, shinto_shrine, church, art_gallery, aquarium, zoo, amusement_park, historical_landmark, observation_deck; else OTHER; first matching type wins).
- Produces (server): `class PlacesSearchNotConfigured : RuntimeException`, `class PlacesSearchUnavailable : RuntimeException`; `class GooglePlacesSearch(private val transport: PlacesSearchTransport?, private val store: SqliteStore, private val now: () -> Instant = Instant::now) { fun search(q: String, near: Pair<Double, Double>?, bbox: BBox4?, limit: Int = 10): List<GooglePlaceHit> }` with `data class BBox4(val west: Double, val south: Double, val east: Double, val north: Double)`.
- Full field mask constant `SEARCH_FIELD_MASK = "places.id,places.displayName,places.formattedAddress,places.location,places.types,places.rating,places.userRatingCount,places.photos"`.
- Route: `GET /api/places/search?q=&near=lat,lng&bbox=w,s,e,n` → `PlaceSearchHits`; `q` trimmed 2–200 chars else `400 bad_query`; without transport `503 places_search_not_configured`; `PlacesSearchUnavailable` → `502 places_search_unavailable`.

- [ ] **Step 1: Write failing tests** (`GooglePlacesSearchTest`, fake `PlacesSearchTransport` recording bodies):
  - `biasWithCircleNearPoint`: `search("Ichiran", 35.0 to 135.7, null)` → body has `locationBias.circle.center.latitude == 35.0`, `radius == 30000.0`, `languageCode == "de"`, `pageSize == 10`.
  - `restrictToRectangle`: bbox `BBox4(135.6, 34.9, 135.8, 35.1)` → `locationRestriction.rectangle.low = {34.9, 135.6}`, `high = {35.1, 135.8}`, no `locationBias`.
  - `mapsHits`: fake answer with one place (id "g1", displayName.text "Ichiran", types ["ramen_restaurant","restaurant"], rating 4.3, userRatingCount 1200, photos [{name "places/g1/photos/p1", widthPx 800, heightPx 600}]) → hit with `category == FOOD`, `photos.single().name == "places/g1/photos/p1"`.
  - `cachesSameQuery`: two identical searches → transport called once; after `now` + 25 h → twice.
  - `googleErrorIsUnavailable`: status 429 → `PlacesSearchUnavailable`; status 200 with body `"nope"` → `PlacesSearchUnavailable`.
  - `noTransportIsNotConfigured` → `PlacesSearchNotConfigured`.
  - `PlaceCategories.fromGoogle(listOf("shinto_shrine"))` == SIGHT, `listOf("lodging")` == OTHER, `listOf("clothing_store")` == SHOPPING.
  - In `ApplicationTest`: `placesSearchWithoutKeyIs503` and `placesSearchShortQueryIs400` (`q=a`).
- [ ] **Step 2: Run** `./gradlew :server:test --tests '*GooglePlacesSearch*' --tests '*ApplicationTest*placesSearch*'` → FAIL (unresolved references).
- [ ] **Step 3: Implement.** Cache key `gsearch:` + SHA-256 of the request body, via `store.cachedRoute`/`putCachedRoute` with the stored time; TTL 24 h. Wire `GooglePlacesSearch(config.googlePlacesKey?.let { HttpPlacesSearchTransport(it, SEARCH_FIELD_MASK) }, store)` into the services next to `photos`.
- [ ] **Step 4: Run** the same command → PASS; then `./gradlew :server:test` → all green.
- [ ] **Step 5: Commit** `feat(server): Google text search for places (/api/places/search)`.

### Task 2: App – "Ort suchen" via Google with Photon fallback (0.47.0)

**Files:**
- Modify: `client/src/main/kotlin/de/wanderlogplus/client/GeoSearch.kt` (`GeoPlace.googlePlaceId: String? = null`, `GeoPlace.googleCategory: PlaceCategory? = null`; `category` returns `googleCategory` when set)
- Modify: `client/src/main/kotlin/de/wanderlogplus/client/SyncApi.kt` (`suspend fun searchPlaces(q: String, near: Pair<Double, Double>?, bbox: BBox?): List<GooglePlaceHit>`)
- Create: `client/src/main/kotlin/de/wanderlogplus/client/PlaceFinder.kt`
- Modify: `app/.../ui/AppNavigation.kt` (3 `geo.search` call sites + `placeSearch`), `app/.../ui/MainActivity.kt`, `app/.../ui/trip/PlaceSearchModel.kt` + `PlaceSearchScreen.kt` (hint), `app/.../ui/logistics/LogisticsEditScreen.kt` (`PlaceSearchPanel` debounce), `app/.../ui/trip/TripViewModel.kt` + `client/.../LocalEditor.kt` (`addPlaceToDay(..., googlePlaceId)`), `app/.../ui/places/PlacesViewModel.kt` (`addFoundPlace` passes it), `app/.../ui/logistics/StayEditScreen.kt` (`StayDraft.googlePlaceId`, put on the new Place in `stayToSave`), Reel "Lage finden" call site.
- Test: `client/src/test/kotlin/de/wanderlogplus/client/PlaceFinderTest.kt`, `app/src/test/.../trip/PlaceSearchGoogleTest.kt`

**Interfaces:**
- Consumes: Task 1 `GooglePlaceHit`, route.
- Produces: `data class PlaceFinderResult(val places: List<GeoPlace>, val viaGoogle: Boolean)`; `class PlaceFinder(private val google: (suspend (String, Pair<Double, Double>?, BBox?) -> List<GooglePlaceHit>)?, private val photon: GeoSearch) { suspend fun search(q: String, near: Pair<Double, Double>?, bbox: BBox?, limit: Int = 15): PlaceFinderResult }`. Google hits map to `GeoPlace(name, detail = address, lat, lng, googlePlaceId = id, googleCategory = category)`. Fallback to Photon when `google == null` or it throws `ApiException` (any kind) / `IOException`; an empty Google list is a real answer (no fallback).
- `PlaceSearchModel` gets `var viaGoogle by mutableStateOf(true)`; `AreaSearch` becomes `suspend (String, Pair<Double, Double>?, BBox?) -> PlaceFinderResult`. Hint tag `search-no-google`, text „Suche ohne Google (OpenStreetMap)“.
- `PlaceSearch` typealias keeps returning `List<GeoPlace>` (wraps `PlaceFinder.search(...).places`). `PlaceSearchPanel`: no request below 3 characters, debounce 600 ms.

- [ ] **Step 1: Failing tests.**
  - `PlaceFinderTest.googleFirst`: google returns one hit → `viaGoogle == true`, `places.single().googlePlaceId == "g1"`, `category == FOOD`.
  - `PlaceFinderTest.fallsBackOnError`: google throws `ApiException(NOT_CONFIGURED)` → Photon (MockEngine) used, `viaGoogle == false`.
  - `PlaceFinderTest.emptyGoogleIsAnswer`: google returns empty → `places.isEmpty()`, Photon not called, `viaGoogle == true`.
  - `PlaceSearchGoogleTest.hintWithoutGoogle`: model with a search returning `PlaceFinderResult(listOf(...), viaGoogle = false)`, submit → node `search-no-google` exists; with `true` → absent.
  - `PlaceSearchGoogleTest.addKeepsGoogleId`: `LocalEditor.addPlaceToDay(..., googlePlaceId = "g1")` → the new Place has `googlePlaceId == "g1"`.
  - `StayEdit`: `stayToSave(s, StayDraft(name = "Hotel", lat = 1.0, lng = 2.0, picked = true, googlePlaceId = "g2"), null, "p")` → `newPlace.googlePlaceId == "g2"`.
- [ ] **Step 2: Run** `./gradlew :client:test --tests '*PlaceFinder*' :app:testDebugUnitTest --tests '*PlaceSearchGoogle*' --tests '*StayEdit*'` → FAIL.
- [ ] **Step 3: Implement** per Interfaces; `AppContainer.placeFinder` built from `syncApi()` (null server → `google = null`).
- [ ] **Step 4: Run** the same → PASS; `./gradlew :client:test :app:testDebugUnitTest` → green (existing `PlaceSearchModel` tests adapted to `PlaceFinderResult`).
- [ ] **Step 5: Version 0.47.0 / 61, CHANGELOG, commit** `feat: place search via Google Places with OpenStreetMap fallback; 0.47.0`.

### Task 3: App – link a Google entry afterwards (0.48.0)

**Files:**
- Create: `app/src/main/kotlin/de/wanderlogplus/app/ui/places/GoogleLink.kt`
- Modify: `PlaceDetailScreen.kt`, `LogisticsDetailScreen.kt` (stay), `RegionPlacesScreen.kt` (`no-google-<id>` icon), `PlacesViewModel.kt`, `ui/more/MoreViewModel.kt`, `AppNavigation.kt`
- Test: `app/src/test/.../places/GoogleLinkTest.kt`

**Interfaces:**
- Consumes: `PlaceFinder` (Task 2) — the sheet uses `PlaceSearch`-like `suspend (q, near) -> List<GeoPlace>` restricted to Google hits: `googleSearch: (suspend (String, Pair<Double, Double>?) -> List<GeoPlace>)?` (null hides „Suchen“/„Ändern“; built only when the server answers, results filtered to `googlePlaceId != null`).
- Produces: `@Composable fun GoogleLinkRow(place: Place, details: GoogleDetailsLine?, onSearch: (() -> Unit)?, onOpenMaps: () -> Unit)`; `data class GoogleDetailsLine(val name: String, val address: String)` (null → „Verknüpft“); `@Composable fun GoogleLinkSheet(initialQuery: String, near: Pair<Double, Double>?, search: suspend (String, Pair<Double, Double>?) -> List<GeoPlace>, linked: Boolean, onPick: (GeoPlace, takePosition: Boolean) -> Unit, onUnlink: () -> Unit, onDismiss: () -> Unit)`; `fun distanceText(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): String` („350 m“, „1,2 km“); `fun googleLinkEdits(placeId: String, hit: GeoPlace?, takePosition: Boolean): (LocalEditor) -> Unit` (hit null → `googlePlaceId = ""`; else id, and with `takePosition` also `lat`/`lng`).
- VM: `PlacesViewModel.linkGoogle(placeId: String, hit: GeoPlace?, takePosition: Boolean)`, `MoreViewModel.linkGoogle(...)` same.
- Details line: linked and `place.address` not blank → `GoogleDetailsLine(place.name, place.address)`; linked otherwise → null („Verknüpft“). Picking a hit also writes its address into `Place.address` when the place has none. (Ruling vs. spec: no Google-details cache in the app; the place's own fields are enough.)
- Tags: `google-link`, `google-search`, `google-sheet`, `google-hit-<id>`, `google-take-position`, `google-unlink`.

- [ ] **Step 1: Failing tests** (`GoogleLinkTest`):
  - `unlinkedPlaceOffersSearch`: PlaceDetailScreen with a place `googlePlaceId = ""` and `googleSearch` set → text „Kein Google-Eintrag“, node `google-search` exists; with `googleSearch = null` → absent.
  - `pickLinksAndKeepsPosition`: `googleLinkEdits("p1", GeoPlace("X", "", 1.0, 2.0, googlePlaceId = "g9"), false)` on a LocalEditor → place `googlePlaceId == "g9"`, lat/lng unchanged; with `true` → lat 1.0, lng 2.0.
  - `unlinkClears`: `googleLinkEdits("p1", null, false)` → `googlePlaceId == ""`.
  - `distanceText(35.0, 135.0, 35.0, 135.004)` == "365 m" (below 1 km: whole metres, haversine), `distanceText(35.0, 135.0, 35.0108, 135.0)` == "1,2 km" (one decimal, comma).
  - `sheetShowsHitsWithDistance`: sheet with fake search returning one hit → `google-hit-g9` exists and text containing " km" or " m".
  - `regionListMarksMissingGoogle`: RegionPlacesScreen fixture with one unlinked place → `no-google-<id>` exists (`useUnmergedTree = true`).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*GoogleLink*'` → FAIL.
- [ ] **Step 3: Implement**; stay page uses the stay's Place.
- [ ] **Step 4: Run** → PASS; full `:app:testDebugUnitTest` green.
- [ ] **Step 5: 0.48.0 / 62, CHANGELOG, commit** `feat(app): link a Google entry to places and stays; 0.48.0`.

### Task 4: Server – web photo search and media proxy

**Files:**
- Create: `server/src/main/kotlin/de/wanderlogplus/server/WebPhotosService.kt`
- Modify: `shared/.../sync/PlaceSearchProtocol.kt` (photo types), `Application.kt`
- Test: `server/src/test/kotlin/de/wanderlogplus/server/WebPhotosServiceTest.kt`

**Interfaces:**
- Consumes: `GooglePlacesSearch.search` (Task 1), `PlacePhotosService.media(name, w)` (existing).
- Produces (shared): `enum class WebPhotoSource { GOOGLE, WIKIMEDIA }`; `@Serializable data class WebPhoto(val id: String, val source: WebPhotoSource, val title: String = "", val credit: String = "", val width: Int = 0, val height: Int = 0)`; `@Serializable data class WebPhotos(val photos: List<WebPhoto>)`. Ids: `g:<photo name>` / `w:<File title without "File:">`.
- Produces (server): `fun interface WikimediaTransport { fun get(url: String): Pair<Int, ByteArray> }`; `class WebPhotosService(private val google: GooglePlacesSearch, private val googleMedia: (String, Int) -> java.nio.file.Path, private val wiki: WikimediaTransport) { fun search(q: String, near: Pair<Double, Double>?): List<WebPhoto>; fun media(id: String, width: Int): ByteArray }`.
- Rules: Google: first 5 hits × up to 4 photos, title = place name, credit "Google"; Wikimedia API URL `https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=12&gsrsearch=<q> filetype:bitmap&prop=imageinfo&iiprop=url|size|mime|extmetadata&iiurlwidth=1600`; only mime image/jpeg|png|webp; credit = `Artist` (HTML tags stripped) + " · " + `LicenseShortName`. Total ≤ 30, Google first. A source that throws contributes nothing. Wikimedia media: resolve via `imageinfo` (`iiurlwidth=<w>`) and fetch `thumburl` only if its host is exactly `upload.wikimedia.org` and scheme https, else `PhotosUnavailable`; body > 15 MB → `PhotosUnavailable`. `width` clamped 100–1600. User-Agent `WanderlogPlus/1.0 (private trip planner)`.
- Routes: `GET /api/photos/search?q=&near=` → `WebPhotos` (q 2–200 else 400); `GET /api/photos/media?id=&w=` → `image/jpeg` (or the upstream mime) bytes; unknown id prefix → `400 bad_id`.

- [ ] **Step 1: Failing tests** (`WebPhotosServiceTest`, fake transports):
  - `mixesGoogleFirst`: 2 Google hits with 5 photos each + 3 Wikimedia files → 8 Google (4 each) then 3 Wikimedia; first id starts with "g:".
  - `wikimediaDownStillGoogle`: wiki transport throws `IOException` → only Google photos, no exception.
  - `noGoogleKeyOnlyWikimedia`: `GooglePlacesSearch(null, …)` → Wikimedia photos only.
  - `creditStripsHtml`: Artist `<a href="x">Jane</a>` + license "CC BY-SA 4.0" → credit "Jane · CC BY-SA 4.0".
  - `skipsNonImages`: a `image/tiff`/`application/pdf` entry is dropped.
  - `mediaRejectsForeignHost`: imageinfo answer with `thumburl = "https://evil.example/x.jpg"` → `PhotosUnavailable`, transport never asked for evil.example.
  - `mediaRejectsHttp`: `http://upload.wikimedia.org/...` → `PhotosUnavailable`.
  - `mediaBadPrefix`: `media("x:1", 400)` → `IllegalArgumentException` (route maps to 400).
- [ ] **Step 2: Run** `./gradlew :server:test --tests '*WebPhotos*'` → FAIL.
- [ ] **Step 3: Implement**; `HttpWikimediaTransport` with Java HttpClient, `followRedirects(NEVER)`, 15 s timeout.
- [ ] **Step 4: Run** → PASS; `:server:test` green.
- [ ] **Step 5: Commit** `feat(server): web photo search (Google + Wikimedia Commons) with a safe media proxy`.

### Task 5: App – "Online suchen" for photos (0.49.0)

**Files:**
- Create: `app/src/main/kotlin/de/wanderlogplus/app/ui/attachments/WebPhotoSearch.kt`
- Modify: `client/.../SyncApi.kt` (`searchWebPhotos(q, near): List<WebPhoto>`, `webPhoto(id, w): ByteArray`), `AttachmentHost.kt` (`AttachmentActions.onSearchPhotos(target, query)`), `AttachmentsSection.kt` / `PhotoGallery.kt` (choice „Aus Galerie“ / „Online suchen“), `AttachmentsViewModel.kt` (`addPhotos(..., onAdded)`), `HeaderImagePicker.kt` (button „Online suchen“), `PlacesViewModel.kt` + `MoreViewModel.kt` (`setHeaderFromAttachment`), `AppNavigation.kt`, `MainActivity.kt`
- Test: `app/src/test/.../attachments/WebPhotoSearchTest.kt`

**Interfaces:**
- Consumes: Task 4 types and routes.
- Produces: `class WebPhotoSource(private val photo: WebPhoto, private val fetch: suspend (String, Int) -> ByteArray) : AttachmentSource` — `fileName()` = `photoFileName(photo)`, `mimeType()` = "image/jpeg", `open()` = `runBlocking { fetch(photo.id, 1600) }.inputStream()`.
- `fun photoFileName(p: WebPhoto): String` → `"<title> (Foto: Google).jpg"` / `"<title> (Foto: Wikimedia · <credit>).jpg"`, title fallback „Foto“, characters `/\:*?"<>|` replaced by space, total ≤ 120 chars before ".jpg". (Ruling vs. spec: `Attachment` has no note field, so the credit lives in the file name.)
- `@Composable fun WebPhotoSearchSheet(initialQuery: String, near: Pair<Double, Double>?, multiple: Boolean, search: suspend (String, Pair<Double, Double>?) -> List<WebPhoto>, thumb: (WebPhoto) -> Any /* Coil model */, onPick: (List<WebPhoto>) -> Unit, onDismiss: () -> Unit)`; tags `web-photos`, `web-photo-<index>`, `web-photos-take`; button text „Übernehmen (n)“; single mode: tap selects one, take button „Übernehmen“.
- `fun photoQuery(snapshot: TripSnapshot, target: AttachmentTarget): String` — saved place/place: name + region name; stay: place name + city/region; day: region name of the day (via `TripQueries` region of the day), fallback trip name.
- `AttachmentsViewModel.addPhotos(target, sources, onAdded: suspend (List<String>) -> Unit = {})` passes the ids `addPhoto` returned.
- `PlacesViewModel.setHeaderFromAttachment(savedPlaceId: String, attachmentId: String)` and `MoreViewModel.setHeaderFromAttachment(type: EntityType, id: String, attachmentId: String)`: inside `write { e, s -> }` look up `s.attachments` by id and set `headerImage = HeaderImages.OWN + fileHash`.
- Thumbnail model: authenticated URL is not possible for Coil without headers → `thumb` loads bytes via `SyncApi.webPhoto(id, 400)` (Coil accepts `ByteArray`); cache in a `remember` map.
- Hidden when no server (`searchWebPhotos == null`).

- [ ] **Step 1: Failing tests** (`WebPhotoSearchTest`):
  - `fileNameCarriesCredit`: `photoFileName(WebPhoto("w:a.jpg", WIKIMEDIA, "Kinkaku-ji", "Jane · CC BY-SA 4.0"))` == "Kinkaku-ji (Foto: Wikimedia · Jane · CC BY-SA 4.0).jpg"; Google → "X (Foto: Google).jpg"; title with "a/b" → "a b".
  - `sheetPicksSeveral`: fake search returns 3 photos → click `web-photo-0`, `web-photo-2`, then `web-photos-take` → `onPick` gets ids [0, 2]; text „Übernehmen (2)“ shown before.
  - `singleModeReplacesSelection`: `multiple = false`, click 0 then 1 → onPick gets only photo 1.
  - `queryForPlace`: Fixtures saved place in region "Kyoto" → `photoQuery` == "<name> Kyoto".
  - `addPhotosReportsIds`: AttachmentsViewModel with `addPhoto` returning "a1","a2" → `onAdded` receives ["a1","a2"].
  - `headerFromAttachment`: snapshot with attachment id "a1" hash "h1" → saved place headerImage == HeaderImages.OWN + "h1".
  - `galleryOffersOnlineSearch`: attachments section with `onSearchPhotos` set → after tapping „Fotos hinzufügen“ the option „Online suchen“ exists.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*WebPhotoSearch*'` → FAIL.
- [ ] **Step 3: Implement**; header flow: `HeaderImagePicker(…, onSearchOnline: (() -> Unit)?)` → sheet single mode → `addPhotos(target, listOf(WebPhotoSource(...)), onAdded = { ids -> ids.firstOrNull()?.let { setHeaderFromAttachment(…, it) } })`; the target is the same one the picker's „Hochladen“ uses.
- [ ] **Step 4: Run** → PASS; full `:app:testDebugUnitTest` green.
- [ ] **Step 5: 0.49.0 / 63, CHANGELOG, commit** `feat(app): search photos online (Google, Wikimedia) wherever photos are added; 0.49.0`.

### Task 6: Server – research parallelism setting and throttle retry

**Files:**
- Create: `server/src/main/kotlin/de/wanderlogplus/server/ai/Throttle.kt`
- Modify: `ServerConfig.kt` (`researchParallelism: Int = 10`, env `WLP_RESEARCH_PARALLELISM`, valid 1–20 else 10), `ai/Assistant.kt` (`RESEARCH_PARALLELISM` const → constructor value `parallelism: Int = DEFAULT_RESEARCH_PARALLELISM`; `DEFAULT_RESEARCH_PARALLELISM = 10`), `ai/AiJobs.kt` (step texts use the instance value), `ai/OpenRouter.kt` (`post` uses throttle), `ai/Parallel.kt` (`HttpParallelTransport.send` uses throttle), `Application.kt`/`Main.kt` wiring
- Test: `server/src/test/kotlin/de/wanderlogplus/server/ai/ThrottleTest.kt`, `ServerConfigTest.kt`

**Interfaces:**
- Produces: `data class Attempt(val status: Int, val body: String, val retryAfter: String?)`; `fun throttled(sleep: (Long) -> Unit = Thread::sleep, maxRetries: Int = 3, call: () -> Attempt): Attempt` — retries when `status == 429` or (`status == 402` and `"openrouter_in_flight_budget"` in body); wait = `retryAfter` seconds (integer) clamped to 1–60, missing/unparsable → 10; after `maxRetries` retries returns the last attempt.

- [ ] **Step 1: Failing tests.**
  - `ThrottleTest.retriesOn429ThenOk`: answers 429 (Retry-After "2"), 200 → result 200, sleeps [2000].
  - `inFlightBudget402Retries`: 402 body `{"error":{"metadata":{"limit_source":"openrouter_in_flight_budget"}}}` then 200 → 200, sleeps [10000].
  - `plain402DoesNotRetry`: 402 `openrouter_credits` → returned immediately, no sleep.
  - `givesUpAfterThree`: always 429 → 4 calls, sleeps 3×, returns 429.
  - `clampsRetryAfter`: "600" → 60000; "x" → 10000.
  - `ServerConfigTest`: `WLP_RESEARCH_PARALLELISM=16` → 16; "0" → 10; "abc" → 10; unset → 10.
- [ ] **Step 2: Run** `./gradlew :server:test --tests '*Throttle*' --tests '*ServerConfigTest*'` → FAIL.
- [ ] **Step 3: Implement**; check first that no retry exists elsewhere (`grep -rn "429" server/src/main`), reuse if so.
- [ ] **Step 4: Run** → PASS; `:server:test` green.
- [ ] **Step 5: Commit** `feat(server): research parallelism configurable (default 10), retry on 429/in-flight budget`.

### Task 7: Research queue – server lane + app queue (0.50.0)

**Files:**
- Modify: `server/.../ai/AiJobs.kt` (`researchExecutor: ExecutorService = Executors.newSingleThreadExecutor { … "ai-research" }`; `startResearch` and the research branch of `resumeInterrupted` use it; queued step text for research „Wartet auf die laufende Recherche …“; resume order oldest first)
- Modify: `app/.../ui/places/PlacesViewModel.kt`, `RegionPlacesScreen.kt` (status from queue), `AppNavigation.kt` (Ablauf → `vm.currentResearchJob`)
- Test: `server/src/test/kotlin/de/wanderlogplus/server/ai/ResearchLaneTest.kt`, `app/src/test/.../places/ResearchQueueTest.kt`

**Interfaces:**
- Produces (app): `data class QueuedResearch(val key: Long, val label: String, val ids: List<String>, val jobId: String? = null, val progress: Int = 0, val total: Int, val state: State) { enum class State { WAITING, RUNNING, DONE, FAILED } }`; `PlacesViewModel.queue: StateFlow<List<QueuedResearch>>`; `fun research(savedPlaceIds: List<String>, label: String = "Orte")`; `val currentResearchJob: StateFlow<String?>` (running job id, else last finished); `researchStatus` text = `queueStatus(queue)`; `fun queueStatus(q: List<QueuedResearch>): String?` → running: "<label>: <researchProgress text>" + (waiting labels non-empty → " · danach: " + labels joined ", "); none running → outcome text of the last finished (existing texts) or null.
- Behaviour: `research` filters ids already in any WAITING/RUNNING entry; empty → no entry. Each entry is POSTed immediately (`startResearch`) so the server orders them; a single worker coroutine polls only the first unfinished entry, then the next. `researching` = union of ids of WAITING/RUNNING entries. Network-gap handling and `applyDossiers` as today.

- [ ] **Step 1: Failing tests.**
  - `ResearchLaneTest.secondWaitsForFirst`: AiJobs with a fake assistant whose research blocks on a latch; start job A, then B → B status "QUEUED" with step text „Wartet auf die laufende Recherche …“ while A is RESEARCHING; release latch → B runs after A (record start order [A, B]). Reel job started meanwhile runs without waiting (uses the 2-pool).
  - `ResearchQueueTest.statusShowsOnlyRunning` (Review Focus 3): fake api, two `research` calls ("Kyoto" 12 ids, "Osaka" 3 ids); first job progress 3 → `researchStatus` == "Kyoto: Recherche: 3 von 12 Orten … · danach: Osaka" on several polls; never contains "Osaka:".
  - `currentJobIsRunningOne`: `currentResearchJob` == job id of Kyoto while it runs, Osaka's after Kyoto is DONE.
  - `noDoubleQueue` (Review Focus 4): `research(listOf("a","b"), "Kyoto")` then `research(listOf("b"), "X")` → queue size 1, startResearch called once.
  - `failedFirstContinues`: first job FAILED → second still runs; final status the second's outcome.
- [ ] **Step 2: Run** `./gradlew :server:test --tests '*ResearchLane*' :app:testDebugUnitTest --tests '*ResearchQueue*'` → FAIL.
- [ ] **Step 3: Implement**; region screen passes the region name as label.
- [ ] **Step 4: Run** → PASS; `:server:test :app:testDebugUnitTest` green.
- [ ] **Step 5: 0.50.0 / 64, CHANGELOG (also Task 6: 10 gleichzeitig, `WLP_RESEARCH_PARALLELISM`, Wiederholen bei Drosselung), commit** `feat: research jobs run one after another; status shows the running one; 0.50.0`.

### Task 8: Places overview – research chosen regions (0.51.0)

**Files:**
- Create: `app/src/main/kotlin/de/wanderlogplus/app/ui/places/RegionResearchSheet.kt`
- Modify: `PlacesScreen.kt` (`onAssess`/`assessing` → `onResearchRegions: ((List<Pair<String, List<String>>>) -> Unit)?`, `researchStatus: String?`), `AppNavigation.kt`, `PlacesViewModel.kt` (`researchRegions(batches: List<Pair<String, List<String>>>)`)
- Test: `app/src/test/.../places/RegionResearchTest.kt` (and adapt existing `places-assess` tests)

**Interfaces:**
- Consumes: `research(ids, label)` (Task 7), `unresearched(all)`, `researchCostHint(n)`, `savedPlacesIn(snapshot, key)` (non-dropped, same list as the region screen), `regionTileOrder`.
- Produces: `data class RegionChoice(val key: String, val name: String, val all: List<String>, val fresh: List<String>)`; `fun regionChoices(snapshot: TripSnapshot): List<RegionChoice>` (tile order, „Ohne Region“ last when not empty, empty regions omitted); `@Composable fun RegionResearchSheet(choices: List<RegionChoice>, onStart: (batches: List<Pair<String, List<String>>>) -> Unit, onDismiss: () -> Unit)`; `PlacesViewModel.researchRegions(batches)` calls `research(ids, label)` per batch in order.
- Copy: title „Regionen recherchieren“; row „<Name> · <n> Orte, <k> neu“ (`research-region-<key>`); „Alle auswählen“ (`research-select-all`); buttons „Nur unrecherchierte (<k> · <cost>)“ (`research-new`) and „Alle (<n> · <cost>)“ (`research-all`) with summed counts over the selection; both disabled with nothing selected; `research-new` disabled when k = 0. Button on overview: „Recherchieren (KI)“ (`places-research`); `assessAll` and `places-assess` removed from the UI.

- [ ] **Step 1: Failing tests** (`RegionResearchTest`, Fixtures):
  - `choicesCountFresh`: `regionChoices(fixture)` row for "r-berg" has `all.size`/`fresh.size` as in the fixture (compute expected from `unresearched`).
  - `startsOneBatchPerRegion`: select two regions, tap `research-new` → `onStart` gets 2 batches with only fresh ids, in tile order, labels = region names.
  - `allButtonTakesAll`: tap `research-all` → batches carry `all`.
  - `buttonsDisabledWithoutSelection`: `research-all` `assertIsNotEnabled()`.
  - `selectAll`: `research-select-all` → button text contains summed count.
  - `overviewButton`: PlacesScreen with `onResearchRegions` → `places-research` exists, `places-assess` does not.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*RegionResearch*'` → FAIL.
- [ ] **Step 3: Implement**; status line on the overview shows `researchStatus` (`places-research-status`) with „Ablauf“ like the region screen.
- [ ] **Step 4: Run** → PASS; full `:app:testDebugUnitTest` green.
- [ ] **Step 5: 0.51.0 / 65, CHANGELOG, commit** `feat(app): research chosen regions from the places overview; 0.51.0`.
