# Guides und Recherche-Pipeline – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allgemeine Reise-Guides (Knigge, Sprache, …), die aus der Reise vorgeschlagen, per Parallel + Sonnet erzeugt, vom Chat
erstellt/zugeordnet und an Orten, Unterkünften, Fahrten, Tagen und auf der Heute-Seite angezeigt werden; dazu ein gemeinsamer
Recherche-Ablauf „Parallel liefert Fakten, Textmodell bewertet/schreibt“, auf den auch die Ortsrecherche umgestellt wird.

**Architecture:** Server: `FactSource` (Parallel oder Textmodell mit Websuche) liefert Fakten mit Quellen; `PipelineResearcher`
(Orte) und `GuideWriter` (Guides) lassen das Textmodell daraus das Ziel-JSON schreiben, prüfen und einmal wiederholen.
Shared: neue synchronisierte Entity `Guide` (+ `GuidePrefs`), fester Themenkatalog `GuideTopics`, reine Logik `GuideMatch` /
`GuideSuggestions`. App: Guide-Seiten, Übersicht mit Vorschlägen, Chips auf Detailseiten, Karte „Für heute“.

**Tech Stack:** Kotlin, kotlinx.serialization, Ktor-Server, SQLite-Store, Jetpack Compose, Android `TextToSpeech`.

**Spec:** `docs/superpowers/specs/2026-11-06-guides-research-design.md`

## Global Constraints

- Gradle mit JDK ≥ 11: `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.8.9-hotspot"` vor jedem `./gradlew`.
- Textarbeit läuft auf `WLP_TEXT_MODEL` (`anthropic/claude-sonnet-5-5`, schon in `Main.kt` als `textModel`); Reels bleiben auf `WLP_AI_MODEL`.
- Kein erzwungenes `tool_choice` (`any`/`tool`); Chat-Verlauf nur anhängen.
- Parallel-Processor aus `WLP_PARALLEL_PROCESSOR`, Standard `base`.
- Ungültiges Modell-JSON: genau **einmal** wiederholen, dann `AiFailed("ai_bad_answer")`; nie halbfertig speichern.
- Prompts enthalten nie Buchungsdaten (Bestätigungsnummern, Reisendennamen).
- Alle Nutzertexte auf Deutsch. Neue Felder an bestehenden Klassen immer am Ende mit Standardwert (Sync-Kompatibilität).
- Server schreibt als Assistent über `sync.writeAsServer(ASSISTANT_AUTHOR, edits)`.
- Nach Abschluss: Version in `app/build.gradle.kts` + Abschnitt in `CHANGELOG.md`, lokal in `main` mergen, nicht pushen.

## Review Focus

- Orte/Unterkünfte ohne Kategorien oder mit leerem Namen: `GuideMatch` darf nicht werfen und liefert dann nur `Always`-Themen → Test in Task 4.
- Ein Guide, dessen Ziel (Ort/Zug) gelöscht wurde: `pinnedTo` zeigt ins Leere; Anzeige und Chat ignorieren solche Ziele still → Test in Task 4.
- Zwei Geräte lehnen offline denselben Vorschlag ab bzw. erstellen denselben Guide: Doppelte Themen-Guides werden in der Übersicht zusammen angezeigt, `GuideSuggestions` zählt das Thema als vorhanden; `duplicateOf` verhindert neue → Test in Task 5.
- Grundstock mit einem Thema, bei dem die Recherche scheitert: die übrigen Themen werden trotzdem erstellt, das Thema steht als fehlgeschlagen im Auftrag → Test in Task 6.
- Japanisches TTS fehlt am Gerät: Lautsprecher ausgeblendet, kein Absturz → manueller Check in Task 8 (Init-Ergebnis `LANG_MISSING_DATA`/`LANG_NOT_SUPPORTED` → `ttsReady = false`).

---

## Teil 2: Recherche-Pipeline

### Task 1: `FactSource` mit Parallel und Textmodell

**Files:**
- Create: `server/src/main/kotlin/de/wanderlogplus/server/ai/Facts.kt`
- Modify: `server/src/main/kotlin/de/wanderlogplus/server/ai/Parallel.kt` (Search/Task-Aufrufe wiederverwenden)
- Test: `server/src/test/kotlin/de/wanderlogplus/server/ai/FactsTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class Fact(val text: String, val sources: List<String> = emptyList())
  data class Facts(val items: List<Fact>, val sources: List<String>)   // sources: alle URLs, ohne Duplikate
  /** What to research: [objective] in German, [queries] for the web search, [hints] what to look for (one line each). */
  data class FactQuery(val objective: String, val queries: List<String>, val hints: List<String> = emptyList(), val location: String = "jp")
  fun interface FactSource { fun facts(q: FactQuery, say: (String) -> Unit = {}): Facts? }   // null: nichts Verwertbares
  class ParallelFacts(transport: ParallelTransport, processor: String = "base") : FactSource
  class ModelFacts(model: ChatModel) : FactSource   // Textmodell mit webSearch = true
  ```

