# Startbildschirm-Widgets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Seven responsive Glance home screen widgets (Als Nächstes, Nächste Fahrt, Heute kompakt, Unterkunft, Yen & Budget, Wetter, Reise-Chat mit „Was jetzt?“) that read the local trip data and open the app at the right place.

**Architecture:** A pure model layer (`WidgetData.build`) turns `WidgetInput` (snapshot, now, weather, rate, quick result) into one model per widget, reusing the Heute-Seite queries. Glance widgets render a model per size bucket. A `WidgetUpdater` refreshes them on data changes, every 15 min and at the next time boundary. Taps start `MainActivity` with a target string. The chat widget's quick asks reuse the chat API with a new optional `quick` field.

**Tech Stack:** Kotlin 2.1, Jetpack Glance 1.1.1 (`glance-appwidget`, `glance-material3`, test: `glance-appwidget-testing`), WorkManager 2.10, Robolectric, Ktor server.

**Spec:** `docs/superpowers/specs/2026-11-13-home-widgets-design.md`

## Global Constraints

- Package `de.wanderlogplus.app.widgets`; all UI text German.
- Buckets (dp, B×H): TINY 57×57, STRIP 180×57, SMALL 110×110, MEDIUM 180×110, WIDE 250×110, TALL 180×250, LARGE 250×250; `SizeMode.Responsive`.
- Widgets never touch the network while drawing; only `QuickAskWorker` and the existing repositories do.
- „Jetzt“ = `settings.tripZone.now()`, with the simulated time when debug mode has one (same as Heute).
- Countdowns always beside the fixed time: „14:30 · in 25 min“ (`inText` from `TodayWidgets.kt`).
- No booking data in logs; exceptions logged by class name only.
- Quick ask: result stale after 2 h or the next day (greyed), gone after 6 h or the next day; worker timeout 3 min.
- Version 0.74.0 (versionCode 95) with a CHANGELOG section.
- Build: `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.8.9-hotspot"`.

## Review Focus

- Day change at midnight while a widget is placed: the boundary work must fire and „Als Nächstes“ show the new day.
- A widget resized between buckets: content must change without a data reload (Glance recomposes per size).
- Snapshot not ready yet / no server set up: every widget shows „Reise in der App einrichten“, never crashes.
- Quick ask tapped twice quickly: one request only (unique work, KEEP), the widget shows „Suche …“ once.
- Quick ask answer with only web finds or no cards: shown without image, tap opens the conversation.

---

