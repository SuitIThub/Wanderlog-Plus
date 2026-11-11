# Bild-Posts, Tipps → Guides, App-/Web-Links — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reel-Import versteht Bild-/Galerie-Posts, erkennt und prüft Reisetipps und schlägt sie als Guide-Ergänzungen vor; Guides bekommen geprüfte App-/Web-Links, die überall dort erscheinen, wo die Guides gezeigt werden.

**Architecture:** Server: `ToolReelMedia` fällt auf gallery-dl zurück; `Assistant.analyze` liefert Orte und Tipps; neue `TipCheck` (Abgleich + Faktencheck) und `LinkCheck` (HTTP-Prüfung); `GuideWriter` bekommt Links und einen Ergänzen-Modus; neuer Job „Links ergänzen“. App: Tipp-Bereich im Reel-Ergebnis, Link-Chips in Guide-Detail, `GuidesSection` und „Für heute“.

**Tech Stack:** Kotlin, Ktor-Server, kotlinx.serialization, Jetpack Compose, JUnit; yt-dlp, gallery-dl, ffmpeg.

**Spec:** `docs/superpowers/specs/2026-11-11-post-tips-guide-links-design.md`

## Global Constraints

- Version **0.62.0**, versionCode 80, CHANGELOG-Abschnitt `## 0.62.0` (deutsch, wie die bisherigen).
- Alle nutzersichtbaren Texte und Prompts auf Deutsch; Logs ohne Beitrags-/Nutzerinhalte in Fehlermeldungen (nur `javaClass.simpleName`).
- Neue Felder in `shared`-Modellen immer mit Default (alte Daten/alte Server müssen weiter dekodieren).
- Höchstens 20 Bilder pro Beitrag; Galeriebilder auf max. 1024 px Breite; Video-Standbilder wie bisher (alle 3 s, 512 px, max. 12 je Video); Ton max. 180 s vom ersten Video mit Ton.
- Höchstens 12 Tipps pro Beitrag; höchstens 8 Links pro Guide.
- Server-HTTP zu fremden URLs nur über `UrlGuard` (https, keine privaten Netze), Redirects von Hand (max. 3).
- Abweichung von der Spec (bewusst): der „Links ergänzen“-Job hat `kind = "guides"` (nicht `"guide-links"`), damit die bestehende Guide-Job-Anzeige der App ihn ohne Zusatzlogik verfolgt.

## Review Focus

1. Galerie mit Video **und** Bildern, Bilder zuerst gezählt: bei >20 Bildern fallen Video-Standbilder weg, nicht Galeriebilder → Test in Task 2.
2. Ergänzen-Modus verliert Inhalt (KI kürzt Guide) → Guide bleibt unverändert, Item FAILED → Test in Task 6.
3. KI erfindet Paket-ID / URL nicht erreichbar / Netzfehler → Link fällt weg, Guide wird trotzdem gespeichert → Tests in Task 5.
4. Beitrag nur mit Tipps (keine Orte) → Job DONE mit `tips`, Ergebnis-Bildschirm zeigt nur Tipp-Bereich und Übernehmen-Button → Tests in Task 4 und Task 9.
5. Tipp-Ziel ist Katalog-Thema, für das es schon einen Guide gibt → Ergänzen-Modus auf dem bestehenden Guide (über `GuideMatch.duplicateOf`), kein Duplikat → Test in Task 6.

---

### Task 1: Shared-Modelle

**Files:**
- Modify: `shared/src/main/kotlin/de/wanderlogplus/shared/model/Guides.kt`
- Create: `shared/src/main/kotlin/de/wanderlogplus/shared/guide/GuideLinks.kt`
- Modify: `shared/src/main/kotlin/de/wanderlogplus/shared/sync/AiProtocol.kt`
- Test: `shared/src/test/kotlin/de/wanderlogplus/shared/model/GuidesTest.kt`, `shared/src/test/kotlin/de/wanderlogplus/shared/guide/GuideLinksTest.kt`