- [ ] **Step 1: Failing tests** in `FactsTest`:
  - `parallel facts send search then a task whose schema only has facts` – Fake-`ParallelTransport` (Muster aus `ParallelTest`) zeichnet Requests auf; antwortet auf `/v1/search` mit 2 Treffern, auf `/v1/tasks/runs` mit `{"run_id":"r1"}`, auf `.../result` mit `{"output":{"content":{"facts":[{"text":"Tattoos oft verboten","sources":["https://a.jp"]}]},"basis":[{"citations":[{"url":"https://b.jp"}]}]}}`.
    Assert: Task-Body enthält `"facts"` im `output_schema`, aber **nicht** `"worth"`/`"verdict"`; Ergebnis `items == listOf(Fact("Tattoos oft verboten", listOf("https://a.jp")))`, `sources` enthält `https://a.jp`, `https://b.jp` und die beiden Search-URLs.
  - `parallel facts return null when the task has no facts` – leeres `facts`-Array → `null`.
  - `parallel facts map 402 to research_no_credit` – `assertFailsWith<AiFailed>` mit `code == "research_no_credit"`.
  - `model facts parse the model answer` – Fake-`ChatModel` gibt `{"facts":[{"text":"x","sources":["https://c.jp"]}]}` zurück; Assert `webSearch == true` wurde übergeben und `items.single().text == "x"`.
- [ ] **Step 2: Run** `./gradlew :server:test --tests '*FactsTest'` → FAIL (Klassen fehlen).
- [ ] **Step 3: Implement** `Facts.kt`. `ParallelFacts` nutzt dieselben Transport-/Fehlercodes und das Polling wie `ParallelResearcher.runTask` (Code dorthin auslagern als `internal fun runParallelTask(transport, body, say): Pair<JsonObject, List<String>>?`, `ParallelResearcher` nutzt ihn weiter). Task-Input: Objective + Hints + gefundene Quellen (wie heute bis zu `MAX_SOURCES`, je 800 Zeichen); Output-Schema `{facts: [{text: string, sources: string[]}]}`, Beschreibung „höchstens 25 Fakten, je ein Satz auf Deutsch, nur Belegtes“.
- [ ] **Step 4: Run** → PASS; danach `./gradlew :server:test --tests '*ParallelTest'` → PASS (unverändert).
- [ ] **Step 5: Commit** `feat(ai): fact sources (Parallel, model web search)`

### Task 2: `PipelineResearcher` – Fakten bewerten mit dem Textmodell

**Files:**
- Create: `server/src/main/kotlin/de/wanderlogplus/server/ai/ResearchPipeline.kt`
- Modify: `server/src/main/kotlin/de/wanderlogplus/server/Main.kt:49-52`, `server/src/main/kotlin/de/wanderlogplus/server/ai/Assistant.kt` (`ResearchQuery` um `tripContext: String = ""` erweitern, in `research`/`researchSaved` füllen)
- Test: `server/src/test/kotlin/de/wanderlogplus/server/ai/ResearchPipelineTest.kt`

**Interfaces:**
- Consumes: `FactSource`, `FactQuery`, `Facts` (Task 1); bestehend `parseResearch(o, config, extraSources)`, `criteriaPrompt(config)`, `ResearchOutcome`, `PlaceResearcher`.
- Produces:
  ```kotlin
  /** Asks [model] for a JSON object; retries once when the answer has none or [valid] rejects it. */
  fun completeJson(model: ChatModel, system: String, user: String, valid: (JsonObject) -> Boolean = { true }): JsonObject
  class PipelineResearcher(facts: FactSource, model: ChatModel) : PlaceResearcher
  ```
  `tripContext` (Text): wann die Reisenden in der Region sind und welche Orte dort schon eingeplant sind (aus `TripData`); nie Buchungsdaten.