### Task 1: Glance setup, buckets, targets, receivers

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/de/wanderlogplus/app/widgets/WidgetSizes.kt`, `WidgetTarget.kt`, `WidgetUi.kt` (shared Glance pieces: `WidgetFrame`, `HintText`, `IconButton` row), `Receivers.kt`
- Create: `app/src/main/res/xml/widget_<name>.xml` ×7, `app/src/main/res/layout/widget_loading.xml`
- Test: `app/src/test/kotlin/de/wanderlogplus/app/widgets/WidgetSizesTest.kt`, `WidgetTargetTest.kt`

**Interfaces:**
- Produces: `enum class Bucket(val size: DpSize) { TINY, STRIP, SMALL, MEDIUM, WIDE, TALL, LARGE }`, `val ALL_SIZES: Set<DpSize>`, `fun bucketOf(size: DpSize): Bucket` (largest bucket whose width and height both fit; TINY fallback).
- Produces: `sealed interface WidgetTarget { Today; Day(date); Logistics(type: EntityType, id); Attachment(id); NewExpense; StayAddress(stayId); Place(savedPlaceId); Chat(chatId: String?); Ask(kind: String) }` with `fun encode(): String` (`today`, `day:<d>`, `logistics:TRAIN:<id>`, `attachment:<id>`, `expense:new`, `stay-address:<id>`, `place:<id>`, `chat` / `chat:<id>`, `ask:sight|food`) and `companion fun parse(s: String?): WidgetTarget?`.
- Produces: `const val EXTRA_WIDGET_TARGET = "wlp.widget.target"`; `fun openApp(context: Context, target: WidgetTarget): Action` (Glance `actionStartActivity` with the extra, `FLAG_ACTIVITY_SINGLE_TOP`).
- Produces: seven `GlanceAppWidgetReceiver`s `NextUpReceiver, NextRideReceiver, TodayReceiver, StayReceiver, BudgetReceiver, WeatherReceiver, ChatReceiver`, each pointing to its widget class (placeholder content „…“ in this task).

- [ ] **Step 1: Tests** – `bucketOf(DpSize(57.dp,57.dp)) == TINY`; `(200,60) == STRIP`; `(120,120) == SMALL`; `(190,120) == MEDIUM`; `(260,120) == WIDE`; `(190,260) == TALL`; `(300,300) == LARGE`; `(40,40) == TINY`. Every `WidgetTarget` round-trips `parse(encode())`; `parse("x:1") == null`, `parse(null) == null`, `parse("logistics:BOAT:1") == null`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests "de.wanderlogplus.app.widgets.*"` – Expected: FAIL (unresolved).
- [ ] **Step 3: Implement** deps (`glance = "1.1.1"`), the files above; provider XMLs: `minWidth/minHeight` = 57dp (Chat, Budget, Weather, NextUp, NextRide), 110dp (Stay), 180×110 (Today); `resizeMode="horizontal|vertical"`, `widgetCategory="home_screen"`, `updatePeriodMillis="0"`, German `android:description` strings, `initialLayout="@layout/widget_loading"`.
- [ ] **Step 4: Run** same command – Expected: PASS; `./gradlew :app:compileDebugKotlin` succeeds.
- [ ] **Step 5: Commit** `feat(app): widget scaffolding – sizes, targets, receivers`.

### Task 2: Widget models

