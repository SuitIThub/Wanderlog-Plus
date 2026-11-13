# Reel-Infos zu Orten, kurze Guide-Titel, straffe Guides — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ortsbezogene Reel-Infos landen knapp in der Recherche des Orts, Guides bekommen kurze Titel und ein Format „Auf einen Blick + Kern + Mehr dazu“, vorhandene Guides lassen sich straffen (alle oder einzeln).

**Architecture:** Shared-Modelle bekommen optionale Felder (`GuideSection.details`, `PlaceDossier.reelFacts`, `ReelTip.placeIds`, `ReelCandidate.reelFacts`). Server: Prompts in `GuideWriter`/`TipCheck`/`Assistant` geschärft, neue `GuideWriter.tidy`, neue Klasse `PlaceFacts` (Merge ins Dossier), zwei neue Jobs/Routen nach dem Muster von `guide-links`. App: Guide-Screen eingeklappt, Straffen-Menüs, Reel-Screen mit Ort-Zielen.

**Tech Stack:** Kotlin, Ktor-Server, kotlinx.serialization, Jetpack Compose, kotlin.test/JUnit.

**Spec:** `docs/superpowers/specs/2026-11-13-reel-place-facts-guide-tidy-design.md`

## Global Constraints

- Alle neuen Felder haben Defaults (`""` / `emptyList()`), ältere gespeicherte Daten bleiben lesbar.
- Guide-Titel: nur Richtlinie im Prompt, **kein** serverseitiges Kürzen. Beispiele im Prompt: „Shinkansen & Suica“ statt „Shinkansen & Suica: Tokyo – Nagoya – Shin-Osaka – Hiroshima“, „Yakiniku & Grill“ statt „Yakiniku & Grill in Japan“. Die Beispiele zeigen nur, was weg soll — keine Vorlage: der Prompt sagt ausdrücklich, dass „A & B“ kein Muster ist, und nennt Titel anderer Form („Trinkgeld“, „Onsen-Etikette“, „Bezahlen mit Karte“, „Nachtzüge“).
- summary = „Auf einen Blick“: 3–5 Zeilen, durch `\n` getrennt, je ~80 Zeichen, ohne Aufzählungszeichen.
- Höchstens 6 Abschnitte, text 1–2 Sätze, höchstens 4 dos und 4 donts, details ≤ ~600 Zeichen.
- Reel-Inhalte: nur die Aussage, nichts über Beitrag/Person/Geschichte.
- `FoundPlace.facts` ≤ 4, je ≤ 200 Zeichen. `PlaceFactsRequest`: 1–20 Items, je 1–12 facts. `GuideTidyRequest.guideIds` ≤ 200.
- App-Texte auf Deutsch. Version 0.72.0 (versionCode 92) mit CHANGELOG-Abschnitt.
- Build/Tests: `./gradlew :server:test`, `:shared:test`, `:app:testDebugUnitTest` (JAVA_HOME muss gesetzt sein, siehe frühere Sessions).

## Review Focus

- Modell liefert beim Straffen weniger Sätze zum Zeigen → Guide bleibt unverändert, Item FAILED (Test in Task 3).
- `TipCheck` liefert eine `placeId`, die es nicht gibt, oder 3+ Ids → unbekannte verworfen, höchstens 2, ohne gültige fällt der Tipp auf das Guide-Ziel zurück (Test in Task 5).
- Place-facts für einen gelöschten Ort → Item FAILED „Ort nicht gefunden“, Rest läuft (Test in Task 6).
- Neu-Recherche eines Orts mit `reelFacts` → `reelFacts` bleiben im neuen Dossier (Test in Task 6).
- Alte Guides mit Fließtext-summary → App zeigt eine Zeile, kein Absturz bei leeren Zeilen (Test in Task 8).

---

### Task 1: Shared-Modelle und Protokoll

**Files:**
- Modify: `shared/src/main/kotlin/de/wanderlogplus/shared/model/Guides.kt` (`GuideSection`)
- Modify: `shared/src/main/kotlin/de/wanderlogplus/shared/model/Research.kt` (`PlaceDossier`)
- Modify: `shared/src/main/kotlin/de/wanderlogplus/shared/sync/AiProtocol.kt`
- Test: `shared/src/test/kotlin/.../GuidesTest.kt` (vorhanden) 