- [ ] **Step 1: Failing tests** in `ResearchPipelineTest`:
  - `facts are judged by the text model into the usual outcome` – Fake-`FactSource` liefert 2 Fakten; Fake-`ChatModel` gibt JSON im heutigen Ergebnisformat zurück (`description`, `category`, `criteria`, `worth`, `verdict`, `insiderTips`, …). Assert: `outcome.dossier.verdict` gesetzt, `worth == "MUST"`, `dossier.sources` enthält die Fakten-URLs; der User-Text an das Modell enthält beide Fakten und `tripContext`.
  - `a bad answer is asked again once` – Modell antwortet erst `"kein json"`, dann gültig → Ergebnis da, 2 Aufrufe.
  - `two bad answers fail` – zweimal ungültig → `AiFailed` mit `code == "ai_bad_answer"`, genau 2 Aufrufe.
  - `no facts means no outcome` – `FactSource` gibt `null` → `research(...) == null`, Modell nie aufgerufen.
- [ ] **Step 2: Run** `--tests '*ResearchPipelineTest'` → FAIL.
- [ ] **Step 3: Implement.** Schema-Text für das Modell = die heutigen Felder aus `ParallelResearcher.taskBody` (Beschreibungen übernehmen) als JSON-Beispiel im System-Prompt; Regel „nichts behaupten, was nicht in den Fakten steht – außer allgemein Bekanntem“. `valid` für Orte: `verdict` und `worth` vorhanden. In `Main.kt`: `researcher = PipelineResearcher(config.parallelKey?.let { ParallelFacts(HttpParallelTransport(it), config.parallelProcessor) } ?: ModelFacts(text), text)`. `ParallelResearcher`/`OpenRouterResearcher` bleiben vorerst (ungenutzt in `Main`), damit ihre Tests weiterlaufen.
- [ ] **Step 4: Run** `./gradlew :server:test` → alle PASS.
- [ ] **Step 5: Commit** `feat(ai): place research = Parallel facts judged by the text model`

---

## Teil 3: Guides

### Task 3: Datenmodell `Guide` und Sync

**Files:**
- Create: `shared/src/main/kotlin/de/wanderlogplus/shared/model/Guides.kt`
- Modify: `shared/src/main/kotlin/de/wanderlogplus/shared/sync/EntityRecord.kt:9` (`GUIDE, GUIDE_PREFS` ans Ende des Enums), `client/src/main/kotlin/de/wanderlogplus/client/TripSnapshot.kt` (Felder `guides`, `guidePrefs`), `server/src/main/kotlin/de/wanderlogplus/server/ai/ChatAgent.kt` (`ChatTrip.guides`, in `from(store)` laden)
- Test: `shared/src/test/kotlin/de/wanderlogplus/shared/model/GuidesTest.kt`

**Interfaces:**
- Produces (alle `@Serializable`):
  ```kotlin
  data class Guide(id, topic: String? = null, parentId: String? = null, title = "", summary = "",
      sections: List<GuideSection> = emptyList(), phrases: List<GuidePhrase> = emptyList(), sources: List<String> = emptyList(),
      extraTriggers: List<GuideTrigger> = emptyList(), pinnedTo: List<GuideTarget> = emptyList(), hiddenFrom: List<GuideTarget> = emptyList())
  data class GuideSection(heading: String, text: String = "", dos: List<String> = emptyList(), donts: List<String> = emptyList())
  data class GuidePhrase(german: String, japanese: String, romaji: String, situation: String = "")
  @Serializable data class GuideTarget(val type: GuideTargetType, val id: String)   // id: savedPlaceId | stayId | trainId | flightId | "YYYY-MM-DD"
  enum class GuideTargetType { PLACE, STAY, TRAIN, FLIGHT, DAY }
  sealed interface GuideTrigger {   // Klassen-Diskriminator "kind"
      @SerialName("text") data class Text(val words: List<String>, val on: TriggerOn = TriggerOn.ANY)  // Name oder Kategorien, ohne Groß/klein
      @SerialName("category") data class Category(val category: PlaceCategory)
      @SerialName("stay") data object AnyStay
      @SerialName("train") data class Train(val words: List<String> = emptyList())   // leer: jeder Zug; sonst carrier/number/via-Namen
      @SerialName("flight") data object AnyFlight
      @SerialName("always") data object Always
  }
  enum class TriggerOn { PLACE, STAY, ANY }
  data class GuidePrefs(val id: String = "prefs", val dismissedTopics: List<String> = emptyList())
  ```
  `TripSnapshot.guides: List<Guide>`, `TripSnapshot.guidePrefs: GuidePrefs` (fehlt → `GuidePrefs()`).