**Interfaces:**
- Produces:
  - `enum class GuideLinkKind { APP, WEB }`
  - `@Serializable data class GuideLink(val name: String, val kind: GuideLinkKind, val url: String = "", val androidPackage: String = "", val note: String = "")`
  - `Guide.links: List<GuideLink> = emptyList()`
  - `object GuideLinks { fun key(l: GuideLink): String; fun union(guides: List<Guide>): List<GuideLink> }` — key = `"app:"+pkg` wenn Paket gesetzt, sonst `"web:"+url.lowercase().trimEnd('/')`; union behält die erste Fundstelle, Reihenfolge der Guides.
  - `enum class TipVerdict { TRUE, PARTLY, FALSE, UNVERIFIED }`
  - `@Serializable data class ReelTip(...)` exakt wie in der Spec (claim, topic, evidence, coveredBy, coveredQuote, verdict, text, reason, sources, guideId, targetTopic, targetTitle)
  - `AiJobInfo.tips: List<ReelTip> = emptyList()`
  - `@Serializable data class GuideAddition(val text: String, val sources: List<String> = emptyList())`
  - `GuideAsk.additions: List<GuideAddition> = emptyList()`

- [ ] **Step 1: Failing tests**
  - `GuidesTest.oldGuideJsonWithoutLinksDecodes`: JSON ohne `links` → `links == emptyList()`.
  - `GuidesTest.linkRoundTrip`: Guide mit `GuideLink("Japan Transit", GuideLinkKind.APP, androidPackage = "jp.co.jorudan.jtp")` encode/decode gleich.
  - `GuideLinksTest.unionDropsDuplicates`: zwei Guides, beide mit Paket `jp.co.jorudan.jtp` (verschiedene Namen) und einer mit `https://www.japan-guide.com/` und `https://www.japan-guide.com` → 2 Links, erster Name bleibt.
  - `GuideLinksTest.webAndAppWithSameUrlAreDifferent`: App mit Paket + url X und Web mit url X → 2 Links.
  - `AiProtocol`-Test (in `GuidesTest` oder neuer `AiProtocolTest`): `AiJobInfo`-JSON ohne `tips` dekodiert; `GuideAsk` ohne `additions` dekodiert.
- [ ] **Step 2:** `./gradlew :shared:test` → FAIL (unbekannte Typen).
- [ ] **Step 3:** Modelle und `GuideLinks` implementieren.
- [ ] **Step 4:** `./gradlew :shared:test` → PASS.
- [ ] **Step 5:** Commit `feat(shared): guide links, reel tips, guide additions`.

### Task 2: Bild- und Galerie-Download

**Files:**
- Modify: `server/src/main/kotlin/de/wanderlogplus/server/ai/ReelMedia.kt`
- Modify: `server/src/main/kotlin/de/wanderlogplus/server/ai/AiJobs.kt:399-446` (runReel), `server/src/main/kotlin/de/wanderlogplus/server/Main.kt:71`, Config-Klasse (wo `instagramCookies` gelesen wird: neues `galleryDl: String` aus `WLP_GALLERY_DL`, Default `"gallery-dl"`)
- Modify: `server/Dockerfile`
- Test: `server/src/test/kotlin/de/wanderlogplus/server/ai/ReelMediaTest.kt` (neu oder bestehend), `AssistantTest.kt` (FakeMedia anpassen)

**Interfaces:**
- Produces:
  - `data class Downloaded(val videos: List<Path>, val images: List<Path>, val caption: String?)`
  - `interface ReelMedia { fun download(url: String, dir: Path): Downloaded; fun extract(d: Downloaded, dir: Path): Media }`
  - `ToolReelMedia(ytDlp, ffmpeg, cookies, galleryDl: String = "gallery-dl")`
  - `internal fun collectGallery(dir: Path): Pair<List<Path>, List<Path>>` (videos, images; sortiert nach Dateiname = Galerie-Reihenfolge)
  - `internal fun galleryCaption(dir: Path): String?` (erste `*.json` mit `description` oder `caption`)
  - `internal fun frameBudget(images: Int, videos: Int): List<Int>` — Standbilder je Video bei Budget 20 minus Bildanzahl, max. 12 je Video, Rest gleich verteilt (mind. 0).
  - `ReelInput.Video` → intern `Downloaded(listOf(video), emptyList(), caption)`.

- [ ] **Step 1: Failing tests**
  - `collectGalleryFindsImagesAndVideosInOrder`: Verzeichnis mit `01.jpg`, `02.mp4`, `03.webp`, `x.json` → videos `[02.mp4]`, images `[01.jpg, 03.webp]`.
  - `galleryCaptionReadsDescription`: JSON `{"description":"Tipps für Tokio"}` → `"Tipps für Tokio"`; `{"caption":"…"}` ebenfalls.
  - `frameBudgetPrefersImages`: `frameBudget(18, 2) == listOf(1, 1)`; `frameBudget(20, 1) == listOf(0)`; `frameBudget(0, 1) == listOf(12)`; `frameBudget(4, 2) == listOf(8, 8)`.
  - `AiJobsTest`/`AssistantTest`: FakeMedia liefert `Downloaded(emptyList(), listOf(img), "caption")` → Job läuft bis DONE (kein `download_failed`).