**Files:**
- Create: `app/src/main/kotlin/de/wanderlogplus/app/widgets/WidgetData.kt`
- Create: `app/src/main/kotlin/de/wanderlogplus/app/widgets/QuickStore.kt` with only the data classes `QuickResult`, `QuickCard` (fields in Task 8; the store class follows there)
- Modify (visibility only, if private): `ui/today/TodayWidgets.kt`, `ui/today/TodayScreen.kt` helpers reused below → `internal`
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/data/SettingsStore.kt` (`lastJpyRate: Flow<StoredRate?>`, `suspend fun setLastJpyRate(rate: Double, date: String)`), `app/src/main/kotlin/de/wanderlogplus/app/ui/budget/BudgetViewModel.kt` (successful `rate("JPY", …)` stores it via a new ctor param `onRate: suspend (String, RateInfo) -> Unit = { _, _ -> }`)
- Test: `app/src/test/kotlin/de/wanderlogplus/app/widgets/WidgetDataTest.kt`, extend `BudgetViewModelTest`

**Interfaces:**
- Produces: `data class StoredRate(val eurPerJpy: Double, val date: String)`.
- Produces: `data class WidgetInput(val snapshot: TripSnapshot?, val now: LocalDateTime, val weather: TripWeather?, val rate: StoredRate?, val lastChatTitle: String?, val quick: QuickResult?)`.
- Produces: `object WidgetData { fun build(i: WidgetInput): WidgetModels; fun nextBoundary(i: WidgetInput): LocalDateTime }`.
- Produces: `data class WidgetModels(val phase: Phase, val nextUp: NextUpModel, val ride: RideModel?, val today: TodayModel?, val stay: StayModel?, val budget: BudgetModel, val weather: WeatherModel?, val chat: ChatModel)`; `enum class Phase { NO_TRIP, BEFORE, DURING, AFTER }`.
  - `NextUpModel(current: StepLine?, upcoming: List<StepLine> /* ≤5 */, daysUntilStart: Long?, tripName: String?, openDeadlines: Int, nextDeadline: String?, date: String?)`; `StepLine(time: String?, title: String, kind: StepKind /* PLACE, TRAIN, FLIGHT */, inMinutes: Long?, wayText: String?, done: Boolean, target: WidgetTarget)`.
  - `RideModel(kind: StepKind, departs: String /* HH:mm */, departsDate: String, inMinutes: Long, name: String, from: String, to: String, arrives: String?, platform: String?, seat: String?, transfers: List<String>, tickets: List<Pair<String /*label*/, String /*attachmentId*/>>, target: WidgetTarget)`.
  - `TodayModel(dateLabel: String, region: String?, stayName: String?, steps: List<StepLine>, weatherLine: String?)`.
  - `StayModel(name: String, address: String?, checkLine: String /* "Check-in ab 15:00" | "Check-out heute bis 10:00" | "Check-out Sa 12.04." */, confirmation: String?, mapsUrl: String?, stayId: String)`.
  - `BudgetModel(rate: StoredRate?, todayJpy: Long, totalEur: Double, table: List<Pair<Long, Double>> /* 500,1000,5000,10000 ¥ → € */, topCategories: List<Pair<String, Double>> /* ≤3 */)`.
  - `WeatherModel(place: String, today: DayWeather?, tomorrow: DayWeather?, climate: Boolean, rainHint: String?)`.
  - `ChatModel(lastTitle: String?, quick: QuickResult?, stale: Boolean)` (quick dropped when gone per the constraint).
- Rules: rate = stored rate, else the newest JPY expense with `rate != null` (`rateDate` or `date`), else null. `todayJpy` = sum of JPY expenses dated today (kind expense). Ruling recorded: the spec's „gegenüber dem geplanten Gesamtbetrag“ has no data (no planned total exists); LARGE shows total spent + top 3 categories instead.
- `nextBoundary` = earliest of: next step start, next step end, ride departure, quick stale/gone time, next midnight; never later than now + 15 min is NOT required (periodic work covers that).

- [ ] **Step 1: Tests** with `Fixtures.trip()` (clock in its range, e.g. `2027-04-02T10:00`): DURING → `nextUp.upcoming` non-empty, first `time` not null, `inMinutes > 0`; BEFORE (`2027-03-01T10:00`) → `daysUntilStart > 0`, phase BEFORE; AFTER → phase AFTER; snapshot null → NO_TRIP; `ride` set for the fixture train within 36 h and null 40 h before; stay on checkout day → `checkLine` starts with „Check-out heute“; rate from stored wins over expense rate; rate from expense when none stored; null when neither; `table` for 6.1 €/1000 ¥ → `1000 to 6.1`; quick result 3 h old → `stale = true`, 7 h old → `quick == null`, yesterday → null; `nextBoundary` at 23:59 with no steps → next midnight; with a step at 14:30 and now 10:00 → 14:30 (or its way-adjusted start).
- [ ] **Step 2: Run** `--tests "de.wanderlogplus.app.widgets.WidgetDataTest"` – Expected: FAIL.
- [ ] **Step 3: Implement** `WidgetData` reusing `TodayQueries.state`, `DayNow.layout`, `upcomingMove`, `deadlineRows`, `TripWeather.forDate`, `outdoorPlacesOn` (move to `internal` if private), `BudgetMath.summary`, `stayLinks`/maps URL helper. SettingsStore key `last_jpy_rate` as `"<rate>|<date>"`.
- [ ] **Step 4: Run** same + `BudgetViewModelTest` – Expected: PASS.
- [ ] **Step 5: Commit** `feat(app): widget models from the trip data`.

### Task 3: Loading and updating

**Files:**
- Create: `app/src/main/kotlin/de/wanderlogplus/app/widgets/WidgetUpdater.kt`, `WidgetRefreshWorker.kt`
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/WanderlogPlusApp.kt`, `AppContainer.kt` (`val widgets: WidgetUpdater by lazy`)
- Test: `app/src/test/kotlin/de/wanderlogplus/app/widgets/WidgetUpdaterTest.kt`