- [ ] **Step 1: Failing test** `guide fields survive the codec` – `EntityCodec.fields(guide)` → `EntityRecord` → `EntityCodec.decode<Guide>` ergibt dasselbe Objekt, inkl. aller sechs Trigger-Arten und `GuideTarget(DAY, "2027-05-19")`.
- [ ] **Step 2: Run** `./gradlew :shared:test --tests '*GuidesTest'` → FAIL.
- [ ] **Step 3: Implement** Modell, Enum-Einträge, Snapshot-/`ChatTrip`-Felder.
- [ ] **Step 4: Run** `./gradlew :shared:test :client:test :server:test` → PASS.
- [ ] **Step 5: Commit** `feat(guides): guide entity and sync`

### Task 4: Themenkatalog und `GuideMatch`

**Files:**
- Create: `shared/src/main/kotlin/de/wanderlogplus/shared/guide/GuideTopics.kt`, `shared/src/main/kotlin/de/wanderlogplus/shared/guide/GuideMatch.kt`
- Test: `shared/src/test/kotlin/de/wanderlogplus/shared/guide/GuideMatchTest.kt`

**Interfaces:**
- Consumes: Modell aus Task 3.
- Produces:
  ```kotlin
  data class GuideTopic(val key: String, val name: String, val parentKey: String? = null, val triggers: List<GuideTrigger>)
  object GuideTopics { val all: List<GuideTopic>; fun byKey(key: String): GuideTopic? }
  /** What the matching looks at; built from TripSnapshot (app) or ChatTrip (server). */
  data class GuideWorld(val places: Map<String, Place>, val saved: List<SavedPlace>, val stays: List<Stay>,
      val trains: List<Train>, val flights: List<Flight>, val guides: List<Guide>)
  object GuideMatch {
      fun topicsFor(target: GuideTarget, w: GuideWorld): List<String>          // Katalog-Schlüssel, Katalog-Reihenfolge
      fun guidesFor(target: GuideTarget, w: GuideWorld): List<Guide>           // Regeln ∪ extraTriggers ∪ pinnedTo − hiddenFrom; Eltern-Guide mit
      fun duplicateOf(topic: String?, title: String, guides: List<Guide>): Guide?
  }
  ```
  Ein Guide greift über seine Katalog-Trigger (`GuideTopics.byKey(topic)`) plus `extraTriggers`. `Always` greift nur für `DAY`-Ziele. Ziel eines `DAY`: alle Programmorte, Unterkünfte (Start/Nacht) und Fahrten des Tages (ohne `DayFlow`, das kommt in Task 5).
  `duplicateOf`: gleicher `topic` (nicht null), sonst Titel nach `PlaceMatch`-Normalisierung gleich oder einer enthält den anderen.

  **Katalog (fest):**

  | key | name | parent | triggers |
  |---|---|---|---|
  | `sprache-basics` | Japanisch für unterwegs | – | Always |
  | `bezahlen` | Bezahlen, Bargeld & Trinkgeld | – | Always |
  | `onsen` | Onsen & Sentō | – | Text(onsen, hot spring, sento, sentō, spa, 温泉), Text(ryokan, on=STAY) |
  | `ryokan` | Im Ryokan | – | Text(ryokan, minshuku, 旅館, on=STAY) |
  | `hotel` | Im Hotel | – | AnyStay |
  | `restaurant` | Im Restaurant bestellen | – | Category(FOOD) |
  | `omakase` | Omakase & Sushi-Theke | restaurant | Text(omakase, sushi, kaiseki, on=PLACE) |
  | `izakaya` | Izakaya | restaurant | Text(izakaya, yakitori, on=PLACE) |
  | `ramen` | Ramen & Ticketautomat | restaurant | Text(ramen, udon, soba, on=PLACE) |
  | `yakiniku` | Yakiniku & Grill | restaurant | Text(yakiniku, barbecue, offal, on=PLACE) |
  | `tempel-schrein` | Tempel & Schreine | – | Text(buddhist temple, shinto shrine, place of worship, sacred & religious, temple, shrine, jinja, dera, 寺, 神社, on=PLACE) |
  | `bahn` | Bahnfahren in Japan | – | Train() |
  | `shinkansen` | Shinkansen | bahn | Train(shinkansen, nozomi, hikari, kodama, hayabusa, mizuho, sakura, tsubame, kagayaki, hakutaka, 新幹線) |
  | `flughafen` | Flughafen & Einreise | – | AnyFlight |
  | `wandern` | Wandern & alte Handelswege | – | Text(hiking area, trail, historic walking areas, nakasendo, kumano, on=PLACE) |
  | `museum` | Museen | – | Text(museum, on=PLACE) |
  | `garten-park` | Gärten & Parks | – | Text(garden, park, nature & parks, on=PLACE) |
  | `einkaufen` | Einkaufen & Tax-free | – | Category(SHOPPING) |
  | `nachtleben` | Abends unterwegs | – | Category(NIGHTLIFE) |
  | `burg` | Burgen | – | Text(castle, 城, on=PLACE) |

