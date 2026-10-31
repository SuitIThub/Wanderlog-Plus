# Plan 1: shared + importer – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Gemeinsames Datenmodell inkl. Sync-Grundlagen (HLC, Feld-Merge, Sortierschlüssel) und ein Wanderlog-Importer mit 3-Wege-Re-Import, ausführbar als CLI gegen die echten Reisedaten.

**Architecture:** Gradle-Multi-Projekt mit zwei reinen Kotlin/JVM-Modulen. `shared` speichert jedes Objekt generisch als `EntityRecord` (Feldname → Wert + HLC) und bietet typisierte Sichten darauf; alle Änderungen sind `FieldChange`s, die per Last-Writer-Wins angewendet werden. `importer` parst das Wanderlog-JSON, bildet es auf ein `ImportSnapshot` ab und erzeugt per `ReimportMerger` die `FieldChange`s gegen einen bestehenden Stand.

**Tech Stack:** Kotlin 2.1.0 (JVM, Toolchain 17), Gradle 8.11.1 (Wrapper, Kotlin DSL, Version Catalog), kotlinx-serialization-json 1.7.3, JUnit Jupiter 5.11.3.

**Spec:** `docs/superpowers/specs/2026-10-31-wanderlog-plus-design.md` (§3–§6, §12). Dieser Plan ist Teil 1 von 4 (2: server, 3: App-Grundgerüst, 4: App-Ausbau).

## Global Constraints

- Paket-Root: `de.wanderlogplus` (`de.wanderlogplus.shared…`, `de.wanderlogplus.importer…`).
- `shared` darf keine JVM-fremden Abhängigkeiten außer kotlinx-serialization haben (wird später von Android und Ktor genutzt). Kein `java.net`, kein Dateisystem in `shared`.
- Wanderlog-Endpoint: `https://wanderlog.com/api/tripPlans/{key}?clientSchemaVersion=2&registerView=false`.
- Echte Wanderlog-Exporte liegen nur unter `testdata/private/` (gitignored), nie im Repo.
- Reservierte Feldnamen in `EntityRecord`: `deleted`, `_source`, `_sourceId`, `_removedInSource`, `_base.<feld>`, `_conflict.<feld>`. Typisierte Modelle ignorieren alle Felder mit `_`-Präfix.
- IDs importierter Objekte sind deterministisch: `UUID.nameUUIDFromBytes("wanderlog:<EntityType.name>:<sourceId>")`. App-erzeugte IDs: UUID v7 (Plan 3).
- Texte aus Wanderlog werden als Markdown gespeichert.
- Kommandos werden aus dem Repo-Root ausgeführt; unter Windows/Git-Bash `./gradlew`.

## Review Focus

1. **Idempotenter Re-Import:** Zweimal dieselben Daten importieren → zweiter Lauf erzeugt 0 Änderungen. (Test in Task 9 und Task 10.)
2. **Derselbe Ort in mehreren Listen** (gleiche `place_id` z. B. in „Tokio Sehenswürdigkeiten“ und „Food-Spots“) → genau ein `Place`, zwei `SavedPlace`s. (Test in Task 8.)
3. **Fehlende optionale Felder** (Zug ohne `carrier`, Flug ohne `time`, Ort ohne `placeMetadata`, Block ohne `text`) → kein Absturz, Feld fehlt bzw. ist `null`. (Test in Task 7 und 8.)
4. **Emoji und Umlaute** in Notizen bleiben durch Quill→Markdown und Serialisierung unverändert (`⚠️`, `🌟`, `ü`, `妻籠`). (Test in Task 6.)
5. **Uhren-Versatz:** Eine entfernte HLC weit in der Zukunft darf die lokale Uhr nicht rückwärts laufen lassen; neue lokale Stempel sind danach größer. (Test in Task 1.)

---

## Dateistruktur

```
settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml, gradlew(.bat), gradle/wrapper/*
shared/build.gradle.kts
shared/src/main/kotlin/de/wanderlogplus/shared/
  sync/Hlc.kt            HLC-Wert + HlcClock
  sync/SortKey.kt        fraktionale Sortierschlüssel
  sync/EntityRecord.kt   EntityType, FieldValue, EntityRecord, FieldChange, LWW-Anwendung
  sync/EntityStore.kt    EntityStore-Interface + InMemoryEntityStore
  model/Models.kt        typisierte Datenklassen (Trip, Region, Place, …)
  model/EntityCodec.kt   Record ↔ Datenklasse, deterministische IDs
  query/TripQueries.kt   Tagesliste, Region pro Tag, Vorschläge
shared/src/test/kotlin/de/wanderlogplus/shared/…   (je eine Testdatei pro Hauptdatei)
importer/build.gradle.kts
importer/src/main/kotlin/de/wanderlogplus/importer/
  QuillMarkdown.kt       Quill-Delta → Markdown
  WanderlogDto.kt        DTOs + WanderlogParser (Validierung)
  WanderlogMapper.kt     DTO → ImportSnapshot (inkl. Regionen-Heuristik)
  ReimportMerger.kt      Snapshot × Store → FieldChanges + ImportStats
  WanderlogClient.kt     HTTP-Abruf
  Cli.kt                 main()
importer/src/test/kotlin/de/wanderlogplus/importer/…
importer/src/test/resources/wanderlog-mini.json
testdata/private/       (gitignored, echter Export)
```

---

### Task 1: Projekt-Gerüst + Hybrid Logical Clock

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `shared/build.gradle.kts`, `importer/build.gradle.kts`, Gradle-Wrapper
- Create: `shared/src/main/kotlin/de/wanderlogplus/shared/sync/Hlc.kt`
- Test: `shared/src/test/kotlin/de/wanderlogplus/shared/sync/HlcTest.kt`