- [ ] **Step 2:** `./gradlew :server:test --tests "*ReelMedia*" --tests "*AssistantTest*"` → FAIL.
- [ ] **Step 3:** Implementieren:
  - `download`: yt-dlp wie bisher; scheitert es oder gibt es keine Videodatei → `galleryDl --cookies <c> --write-metadata -D <dir>/gallery <url>` (Timeout 180 s), dann `collectGallery` + `galleryCaption`; beides leer → `DownloadFailed`. yt-dlp-Erfolg → `Downloaded(listOf(video), emptyList(), caption)`.
  - `extract`: Bilder per `ffmpeg -i img -vf scale='min(1024,iw)':-2 -q:v 4 img_NN.jpg`, Videos mit `frames:v = frameBudget(...)[i]` (0 → kein Aufruf), Ton nur vom ersten Video mit Ton; Reihenfolge: Galeriebilder, dann Standbilder; insgesamt `take(20)`.
  - `runReel`: Log „Beitrag bereit: N Bilder, M Videos, Beschreibung mit K Zeichen“; Extraktions-Log „N Bilder“ + Tonspur.
  - Dockerfile: `curl -fsSL https://github.com/mikf/gallery-dl/releases/latest/download/gallery-dl.bin -o /usr/local/bin/gallery-dl && chmod 755 /usr/local/bin/gallery-dl`, Kommentar wie bei yt-dlp.
- [ ] **Step 4:** Tests → PASS; `./gradlew :server:test` grün.
- [ ] **Step 5:** Commit `feat(server): image and gallery posts via gallery-dl`.

### Task 3: Analyse liefert Orte und Tipps

**Files:**
- Modify: `server/src/main/kotlin/de/wanderlogplus/server/ai/Assistant.kt:90-123`
- Test: `server/src/test/kotlin/de/wanderlogplus/server/ai/AssistantTest.kt`

**Interfaces:**
- Produces:
  - `data class FoundTip(val claim: String, val topic: String, val evidence: String)`
  - `data class Analysis(val summary: String?, val places: List<FoundPlace>, val tips: List<FoundTip>)`
  - `fun Assistant.analyze(caption: String?, media: Media, context: TripContext): Analysis` (ersetzt `findPlaces`; alle Aufrufer umstellen)
  - `const val MAX_TIPS = 12`

- [ ] **Step 1: Failing tests**
  - `analyzeParsesTipsBesidePlaces`: Modell-Antwort mit 1 Location + 2 Tips → `places.size == 1`, `tips.map { it.claim }` wie geliefert.
  - `analyzeTipsOnly`: `"locations": []`, 1 Tip → `places` leer, `tips.size == 1`.
  - `analyzeCapsTipsAndDropsBlankClaims`: 15 Tips, davon einer mit leerem claim → 12.
- [ ] **Step 2:** Tests → FAIL.
- [ ] **Step 3:** Prompt erweitern: Einleitung „Du analysierst einen Instagram-Reel oder -Post (Video, Bild oder Bildergalerie) …“ (der Teilstring `Instagram-Reel` bleibt, bestehende Test-Fakes matchen darauf); Hinweis „Text auf Bildern/Folien ist oft der eigentliche Inhalt“; JSON um `"tips": [{"claim","topic","evidence"}]` mit Regel aus Spec B.1 (nur allgemeine Reisetipps, ortsbezogene bleiben beim Ort; keine Tipps: `[]`).
- [ ] **Step 4:** `./gradlew :server:test --tests "*AssistantTest*"` → PASS.
- [ ] **Step 5:** Commit `feat(server): reel analysis finds travel tips`.

### Task 4: TipCheck und Reel-Job

**Files:**
- Create: `server/src/main/kotlin/de/wanderlogplus/server/ai/TipCheck.kt`
- Modify: `server/src/main/kotlin/de/wanderlogplus/server/ai/AiJobs.kt` (Konstruktor-Param `tipCheck: TipCheck? = null`, runReel), `server/src/main/kotlin/de/wanderlogplus/server/Main.kt` (Wiring: `TipCheck(facts, text, parallelism = config.researchParallelism)` mit derselben FactSource-Wahl wie GuideWriter)
- Test: `server/src/test/kotlin/de/wanderlogplus/server/ai/TipCheckTest.kt`, `AssistantTest.kt` (Job-Ebene)