- [ ] **Step 1: Failing tests** in `GuideMatchTest` (kleine Welt im Test: Ryokan-Unterkunft „Tsutaya Ryokan“ in Kiso, Orte „Fushimi Inari“ mit Kategorie `Shinto shrine`, „Sushi Saito“ (FOOD), ein Zug `number = "Nozomi 21"`, ein Ort ohne Kategorien und Namen):
  - `ryokan stay touches ryokan, onsen and hotel` → `topicsFor(STAY st) == listOf("onsen", "ryokan", "hotel")`.
  - `shinkansen train touches bahn and shinkansen`; ein Zug `carrier = "Deutsche Bahn"` nur `bahn`.
  - `sushi place touches restaurant and omakase`; Schrein → `tempel-schrein`.
  - `a place without name or categories touches nothing` → leere Liste, kein Fehler.
  - `guides for a target follow rules, pins and hides` – Guide `omakase` erscheint am Sushi-Ort samt Eltern-Guide `restaurant`; mit `hiddenFrom = [PLACE sushi]` nicht mehr (Eltern bleibt, wenn eigener Trigger greift); freier Guide mit `pinnedTo = [PLACE schrein]` nur dort; `pinnedTo` auf gelöschte id wirft nicht.
  - `always topics only on days` → `sprache-basics` bei `DAY`, nicht bei `PLACE`.
  - `duplicates by topic or title` – `duplicateOf("onsen", "x", [onsenGuide]) == onsenGuide`; `duplicateOf(null, "Onsen-Knigge", [Guide(title = "Onsen Knigge")])` trifft; `"Ramen"` vs `"Omakase"` nicht.