**Interfaces:**
- Produces:
  - `data class Hlc(val wallMillis: Long, val counter: Int, val nodeId: String) : Comparable<Hlc>` – Vergleich nach (wallMillis, counter, nodeId).
  - `fun Hlc.encode(): String` → `"%015d-%06d-%s"`, lexikografisch gleich sortiert wie `compareTo`.
  - `companion fun Hlc.Companion.parse(s: String): Hlc` (wirft `IllegalArgumentException` bei ungültigem Format).
  - `class HlcClock(val nodeId: String, private val now: () -> Long = System::currentTimeMillis)` mit `fun tick(): Hlc` (für lokale Änderungen) und `fun receive(remote: Hlc): Hlc` (beim Empfang).

- [ ] **Step 1: Gradle-Wrapper erzeugen**

Gradle ist nicht installiert. Distribution einmalig ins Scratchpad laden und Wrapper erzeugen:
```bash
curl -sL https://services.gradle.org/distributions/gradle-8.11.1-bin.zip -o "$TMP/gradle.zip" && unzip -q "$TMP/gradle.zip" -d "$TMP/gradle-dist"
"$TMP/gradle-dist/gradle-8.11.1/bin/gradle" wrapper --gradle-version 8.11.1
```
Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.{jar,properties}` existieren.

- [ ] **Step 2: Build-Dateien anlegen**

- `settings.gradle.kts`: `rootProject.name = "wanderlog-plus"`, `include("shared", "importer")`, Repositories `mavenCentral()` via `dependencyResolutionManagement`.
- `libs.versions.toml`: kotlin `2.1.0`, kotlinx-serialization-json `1.7.3`, junit-jupiter `5.11.3`; Plugins `kotlin-jvm`, `kotlin-serialization`.
- Beide Module: `kotlin("jvm")`, `kotlin("plugin.serialization")`, `jvmToolchain(17)`, `tasks.test { useJUnitPlatform() }`, Testabhängigkeiten `kotlin("test")` + junit-jupiter.
- `importer` hängt von `project(":shared")` ab und nutzt das `application`-Plugin mit `mainClass = "de.wanderlogplus.importer.CliKt"`.

- [ ] **Step 3: Failing Tests schreiben**

```kotlin
class HlcTest {
    @Test fun `tick is strictly monotonic even if wall clock stands still`() {
        val clock = HlcClock("a") { 1000L }
        val t1 = clock.tick(); val t2 = clock.tick()
        assertTrue(t2 > t1); assertEquals(1000L, t2.wallMillis); assertEquals(1, t2.counter)
    }
    @Test fun `tick never goes backwards when wall clock jumps back`() {
        var now = 5000L; val clock = HlcClock("a") { now }
        val t1 = clock.tick(); now = 1000L
        assertTrue(clock.tick() > t1)
    }
    @Test fun `receive from far-future remote keeps later local ticks greater`() {
        val clock = HlcClock("a") { 1000L }
        val remote = Hlc(9_999_999_000L, 3, "b")
        val r = clock.receive(remote)
        assertTrue(r > remote); assertTrue(clock.tick() > r)
    }
    @Test fun `encode sorts like compareTo and roundtrips`() {
        val a = Hlc(999, 5, "x"); val b = Hlc(1000, 0, "a")
        assertTrue(a.encode() < b.encode())
        assertEquals(a, Hlc.parse(a.encode()))
    }
    @Test fun `nodeId breaks ties`() {
        assertTrue(Hlc(1, 1, "a") < Hlc(1, 1, "b"))
    }
}
```

- [ ] **Step 4: Test ausführen, Fehlschlag prüfen**

Run: `./gradlew :shared:test --tests "*HlcTest"`
Expected: FAIL (Kompilierfehler, `Hlc` nicht definiert)

- [ ] **Step 5: `Hlc` und `HlcClock` implementieren**

Standard-HLC-Algorithmus: `tick`: `wall = max(now(), last.wall)`; Zähler +1, wenn `wall == last.wall`, sonst 0. `receive`: `wall = max(now(), last.wall, remote.wall)`; Zähler je nachdem, welche Werte gleich `wall` sind (max der gleichen Zähler + 1, sonst 0). `HlcClock` ist nicht thread-safe; `@Synchronized` auf beiden Methoden.

- [ ] **Step 6: Tests ausführen**

Run: `./gradlew :shared:test --tests "*HlcTest"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradle gradlew gradlew.bat shared importer
git commit -m "feat(shared): project scaffold and hybrid logical clock"
```

---

### Task 2: Fraktionale Sortierschlüssel

**Files:**
- Create: `shared/src/main/kotlin/de/wanderlogplus/shared/sync/SortKey.kt`
- Test: `shared/src/test/kotlin/de/wanderlogplus/shared/sync/SortKeyTest.kt`

**Interfaces:**
- Produces: `object SortKey { fun between(before: String?, after: String?): String; fun sequence(count: Int): List<String> }`
  - Alphabet: `0-9A-Za-z` (62 Zeichen, ASCII-sortiert). `null` = offenes Ende.
  - `between` liefert einen String strikt zwischen `before` und `after` (String-Vergleich), endet nie auf `'0'`. Wirft `IllegalArgumentException`, wenn `before >= after`.
  - `sequence(n)` liefert n aufsteigende, gleichmäßig verteilte Schlüssel (für den Import).

- [ ] **Step 1: Failing Tests schreiben**

```kotlin
class SortKeyTest {
    @Test fun `between open ends`() { val k = SortKey.between(null, null); assertTrue(k.isNotEmpty()) }
    @Test fun `between is strictly between`() {
        val a = SortKey.between(null, null); val b = SortKey.between(a, null); val m = SortKey.between(a, b)
        assertTrue(a < m && m < b)
    }
    @Test fun `repeated insertion at the front stays ordered`() {
        var first = SortKey.between(null, null); val keys = mutableListOf(first)
        repeat(200) { first = SortKey.between(null, first); keys.add(0, first) }
        assertEquals(keys.sorted(), keys); assertEquals(keys.size, keys.toSet().size)
    }
    @Test fun `repeated insertion between neighbours stays ordered`() {
        val a = SortKey.between(null, null); var b = SortKey.between(a, null)
        repeat(200) { val m = SortKey.between(a, b); assertTrue(a < m && m < b); b = m }
    }
    @Test fun `sequence is ascending and distinct`() {
        val s = SortKey.sequence(50); assertEquals(s.sorted(), s); assertEquals(50, s.toSet().size)
    }
    @Test fun `invalid order throws`() { assertFailsWith<IllegalArgumentException> { SortKey.between("b", "a") } }
}
```

- [ ] **Step 2: Test ausführen, Fehlschlag prüfen**

Run: `./gradlew :shared:test --tests "*SortKeyTest"` → Expected: FAIL

- [ ] **Step 3: `SortKey` implementieren**

Ziffernweise Mittelwertbildung: `before` fehlt → wie `""` behandeln (unterste Grenze), `after` fehlt → obere Grenze `z` + 1. Position für Position: Ziffer `lo` aus `before` (oder 0), `hi` aus `after` (oder 62); ist `hi - lo > 1` → Ziffer `(lo+hi)/2` anhängen, fertig; sonst `lo`-Ziffer anhängen und weiter (beim Weitermachen gilt `after` danach als offen, wenn `hi > lo`). `sequence(n)`: wiederholt `between(prev, null)` ist zu ungleichmäßig – stattdessen n Schlüssel fester Länge `ceil(log62(n+1))+1` gleichmäßig über den Zahlenraum verteilen.

- [ ] **Step 4: Tests ausführen** → `./gradlew :shared:test --tests "*SortKeyTest"` → PASS

- [ ] **Step 5: Commit** → `git commit -m "feat(shared): fractional sort keys"`

---

### Task 3: Generische Records, Feld-LWW und Store

**Files:**
- Create: `shared/src/main/kotlin/de/wanderlogplus/shared/sync/EntityRecord.kt`, `.../sync/EntityStore.kt`
- Test: `shared/src/test/kotlin/de/wanderlogplus/shared/sync/EntityRecordTest.kt`, `.../sync/ConvergenceTest.kt`

**Interfaces:**
- Consumes: `Hlc`, `HlcClock` (Task 1)
- Produces:
  - `enum class EntityType { TRIP, REGION, PLACE, SAVED_PLACE, STAY, FLIGHT, TRAIN, DAY, DAY_ITEM, NOTE, ATTACHMENT, EXPENSE }`
  - `@Serializable data class FieldValue(val value: JsonElement, val hlc: String)` (`hlc` = `Hlc.encode()`)
  - `@Serializable data class EntityRecord(val type: EntityType, val id: String, val fields: Map<String, FieldValue> = emptyMap())` mit
    - `fun value(field: String): JsonElement?` (`JsonNull` → `null`)
    - `val isDeleted: Boolean` (Feld `deleted` == `true`)
    - `fun apply(change: FieldChange): EntityRecord?` → neuer Record, wenn `change.hlc` > vorhandener HLC des Felds (oder Feld fehlt); sonst `null` (verworfen).
  - `@Serializable data class FieldChange(val type: EntityType, val id: String, val field: String, val value: JsonElement, val hlc: String)`
  - `interface EntityStore { fun get(type: EntityType, id: String): EntityRecord?; fun put(record: EntityRecord); fun all(type: EntityType): List<EntityRecord>; fun all(): List<EntityRecord> }`
  - `class InMemoryEntityStore : EntityStore`
  - `fun EntityStore.applyChanges(changes: List<FieldChange>): List<FieldChange>` → gibt die tatsächlich übernommenen Änderungen zurück.
  - `fun HlcClock.change(type: EntityType, id: String, field: String, value: JsonElement): FieldChange` – Hilfsfunktion mit `tick()`.

- [ ] **Step 1: Failing Tests schreiben**

`EntityRecordTest`:
- `newer change wins`: Feld `title` mit hlc t1 = "A", Änderung t2 > t1 = "B" → Wert "B".
- `older change is rejected`: umgekehrt → `apply` liefert `null`, Wert bleibt.
- `fields merge independently`: Gerät 1 ändert `title`, Gerät 2 ändert `note` → beide Werte im Ergebnis.
- `isDeleted`: Feld `deleted=true` → `true`; fehlt → `false`.
- `applyChanges returns only accepted changes`: zwei Änderungen, eine veraltet → Liste mit einer.

`ConvergenceTest`:
```kotlin
@Test fun `two devices and importer converge regardless of delivery order`() {
    // 3 Uhren ("dev-a","dev-b","importer"), je 200 zufällige FieldChanges (Random(seed=42))
    // auf 5 IDs × 4 Felder; jedes Gerät erhält alle Änderungen in einer eigenen zufälligen Reihenfolge.
    // Erwartung: alle drei InMemoryEntityStores haben identische all()-Listen (sortiert nach id).
}
```

- [ ] **Step 2: Tests ausführen, Fehlschlag prüfen** → `./gradlew :shared:test` → FAIL

- [ ] **Step 3: Implementieren** – HLC-Vergleich über `Hlc.parse(...)` (nicht über String, damit ungültige Werte auffallen). `InMemoryEntityStore` auf `LinkedHashMap<Pair<EntityType,String>, EntityRecord>`.

- [ ] **Step 4: Tests ausführen** → PASS

- [ ] **Step 5: Commit** → `git commit -m "feat(shared): entity records with field-level LWW merge"`

---

### Task 4: Typisierte Modelle und Codec

**Files:**
- Create: `shared/src/main/kotlin/de/wanderlogplus/shared/model/Models.kt`, `.../model/EntityCodec.kt`
- Test: `shared/src/test/kotlin/de/wanderlogplus/shared/model/EntityCodecTest.kt`

**Interfaces:**
- Consumes: `EntityRecord`, `EntityType`, `FieldChange`, `HlcClock`
- Produces – alle `@Serializable data class`, alle Felder außer `id` mit Default (fehlende Felder dürfen nicht crashen). Datum als ISO-String `"2027-05-09"`, Zeit als `"HH:mm"`:
  - `Trip(id, title, startDate, endDate, wanderlogKey: String?)`
  - `Region(id, name, color: String?, dayTripFromRegionId: String?)`
  - `Place(id, googlePlaceId, name, lat: Double, lng: Double, address: String?, description: String?, rating: Double?, numRatings: Int?, minMinutes: Int?, maxMinutes: Int?, categories: List<String>, website: String?, phone: String?, priceLevel: Int?, openingPeriods: List<OpeningPeriod>, imageKeys: List<String>, googleMapsUrl: String?)`
  - `OpeningPeriod(openDay: Int, openTime: String, closeDay: Int?, closeTime: String?)` (Tag 0 = Sonntag, Zeit `"0830"` → `"08:30"`)
  - `enum class PlaceCategory { SIGHT, FOOD, SHOPPING, NIGHTLIFE, OTHER }`, `enum class PlaceStatus { IDEA, PLANNED, VISITED, DROPPED }`
  - `SavedPlace(id, placeId, regionId: String?, category: PlaceCategory, note: String, tags: List<String>, status: PlaceStatus)`
  - `Stay(id, placeId, regionId: String?, checkIn, checkOut, confirmationNumber: String?, travelerNames: List<String>, adults: Int?, rooms: Int?, cancellationDeadline: String?, cancellationPolicy: String?, note: String)`
  - `Endpoint(name: String, code: String?, placeId: String?, date: String?, time: String?)`
  - `Flight(id, airline: String?, airlineCode: String?, number: String?, depart: Endpoint, arrive: Endpoint, confirmationNumber: String?, travelerNames: List<String>, note: String)`
  - `Train(id, carrier: String?, depart: Endpoint, arrive: Endpoint, confirmationNumber: String?, travelerNames: List<String>, note: String)`
  - `Day(id, date, title: String, note: String, regionOverrideId: String?)`
  - `enum class DayItemKind { PLACE, NOTE }`; `DayItem(id, dayId, sortKey, kind, placeId: String?, text: String, time: String?)`
  - `Note(id, title, body, sortKey)`
  - `Attachment(id, targetType: EntityType, targetId, fileHash, fileName, mimeType, size: Long)`
  - `Expense(id, amount: Double, currency, category: String?, description: String, date: String?, relatedId: String?)`
  - `object EntityCodec`:
    - `inline fun <reified T> decode(record: EntityRecord): T` – Felder ohne `_`-Präfix und ohne `deleted` + `id` zu `JsonObject`, dann `Json { ignoreUnknownKeys = true }`.
    - `inline fun <reified T> fields(entity: T): Map<String, JsonElement>` – Datenklasse → Feldmap ohne `id`, inkl. Default-Werte (`encodeDefaults = true`).
    - `fun importedId(type: EntityType, sourceId: String): String` = `UUID.nameUUIDFromBytes("wanderlog:${type.name}:$sourceId".toByteArray()).toString()` – nutzt `java.util.UUID` (auf Android verfügbar, daher erlaubt).

- [ ] **Step 1: Failing Tests schreiben**
- `roundtrip stay`: `Stay` mit allen Feldern → `fields` → Record mit beliebigen HLCs → `decode<Stay>` == Original.
- `decode ignores reserved fields`: Record mit `_base.title`, `_conflict.title`, `deleted` → `decode<Trip>` ok, Werte unbeeinflusst.
- `decode tolerates missing optional fields`: Record nur mit `placeId` → `decode<SavedPlace>` liefert Defaults (`status = IDEA`, `category = SIGHT`, `note = ""`).
- `importedId is deterministic and type-specific`: gleicher Input → gleiche ID; `PLACE`/`SAVED_PLACE` mit gleicher sourceId → verschiedene IDs.

- [ ] **Step 2: Tests ausführen** → FAIL
- [ ] **Step 3: Implementieren**
- [ ] **Step 4: Tests ausführen** → PASS
- [ ] **Step 5: Commit** → `git commit -m "feat(shared): typed models and entity codec"`

---

### Task 5: Tagesliste, Region pro Tag, Vorschläge

**Files:**
- Create: `shared/src/main/kotlin/de/wanderlogplus/shared/query/TripQueries.kt`
- Test: `shared/src/test/kotlin/de/wanderlogplus/shared/query/TripQueriesTest.kt`

**Interfaces:**
- Consumes: Modelle aus Task 4
- Produces:
  - `data class DayView(val date: String, val day: Day?, val regionId: String?, val stay: Stay?, val isTravelDay: Boolean)`
  - `class TripQueries(trip: Trip, stays: List<Stay>, days: List<Day>, regions: List<Region>, savedPlaces: List<SavedPlace>, dayItems: List<DayItem>)` (nur nicht gelöschte Objekte übergeben) mit
    - `fun days(): List<DayView>` – ein Eintrag pro Datum `startDate..endDate` inklusive.
    - `fun regionFor(date: String): String?` – Regel aus Spec §4: `Day.regionOverrideId` ?: Region des Stays mit `checkIn ≤ date < checkOut`; kein solcher Stay und `date ≥` letztem `checkOut` → Region des Stays mit dem spätesten `checkOut`; `date <` frühestem `checkIn` → `null`.
    - `isTravelDay` = ein Stay hat `checkIn == date` oder `checkOut == date`, oder `regionFor(date) == null`.
    - `fun suggestionsFor(date: String): List<SavedPlace>` – `regionFor(date)` = R; SavedPlaces mit `regionId == R` oder `regionId` einer Region mit `dayTripFromRegionId == R`, Status `IDEA`, deren `placeId` in keinem `DayItem` (kind PLACE) vorkommt. Leere Liste bei R == null.

- [ ] **Step 1: Failing Tests schreiben** – Fixture in Testdatei: Trip 2027-05-08..2027-05-29, Regionen Tokio(t), Osaka(o), Kyoto(k, dayTripFrom = o); Stays Tokio 05-09→05-13, Osaka 05-17→05-22, Tokio 05-28→05-29.
- `days covers whole trip`: 22 Einträge, erster `2027-05-08`, letzter `2027-05-29`.
- `arrival day before first stay has no region`: `regionFor("2027-05-08") == null`, `isTravelDay`.
- `check-in day belongs to new stay`: `regionFor("2027-05-17") == o`.
- `last day falls back to last stay`: `regionFor("2027-05-29") == t`.
- `gap between stays`: `regionFor("2027-05-14")` → kein Stay deckt es ab und es liegt vor dem letzten checkOut → `null`. (Im echten Trip gibt es keine Lücken; Verhalten wird hiermit festgelegt.)
- `override wins`: Day 05-20 mit `regionOverrideId = k` → `k`.
- `suggestions include day-trip regions and exclude planned`: SavedPlaces in o, k, t; einer in o bereits als DayItem verplant, einer in o mit Status VISITED → Ergebnis für 05-19 = die übrigen aus o und k, keiner aus t.

- [ ] **Step 2: Tests ausführen** → FAIL
- [ ] **Step 3: Implementieren** – Datumsvergleich über `java.time.LocalDate` (auf Android ab API 26 verfügbar).
- [ ] **Step 4: Tests ausführen** → PASS
- [ ] **Step 5: Commit** → `git commit -m "feat(shared): day list, day region and suggestions"`

---

### Task 6: Quill-Delta → Markdown

**Files:**
- Create: `importer/src/main/kotlin/de/wanderlogplus/importer/QuillMarkdown.kt`
- Test: `importer/src/test/kotlin/de/wanderlogplus/importer/QuillMarkdownTest.kt`

**Interfaces:**
- Produces: `object QuillMarkdown { fun convert(ops: JsonArray?): String }`
  - Zeilenbasiert: Text sammelt sich bis zu einem `"\n"`; die `attributes` des Ops, das das `"\n"` enthält, gelten für die abgeschlossene Zeile (`list: bullet` → `"- "`, `list: ordered` → `"1. "`).
  - Inline: `bold` → `**x**`, `italic` → `*x*`, `link` → `[x](url)`; ist der Text gleich der URL → nur `url`.
  - Nicht-String-Inserts (Embeds) werden ignoriert. Ergebnis wird `trim()`t. `null`/leer → `""`.

- [ ] **Step 1: Failing Tests schreiben**
```kotlin
@Test fun `bullets bold and emoji`() {
    val ops = Json.parseToJsonElement("""[
      {"insert":"⚠️ "},{"insert":"Overrated","attributes":{"bold":true}},{"insert":"\n","attributes":{"list":"bullet"}},
      {"insert":"Tsumago 妻籠 – schön für Füße"},{"insert":"\n","attributes":{"list":"bullet"}}]""").jsonArray
    assertEquals("- ⚠️ **Overrated**\n- Tsumago 妻籠 – schön für Füße", QuillMarkdown.convert(ops))
}
@Test fun `plain multi-line text with blank lines`() {
    // [{"insert":"WANDERTAG\n\nZeile"}] → "WANDERTAG\n\nZeile"
}
@Test fun `link with same text collapses to url`() {
    // [{"insert":"https://a.b","attributes":{"link":"https://a.b"}},{"insert":"\n"}] → "https://a.b"
}
@Test fun `link with other text`() {
    // [{"insert":"Seite","attributes":{"link":"https://a.b"}}] → "[Seite](https://a.b)"
}
@Test fun `null and embeds`() {
    // convert(null) == ""; [{"insert":{"image":"x"}},{"insert":"a\n"}] → "a"
}
```

- [ ] **Step 2: Tests ausführen** → `./gradlew :importer:test --tests "*QuillMarkdownTest"` → FAIL
- [ ] **Step 3: Implementieren**
- [ ] **Step 4: Tests ausführen** → PASS
- [ ] **Step 5: Commit** → `git commit -m "feat(importer): quill delta to markdown"`

---

### Task 7: Wanderlog-DTOs und Parser mit Validierung

**Files:**
- Create: `importer/src/main/kotlin/de/wanderlogplus/importer/WanderlogDto.kt`
- Create: `importer/src/test/resources/wanderlog-mini.json`
- Test: `importer/src/test/kotlin/de/wanderlogplus/importer/WanderlogParserTest.kt`

**Interfaces:**
- Produces (alle `@Serializable`, `ignoreUnknownKeys`, alle Felder außer den unten als Pflicht markierten nullable/mit Default):
  - `WlResponse(success: Boolean, tripPlan: WlTripPlan, resources: WlResources = WlResources())`
  - `WlTripPlan(key*, title*, startDate*, endDate*, itinerary*: WlItinerary)`; `WlItinerary(sections*: List<WlSection>, budget: WlBudget? )`
  - `WlSection(id*: Long, type: String, mode*: String, date: String?, heading: String?, text: JsonObject?, blocks: List<WlBlock> = [])`
  - `WlBlock(id*: Long, type*: String, place: WlPlace?, text: JsonObject?, hotel: WlHotel?, guests: WlGuests?, cancellationDeadline: String?, cancellationPolicy: String?, reactions: Map<String, List<Long>> = {}, flightInfo: WlFlightInfo?, depart: WlEndpoint?, arrive: WlEndpoint?, carrier: String?, confirmationNumber: String?, travelerNames: List<String> = [])`
  - `WlPlace(place_id*, name*, geometry*: WlGeometry, formatted_address: String?, rating: Double?, user_ratings_total: Int?, website: String?, international_phone_number: String?, price_level: Int?, url: String?, address_components: List<WlAddressComponent> = [])` – Feldnamen per `@SerialName`, Kotlin-Namen camelCase.
  - `WlEndpoint(date: String?, time: String?, airport: WlAirport?, place: WlPlace?)`; `WlAirport(iata: String?, name: String?)`; `WlFlightInfo(airline: WlAirline?, number: Int?)`
  - `WlHotel(checkIn*, checkOut*, travelerNames = [], confirmationNumber: String?)`; `WlGuests(adultCount: Int?, roomCount: Int?)`
  - `WlBudget(expenses: List<WlExpense> = [])`; `WlExpense(id*: Long, amount*: WlAmount, category: String?, description: String = "", associatedDate: String?, blockId: Long?)`; `WlAmount(amount*: Double, currencyCode*: String)`
  - `WlResources(placeMetadata: List<WlPlaceMetadata> = [])`; `WlPlaceMetadata(placeId*, description: String?, generatedDescription: String?, categories: List<String> = [], minMinutesSpent: Int?, maxMinutesSpent: Int?, rating: Double?, numRatings: Int?, imageKeys: List<String> = [], openingPeriods: List<WlOpeningPeriod> = [])`
  - `class ImportFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)`
  - `object WanderlogParser { fun parse(json: String): WlResponse }` – wirft `ImportFormatException`, wenn: kein gültiges JSON, `success != true`, Pflichtfelder fehlen (Serialisierungsfehler mit Pfad in der Meldung), Block-`type` `place` ohne `place`, `flight` ohne `depart`/`arrive`, Section `dayPlan` ohne `date`.

- [ ] **Step 1: Fixture `wanderlog-mini.json` anlegen** – anonymisierter Ausschnitt in exakt der echten Struktur (Feldnamen aus `testdata/private/wanderlog-real.json` übernehmen), Inhalt:
  - Trip `key="mini"`, `2027-05-08`..`2027-05-12`.
  - Section `Flights` (type `flights`): 1 Flug FRA→NRT, 05-08 11:15 → 05-09 13:15, Airline „Test Air“ (iata `TA`), Nr. 1.
  - Section `Verkehr` (type `transit`): 1 Zug **ohne** `carrier`, 05-11, Place A → Place B, `text` mit Routentext.
  - Section `Hotels` (type `hotels`): 2 Hotels: Hotel T (Tokio, 35.67/139.74) 05-09→05-11; Hotel O (Osaka, 34.67/135.50) 05-11→05-12.
  - Section `Tokio Sehenswürdigkeiten`: 2 Orte (Shrine T1 mit `text`-Notiz und `reactions {"🕖":[1]}`, T2 **ohne** `text`) + 1 Note-Block.
  - Section `Kyoto Tagesausflug (Osaka)`: 1 Ort K1 (35.01/135.77).
  - Section `Osaka Sehenswürdigkeiten`: 1 Ort O1.
  - Section `Food-Spots`: 1 Ort F1 bei Tokio **und** Ort T1 (gleiche `place_id` wie in Tokio-Liste).
  - Section `Useful Apps` (type `normal`, nur Note-Blöcke): 2 Notes.
  - Section dayPlan `2027-05-10` „Tokio Tag“: 1 Note + Ort T2.
  - `resources.placeMetadata`: Einträge für T1 und K1 (mit `openingPeriods`, `minMinutesSpent`), **keiner** für T2.
  - `budget.expenses`: 1 Ausgabe 100.0 EUR zu Hotel T (`blockId`).

- [ ] **Step 2: Failing Tests schreiben**
- `parses mini fixture`: 9 Sections, Trip-Daten korrekt, Zug-`carrier == null`, Flug-`depart.airport.iata == "FRA"`.
- `rejects success false`: `{"success":false}` → `ImportFormatException`.
- `rejects missing trip dates`: Fixture-JSON mit entferntem `startDate` (per `JsonObject`-Manipulation im Test) → `ImportFormatException`, Meldung enthält `startDate`.
- `rejects place block without place`: → `ImportFormatException`.
- `rejects invalid json`: `"<html>"` → `ImportFormatException`.
- `real export parses` (nur wenn `testdata/private/wanderlog-real.json` existiert, sonst `assumeTrue(false)`; Pfad relativ zum Repo-Root über `System.getProperty("repoRoot")`, in `importer/build.gradle.kts` als `systemProperty("repoRoot", rootDir.path)` gesetzt): 30 Sections, `startDate == "2027-05-08"`.

- [ ] **Step 3: Tests ausführen** → FAIL
- [ ] **Step 4: DTOs und Parser implementieren**
- [ ] **Step 5: Tests ausführen** → PASS
- [ ] **Step 6: Echten Export als lokale Fixture ablegen**
```bash
mkdir -p testdata/private && curl -s -A "Mozilla/5.0" -o testdata/private/wanderlog-real.json "https://wanderlog.com/api/tripPlans/rkqpffsjnb?clientSchemaVersion=2&registerView=false"
git status --short testdata
```
Expected: `git status` zeigt **nichts** für `testdata/private` (gitignored). Dann `./gradlew :importer:test --tests "*WanderlogParserTest"` → PASS inkl. `real export parses`.
- [ ] **Step 7: Commit** → `git commit -m "feat(importer): wanderlog DTOs and validating parser"`

---

### Task 8: Mapper DTO → ImportSnapshot

**Files:**
- Create: `importer/src/main/kotlin/de/wanderlogplus/importer/WanderlogMapper.kt`
- Test: `importer/src/test/kotlin/de/wanderlogplus/importer/WanderlogMapperTest.kt`

**Interfaces:**
- Consumes: DTOs (Task 7), `QuillMarkdown` (Task 6), Modelle/`EntityCodec`/`SortKey`/`TripQueries` (shared)
- Produces:
  - `data class ImportedEntity(val type: EntityType, val sourceId: String, val fields: Map<String, JsonElement>, val masterFields: Set<String> = emptySet())` – `id` = `EntityCodec.importedId(type, sourceId)` (berechnet, Property `val id`).
  - `data class ImportSnapshot(val entities: List<ImportedEntity>)`
  - `object WanderlogMapper { fun map(response: WlResponse): ImportSnapshot }`

**Abbildungsregeln (sourceIds):**
| Ziel | sourceId | Bemerkung |
|---|---|---|
| Trip | `trip:<key>` | |
| Region | `section:<sectionId>` | Sections mit Heading auf `"Sehenswürdigkeiten"` → Name = Text davor (getrimmt); Heading-Muster `^(.+?) Tagesausflug \((.+)\)$` → Name = Gruppe 1, `dayTripFromRegionId` = ID der Region mit Name Gruppe 2. Farbe = `placeMarkerColor`. |
| Place | `place:<place_id>` | einmal pro `place_id`; `masterFields` = alle Felder; `description` = `generatedDescription ?: description` aus Metadata; Öffnungszeiten aus Metadata. |
| SavedPlace | `block:<blockId>` | `category`: Heading „Food-Spots“ → FOOD, „Shopping“ → SHOPPING, „Nightlife“ → NIGHTLIFE, sonst SIGHT; `tags` = Schlüssel von `reactions`; `status` = IDEA, bei Orten aus dayPlan-Sections PLANNED. |
| Stay | `block:<blockId>` | Blöcke mit `hotel != null`; `note` = Markdown von `text`. |
| Flight / Train | `block:<blockId>` | `Endpoint.code` = Flughafen-IATA; bei Zügen `name` = `place.name`, `placeId` = importedId(PLACE, place_id) (Places werden dafür ebenfalls angelegt). |
| Day | `section:<sectionId>` | dayPlan-Sections; `title` = heading, `note` = Markdown von `text`. |
| DayItem | `block:<blockId>` | Reihenfolge per `SortKey.sequence`; Note-Blöcke: `kind = NOTE`, `text` = Markdown. |
| Note | `section:<sectionId>` | Jede Nicht-dayPlan-Section mit Section-`text` oder Note-Blöcken: `title` = heading, `body` = Section-Text + Note-Blöcke, getrennt durch `"\n\n"`; `sortKey` nach Section-Reihenfolge. |
| Expense | `expense:<id>` | `relatedId` = importedId(STAY, `block:<blockId>`), wenn `blockId` gesetzt. |

**Regionen-Heuristik** (nach dem Anlegen der Regionen):
- Schwerpunkt jeder Region = Mittel der Koordinaten ihrer Orte.
- `Stay.regionId` = nächster Schwerpunkt unter Regionen **ohne** `dayTripFromRegionId` (Haversine).
- SavedPlaces aus Sections ohne eigene Region (Food/Shopping/Nightlife/sonstige): nächster Schwerpunkt aller Regionen ohne `dayTripFromRegionId`, wenn ≤ 50 km, sonst `null`.
- SavedPlaces aus dayPlan-Sections: Region des Tages per `TripQueries(...).regionFor(date)` mit den gemappten Stays.

- [ ] **Step 1: Failing Tests schreiben** (alle gegen `wanderlog-mini.json`)
- `creates regions with day trip link`: 3 Regionen; „Kyoto“ hat `dayTripFromRegionId` == ID von „Osaka“.
- `deduplicates places across lists`: genau ein PLACE für T1; zwei SAVED_PLACEs mit dessen `placeId`, Kategorien SIGHT und FOOD.
- `stay regions by proximity`: Hotel T → Tokio, Hotel O → Osaka (nicht Kyoto).
- `food spot near tokyo gets tokyo region`: F1 → Tokio.
- `day plan place is planned in day region`: SavedPlace für T2 aus dayPlan 05-10 hat `status = PLANNED`, Region Tokio; DayItems der Day-Section: [NOTE, PLACE] in aufsteigender `sortKey`.
- `missing metadata and text are tolerated`: PLACE T2 hat `description == null`, `openingPeriods` leer; SavedPlace T2 (aus Tokio-Liste) `note == ""`.
- `reactions become tags`: SavedPlace T1 (Tokio) `tags == ["🕖"]`.
- `notes section`: NOTE „Useful Apps“ mit beiden Note-Texten im `body`.
- `ids are stable`: zweimal `map` → identische Entity-IDs und Felder.
- `real export` (assume Datei vorhanden): 8 Regionen (Tokio, Kiso, Gero, Osaka, Kyoto, Nara, Hiroshima, Okinawa); 7 Stays mit Regionen in dieser Reihenfolge nach `checkIn`: Tokio, Kiso, Gero, Osaka, Hiroshima, Okinawa, Tokio; 6 Flights; 10 Trains; 12 Days.

- [ ] **Step 2: Tests ausführen** → FAIL
- [ ] **Step 3: Implementieren** – Felder über `EntityCodec.fields(...)` der Modell-Datenklassen erzeugen (Modell-ID wird ignoriert), damit Feldnamen exakt mit `shared` übereinstimmen.
- [ ] **Step 4: Tests ausführen** → PASS
- [ ] **Step 5: Commit** → `git commit -m "feat(importer): map wanderlog trip to import snapshot"`

---

### Task 9: ReimportMerger (3-Wege-Merge)

**Files:**
- Create: `importer/src/main/kotlin/de/wanderlogplus/importer/ReimportMerger.kt`
- Test: `importer/src/test/kotlin/de/wanderlogplus/importer/ReimportMergerTest.kt`

**Interfaces:**
- Consumes: `ImportSnapshot`, `EntityStore`, `HlcClock`, `FieldChange`
- Produces:
  - `data class ImportStats(val created: Int, val updated: Int, val conflicts: Int, val removed: Int, val flaggedRemoved: Int)`
  - `data class ImportResult(val changes: List<FieldChange>, val stats: ImportStats)`
  - `class ReimportMerger(private val clock: HlcClock) { fun merge(snapshot: ImportSnapshot, store: EntityStore): ImportResult }` – **liest** nur aus dem Store; der Aufrufer wendet `changes` per `applyChanges` an.

**Regeln** (W = Snapshot-Wert, B = `_base.<f>`, A = aktueller Wert; Vergleich auf `JsonElement`-Gleichheit, fehlend ≙ `JsonNull`):
- Entity nicht im Store → alle Felder + `_base.<f>` = W + `_source="wanderlog"`, `_sourceId`, `deleted=false`, `_base.deleted=false`; zählt `created`.
- Für jedes Feld f:
  - f ∈ `masterFields` → wenn A ≠ W: `f = W` (kein `_base`).
  - A == B und W ≠ B → `f = W`, `_base.f = W`.
  - A ≠ B und W == B → nichts.
  - A ≠ B und W ≠ B und A ≠ W → `_conflict.f = W`, `_base.f = W`; zählt `conflicts`.
  - A ≠ B und W ≠ B und A == W → `_base.f = W`; vorhandenen `_conflict.f` auf `JsonNull`.
  - Entity zählt `updated`, wenn mindestens eine Nutzfeld-Änderung entsteht.
- `deleted` ist ein normales Nutzfeld mit `_base.deleted`; der Snapshot liefert implizit W = `false` für jede enthaltene Entity.
- Entity mit `_source="wanderlog"` im Store, aber nicht im Snapshot, und `deleted == false`:
  - kein Nutzfeld (inkl. `deleted`, ohne masterFields) mit A ≠ B → `deleted = true` **und** `_base.deleted = true`; zählt `removed`.
  - sonst → `_removedInSource = true` (nur wenn nicht schon gesetzt); zählt `flaggedRemoved`.
- Damit folgt aus der normalen Feldregel: Taucht eine vom Import gelöschte Entity wieder auf (A = true, B = true, W = false) → `deleted = false`. Eine vom Nutzer gelöschte Entity (A = true, B = false, W = false) bleibt gelöscht.
- Entity im Snapshot mit `_removedInSource == true` → `_removedInSource = false`.
- Entities mit `_source` ≠ `"wanderlog"` werden nie angefasst.
- Alle Änderungen eines Laufs bekommen HLCs aus `clock.tick()`.

- [ ] **Step 1: Failing Tests schreiben** (Hilfsfunktion: Snapshot aus `wanderlog-mini.json` per `WanderlogMapper`, Store = `InMemoryEntityStore`, Merge + `applyChanges`)
- `first import creates everything`: `stats.created == snapshot.entities.size`, alle im Store, `decode<Stay>` funktioniert.
- `second identical import is a no-op`: zweiter Merge → `changes.isEmpty()`.
- `wanderlog change on untouched field is applied`: Snapshot mit geändertem `SavedPlace.note` → Wert übernommen, `_base.note` aktualisiert.
- `app edit survives reimport`: App ändert `note` (eigene HlcClock „dev-a“), Snapshot unverändert → `note` bleibt App-Wert.
- `conflict is recorded`: App ändert `note`, Snapshot ändert `note` anders → Wert = App, `_conflict.note` = Snapshot-Wert, `stats.conflicts == 1`.
- `same change on both sides clears conflict`: danach Snapshot-Wert == App-Wert → `_conflict.note` == `JsonNull`.
- `master fields always follow wanderlog`: App ändert `Place.rating` (sollte nicht vorkommen), Snapshot anderer Wert → Snapshot gewinnt.
- `removed untouched entity is soft-deleted`: Snapshot ohne Ort F1 → SavedPlace `isDeleted`, `stats.removed ≥ 1`.
- `removed edited entity is flagged`: App ändert Notiz von F1, dann Snapshot ohne F1 → nicht gelöscht, `_removedInSource == true`.
- `reappearing entity is restored`: F1 entfernt (soft-deleted), dann wieder im Snapshot → `isDeleted == false`.
- `user deletion survives reimport`: App setzt `deleted = true` auf SavedPlace T2, Snapshot unverändert → bleibt gelöscht.
- `app-created entities are untouched`: Record mit `_source = "app"` bleibt nach Merge unverändert.

- [ ] **Step 2: Tests ausführen** → FAIL
- [ ] **Step 3: Implementieren**
- [ ] **Step 4: Tests ausführen** → PASS
- [ ] **Step 5: Commit** → `git commit -m "feat(importer): three-way reimport merge"`

---

### Task 10: HTTP-Client, CLI und Ende-zu-Ende-Lauf mit echten Daten

**Files:**
- Create: `importer/src/main/kotlin/de/wanderlogplus/importer/WanderlogClient.kt`, `.../importer/Cli.kt`
- Test: `importer/src/test/kotlin/de/wanderlogplus/importer/EndToEndTest.kt`

**Interfaces:**
- Consumes: alles aus Task 1–9
- Produces:
  - `class WanderlogClient(private val http: java.net.http.HttpClient = HttpClient.newHttpClient()) { fun fetch(tripKey: String): String }` – Endpoint aus Global Constraints, Header `User-Agent: Mozilla/5.0`, Timeout 30 s, 1 Retry; HTTP ≠ 200 → `ImportFormatException("HTTP <code>")`.
  - `object ImportRunner { fun run(json: String, store: EntityStore, clock: HlcClock): ImportResult }` – parse → map → merge → `applyChanges`. Wirft vor jeder Store-Änderung, wenn Parse/Map fehlschlägt.
  - `fun main(args: Array<String>)` – Optionen: `--key <tripKey>` **oder** `--file <path>`; `--state <path>` (JSON-Datei mit `List<EntityRecord>`; wird geladen, falls vorhanden, und nach dem Lauf geschrieben); `--summary` gibt pro Tag `datum | titel | region | unterkunft | anzahl vorschläge` aus. Ausgabe der `ImportStats` immer. Exit-Code 1 bei `ImportFormatException` mit Meldung auf stderr; State-Datei bleibt dann unverändert.

- [ ] **Step 1: Failing Tests schreiben** (`EndToEndTest`, assume echte Datei vorhanden)
- `real import twice is idempotent`: `ImportRunner.run` zweimal auf denselben Store → zweites `changes.isEmpty()`.
- `real summary`: `TripQueries` aus Store → 22 Tage; `regionFor("2027-05-08") == null`; Region-Namen für `05-09`, `05-14`, `05-16`, `05-20`, `05-23`, `05-26`, `05-29` = Tokio, Kiso, Gero, Osaka, Hiroshima, Okinawa, Tokio; `suggestionsFor("2027-05-20")` enthält Orte aus Kyoto und Nara.
- `broken input leaves store untouched`: Store nach erfolgreichem Import, dann `run("{}", …)` → `ImportFormatException`, `store.all()` unverändert.

- [ ] **Step 2: Tests ausführen** → FAIL
- [ ] **Step 3: Implementieren**
- [ ] **Step 4: Tests ausführen** → `./gradlew test` → alle PASS
- [ ] **Step 5: CLI manuell gegen Live-Endpoint prüfen**
```bash
./gradlew :importer:run --args="--key rkqpffsjnb --state build/state.json --summary"
./gradlew :importer:run --args="--key rkqpffsjnb --state build/state.json"
```
Expected: erster Lauf `created > 0`, Zusammenfassung mit 22 Zeilen und plausiblen Regionen; zweiter Lauf `created=0 updated=0 conflicts=0 removed=0 flaggedRemoved=0`.
- [ ] **Step 6: Commit** → `git commit -m "feat(importer): http client, import runner and CLI"`
