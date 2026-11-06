# Guides und Recherche-Pipeline – Design

Datum: 2026-11-06 · Status: zur Freigabe

## Problem

Unterwegs fehlt schnelles Wissen dazu, *wie* man sich an einem Ort verhält: Onsen-Knigge, im Restaurant bestellen,
Omakase, Shinkansen-Etikette, Grundsätze der Sprache. Dieses Wissen soll als **Guides** in der App liegen (offline) und dort
auftauchen, wo es gebraucht wird: an Orten, Unterkünften, Fahrten, Tagen und auf der Heute-Seite.

Gleichzeitig bewertet heute bei der Ortsrecherche der Parallel-Processor selbst (Fakten *und* Urteil), ohne den
Reisekontext zu kennen. Guides und Ortsrecherche bekommen einen gemeinsamen Ablauf: Parallel recherchiert Fakten,
das Textmodell (Sonnet 5.5) bewertet und schreibt.

## Abgrenzung

Drei Teile, nacheinander auf eigenen Branches:

1. **Modelle aufteilen + Chat-Kontext erweitern** – eigener kurzer Entwurf im Chat, nicht Teil dieser Spec.
   Voraussetzung für 2 und 3: `WLP_TEXT_MODEL` existiert.
2. **Recherche-Pipeline** (diese Spec).
3. **Guides** (diese Spec), baut auf 2 auf.

## Entscheidungen (abgestimmt)

- Guides sind **allgemein** (kein ortsgenauer Hinweis), können **Unterthemen** haben (Restaurant → Omakase).
- Inhalte entstehen aus **Parallel-Recherche + Sonnet 5.5** (über OpenRouter, `WLP_TEXT_MODEL`).
- Der **Grundstock wird aus der Reise abgeleitet**: die App schlägt Themen vor, der Nutzer hakt ab.
- Bei späteren Reiseänderungen: **vorschlagen statt erstellen** (Variante B) – Erkennung ohne KI, Erstellen erst auf Tippen.
- Der **Chat** kann Guides recherchieren, erweitern **und zuordnen** (einzeln anheften und per Regel).
- Gemeinsame Pipeline für Guides und Ortsrecherche.

## Modell-Rollen (aus Teil 1, zur Einordnung)

| Variable | Modell | Aufgaben |
|---|---|---|
| `WLP_AI_MODEL` | `google/gemini-2.5-flash` | Reels/Videos (Bild + Ton) |
| `WLP_TEXT_MODEL` | `anthropic/claude-sonnet-5-5` | Chat, Einschätzen (`assess`), Recherche-Bewertung, Guides |

Hinweise für Sonnet 5.5: kein erzwungenes `tool_choice` (`any`/`tool`), Chat-Verlauf nur anhängen.

## Teil 2: Recherche-Pipeline

### Ablauf (`server/.../ai/ResearchPipeline.kt`)

1. **Recherche (Parallel, Processor `WLP_PARALLEL_PROCESSOR`, Standard `base`)**: Auftrag mit Thema bzw. Ort und
   Reisekontext. Ausgabe-Schema nur **Fakten mit Quellen** (keine Wertung, kein Fließtext):
   `facts: [{text, sourceUrls[]}]`, plus bei Orten die heute schon erfassten Felder (Öffnungszeiten, Preise,
   Reservierung, POIs).
2. **Bewerten/Schreiben (`WLP_TEXT_MODEL`)**: bekommt die Fakten + Reisekontext (wann ist man in der Region,
   Kriterien aus `AssessConfig`, Ähnliches im Plan) und liefert das Ziel-JSON (Ortsdossier bzw. Guide).
   Regel im Prompt: nichts behaupten, was nicht in den Fakten steht – außer allgemein Bekanntem (z. B. Grußformeln).
3. **Prüfen**: JSON parsen und gegen das Schema prüfen; bei Fehler **einmal wiederholen**, sonst `AiFailed`.
4. **Speichern**: nur vollständige Ergebnisse, als Assistent über `sync.writeAsServer`.

Ohne Parallel-Key: Schritt 1 entfällt, das Textmodell recherchiert mit `webSearch = true` (wie heute
`OpenRouterResearcher`), Schritt 2–4 bleiben.

### Ortsrecherche