**Interfaces — Produces:**
- `GuideSection(heading, text, dos, donts, details: String = "")`
- `PlaceDossier.reelFacts: List<String> = emptyList()`
- `ReelTip.placeIds: List<String> = emptyList()`
- `ReelCandidate.reelFacts: List<String> = emptyList()`
- `@Serializable data class GuideTidyRequest(val guideIds: List<String> = emptyList())`
- `@Serializable data class PlaceFactsItem(val savedPlaceId: String, val facts: List<String>, val sources: List<String> = emptyList())`
- `@Serializable data class PlaceFactsRequest(val items: List<PlaceFactsItem>)`

- [ ] **Step 1: Failing test** `old guide json without details still decodes` — JSON einer Section ohne `details` dekodiert zu `details == ""`; `EntityCodec.fields(guide)` → zurück ergibt gleiches `details` (Roundtrip mit `details = "Hintergrund"`).
- [ ] **Step 2:** `./gradlew :shared:test` → FAIL (unresolved `details`).
- [ ] **Step 3:** Felder und Klassen wie oben ergänzen (KDoc auf Englisch wie im Projekt).
- [ ] **Step 4:** `./gradlew :shared:test` → PASS.
- [ ] **Step 5:** Commit `feat(shared): guide section details, reel facts and place targets`.

### Task 2: GuideWriter — neues Format und Titel-Richtlinie

**Files:**
- Modify: `server/src/main/kotlin/de/wanderlogplus/server/ai/GuideWriter.kt`
- Test: `server/src/test/kotlin/de/wanderlogplus/server/ai/GuideWriterTest.kt`

**Interfaces — Produces:** `GuideWriter.FORMAT_RULES: String` (companion, `internal const`), von `write`, `merge`, `tidy` genutzt.

- [ ] **Step 1: Failing tests**
  - `sections carry details` — Antwort mit `"details":"Hintergrund"` → `g.sections.single().details == "Hintergrund"`.
  - `prompts carry the format rules` — Scripted-Model, das auch `system` mitschreibt: der System-Prompt von `write` und von `merge` enthält „Shinkansen & Suica“, „Onsen-Etikette“, „kein Muster“, „Auf einen Blick“ und „details“; der Titel aus der Antwort wird unverändert übernommen (`"Shinkansen & Suica: Tokyo – Nagoya"` bleibt so).
  - `merge keeps details` — Merge eines Guides mit `details` in einer Section, Antwort behält sie → im Ergebnis vorhanden.
- [ ] **Step 2:** `./gradlew :server:test --tests '*GuideWriterTest*'` → FAIL.
- [ ] **Step 3:** `FORMAT_RULES` schreiben (Inhalt = Global Constraints: Titel-Richtlinie mit beiden Beispielen, summary 3–5 Zeilen, ≤6 Abschnitte, text 1–2 Sätze, ≤4 dos/donts, details-Regel, Reel-Ergänzungen nur Aussage). In beide JSON-Formate `"details": "Hintergrund zum Vorbereiten oder leer"` aufnehmen; `parsed` liest `details`. `merge` übergibt `existing` inklusive details (Serialisierung macht das schon).
- [ ] **Step 4:** Tests → PASS (auch alle vorhandenen GuideWriter-Tests).
- [ ] **Step 5:** Commit `feat(server): guides written as glance, core and details; short title guideline`.

### Task 3: GuideWriter.tidy

**Files:** wie Task 2.

**Interfaces — Produces:**
- `fun tidy(existing: Guide, say: (String) -> Unit = {}): Guide` — wirft `AiFailed("ai_bad_answer")` über `completeJson`, wenn die Prüfung nie besteht.
- `internal fun tidyKeeps(before: Guide, after: Guide): Boolean` — `after.sections` nicht leer, `after.phrases.size == before.phrases.size`, summary nicht leer und `!mostlyJapanese(summary)`.