**Interfaces:**
- Produces: `class WidgetUpdater(context, load: suspend () -> WidgetInput, scheduler: BoundaryScheduler)` with `suspend fun input(): WidgetInput` (cached 1 s), `suspend fun updateAll()` (calls `GlanceAppWidget.updateAll` for the 7 widgets, then `scheduler.at(WidgetData.nextBoundary(input))`), `fun watch(scope)` (collects `snapshots().changes()` + weather flow, debounced 2 s → `updateAll`).
- Produces: `fun interface BoundaryScheduler { fun at(time: LocalDateTime) }`; the real one enqueues unique one-time work `widget-boundary` (REPLACE) with the delay; periodic unique work `widget-refresh` every 15 min (KEEP), both running `WidgetRefreshWorker` → `container.widgets.updateAll()`.
- Produces: `fun loadWidgetInput(container): WidgetInput` (snapshot from store as `DeadlineWorker.loadFromContainer` does, `weather.weather.value` after `load`, `settings.lastJpyRate`, `QuickStore.read()`, last chat title via `LastChat`-less local: null is fine).
- Every widget's `provideGlance` calls `container.widgets.input()` then `WidgetData.build`; any exception → NO_TRIP model.

- [ ] **Step 1: Tests** – with a fake `load` and recording `BoundaryScheduler`: `updateAll` schedules the boundary from `nextBoundary`; two `input()` calls within 1 s call `load` once; a throwing `load` makes `input()` return an input with `snapshot = null` (no throw).
- [ ] **Step 2: Run** – Expected: FAIL. **Step 3: Implement**; start `watch` and the periodic work in `WanderlogPlusApp.onCreate` (inside the existing `handler` launch), and call `updateAll` after each sync in `SyncRunner` success path (one line via an `onSynced` hook if present, else rely on `changes()`).
- [ ] **Step 4: Run** – Expected: PASS. **Step 5: Commit** `feat(app): widget refresh on changes, every 15 min and at the next boundary`.

### Task 4: Als Nächstes, Nächste Fahrt, Heute kompakt

**Files:**
- Create: `app/src/main/kotlin/de/wanderlogplus/app/widgets/NextUpWidget.kt`, `NextRideWidget.kt`, `TodayWidget.kt`
- Test: `app/src/test/kotlin/de/wanderlogplus/app/widgets/NextUpWidgetTest.kt`, `NextRideWidgetTest.kt`, `TodayWidgetTest.kt`

**Interfaces:**
- Consumes: `NextUpModel`, `RideModel`, `TodayModel`, `Bucket`, `openApp`.
- Produces: per widget `@Composable fun NextUpContent(m: WidgetModels, bucket: Bucket)` (same pattern for the others) – the testable part; the `GlanceAppWidget` only loads and calls it with `bucketOf(LocalSize.current)`.
- Content per bucket exactly as the spec; tags via `GlanceModifier.semantics { testTag = ... }`: `nu-next`, `nu-current`, `nu-row-<i>`, `ride-platform`, `ride-ticket-<i>`, `today-step-<i>`. Phase texts: BEFORE „noch N Tage“, AFTER „Willkommen zurück“, NO_TRIP „Reise in der App einrichten“. No ride: „Keine Fahrt in den nächsten 36 Stunden“. TALL/LARGE lists use `LazyColumn`.

- [ ] **Step 1: Tests** (`runGlanceAppWidgetUnitTest`, `setAppWidgetSize(Bucket.X.size)`, `provideComposable { NextUpContent(models, Bucket.X) }`): TINY shows the next time and no title; STRIP shows time + title + „in “; MEDIUM shows current and next and no `nu-row-3`; LARGE shows up to 5 rows; BEFORE shows „noch“; NO_TRIP hint. Ride: STRIP shows platform, not `ride-ticket-0`; LARGE shows `ride-ticket-0` when tickets exist; none → hint. Today: MEDIUM shows stay name, not `today-step-2`; LARGE shows all steps.
- [ ] **Step 2: Run** `--tests "de.wanderlogplus.app.widgets.*WidgetTest"` – Expected: FAIL. **Step 3: Implement.** **Step 4: Run** – Expected: PASS.
- [ ] **Step 5: Commit** `feat(app): Als Nächstes, Nächste Fahrt and Heute widgets`.

### Task 5: Unterkunft, Yen & Budget, Wetter

**Files:**
- Create: `StayWidget.kt`, `BudgetWidget.kt`, `WeatherWidget.kt` (+ tests `StayWidgetTest`, `BudgetWidgetTest`, `WeatherWidgetTest`)