**Interfaces:**
- Consumes: `FoundTip` (Task 3), `ReelTip`, `TipVerdict` (Task 1), `FactSource`, `completeJson`, `ChatModel`, `GuideTopics`.
- Produces:
  - `class TipCheck(facts: FactSource, model: ChatModel, parallelism: Int = 3)`
  - `fun TipCheck.match(tips: List<FoundTip>, guides: List<Guide>): List<ReelTip>` — setzt `coveredBy/coveredQuote` oder genau ein Ziel; bei Modellfehler: alle Tipps neu mit `targetTitle = tip.topic.ifBlank { "Reisetipps" }` und `say`-Warnung.
  - `fun TipCheck.verify(tip: ReelTip, say: (String) -> Unit = {}): ReelTip` — setzt verdict/text/reason/sources; jede Exception (auch `AiFailed`) → `UNVERIFIED`, reason `"Prüfung fehlgeschlagen"`.
  - `fun TipCheck.checkAll(tips: List<FoundTip>, guides: List<Guide>, progress: JobProgress): List<ReelTip>` — match, dann verify für alle nicht-covered, bis `parallelism` gleichzeitig; Items `tip-<i>` (Name = claim gekürzt auf 60 Zeichen).
  - `internal fun guideDigest(guides: List<Guide>): String` — je Guide eine Zeile `id | title | topic | summary`, darunter Abschnitte `heading: text` + Do's/Don'ts, jeder Guide max. 1500 Zeichen.
  - Neuer Job-Status `"CHECKING"`.

- [ ] **Step 1: Failing tests** (Fake-ChatModel nach `system`-Inhalt, Fake-FactSource)
  - `matchMarksCovered`: Antwort `{"tips":[{"i":0,"covered":true,"guideId":"g1","quote":"…"}]}` → `coveredBy == "g1"`.
  - `matchDropsUnknownTargets`: Antwort mit `guideId: "nope"` → `guideId == null`, `targetTitle == tip.topic`.
  - `matchKeepsCatalogTopic`: `topic: "onsen"` (existierender Katalog-Key) → `targetTopic == "onsen"`; unbekannter Key → `targetTitle`.
  - `verifyParsesVerdict`: Antwort `{"verdict":"PARTLY","text":"korrigiert","reason":"…"}` + Facts mit Quellen → `verdict == PARTLY`, `text == "korrigiert"`, `sources` = Fact-Quellen.
  - `verifyFailureIsUnverified`: FactSource wirft `AiFailed("research_timeout")` → `UNVERIFIED`, reason `"Prüfung fehlgeschlagen"`.
  - `checkAllSkipsCovered`: 2 Tipps, 1 covered → FactSource genau 1× aufgerufen.
  - Job-Test `reelJobWithTipsOnly`: Analyse ohne Orte, 1 Tipp → Job `DONE`, `tips.size == 1`, `candidates` leer.
- [ ] **Step 2:** `./gradlew :server:test --tests "*TipCheck*" --tests "*AssistantTest*"` → FAIL.
- [ ] **Step 3:** Implementieren. Prompts:
  - match (system): Abgleich-Regeln aus Spec B.2; Antwort `{"tips":[{"i":0,"covered":bool,"guideId":"…|null","quote":"…","topic":"katalog-key|null","title":"neuer Guide|null"}]}`; user = nummerierte Tipps + `guideDigest` + Katalog (`key: Name`).
  - verify: `FactQuery(objective = "Stimmt das für Touristen in Japan, Stand heute? <claim>", queries = [claim, "<claim> Japan"], hints = ["Belege dafür", "Belege dagegen", "Ausnahmen"])`; system: Urteilsregeln aus der Spec-Tabelle, Antwort `{"verdict":"TRUE|PARTLY|FALSE|UNVERIFIED","text":"was in den Guide kommt","reason":"1–2 Sätze"}`, `GERMAN_PLEASE`; unbekanntes verdict → UNVERIFIED; `text` leer → claim.
  - runReel: nach `researchAll` wenn `tips` nicht leer und `tipCheck != null`: Status `CHECKING`, Log „N Tipps erkannt, Abgleich mit Guides …“, `checkAll(...)` mit `ChatTrip.from(store).guides`, dann `done { it.copy(candidates = …, tips = …) }`. Ohne `tipCheck`: Tipps ungeprüft als `UNVERIFIED` mit `targetTitle`.