- [ ] **Step 1: Failing tests**
  - `tidy reshapes and keeps the rest` — Guide mit id, topic, pinnedTo, hiddenFrom, links, sources, extraTriggers, parentId, 1 Phrase; Antwort mit neuem Titel, 2 Sections mit details, 1 Phrase → alles außer title/summary/sections/phrases identisch zu vorher; neue Sections übernommen.
  - `tidy rejects lost phrases` — Antwort immer mit 0 Phrasen → `assertFailsWith<AiFailed>`.
  - `tidy prompt asks to keep all content` — System-Prompt enthält „kein Inhalt geht verloren“ und `FORMAT_RULES`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implementieren: System-Prompt „baue den Guide ins neue Format um; kein Inhalt geht verloren — was nicht in den Kern passt, nach details; Doppeltes zusammenfassen; Sätze alle behalten“ + `FORMAT_RULES`; User = Guide-JSON wie in `merge`. Ergebnis `existing.copy(title, summary, sections, phrases)`.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(server): GuideWriter.tidy reshapes a guide without losing content`.

### Task 4: Job und Route `guide-tidy`

**Files:**
- Modify: `server/src/main/kotlin/de/wanderlogplus/server/ai/AiJobs.kt` (neben `startGuideLinks`)
- Modify: `server/src/main/kotlin/de/wanderlogplus/server/Application.kt` (neben `/api/ai/guide-links`)
- Modify: `client/src/main/kotlin/de/wanderlogplus/client/SyncApi.kt`
- Test: der Test, der `startGuideLinks` abdeckt (per `grep -rn startGuideLinks server/src/test` finden) — dort ergänzen.

**Interfaces:**
- Consumes: `GuideWriter.tidy`, `GuideTidyRequest`.
- Produces: `AiJobs.startGuideTidy(guideIds: List<String>): AiJobInfo` (kind `"guides"`, ein Item pro Guide, `guideIds` gesetzt); Route `POST /api/ai/guide-tidy` (`guidesConfigured` sonst `AiNotConfigured`, > 200 Ids → `BadInput`); `SyncApi.startGuideTidy(guideIds: List<String> = emptyList()): AiJobInfo`.

- [ ] **Step 1: Failing tests**
  - `guide tidy writes each guide` — zwei Guides im Store, Model antwortet gültig → nach Job beide mit neuem Titel im Store, `writtenAt` unverändert, Job DONE.
  - `guide tidy keeps going after one failure` — Model scheitert für Guide A (0 Phrasen), B ok → A unverändert, Item A FAILED, B geschrieben, Job DONE.
  - `only the given guide is tidied` — `startGuideTidy(listOf(b.id))` → nur B geändert.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** `startGuideTidy`/`runGuideTidy` analog `startGuideLinks`/`runGuideLinks`; schreibt per `writeResults` mit `EntityCodec.fields(g)`; Step-Texte „<Titel>: gestrafft“. Route und Client-Methode analog.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(server): guide-tidy job for all or single guides`.

### Task 5: Reel-Analyse mit Ort-Fakten, TipCheck mit Ort-Zielen

**Files:**
- Modify: `server/src/main/kotlin/de/wanderlogplus/server/ai/Assistant.kt` (`FoundPlace`, `analyze`, `research`)
- Modify: `server/src/main/kotlin/de/wanderlogplus/server/ai/TipCheck.kt`
- Modify: `server/src/main/kotlin/de/wanderlogplus/server/ai/AiJobs.kt` (`runReel`: Orte an `checkAll` übergeben)
- Test: `AssistantTest.kt`, `TipCheckTest.kt`

**Interfaces — Produces:**
- `FoundPlace.facts: List<String> = emptyList()`
- `TipCheck.match(tips, guides, places: List<PlaceRef> = emptyList(), say)` und `checkAll(tips, guides, places: List<PlaceRef> = emptyList(), progress)`
- `data class PlaceRef(val savedPlaceId: String, val name: String, val city: String?)` in `TipCheck.kt`
- `ChatTrip.placeRefs(): List<PlaceRef>` (nicht DROPPED, höchstens 300, Stadt = Regionsname)

- [ ] **Step 1: Failing tests**
  - AssistantTest `places carry the reel's facts` — Analyse-Antwort mit `"facts":["Montags zu","Bestes Gericht: Tonkotsu", "a","b","c"]` und einem 300-Zeichen-Fakt → höchstens 4, jeder ≤ 200 Zeichen.
  - AssistantTest `reel facts go into the research evidence` — FakeModel zeichnet die Research-Query auf; ein `FoundPlace` mit facts → `evidence` enthält „Laut Beitrag: Montags zu“.
  - AssistantTest `candidate keeps reel facts` — `research(...)` liefert `ReelCandidate.reelFacts == p.facts`, auch im „bereits gespeichert“-Zweig.
  - TipCheckTest `a tip about one place targets the place` — Antwort `"placeIds":["sp1"]` mit `places=[PlaceRef("sp1","Ichiran","Osaka")]` → `placeIds == ["sp1"]`, guideId/targetTopic/targetTitle null.
  - TipCheckTest `unknown and extra place ids are dropped` — `["x","sp1","sp2","sp3"]` → `["sp1","sp2"]`; nur `["x"]` → Fallback wie bisher (targetTitle = topic).
  - TipCheckTest `the prompt lists the trip's places and the guide rule` — User-Prompt enthält „sp1 | Ichiran | Osaka“; System-Prompt enthält „nie für einen einzelnen Ort“.