- [ ] **Step 2: Run** `./gradlew :shared:test --tests '*GuideMatchTest'` → FAIL.
- [ ] **Step 3: Implement.** Textvergleich: `words` klein, Name/Kategorien klein, `contains`. Für Unterkünfte zählen Name und Kategorien des Stay-Orts.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(guides): topic catalog and matching`

### Task 5: Tagesreihenfolge und Vorschläge

**Files:**
- Modify: `shared/src/main/kotlin/de/wanderlogplus/shared/guide/GuideMatch.kt`
- Create: `shared/src/main/kotlin/de/wanderlogplus/shared/guide/GuideSuggestions.kt`
- Test: `shared/src/test/kotlin/de/wanderlogplus/shared/guide/GuideDayTest.kt`

**Interfaces:**
- Consumes: `DayFlowView`/`FlowEntry` (`shared/.../query/DayFlow.kt`), Task 4.
- Produces:
  ```kotlin
  data class DayGuide(val guide: Guide, val atMinutes: Int?, val done: Boolean)
  fun GuideMatch.forDay(date: String, flow: DayFlowView, w: GuideWorld, nowMinutes: Int?): List<DayGuide>
  object GuideSuggestions {
      fun topicsInTrip(w: GuideWorld, days: List<String>): List<String>          // Katalog-Reihenfolge, ohne Duplikate
      fun open(w: GuideWorld, days: List<String>, prefs: GuidePrefs): List<String> // − Themen mit Guide − dismissedTopics
      fun openOn(date: String, w: GuideWorld, prefs: GuidePrefs): List<String>
  }
  ```
  `forDay`: Zeit eines Guides = früheste Zeit der `FlowEntry`, die ihn auslöst (Item-Zeit, Ride-Abfahrt, Night = Check-in-Zeit oder 18:00, Start = 00:00); `Always`-Guides ohne Zeit. `done` = Zeit < `nowMinutes`. Sortierung: nicht erledigt nach Zeit, dann ohne Zeit, dann erledigte.

- [ ] **Step 1: Failing tests** in `GuideDayTest` mit dem Tokio→Kiso-Fall aus `DayFlowTest` (Daten von dort übernehmen) plus Guides `shinkansen`, `ryokan`, `onsen`, `sprache-basics`:
  - `day guides come in the order of the day` → um 08:00: `[shinkansen, ryokan, onsen, sprache-basics]`; um 20:00: `ryokan`/`onsen` vorne, `shinkansen` mit `done = true` hinten.
  - `suggestions are trip topics without guides and dismissed ones` – `open` ohne Guides enthält `ryokan`; mit `dismissedTopics = ["ryokan"]` nicht; mit Guide `topic = "ryokan"` nicht; zwei Guides zum selben Thema → Thema einmal als vorhanden.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** `./gradlew :shared:test` → PASS.
- [ ] **Step 5: Commit** `feat(guides): day order and suggestions`

### Task 6: `GuideWriter` und Auftrag „guides“ auf dem Server

**Files:**
- Create: `server/src/main/kotlin/de/wanderlogplus/server/ai/GuideWriter.kt`
- Modify: `server/src/main/kotlin/de/wanderlogplus/server/ai/AiJobs.kt` (Art `"guides"`), `shared/src/main/kotlin/de/wanderlogplus/shared/sync/AiProtocol.kt` (`GuideRequest`, `AiJobInfo.guideIds: List<String> = emptyList()` am Ende), `server/src/main/kotlin/de/wanderlogplus/server/Application.kt` (`POST /api/ai/guides`), `server/src/main/kotlin/de/wanderlogplus/server/Main.kt` (`GuideWriter` an `AiJobs`), `client/.../SyncApi.kt` (`startGuides(req)`)
- Test: `server/src/test/kotlin/de/wanderlogplus/server/ai/GuideWriterTest.kt`

**Interfaces:**
- Consumes: `FactSource`, `completeJson` (Tasks 1–2), `GuideTopics`, `GuideMatch.duplicateOf`.
- Produces:
  ```kotlin
  @Serializable data class GuideAsk(val topic: String? = null, val title: String = "", val parentId: String? = null,
      val wish: String = "", val guideId: String? = null)   // guideId gesetzt: bestehenden Guide überarbeiten
  @Serializable data class GuideRequest(val asks: List<GuideAsk>)   // 1..30
  class GuideWriter(facts: FactSource?, model: ChatModel) {
      /** A complete guide (id = existing or new), or AiFailed. Free guides get extraTriggers from the model. */
      fun write(ask: GuideAsk, tripContext: String, existing: Guide?, say: (String) -> Unit = {}): Guide
  }
  // in AiJobs (neuer Konstruktor-Parameter guideWriter: GuideWriter? = null):
  fun startGuides(asks: List<GuideAsk>): AiJobInfo   // kind = "guides", items je Ask, nacheinander; ergebnis-ids in guideIds
  ```
  Guide-JSON vom Modell: `{title, summary, sections:[{heading,text,dos,donts}], phrases:[{german,japanese,romaji,situation}], extraTriggers:[…]}`; `valid`: `title`, `summary` nicht leer, mind. eine Section. `sources` = `Facts.sources`. Bei `existing`: Inhalt ersetzen, `pinnedTo`/`hiddenFrom`/`extraTriggers` (außer das Modell ändert sie auf `wish` hin) bleiben. `tripContext`: Monat der Reise, Regionen, betroffene Orte/Unterkünfte des Themas (Namen). Facts ohne Parallel: `ModelFacts(text)`.

- [ ] **Step 1: Failing tests** in `GuideWriterTest`:
  - `a topic guide is written from facts` – Fake-Facts + Fake-Modell → `Guide(topic = "onsen")`, `sources` aus den Fakten, Prompt enthält Fakten + `tripContext` + „Onsen & Sentō“.
  - `a free guide gets triggers from the model` – Ask `title = "Kiso-Wanderwege"` → `extraTriggers == listOf(GuideTrigger.Text(listOf("nakasendo"), TriggerOn.PLACE))` aus dem Modell-JSON; unbekannte Trigger-Arten werden verworfen.
  - `rewriting keeps pins and hides` – `existing` mit `pinnedTo`/`hiddenFrom` → bleiben, Titel/Sections neu.
  - `guides job keeps going after a failed topic` (über `AiJobs` mit Fake-Writer wie in `AssistantTest`) – 3 Asks, der zweite wirft `AiFailed` → 2 Guides per `writeResults` geschrieben, Item 2 im Auftrag als fehlgeschlagen, Status `DONE`.
  - `asking for an existing topic rewrites that guide` – Store hat Guide `onsen`; Ask `topic = "onsen"` ohne `guideId` → derselbe id wird überschrieben, kein zweiter Guide.
- [ ] **Step 2: Run** `--tests '*GuideWriterTest'` → FAIL.
- [ ] **Step 3: Implement.** Endpoint wie `/api/ai/research` (`AiNotConfigured`, `BadInput` bei 0 oder > 30 Asks).
- [ ] **Step 4: Run** `./gradlew :server:test :client:test` → PASS.
- [ ] **Step 5: Commit** `feat(guides): guide writer and guides job`

### Task 7: Chat-Werkzeuge für Guides

**Files:**
- Modify: `server/src/main/kotlin/de/wanderlogplus/server/ai/ChatAgent.kt` (`ChatTools`-Definitionen + `run`, System-Prompt-Regeln, `ChatContextText.guides(t)` im festen Teil), `server/src/main/kotlin/de/wanderlogplus/server/Main.kt` / `ChatService` (Callbacks durchreichen)
- Test: `server/src/test/kotlin/de/wanderlogplus/server/ai/ChatAgentTest.kt`

**Interfaces:**
- Consumes: Tasks 3–6.
- Produces: neuer `ChatAgent`-Parameter
  ```kotlin
  interface ChatGuides {
      fun saveGuide(g: Guide)                      // Zuordnen/Regeln: sofort, alle Felder per sync.writeAsServer
      fun start(asks: List<GuideAsk>): AiJobInfo   // Erstellen/Überarbeiten: Auftrag über AiJobs.startGuides
  }
  // ChatAgent(..., guides: ChatGuides? = null); null: Guide-Werkzeuge fehlen in tools.definitions
  ```
  Werkzeuge (Namen exakt): `list_guides`, `create_guide(topic?, title?, parent?, wish?)`, `update_guide(guideId, wish)`, `assign_guide(guideId, targetType, targetId)`, `unassign_guide(guideId, targetType, targetId)`, `set_guide_triggers(guideId, triggers)`.
  `assign_guide` entfernt das Ziel aus `hiddenFrom` und fügt es zu `pinnedTo` hinzu; `unassign_guide` umgekehrt. `create_guide` prüft `duplicateOf` und ruft dann `start` mit `guideId` des Duplikats. Ziel-ids werden gegen `ChatTrip` geprüft (unbekannt → Fehlertext an das Modell, nichts geschrieben).
  Prompt: Regel „Du kannst nichts an der Reise ändern“ wird zu „… außer an Guides: Guides darfst du erstellen, überarbeiten und zuordnen. ‚Dieser Ort‘ → assign_guide, ‚alle …‘ → set_guide_triggers.“ Fester Kontext bekommt „Guides:“-Liste (`- Titel [id=…] · Thema · Zuordnungen`).

- [ ] **Step 1: Failing tests** in `ChatAgentTest`:
  - `assign and unassign a guide` – `run("assign_guide", {guideId, "PLACE", "s-kiyo"})` → `saveGuide` mit `pinnedTo` enthält `s-kiyo`; danach `unassign_guide` → in `hiddenFrom`, nicht mehr in `pinnedTo`.
  - `unknown targets are refused` – `targetId = "s-nope"` → Antworttext beginnt mit `"Unbekanntes Ziel"`, `saveGuide` nie aufgerufen.
  - `set triggers replaces the extra triggers` – `{"triggers":[{"kind":"text","words":["ryokan"],"on":"STAY"}]}`.
  - `create guide extends an existing topic` – `ChatTrip.guides` hat `onsen`; `create_guide(topic="onsen")` → `start` mit `GuideAsk(guideId = onsen.id, …)`.
  - `the cached context lists the guides` – `systemParts(...).first` enthält `"- Onsen & Sentō [id=g1] · onsen"`.
- [ ] **Step 2: Run** `--tests '*ChatAgentTest'` → FAIL. **Step 3: Implement.** **Step 4: Run** `./gradlew :server:test` → PASS.
- [ ] **Step 5: Commit** `feat(chat): create, update and assign guides`

### Task 8: App – Guide-Seite und Übersicht mit Vorschlägen

**Files:**
- Create: `app/src/main/kotlin/de/wanderlogplus/app/ui/guides/GuideScreen.kt`, `GuidesScreen.kt`, `GuidesViewModel.kt`, `PhraseSpeaker.kt`
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/ui/AppNavigation.kt` (Routen `guides`, `guide/{id}`, Einstieg im Mehr-Menü `ui/more`)
- Test: `app/src/test/kotlin/de/wanderlogplus/app/ui/guides/GuidesViewModelTest.kt`