- [ ] **Step 4:** Tests → PASS.
- [ ] **Step 5:** Commit `feat(server): match and fact-check reel tips against guides`.

### Task 5: LinkCheck

**Files:**
- Create: `server/src/main/kotlin/de/wanderlogplus/server/ai/LinkCheck.kt`
- Test: `server/src/test/kotlin/de/wanderlogplus/server/ai/LinkCheckTest.kt`

**Interfaces:**
- Consumes: `GuideLink`, `GuideLinks.key` (Task 1), `UrlGuard`.
- Produces:
  - `fun interface UrlProbe { fun ok(url: String): Boolean }` + `class HttpUrlProbe(guard: UrlGuard = UrlGuard.DNS) : UrlProbe` (GET, Redirects von Hand max. 3, jeder Hop durch `guard`, Timeout 10 s, Body sofort schließen, `ok` = finaler Status 200..299; Exception → false)
  - `class LinkCheck(probe: UrlProbe = HttpUrlProbe())` mit `fun check(links: List<GuideLink>): List<GuideLink>`
  - `internal val PACKAGE_ID = Regex("""[a-zA-Z][\w]*(\.[a-zA-Z][\w]*)+""")`
  - `internal fun parseLinks(arr: JsonArray?): List<GuideLink>` (aus Modell-JSON, `kind` "app"/"web" case-insensitive, unbekannt → verwerfen)

- [ ] **Step 1: Failing tests** (Fake-UrlProbe mit Set erreichbarer URLs)
  - `keepsReachableWeb`, `dropsUnreachableWeb`, `dropsNonHttpUrl` (`ftp://…`, `javascript:…`).
  - `appWithValidPackageStays`: Probe kennt `https://play.google.com/store/apps/details?id=jp.co.jorudan.jtp` → bleibt.
  - `appWithUnknownPackageKeepsWebsiteOnly`: Paket unbekannt, url erreichbar → Paket leer, Link bleibt.
  - `appWithInvalidPackageAndNoUrlIsDropped`: Paket `"not a package"`, url leer → weg.
  - `dedupAndLimit`: 10 gültige Links, 2 doppelt → 8.
  - `probeExceptionDropsLink`: Probe wirft → Link weg, kein Crash.
  - `parseLinksIgnoresUnknownKind`.
- [ ] **Step 2:** `./gradlew :server:test --tests "*LinkCheck*"` → FAIL.
- [ ] **Step 3:** Implementieren (Prüfungen höchstens 4 gleichzeitig über einen kleinen Executor oder `parallelStream` mit fester Pool-Größe).
- [ ] **Step 4:** Tests → PASS.
- [ ] **Step 5:** Commit `feat(server): verify app and web links`.

### Task 6: GuideWriter — Links und Ergänzen-Modus

**Files:**
- Modify: `server/src/main/kotlin/de/wanderlogplus/server/ai/GuideWriter.kt`
- Modify: `server/src/main/kotlin/de/wanderlogplus/server/Main.kt:86` (`GuideWriter(facts, text, LinkCheck())`), `server/src/main/kotlin/de/wanderlogplus/server/Application.kt:397-402` (Route: `additions` je Ask max. 20, Text max. 1000 Zeichen, sonst `BadInput`)
- Test: `server/src/test/kotlin/de/wanderlogplus/server/ai/GuideWriterTest.kt`, AiJobs-Guide-Test

**Interfaces:**
- Consumes: `LinkCheck`, `parseLinks` (Task 5), `GuideAsk.additions`, `GuideAddition` (Task 1).
- Produces:
  - `class GuideWriter(facts: FactSource?, model: ChatModel, links: LinkCheck? = null)` — `links == null`: Modell-Links werden ohne Prüfung verworfen (kein ungeprüfter Link wird gespeichert).
  - `write(ask, tripContext, existing, say)`: Ergänzen-Modus wenn `existing != null && ask.additions.isNotEmpty()`; sonst Schreiben (mit `additions` als Pflichtinhalt, falls vorhanden).
  - `fun GuideWriter.extractLinks(g: Guide, say: (String) -> Unit = {}): List<GuideLink>` (für Task 7: ein Modellaufruf auf `title + summary + sections`, dann `LinkCheck`).
  - `internal fun keepsContent(before: Guide, after: Guide): Boolean` — `after.sections.size >= before.sections.size` und Summe Do's ≥ vorher und Summe Don'ts ≥ vorher und `after.phrases.size >= before.phrases.size`.