- [ ] **Step 2:** `./gradlew :server:test --tests '*AssistantTest*' --tests '*TipCheckTest*'` → FAIL.
- [ ] **Step 3:** Implementieren:
  - `analyze`-Prompt: `locations[].facts` („bis zu 4 Aussagen des Beitrags über genau diesen Ort, je ein kurzer Satz auf Deutsch, nur die Aussage“) und Regel „Aussagen zu einem oder zwei konkreten Orten gehören in facts dieser Orte, nicht in tips“.
  - `research`: evidence um `"Laut Beitrag: " + facts.joinToString("; ")` ergänzen; `reelFacts = p.facts` in beiden Rückgaben.
  - `TipCheck.match`: Prompt-Ziel `placeIds` + Regel „title/topic nur für allgemeine Themen, die viele Orte oder die ganze Reise betreffen; nie für einen einzelnen Ort“ + Titel-Richtlinie (`GuideWriter.FORMAT_RULES`-Titelzeile oder eigene Konstante `TITLE_RULE` in GuideWriter, die FORMAT_RULES einbindet); Auswertung: `placeIds` auf bekannte filtern, `take(2)`, wenn nicht leer → Vorrang vor guideId/topic/title (nicht vor covered).
  - `runReel`: `tipCheck.checkAll(analysis.tips, trip.guides, trip.placeRefs(), t)`.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(server): reel facts per place and place targets for tips`.

### Task 6: PlaceFacts — Merge ins Dossier, Job, Route, Recherche behält reelFacts

**Files:**
- Create: `server/src/main/kotlin/de/wanderlogplus/server/ai/PlaceFacts.kt`
- Create: `server/src/test/kotlin/de/wanderlogplus/server/ai/PlaceFactsTest.kt`
- Modify: `AiJobs.kt` (Konstruktor-Param `placeFacts: PlaceFacts? = null`, `startPlaceFacts`), `Application.kt`, `Main.kt` (Wiring wie `tipCheck`, mit dem Textmodell), `Assistant.researchSaved`, `SyncApi.kt`

**Interfaces — Produces:**
- `class PlaceFacts(model: ChatModel) { fun merge(old: PlaceDossier?, placeName: String, facts: List<String>, sources: List<String>, say: (String) -> Unit = {}): PlaceDossier }`
- `internal fun keepsDossier(before: PlaceDossier?, after: PlaceDossier): Boolean` — alle alten Einträge von goodToKnow/tips/hints sind in den neuen enthalten.
- `AiJobs.startPlaceFacts(items: List<PlaceFactsItem>): AiJobInfo` (kind `"research"`, `savedPlaceIds` gesetzt, ein Item pro Ort)
- Route `POST /api/ai/place-facts` (`configured` sonst `AiNotConfigured`; Items 1–20, facts je 1–12, sonst `BadInput`)
- `SyncApi.startPlaceFacts(items: List<PlaceFactsItem>): AiJobInfo`

- [ ] **Step 1: Failing tests (PlaceFactsTest)**
  - `facts are sorted in and old entries stay` — altes Dossier `goodToKnow=["A"]`, `researchedAt=5`; Antwort `{"goodToKnow":["A","Montags zu"],"tips":["Tonkotsu nehmen"],"hints":[]}` → beide Listen übernommen, `researchedAt == 5`, `reelFacts` enthält die übergebenen facts, `sources` um die neuen ergänzt (distinct).
  - `a merge that drops old entries fails` — Antwort ohne „A“ → `AiFailed`.
  - `without a dossier one is made` — `old = null` → Dossier mit `researchedAt == 0` und den Listen.
  - `prompt asks for short sentences without context` — System-Prompt enthält „ein Satz“ und „nichts über den Beitrag“.
  - Job-Test `place facts write the dossier and skip unknown places` — Store mit sp1; Items sp1 + „gone“ → sp1-Dossier geschrieben, Item „gone“ FAILED „Ort nicht gefunden“, Job DONE.
  - Research-Test `research keeps reel facts` — sp1 mit Dossier `reelFacts=["Montags zu"]`; `researchSaved` → Query-evidence enthält „Laut Reel: Montags zu“, Ergebnis-Dossier hat `reelFacts == ["Montags zu"]`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implementieren:
  - `merge`: Prompt mit altem goodToKnow/tips/hints und neuen facts; Regeln: nur Neues, je ein Satz, nichts über den Beitrag, alte Einträge wörtlich behalten. `completeJson(..., GERMAN_PLEASE) { keepsDossier(old, parsed) }`. `reelFacts = (old.reelFacts + facts).distinct()`.
  - Job: pro Item Saved Place aus Store laden (gelöscht/fehlt → FAILED „Ort nicht gefunden“), Name über PLACE, `merge`, schreiben als `ServerEdit(SAVED_PLACE, id, mapOf("dossier" to …))` direkt (nicht über `writeDossier`, das neuere `researchedAt` verlangt).
  - `researchSaved`: altes `s.dossier?.reelFacts` → evidence `"Laut Reel: …"` anhängen (evidence-Länge insgesamt ≤ 600) und `reelFacts` ins neue Dossier kopieren.
- [ ] **Step 4:** `./gradlew :server:test` → PASS (alles).
- [ ] **Step 5:** Commit `feat(server): place-facts job works reel facts into a place's research`.