`ParallelResearcher` wird auf die Pipeline umgestellt: Parallel liefert Fakten, `ResearchOutcome` (Kriterien,
`worth`, Fazit, Dossier) kommt aus Schritt 2. Das Ergebnisformat für App und Sync bleibt **unverändert**.
`Assistant.assess` läuft auf `WLP_TEXT_MODEL`.

## Teil 3: Guides

### Themenkatalog (`shared/.../guide/GuideTopics.kt`)

Fester Katalog im Code, ca. 20–25 Themen. Je Thema: `key`, `name`, `parentKey?`, `triggers`.

Auslöser-Arten (`GuideTrigger`, dieselben für Katalog und freie Guides):

- `PlaceType(googleTypes)` – Google-Typen eines Orts, z. B. `buddhist_temple`, `shinto_shrine`, `spa`
- `PlaceCategory(cat)` – `SIGHT`, `FOOD`, …
- `NameContains(words)` – Wörter im Namen von Ort/Unterkunft (Groß-/Kleinschreibung egal), z. B. „Ryokan“, „Onsen“, „Omakase“
- `StayAny` / `StayNameContains(words)`
- `TrainAny` / `TrainKind(shinkansen)`, `FlightAny`
- `Always` – z. B. `sprache-basics`

Beispiele: `onsen`, `ryokan`, `restaurant` (Kinder `omakase`, `izakaya`, `ramen`), `shinkansen`, `bahn-allgemein`,
`flughafen`, `tempel-schrein`, `sprache-basics`, `bezahlen-bargeld`, `muell-und-strasse`.
Die erste Liste wird einmal mit der KI anhand der echten Reisedaten vorgeschlagen und dann im Code festgelegt.

### Datenmodell

Neue synchronisierte Entity `Guide` (HLC wie die anderen; vom Import unberührt):

```kotlin
@Serializable
data class Guide(
    val id: String,
    val topic: String? = null,          // Katalog-Schlüssel; null = freier Guide (aus dem Chat)
    val parentId: String? = null,       // Unterthema
    val title: String = "",
    val summary: String = "",           // das Wichtigste in ~3 Zeilen
    val sections: List<GuideSection> = emptyList(),
    val phrases: List<GuidePhrase> = emptyList(),
    val sources: List<String> = emptyList(),
    val extraTriggers: List<GuideTrigger> = emptyList(),
    val pinnedTo: List<GuideTarget> = emptyList(),
    val hiddenFrom: List<GuideTarget> = emptyList(),
)
data class GuideSection(val heading: String, val text: String = "", val dos: List<String> = emptyList(), val donts: List<String> = emptyList())
data class GuidePhrase(val german: String, val japanese: String, val romaji: String, val situation: String = "")
sealed GuideTarget: Place(savedPlaceId) | Stay(stayId) | Train(id) | Flight(id) | Day(date)
```

Abgelehnte Vorschläge: synchronisierte Liste von Themen-Schlüsseln (`GuidePrefs.dismissedTopics`).

### Logik (`shared`, getestet)

- `GuideMatch.topicsFor(target, snapshot)`: Katalog-Themen, die ein Ziel berührt.
- `GuideMatch.guidesFor(target, snapshot)`: (Treffer über Katalog-Auslöser ∪ `extraTriggers` ∪ `pinnedTo`) − `hiddenFrom`.
  Ein Unterthema-Guide erscheint, wenn *seine* Auslöser greifen; der Eltern-Guide wird dann mit angezeigt.
- `GuideMatch.forDay(date, snapshot)`: über den `DayFlow` des Tages (Programm, Unterkunft des Abends, Fahrten),
  sortiert nach dem nächsten anstehenden Ereignis; erledigte Ereignisse nach hinten.
- `GuideSuggestions.open(snapshot)`: Themen aus der ganzen Reise − vorhandene Guides − `dismissedTopics`.
- `GuideMatch.duplicateOf(topic | title, guides)`: gleicher Themen-Schlüssel oder ähnlicher Titel
  (Namensabgleich wie `PlaceMatch`).

### Erzeugen

- Ein Guide wird über die Recherche-Pipeline erzeugt (Thema + Reisekontext → Fakten → Guide-JSON).
  Bei Katalog-Themen kommen die Auslöser aus dem Katalog; bei freien Guides schlägt das Textmodell `extraTriggers`
  im selben Schritt vor (nur aus den Auslöser-Arten oben).