**Interfaces:**
- Consumes: `StayModel`, `BudgetModel`, `WeatherModel`; weather icons/texts from `ui/weather/WeatherViews.kt` (pure mapping functions; move a code→icon-res/label function to `internal` if needed, Glance needs drawable resources: add `res/drawable/ic_w_*.xml` vectors for sun, cloud, rain, snow, storm, fog).
- Tags: `stay-address`, `stay-show-address`, `stay-map`, `budget-rate`, `budget-table`, `budget-add`, `weather-today`, `weather-tomorrow`, `weather-hours`.
- `budget-add` → `openApp(NewExpense)`; `stay-show-address` → `openApp(StayAddress(id))`; `stay-map` → `actionStartActivity(Intent(ACTION_VIEW, mapsUrl))` when present.
- Money format German (`6,10 €`, `4.300 ¥`); rate line „Kurs vom 12.04.“.

- [ ] **Step 1: Tests**: Stay SMALL has name + check line, no `stay-address`; MEDIUM has `stay-address`; LARGE has `stay-show-address` and the confirmation. Budget TINY shows „1000 ¥“ and „€“; MEDIUM has `budget-table` with „5.000 ¥“; no rate → „Kein Kurs bekannt“ and still `budget-add` from STRIP on. Weather TINY shows max temp; MEDIUM has `weather-tomorrow`; LARGE has `weather-hours` when hours exist; climate source labelled „Klima-Mittel“; none → „Noch keine Vorhersage“.
- [ ] **Step 2–4:** run (FAIL) → implement → run (PASS).
- [ ] **Step 5: Commit** `feat(app): Unterkunft, Yen & Budget and Wetter widgets`.

### Task 6: Opening the app from a widget

**Files:**
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/ui/MainActivity.kt` (`pendingWidget` flow from `EXTRA_WIDGET_TARGET` in `onCreate`/`onNewIntent`), `ui/AppNavigation.kt` (`pendingWidgetTarget: WidgetTarget?`, `onPendingWidgetConsumed`), `ui/budget/BudgetScreen.kt` (`startNewJpy: Boolean = false, onStartConsumed: () -> Unit = {}` opens `BudgetSheet.Edit` with a new JPY expense dated today), `ui/chat/ChatViewModel.kt` (`fun ask(kind: String, quick: QuickAsk)`: new chat, sends the fixed question with `quick`)
- Create: `app/src/main/kotlin/de/wanderlogplus/app/ui/logistics/StayAddressScreen.kt` (route `stay-address/{stayId}`: name + address in `displaySmall`, `FLAG_KEEP_SCREEN_ON` while shown, back arrow; tag `stay-address-screen`)
- Test: `app/src/test/kotlin/de/wanderlogplus/app/ui/WidgetTargetNavigationTest.kt`, extend `ChatViewModelTest`

**Interfaces:**
- Consumes: `WidgetTarget`, `QuickAsk` (Task 7; `ask` sends `QuickAsk` built by `QuickAsks.request(kind, input)` from Task 8 – in this task `ask(kind, quick)` takes it as a parameter).
- Navigation per target: Today → Heute tab; Day → `dayRoute`; Logistics → `logisticsDetailRoute`; Attachment → `attachmentActions.onOpen`; NewExpense → Budget tab + `startNewJpy`; StayAddress → new route; Place → `placeRoute`; Chat(id) → `chatVm.select(id)` (if id) + `openChat`; Ask(kind) → `chatVm.ask(...)` + open chat. Fixed questions: sight „Was kann ich jetzt in der Nähe besichtigen?“, food „Wo kann ich jetzt gut essen?“.

- [ ] **Step 1: Tests** (Robolectric `AppNavigation` like `AppNavigationSmokeTest`): each target shows its screen tag (`day-…`/`logistics-detail`/`expense-sheet`/`stay-address-screen`/`chat-screen`); unknown target ignored; `Ask("food")` → fake backend got `create` + `send` with text „Wo kann ich jetzt gut essen?“ and `quick.kind == "food"`.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(app): widget taps open the right screen`.