### Task 7: App — Reel-Screen mit Ort-Zielen und „Neu aus dem Reel“

**Files:**
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/ui/reels/Reels.kt` (`tipAsks`, neue `placeFactItems`, `importLabel`)
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/ui/reels/ReelScreens.kt` (`ReelTipCard`, Kandidaten-Karte, `onImport`)
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/ui/reels/ReelsViewModel.kt` (`import`)
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/ui/MainActivity.kt` falls die API-Lambda dort verdrahtet wird
- Test: `app/src/test/kotlin/de/wanderlogplus/app/ui/reels/ReelTipsTest.kt`

**Interfaces:**
- Consumes: `ReelTip.placeIds`, `ReelCandidate.reelFacts`, `SyncApi.startPlaceFacts`.
- Produces:
  - `fun tipAsks(picked)` ignoriert Tipps mit `placeIds` nicht leer.
  - `fun placeFactItems(tips: List<ReelTip>, savedFacts: List<ReelCandidate>): List<PlaceFactsItem>` — Tipps mit Ort-Ziel (Text = `text.ifBlank{claim}`, Quellen ≤ 20) und gewählte gespeicherte Kandidaten (`savedPlaceId` + `reelFacts`), gruppiert pro `savedPlaceId`, facts je Ort ≤ 12, Items ≤ 20.
  - `onImport: (List<ReelPick>, List<ReelTip>, List<ReelCandidate>) -> Unit` — dritter Parameter: gespeicherte Kandidaten mit Haken „Zum Ort hinzufügen“.
  - `importLabel(places: Int, tips: Int)` unverändert; Ort-Infos zählen bei `tips` mit (Kandidaten-Fakten als 1 je Ort).

- [ ] **Step 1: Failing tests (ReelTipsTest)**
  - `place tips do not go into guides` — ein Tipp mit `placeIds=["sp1"]`, einer mit guideId → `tipAsks` liefert nur einen Ask.
  - `place facts are grouped per place` — Tipp auf sp1 + Kandidat sp1 mit 2 reelFacts + Tipp auf sp2 → 2 Items, sp1 mit 3 facts, Quellen des Tipps dabei.
  - `facts per place are capped` — 15 facts für sp1 → 12.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests '*ReelTipsTest*'` → FAIL.
- [ ] **Step 3:** Implementieren:
  - `placeFactItems` in `Reels.kt`.
  - `ReelTipCard`: Optionen um `"p:<id>" to "In Recherche: <Name>"` für jede Id aus `tip.placeIds` und jeden gespeicherten Kandidaten des Reels (Name aus `snapshot`/Kandidat); Auswahl setzt `placeIds = listOf(id)` und Guide-Ziele null; `current` = `"p:"+placeIds.first()` wenn gesetzt.
  - Kandidaten-Karte mit `savedPlaceId != null && reelFacts.isNotEmpty()`: Überschrift „Neu aus dem Reel“, facts als Stichpunkte, Checkbox „Zum Ort hinzufügen“ (Tag `reel-facts-$i`, vorausgewählt, deaktiviert nach Import).
  - `ReelsViewModel.import(jobId, picks, tips, savedFacts, source)`: Guide-Tipps wie bisher über `startTips`; `placeFactItems(...)` nicht leer → `startPlaceFacts`; Fehler wie bei `startTips` mit Meldung „Die Infos zu Orten konnten nicht übernommen werden: …“. Meldung bei Erfolg: „… werden in Guides und Orte übernommen“ wenn beides, sonst passend. Button aktiv, wenn auch nur Ort-Infos gewählt sind.
- [ ] **Step 4:** Run → PASS; `./gradlew :app:compileDebugKotlin` grün.
- [ ] **Step 5:** Commit `feat(app): reel infos go into a place's research`.