- [ ] **Step 1: Failing tests**
  - `writeParsesAndChecksLinks`: Modell liefert 2 Links, Fake-Probe kennt nur einen → `links.size == 1`.
  - `writeWithoutLinkCheckStoresNoLinks`.
  - `mergeDoesNotResearch`: existing + additions → FactSource 0× aufgerufen; Prompt (user) enthält den Additions-Text.
  - `mergeKeepsSourcesAndAddsNew`: alte Quellen + Additions-Quellen, ohne Doppelte.
  - `mergeRejectsShrunkGuide`: Modell liefert stets weniger Abschnitte → `AiFailed("ai_bad_answer")`.
  - `newGuideWithAdditionsMentionsThemInPrompt`: existing null → Recherche läuft, user-Prompt enthält „geprüft“ + Text.
  - AiJobs-Test `tipForTopicWithExistingGuideMerges`: Ask `topic = "onsen"`, Guide mit topic `onsen` existiert → geschrieben wird dieselbe Guide-id (kein zweiter Guide).
- [ ] **Step 2:** `./gradlew :server:test --tests "*GuideWriter*"` → FAIL.
- [ ] **Step 3:** Implementieren:
  - Schreib-Prompt: JSON um `"links": [{"name","kind":"app|web","url","androidPackage","note"}]` ergänzen: „nur Apps und Webseiten, die im Guide erwähnt werden; androidPackage nur, wenn du die Paket-ID sicher kennst“.
  - Ergänzen-Prompt (system): „Du ergänzt einen bestehenden Guide um geprüfte Informationen. Behalte alle Abschnitte, Do's, Don'ts und Sätze inhaltlich bei; arbeite die Ergänzungen in passende Abschnitte ein oder lege einen neuen Abschnitt an. Gleiches JSON-Format.“; user = existing als JSON (`Guide.serializer()`) + nummerierte Ergänzungen. `completeJson(..., valid = { keepsContent(existing, parsed(it)) && … })`; `completeJson` wirft nach dem Retry → `AiFailed("ai_bad_answer")` (prüfen, dass das Verhalten so ist; sonst explizit werfen).
  - Ergebnis-Links: `links?.check(parsed + existing.links)` (vorhandene Links bleiben Kandidaten); Ergänzen-Modus: `sources = (existing.sources + additions.flatMap { it.sources }).distinct()`.
- [ ] **Step 4:** Tests → PASS; `./gradlew :server:test` grün.
- [ ] **Step 5:** Commit `feat(server): guide links and merging verified additions`.

### Task 7: Job „Links ergänzen“ + Client

**Files:**
- Modify: `server/src/main/kotlin/de/wanderlogplus/server/ai/AiJobs.kt` (`startGuideLinks`), `server/src/main/kotlin/de/wanderlogplus/server/Application.kt` (Route; Job-Liste-Whitelist um `"guides"` ergänzen)
- Modify: `client/src/main/kotlin/de/wanderlogplus/client/SyncApi.kt`
- Test: AiJobs-Test, Route-Test (bestehende Application-Tests als Vorlage)

**Interfaces:**
- Consumes: `GuideWriter.extractLinks` (Task 6).
- Produces:
  - `fun AiJobs.startGuideLinks(guideIds: List<String>): AiJobInfo` — `kind = "guides"`, `guideIds` = alle betroffenen (leer = alle Guides), Item je Guide (`link-<id>`, Name = Titel); schreibt nur `ServerEdit(EntityType.GUIDE, id, mapOf("links" to …))`; Item-Detail „N Links“ bzw. „keine Links“.
  - Route `POST /api/ai/guide-links`, Body `@Serializable data class GuideLinksRequest(val guideIds: List<String> = emptyList())` (in `AiProtocol.kt`), max. 200 ids.
  - `suspend fun SyncApi.startGuideLinks(guideIds: List<String> = emptyList()): AiJobInfo`
  - `GET /api/ai/jobs?kind=guides` filtert jetzt wirklich auf Guide-Jobs.

- [ ] **Step 1: Failing tests**
  - `guideLinksJobWritesOnlyLinks`: 2 Guides, Fake-Modell liefert für einen 1 gültigen Link → nur Feld `links` dieses Guides geändert, `title`/`sections` unverändert (Feldwerte im Store vergleichen); anderer Guide Item DONE „keine Links“.
  - `jobsListFiltersGuides`: ein reel- und ein guides-Job → `kind=guides` liefert nur den guides-Job.