### Task 7: Server – quick asks

**Files:**
- Modify: `shared/src/main/kotlin/de/wanderlogplus/shared/sync/ChatProtocol.kt`, `server/src/main/kotlin/de/wanderlogplus/server/ChatService.kt`, `server/src/main/kotlin/de/wanderlogplus/server/ai/ChatAgent.kt` (+ `ChatContextText`)
- Test: `server/src/test/kotlin/de/wanderlogplus/server/ai/ChatAgentTest.kt` (or the existing prompt test file), `shared` serialization test

**Interfaces:**
- Produces: `@Serializable data class QuickAsk(val kind: String, val now: String /* HH:mm */, val freeUntil: String? = null, val nextLabel: String? = null, val nextLat: Double? = null, val nextLng: Double? = null)` with `KIND_SIGHT = "sight"`, `KIND_FOOD = "food"`; `ChatSendRequest.quick: QuickAsk? = null`.
- `ChatService.send` validates (`kind` known, times `HH:mm`, labels ≤ 120 chars, else drops `quick`) and passes it to `runAnswer` → `agent.answer(..., quick = q)` → `systemParts(..., quick)` appends `ChatContextText.quick(q)`:
  - „Schnellfrage vom Widget (<Sehenswürdigkeit|Essen>), jetzt <now> Uhr: Antworte kurz (2–4 Sätze). Schlage 2–5 passende Ziele als Karten vor, das beste zuerst. Beachte Uhrzeit, Öffnungszeiten, Wetter, das restliche Programm und die Entfernung; bevorzuge hinterlegte Orte, Web-Funde sind erlaubt.“
  - with `freeUntil`: „Bis zum nächsten Programmpunkt <nextLabel> um <freeUntil> ist Zeit (<Koordinaten wenn da>). Schlage vor, was samt Hin- und Weiterweg ungefähr in diese Zeit passt; das ist ein Richtwert, kein Muss – es ist Urlaub. Ziele in Richtung des nächsten Punkts sind ein Plus.“
- `quick` is not stored in the chat (like `location`).

- [ ] **Step 1: Tests**: `systemPrompt(trip, ctx, quick = QuickAsk("food","14:05","16:30","Teamlab",35.6,139.7))` contains „Essen“, „16:30“, „Teamlab“, „Richtwert“; with `freeUntil = null` contains „das beste zuerst“ and not „Richtwert“; without quick contains neither. `ChatService.send` with an invalid quick kind still answers (quick dropped). Shared: old JSON without `quick` decodes.
- [ ] **Step 2: Run** `./gradlew :shared:test :server:test` – Expected: FAIL. **Step 3: Implement.** **Step 4: Run** – Expected: PASS.
- [ ] **Step 5: Commit** `feat(server): quick widget asks in the chat (ranked, free time as a guide)`.

### Task 8: App – quick ask worker and store

**Files:**
- Create: `app/src/main/kotlin/de/wanderlogplus/app/widgets/QuickStore.kt`, `QuickAsks.kt`, `QuickAskWorker.kt`
- Test: `app/src/test/kotlin/de/wanderlogplus/app/widgets/QuickAsksTest.kt`, `QuickAskWorkerTest.kt`