- **Grundstock**: Vorschlagsliste → Haken → „Ausgewählte erstellen“ → ein `AiJobs`-Auftrag arbeitet die Themen
  nacheinander ab (Fortschritt wie beim Einschätzen in Durchgängen). Ein fehlgeschlagenes Thema bricht den Rest nicht ab.
- Liefert die Recherche nichts Verwertbares: kein Guide, klare Fehlermeldung.

### Chat-Werkzeuge (`ChatAgent`)

- `listGuides` – Titel, Thema, Auslöser, Zuordnungen. Wird vor jedem Anlegen genutzt.
- `createGuide(topic | freier Titel, parent?, research)` – startet den Pipeline-Auftrag, Fortschritt im Chat.
  Gibt es schon einen Guide zum Thema (`duplicateOf`), wird er stattdessen erweitert.
- `updateGuide(guideId, wunsch, research?)` – Inhalt ergänzen oder korrigieren.
- `assignGuide(guideId, ziel)` / `unassignGuide(guideId, ziel)` – an Ort, Unterkunft, Fahrt oder Tag anheften bzw. dort ausblenden.
- `setGuideTriggers(guideId, auslöser)` – Regeln ändern („bei allen Ryokans“).

Der Prompt erklärt den Unterschied: „dieser Ort“ → `assignGuide`, „alle …“ → `setGuideTriggers`.

### Anzeige (App)

- **Heute**: Karte „Für heute“ unter Jetzt/Nächste Fahrt mit Chips aus `GuideMatch.forDay`. Antippen → Bottom-Sheet
  (Zusammenfassung, Top-3 Do/Don't, „Ganzen Guide öffnen“). Hinweis „N Guides vorgeschlagen“, wenn heute Themen ohne Guide sind.
- **Detailseiten** (Ort, Unterkunft, Fahrt, Tag): Bereich „Guides“ mit Chips; lange drücken → „Hier ausblenden“; Plus → anheften.
- **Guide-Seite**: Titel, Zusammenfassung, Abschnitte mit Do/Don't-Listen, Unterthemen, Sätze zum Zeigen
  (Deutsch, groß Japanisch, Lautschrift; Lautsprecher = Android-TTS `ja-JP`; Vollbild zum Hinhalten), Quellen.
  Menü: Bearbeiten, Im Chat verbessern (Chat mit Guide als Kontext), Löschen.
  Ohne japanisches TTS-Sprachpaket: Lautsprecher ausgeblendet, einmaliger Hinweis.
- **Guide-Übersicht** (Reise-/Mehr-Menü): nach Oberthemen gruppiert; oben Vorschläge mit Haken und
  „Ausgewählte erstellen“; laufende Aufträge mit Fortschritt.
- **Offline**: alles per Sync lokal; Netz nur für Erstellen/Verbessern.

## Fehlerbehandlung

- Ungültiges Modell-JSON: einmal wiederholen, dann Fehlermeldung; nie halbfertig speichern.
- Parallel-Fehler/Timeout: Auftrag scheitert mit Meldung; im Grundstock geht es mit dem nächsten Thema weiter.
- Doppelte Guides: `duplicateOf` vor jedem Anlegen (App und Chat).

## Tests

- `shared`, TDD: `GuideMatch` (Ryokan in Kiso → `ryokan` + `onsen`; Shinkansen-Tag → `shinkansen`; ausgeblendet →
  nicht angezeigt; Unterthema zieht Eltern-Guide mit), `GuideSuggestions` (Reise − vorhanden − abgelehnt),
  `forDay`-Reihenfolge am Tokio→Kiso-Tag aus `DayFlowTest`, `duplicateOf`.
- Server, ohne echte KI: `ResearchPipeline` mit Fake-Parallel-Transport und Fake-Modell (Wiederholung bei
  ungültigem JSON, nichts Halbfertiges gespeichert); Ortsrecherche nach Umstellung mit den Erwartungen der
  bisherigen `ParallelResearcher`-Tests; Chat-Werkzeuge in `ChatAgentTest` (`assignGuide`, `setGuideTriggers`,
  `createGuide` erweitert statt doppelt anzulegen).
- Manuell: Grundstock-Stichprobe (3 Guides) mit echtem Sonnet + Parallel auf Qualität prüfen; TTS und Vollbild am Gerät.