- [ ] **Step 2:** Tests → FAIL.
- [ ] **Step 3:** Implementieren (Fehler pro Guide wie `runGuides`: Item FAILED, weiter).
- [ ] **Step 4:** `./gradlew :server:test :client:test` → PASS.
- [ ] **Step 5:** Commit `feat(server): job to add links to existing guides`.

### Task 8: App — Links öffnen und anzeigen

**Files:**
- Create: `app/src/main/kotlin/de/wanderlogplus/app/links/GuideLinkOpen.kt`
- Create: `app/src/main/kotlin/de/wanderlogplus/app/ui/guides/GuideLinkChips.kt`
- Modify: `app/src/main/AndroidManifest.xml` (`<queries>` aus der Spec)
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/ui/guides/GuideScreens.kt` (GuideScreen: Block „Apps & Links“ nach der Zusammenfassung; `GuidesSection`: `GuideLinkChips(GuideLinks.union(shown))` unter den Guide-Chips; Early-Return-Bedingung unverändert)
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/ui/today/TodayWidgets.kt` (`TodayGuidesWidget`: Link-Zeile mit `GuideLinks.union(dayGuides.filter { !it.done }.map { it.guide })`)
- Test: `app/src/test/kotlin/de/wanderlogplus/app/links/GuideLinkOpenTest.kt`, Compose-/Robolectric-Test nach Muster von `TodayGuidesTest`

**Interfaces:**
- Consumes: `GuideLink`, `GuideLinks.union` (Task 1).
- Produces:
  - `sealed interface LinkTarget { data class Launch(val pkg: String) : LinkTarget; data class View(val uri: String) : LinkTarget }`
  - `fun guideLinkTargets(link: GuideLink, installed: Boolean): List<LinkTarget>`
  - `fun openGuideLink(context: Context, link: GuideLink): Boolean` (installed = `getLaunchIntentForPackage(pkg) != null`; probiert Ziele der Reihe nach, `ActivityNotFoundException` → nächstes)
  - `@Composable fun GuideLinkChips(links: List<GuideLink>, modifier: Modifier = Modifier)` — nichts bei leerer Liste; Tag `guide-links`, je Chip `guide-link-<i>`; Icon `Icons.Filled.Apps` / `Icons.Filled.Language`; bei Fehlschlag Toast „Lässt sich nicht öffnen“.

- [ ] **Step 1: Failing tests**
  - `installedAppLaunches`: APP mit Paket, installed → `[Launch(pkg)]` zuerst.
  - `missingAppGoesToStoreThenWeb`: installed=false, url gesetzt → `[View("market://details?id=pkg"), View("https://play.google.com/store/apps/details?id=pkg"), View(url)]`.
  - `appWithoutPackageOpensUrl`, `webOpensUrl`, `noUsableTargetIsEmpty` (APP ohne Paket und ohne url → `[]`).
  - UI: `GuidesSection` mit Guide mit 1 Link → Node `guide-links` existiert; `TodayGuidesWidget` analog.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests "*GuideLink*" --tests "*TodayGuides*"` → FAIL.
- [ ] **Step 3:** Implementieren.
- [ ] **Step 4:** Tests → PASS.
- [ ] **Step 5:** Commit `feat(app): open and show guide app/web links`.

### Task 9: App — Tipps im Reel-Ergebnis übernehmen

**Files:**
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/ui/reels/Reels.kt` (reine Logik), `ReelScreens.kt` (UI), `ReelsViewModel.kt` (Import), `app/src/main/kotlin/de/wanderlogplus/app/ui/share/ShareReel.kt` + `ReelsScreen`-Texte („Reel- oder Post-Link“, Erklärtext nennt Bild-Posts und Tipps)
- Test: `app/src/test/kotlin/de/wanderlogplus/app/ui/reels/ReelsTest.kt`, `ReelTipsTest.kt` (neu)