**Interfaces:**
- Produces: `@Serializable data class QuickResult(val kind: String, val chatId: String?, val state: String /* RUNNING, DONE, FAILED */, val at: Long /* epoch ms */, val date: String, val cards: List<QuickCard> = emptyList(), val text: String? = null)`; `@Serializable data class QuickCard(val title: String, val subtitle: String?, val savedPlaceId: String?, val imageKey: String?, val distanceMeters: Int?)`.
- Produces: `class QuickStore(file: File)` – `read(): QuickResult?`, `write(r: QuickResult)`; file `widget-quick.json` (scoped like weather).
- Produces: `object QuickAsks { fun request(kind: String, s: TripSnapshot?, now: LocalDateTime): Pair<QuickAsk, ChatLocation?> /* free time from DayNow.next (Visit/Move with start > now); anchor = current/next visit place, else stay */; fun result(kind, chat: ChatInfo, from: ChatLocation?, s: TripSnapshot?, now): QuickResult /* cards of the last assistant message in order; saved place → first imageKey; distance from `from` (haversine, `DayNow.km`); no cards → text cut to 160 chars; error message → FAILED */ }`.
- Produces: `class QuickAskWorker(ctx, params, deps: QuickDeps)`; `QuickDeps(backend: ChatBackend, load: suspend () -> WidgetInput, locate: suspend () -> ChatLocation?, store: QuickStore, update: suspend () -> Unit, pollMillis: Long = 2000, timeoutMillis: Long = 180_000)`. Flow: write RUNNING → update → `create` → `send(ChatSendRequest(question, ChatContext(TODAY, date), location ?: anchor, quick = q))` → poll `get` until `!pending` or timeout → write DONE/FAILED → update. Location: `AndroidChatLocator` only when `hasLocationPermission`, with 5 s timeout; else the anchor.
- Produces: `fun enqueueQuickAsk(context, kind)` – unique work `quick-ask` (KEEP), `NetworkType.CONNECTED` not required (failure handled), expedited if allowed.

- [ ] **Step 1: Tests**: `request` at 10:00 with next visit at 12:00 → `freeUntil == "12:00"`, `nextLabel` = place name; no later step → `freeUntil == null`; `result` keeps card order, first card has `imageKey` for a saved place with images, distance ≈ expected (±10 %); no cards → text; error role → FAILED. Worker with fake backend: RUNNING then DONE written, `update` called twice, request carries `quick.kind`; backend pending forever with `timeoutMillis = 50` → FAILED; `create` throws → FAILED.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(app): quick ask from the widget through the chat`.

### Task 9: Chat widget, release

**Files:**
- Create: `app/src/main/kotlin/de/wanderlogplus/app/widgets/ChatWidget.kt` (+ `ChatWidgetTest.kt`), `QuickActions.kt` (Glance `ActionCallback` `AskCallback` with parameter `kind` → `enqueueQuickAsk`)
- Modify: `app/build.gradle.kts` (versionCode 95, versionName "0.74.0"), `CHANGELOG.md`

**Interfaces:**
- Consumes: `ChatModel`, `QuickResult`, `Bucket`, `openApp`, `AskCallback`.
- Buttons: TINY → whole widget `openApp(Chat(null))`. STRIP/SMALL → three buttons `chat-open`, `ask-sight`, `ask-food`; ask buttons `openApp(Ask(kind))`. MEDIUM+ → ask buttons `actionRunCallback<AskCallback>(kind)`.
- Result (MEDIUM+): RUNNING „Suche passende Ziele …“ (tag `quick-running`); DONE: `quick-top` (title, distance „650 m“/„1,2 km“, subtitle 1 line), WIDE adds `quick-image` when an image is cached; TALL/LARGE: `quick-top` with image + `quick-more-<i>` ≤ 3; „von 14:05“; stale → grey + „älter“. FAILED: „Keine Antwort – im Chat erneut versuchen“. Taps: card with `savedPlaceId` → `Place(id)`; else `Chat(chatId)`; `chat-open` → `Chat(quick?.chatId)`.
- Image: `provideGlance` loads the top card's image with Coil (`ImageLoader.execute`, `allowHardware(false)`, size 300 px, from cache/network best effort) before `provideContent`; failure → no image.

- [ ] **Step 1: Tests**: TINY has no `ask-sight`; STRIP has `ask-sight` and `ask-food`, no `quick-top` even with a DONE result; MEDIUM with RUNNING shows `quick-running`; MEDIUM DONE shows `quick-top` with title + „650 m“, no `quick-more-0`; LARGE shows `quick-more-0..2`; FAILED text; stale shows „älter“; without result STRIP shows the last chat title.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5:** Full suite `./gradlew :shared:test :server:test :app:testDebugUnitTest` – Expected: BUILD SUCCESSFUL.
- [ ] **Step 6:** Version bump + CHANGELOG `## 0.74.0` (seven widgets, sizes, „Was jetzt?“ with free time). Commit `feat(app): Reise-Chat widget with quick asks; 0.74.0`.