### Task 8: App — Guide-Screen eingeklappt, Straffen-Menüs

**Files:**
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/ui/guides/GuideScreens.kt`
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/ui/guides/GuidesViewModel.kt` (`GuideActions.tidy`, Konstruktor-Param `startGuideTidy`)
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/ui/MainActivity.kt:341` (Verdrahtung)
- Test: `app/src/test/kotlin/de/wanderlogplus/app/ui/guides/GuidesViewModelTest.kt`, neue Pure-Funktionen-Tests in `GuideJobsTest.kt` oder neuer `GuideTextTest.kt`

**Interfaces — Produces:**
- `fun glanceLines(summary: String): List<String>` — split an `\n`, trim, führende „•“/„-“/„*“ entfernen, Leere weg.
- `fun coreLine(text: String): String` — erster Satz (bis zum ersten `. `/`! `/`? ` inkl. Zeichen), sonst ganzer Text.
- `GuideActions.tidy(guideIds: List<String>)` (Default `{}`); leer = alle.
- `GuidesViewModel(..., startGuideTidy: suspend (List<String>) -> AiJobInfo? = { null })`

- [ ] **Step 1: Failing tests**
  - `glance lines from bullets and plain text` — `"• A\n- B\n\n C "` → `["A","B","C"]`; `"Ein Satz."` → `["Ein Satz."]`.
  - `core line is the first sentence` — `"Erst waschen. Dann baden."` → `"Erst waschen."`; ohne Punkt → ganzer Text.
  - GuidesViewModelTest `tidy starts the job for one guide` — Fake-Lambda zeichnet `listOf("g1")` auf, Job erscheint in `jobs`, Meldung „Guides werden gestrafft …“; Fake liefert null → Meldung „Guides lassen sich gerade nicht straffen (Server nicht erreichbar?).“
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests '*Guide*'` → FAIL.
- [ ] **Step 3:** Implementieren:
  - Guide-Screen: Karte „Auf einen Blick“ mit `glanceLines` als Stichpunkte; jeder Abschnitt als einklappbare Karte (eingeklappt: heading + `coreLine(text)`; aufgeklappt: text, DoLines, bei `details` nicht leer TextButton „Mehr dazu“/„Weniger“ und details-Text); „Sätze zum Zeigen (n)“ einklappbar; alle Texte weiter durch `euroed`.
  - Menü im Guide: „Straffen“ → `actions.tidy(listOf(g.id))`, Fortschritt über die vorhandene Job-Anzeige.
  - Guides-Übersicht: Menüeintrag „Guides straffen“ neben „Links ergänzen“ → `tidy(emptyList())`. (Abweichung von der Spec „Mehr → KI“: dort, wo der verwandte Job „Links ergänzen“ schon liegt.)
  - `GuideRow`-Vorschau: `glanceLines(summary).take(2).joinToString(" · ")`.
  - Bearbeiten-Dialog: Label „Auf einen Blick (eine Zeile pro Punkt)“.
- [ ] **Step 4:** Run → PASS; `./gradlew :app:compileDebugKotlin` grün.
- [ ] **Step 5:** Commit `feat(app): guides as glance, folded sections and details; tidy all or one`.

### Task 9: Version, CHANGELOG, Gesamttest, Merge

**Files:** `app/build.gradle.kts` (versionCode 92, versionName "0.72.0"), `CHANGELOG.md`

- [ ] **Step 1:** Version setzen; CHANGELOG-Abschnitt 0.72.0 (Deutsch, Stil der vorhandenen Einträge): Reel-Infos zu Orten in die Recherche, kurze Guide-Titel, Guides mit „Auf einen Blick“/eingeklappten Abschnitten/„Mehr dazu“, Guides straffen (alle oder einzeln).
- [ ] **Step 2:** `./gradlew :shared:test :server:test :app:testDebugUnitTest` → alles grün.
- [ ] **Step 3:** Commit `chore: 0.72.0`; Branch lokal in `main` mergen (`git checkout main && git merge --no-ff feat/reel-place-facts-guide-tidy`).