**Interfaces:**
- Consumes: `TripSnapshot.guides/guidePrefs`, `GuideSuggestions.open`, `GuideTopics`, `SyncApi.startGuides`.
- Produces: `GuidesViewModel` mit `dismiss(topic)`, `createSelected(topics: List<String>)`, `save(guide)`, `delete(id)`, `pin(guideId, target)`, `hide(guideId, target)` (alle über `LocalEditor`, Typ `GUIDE`/`GUIDE_PREFS`); Composable `GuideChips(guides: List<Guide>, onOpen: (String) -> Unit, onHide: ((String) -> Unit)?, onAdd: (() -> Unit)?)` für Task 9.
  `PhraseSpeaker`: kapselt `TextToSpeech` mit `Locale.JAPAN`; `ready: Boolean` false bei `LANG_MISSING_DATA`/`LANG_NOT_SUPPORTED`.

- [ ] **Step 1: Failing tests** `GuidesViewModelTest`: `dismiss` schreibt `GuidePrefs.dismissedTopics`; `hide` entfernt aus `pinnedTo` und fügt zu `hiddenFrom` hinzu; `createSelected` ruft `startGuides` mit `GuideAsk(topic = …)` je Thema (Fake-Api wie in bestehenden ViewModel-Tests).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*GuidesViewModelTest'` → FAIL.
- [ ] **Step 3: Implement** ViewModel und Screens nach Spec-Abschnitt „Anzeige“: Guide-Seite (Zusammenfassung, Sections mit ✓/✗-Listen, Unterthemen, Satzkarten mit Lautsprecher und Vollbild, Quellen, Menü Bearbeiten/Im Chat verbessern/Löschen); Übersicht nach Oberthema gruppiert, oben Vorschläge mit Haken + „Ausgewählte erstellen“ + laufende `guides`-Aufträge mit Fortschritt; „Im Chat verbessern“ öffnet den Chat mit Vorgabetext „Verbessere den Guide „<Titel>“: “.
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest` und `./gradlew :app:assembleDebug` → PASS / BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `feat(app): guide pages, overview and suggestions`

### Task 9: App – Chips auf Detailseiten und „Für heute“

**Files:**
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/ui/places/PlaceDetailScreen.kt`, `app/src/main/kotlin/de/wanderlogplus/app/ui/logistics/LogisticsDetailScreen.kt` (Züge, Flüge, Unterkünfte), Tagesansicht in `app/src/main/kotlin/de/wanderlogplus/app/ui/trip/`, `app/src/main/kotlin/de/wanderlogplus/app/ui/today/TodayScreen.kt` (`During`), `app/src/main/kotlin/de/wanderlogplus/app/ui/today/TodayWidgets.kt`
- Create: `app/src/main/kotlin/de/wanderlogplus/app/ui/guides/GuideWorldOf.kt` (`fun TripSnapshot.guideWorld(): GuideWorld`)
- Test: `app/src/test/kotlin/de/wanderlogplus/app/ui/guides/GuideWorldOfTest.kt`