**Interfaces:**
- Consumes: `ReelTip`, `TipVerdict`, `GuideAsk`, `GuideAddition` (Task 1); `SyncApi.startGuides`.
- Produces:
  - `fun tipSelectable(t: ReelTip): Boolean` — `coveredBy == null && verdict != FALSE`
  - `fun tipPreselected(t: ReelTip): Boolean` — selectable und verdict in `TRUE, PARTLY`
  - `fun tipAsks(picked: List<ReelTip>): List<GuideAsk>` — gruppiert nach (guideId, targetTopic, targetTitle); je Gruppe ein Ask mit `guideId`/`topic`/`title` und `additions = tips.map { GuideAddition(it.text.ifBlank { it.claim }, it.sources) }`
  - `ReelsViewModel.import(jobId: String, picks: List<ReelPick>, tips: List<ReelTip>, source: String?)` — Orte wie bisher; Tipps → `syncApi.startGuides(tipAsks(tips))` (nur wenn nicht leer); Meldung „Guides werden ergänzt“. Bisheriges `picks.isEmpty()`-Return gilt nur, wenn auch `tips` leer.
  - `ReelJobScreen(... onImport: (List<ReelPick>, List<ReelTip>) -> Unit ...)`; Tags `reel-tip-<i>`, `reel-tip-target-<i>`.
  - Ziel-Dropdown: Einträge = alle Guides des Snapshots (Titel) + „Neuer Guide: <topic>“; Auswahl setzt `guideId` bzw. `targetTitle` (und leert die anderen Zielfelder).
  - Listen-Zustand im `ReelsScreen`: „N Orte, M Tipps gefunden · neu“.

- [ ] **Step 1: Failing tests**
  - `tipAsksGroupsByTarget`: 3 Tipps (2× guideId g1, 1× targetTitle „Geld“) → 2 Asks, g1-Ask mit 2 additions.
  - `partlyUsesCorrectedText`: PARTLY mit text „korrigiert“ → addition.text == „korrigiert“.
  - `falseAndCoveredNotSelectable`, `unverifiedSelectableNotPreselected`.
  - UI: Job nur mit Tipps (keine candidates) → `reel-tip-0` sichtbar, `reel-import` aktiv und ruft `onImport(emptyList(), [tip])`.
  - VM: `import` mit nur Tipps → `startGuides` 1× mit erwarteten Asks (Fake).
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests "*Reel*"` → FAIL.
- [ ] **Step 3:** Implementieren (Badge-Texte: „stimmt“, „übertrieben – korrigiert“, „falsch“, „nicht belegt“, „steht schon in „<Titel>““; Quellen aufklappbar wie im Guide; Button-Text „N Orte und M Tipps übernehmen“, nur Orte/nur Tipps entsprechend).
- [ ] **Step 4:** Tests → PASS.
- [ ] **Step 5:** Commit `feat(app): take verified reel tips into guides`.

### Task 10: App — Button „Links ergänzen“

**Files:**
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/ui/guides/GuidesViewModel.kt` (Konstruktor-Param `startGuideLinks: suspend () -> AiJobInfo? = { null }`, `GuideActions.addLinks()` mit Default `{}`), `GuideScreens.kt` (`GuidesScreen`: Menü oben rechts mit „Links ergänzen“, nur wenn `actions != null` und es Guides gibt), `MainActivity.kt:334-341` (Wiring `container.syncApi()?.startGuideLinks()`)
- Test: `app/src/test/kotlin/de/wanderlogplus/app/ui/guides/GuidesViewModelTest.kt`

**Interfaces:**
- Consumes: `SyncApi.startGuideLinks` (Task 7).
- Produces: `GuideActions.addLinks()`; Snackbar-Texte „Links werden ergänzt …“ / bei null „Links lassen sich gerade nicht ergänzen (Server nicht erreichbar?).“

- [ ] **Step 1: Failing test** `addLinksStartsJobAndPolls`: Fake `startGuideLinks` liefert laufenden Job → `jobs.value` enthält ihn; null → `message` gesetzt.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests "*GuidesViewModel*"` → FAIL.
- [ ] **Step 3:** Implementieren (wie `createSelected`).
- [ ] **Step 4:** Test → PASS.
- [ ] **Step 5:** Commit `feat(app): add links to existing guides`.

### Task 11: Version, Changelog, Gesamtprüfung

**Files:**
- Modify: `app/build.gradle.kts` (versionCode 80, versionName "0.62.0"), `CHANGELOG.md`, `README.md` (Abschnitt Reel-Import/Guides: Bild-Posts, Tipps, Links)

- [ ] **Step 1:** CHANGELOG `## 0.62.0` mit drei Punkten (Bild-/Galerie-Posts; Tipps erkennen, prüfen, in Guides übernehmen; App-/Web-Links in Guides inkl. „Links ergänzen“) plus Hinweis „Server neu bauen (gallery-dl)“.
- [ ] **Step 2:** `./gradlew :shared:test :server:test :client:test :app:testDebugUnitTest` → alles grün (bekannte rote Tests vorher mit `git stash`-Vergleich auf main abgleichen und im Ergebnis benennen).
- [ ] **Step 3:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 4:** Commit `chore: 0.62.0`.