**Interfaces:**
- Consumes: `GuideMatch.guidesFor/forDay`, `GuideSuggestions.openOn`, `GuideChips` (Task 8).
- Produces: Widget `TodayGuides(dayGuides: List<DayGuide>, suggested: Int, onOpen, onSuggestions)` unter der Jetzt-/Nächste-Fahrt-Karte; Bottom-Sheet mit `summary` + erste 3 `dos`/`donts` + „Ganzen Guide öffnen“.

- [ ] **Step 1: Failing test** `snapshot world has everything matching needs` – `TripSnapshot.from(records)` mit Guide, Stay, Zug → `guideWorld()` enthält sie; `GuideMatch.guidesFor(STAY …)` liefert den Ryokan-Guide.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** Chips (lange drücken → „Hier ausblenden“, Plus → Guide-Auswahl zum Anheften) und „Für heute“ (nur sichtbar mit Inhalt, wie die anderen Widgets).
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest :app:assembleDebug` → PASS.
- [ ] **Step 5: Commit** `feat(app): guide chips on details and today card`

### Task 10: Abschluss

- [ ] **Step 1:** `CHANGELOG.md` Abschnitt `## 0.40.0` (Guides, Vorschläge, Chat-Werkzeuge, Ortsrecherche bewertet mit Sonnet; „Benötigt das Server-Update“), `versionCode = 49`, `versionName = "0.40.0"`; `server/README.md`: `/api/ai/guides`.
- [ ] **Step 2:** `./gradlew :shared:test :client:test :server:test :app:testDebugUnitTest :app:assembleDebug` → alles grün.
- [ ] **Step 3:** Commit, lokal in `main` mergen (`--no-ff`), nicht pushen.
- [ ] **Step 4 (manuell, mit Nutzer):** nach Deploy Grundstock-Stichprobe mit 3 Themen (`onsen`, `restaurant`, `shinkansen`) auf Qualität prüfen; eine Ortsrecherche vergleichen; TTS + Vollbild am Gerät.
