# Bild-Posts, Tipps aus Beiträgen, App-/Web-Links in Guides — Design

Datum: 2026-11-11 · Version: 0.62.0

## Ziel

1. Der Reel-Import versteht auch geteilte Instagram-Posts aus **einem Bild oder einer Bildergalerie** (auch gemischt mit Videos).
2. Der Import erkennt **allgemeine Reisetipps** im Beitrag, prüft, ob sie in bestehenden Guides schon stehen, prüft neue
   Tipps auf **Wahrheitsgehalt** und schlägt vor, sie in einen bestehenden Guide einzuarbeiten oder einen neuen Guide
   anzulegen. Der Nutzer bestätigt, bevor geschrieben wird.
3. Guides bekommen **Links zu erwähnten Apps und Webseiten**. Apps öffnen sich, wenn installiert, sonst der Play Store;
   Webseiten im Browser. Die Links der Guides erscheinen auch dort, wo die Guides referenziert werden (Ort, Unterkunft,
   Fahrt, Flug, Tag, „Für heute“).

Entscheidungen des Nutzers: Tipps werden **vorgeschlagen und bestätigt** (nicht automatisch geschrieben). Bestehende
Guides bekommen Links über einen Button **„Links ergänzen“** (eigener Job), neue/überarbeitete Guides automatisch.

## A. Bild- und Galerie-Posts

### Download

`ReelMedia.download` liefert künftig

```kotlin
data class Downloaded(val videos: List<Path>, val images: List<Path>, val caption: String?)
```

`ToolReelMedia`:

1. yt-dlp wie bisher (ein Video; Galerien, auch mit Videos, lädt gallery-dl).
2. Findet yt-dlp kein Video (Exit ≠ 0 oder keine Videodatei), läuft **gallery-dl** mit derselben Cookies-Datei
   (`--cookies`), Ausgabe in `dir/gallery/`, Metadaten per `--write-metadata` (Caption aus `description`/`caption`).
   Ergebnis: Bilder (`.jpg/.jpeg/.png/.webp/.heic`) und Videos (`.mp4/.mov/.webm`) in Galerie-Reihenfolge.
3. Ohne beides → `DownloadFailed`.

Konfiguration: `WLP_GALLERY_DL` (Pfad, Default `gallery-dl`), analog zu yt-dlp.

### Zerlegen

`ReelMedia.extract(downloaded, dir): Media`:

- Jedes Video: Standbilder wie bisher (alle 3 s, 512 px, höchstens 12 je Video) und Ton (nur vom ersten Video mit Ton,
  höchstens 180 s).
- Jedes Bild: per ffmpeg auf max. 1024 px Breite als JPEG (Text auf Tipp-Folien bleibt lesbar).
- Insgesamt höchstens 20 Bilder; Galeriebilder haben Vorrang vor Video-Standbildern.

`Media` bleibt `frames + audio`. Der hochgeladene Video-Weg (`ReelInput.Video`) wird zu einem `Downloaded` mit einem Video.

### Prompt

`findPlaces` heißt künftig `analyze` und spricht von „Beitrag (Video, Bild oder Bildergalerie)“; Hinweis, dass Text auf
Bildern/Folien oft der eigentliche Inhalt ist. Log-Texte sagen „Beitrag“ statt „Video“, wo es um beides geht, z. B.
„3 Bilder, 1 Video, Beschreibung mit 840 Zeichen“.

### Server-Image

Dockerfile: gallery-dl als aktuelle Release-Datei (`gallery-dl.bin` von GitHub, wie yt-dlp). Neu bauen holt eine neue
Version. Heimserver muss nach dem Merge neu gebaut werden.

## B. Tipps erkennen, prüfen, übernehmen

### 1. Erkennen

Die Analyse liefert zusätzlich zu `locations`:

```json
"tips": [{"claim": "eigenständige Aussage auf Deutsch", "topic": "Thema in 1–3 Wörtern", "evidence": "woher (Folie 3, gesprochen, Beschreibung)"}]
```

Nur allgemeine Reisetipps (Etikette, Verkehr, Geld, Apps, Buchen, Hacks). Tipps zu einem konkreten Ort bleiben bei dem
Ort (wie bisher über dessen Recherche). Höchstens 12 Tipps. Ein Beitrag kann nur Orte, nur Tipps oder beides haben.

### 2. Abgleich mit bestehenden Guides

Neue Klasse `TipCheck` (Server, `ai/TipCheck.kt`). Ein Aufruf des Textmodells bekommt die Tipps und einen kompakten
Auszug aller Guides (id, Titel, Thema, Zusammenfassung, Abschnittsüberschriften mit Text, Do's und Don'ts; gekürzt) sowie
den Themenkatalog (`GuideTopics`: key + Name). Antwort pro Tipp:

- `covered`: `guideId` + kurzes Zitat, wo es schon steht → kein Faktencheck.
- `new` mit Ziel: `guideId` (bestehender Guide) **oder** `topic` (Katalog-Thema ohne Guide) **oder** `title` (neuer
  freier Guide).

Unbekannte guideIds/Topics aus der Antwort werden verworfen (Ziel = neuer freier Guide mit dem Tipp-Thema als Titel).

### 3. Faktencheck

Für jeden neuen Tipp: `FactSource.facts` (Parallel) mit Ziel „Stimmt das für Touristen in Japan, Stand heute? <claim>“,
dann ein Aufruf des Textmodells mit Tipp + Fakten. Urteil:

| verdict | Bedeutung | Übernahme |
|---|---|---|
| `TRUE` | belegt | vorausgewählt, Text = claim (sprachlich geglättet) |
| `PARTLY` | übertrieben/teilweise richtig | vorausgewählt, Text = korrigierte Fassung |
| `FALSE` | falsch | nicht wählbar, Begründung sichtbar |
| `UNVERIFIED` | nicht belegbar | nicht vorausgewählt, wählbar |

Dazu `reason` (1–2 Sätze) und `sources` (URLs aus der Recherche). Ohne FactSource (kein Parallel-Key) prüft das Modell
mit eigener Websuche (`webSearch = true`), wie `OpenRouterResearcher`. Bis zu `parallelism` Tipps gleichzeitig. Ein
fehlgeschlagener Check → `UNVERIFIED` mit Grund „Prüfung fehlgeschlagen“; der Job läuft weiter.

### Protokoll

```kotlin
enum class TipVerdict { TRUE, PARTLY, FALSE, UNVERIFIED }

@Serializable
data class ReelTip(
    val claim: String,
    val topic: String = "",
    val evidence: String = "",
    /** Guide, in dem es schon steht; dann kein Check. */
    val coveredBy: String? = null,
    val coveredQuote: String = "",
    val verdict: TipVerdict? = null,
    /** Was in den Guide kommt (bei PARTLY die korrigierte Fassung). */
    val text: String = "",
    val reason: String = "",
    val sources: List<String> = emptyList(),
    /** Vorgeschlagenes Ziel: genau eins davon. */
    val guideId: String? = null,
    val targetTopic: String? = null,
    val targetTitle: String? = null,
)
```

`AiJobInfo.tips: List<ReelTip> = emptyList()`. Reel-Job-Ablauf: Download → Analyse → Orte recherchieren → Tipps
abgleichen und prüfen (Status `CHECKING`, Items `tip-<n>` im Job-Fortschritt) → DONE.

### 4. Ergebnis-Bildschirm (App)

Unter den Orten ein Bereich „Tipps“ (nur wenn `tips` nicht leer):

- Pro Tipp: Text, Urteil-Badge (stimmt / übertrieben / falsch / nicht belegt / schon im Guide „X“), Begründung, Quellen
  (aufklappbar), Ziel-Auswahl (Dropdown: bestehende Guides, „Neuer Guide: <Titel>“).
- Checkbox: TRUE/PARTLY vorausgewählt, UNVERIFIED wählbar, FALSE und covered ausgegraut.
- Der Import-Button übernimmt Orte und gewählte Tipps zusammen („3 Orte und 2 Tipps übernehmen“). Ein Beitrag nur mit
  Tipps hat nur den Tipp-Bereich.

### 5. Übernehmen

Die App gruppiert gewählte Tipps nach Ziel und startet **einen** Guide-Job (`POST /api/ai/guides`) mit einer `GuideAsk`
je Ziel:

```kotlin
data class GuideAsk(..., val additions: List<GuideAddition> = emptyList())
@Serializable data class GuideAddition(val text: String, val sources: List<String> = emptyList())
```

`GuideWriter`:

- **Bestehender Guide + additions** (`guideId` gesetzt, `additions` nicht leer): Modus „Ergänzen“: **keine neue
  Recherche**. Das Modell bekommt den vollständigen Guide als JSON und die geprüften Ergänzungen und gibt den ganzen Guide
  zurück: vorhandene Abschnitte, Do's/Don'ts und Phrasen bleiben inhaltlich erhalten, die Ergänzungen werden in passende
  Abschnitte eingearbeitet (oder neuer Abschnitt). Sicherung: Hat die Antwort weniger Abschnitte oder weniger Do's/Don'ts
  als vorher, wird sie abgelehnt und erneut angefragt (über `completeJson`-Prüfung); scheitert es dann noch, wird
  `AiFailed("ai_bad_answer")` geworfen und der Guide bleibt unverändert. Quellen = alte + neue (ohne Doppelte).
- **Neuer Guide + additions**: normaler Ablauf (Recherche + Schreiben), die Ergänzungen gehen als „muss enthalten sein
  (geprüft)“ in den Prompt, ihre Quellen in `sources`.

Der Job erscheint wie andere Guide-Jobs im Fortschritt; die App öffnet danach nichts automatisch.

## C. App- und Web-Links

### Modell

```kotlin
enum class GuideLinkKind { APP, WEB }

@Serializable
data class GuideLink(
    val name: String,
    val kind: GuideLinkKind,
    /** WEB: die Seite. APP: Website der App (Fallback), darf leer sein. */
    val url: String = "",
    /** APP: Android-Paket-ID, z. B. "jp.co.jorudan.jtp"; leer, wenn es keine Android-App gibt. */
    val androidPackage: String = "",
    /** Wofür, ein paar Wörter. */
    val note: String = "",
)
```

`Guide.links: List<GuideLink> = emptyList()`.

### Entstehung

- `GuideWriter` (Schreiben und Ergänzen): das JSON bekommt `"links": [{"name","kind":"app|web","url","androidPackage","note"}]`
  für im Guide erwähnte Apps und Webseiten (nur erwähnte, keine zusätzlichen).
- `LinkCheck` (Server, `ai/LinkCheck.kt`, HTTP über das vorhandene Client-Muster, kurze Timeouts, 4 gleichzeitig):
  - WEB: `GET` mit Redirects; 2xx/3xx → behalten, sonst verwerfen. Nur `https://`/`http://`.
  - APP: Paket-ID muss dem Muster `[a-zA-Z][\w]*(\.[a-zA-Z][\w]*)+` entsprechen und
    `https://play.google.com/store/apps/details?id=<id>` 200 liefern; sonst `androidPackage` leeren. Bleiben weder
    Paket noch gültige URL, fällt der Link weg.
  - Dedup nach Paket-ID bzw. normalisierter URL; höchstens 8 Links pro Guide.
  - Netzfehler beim Prüfen → Link verwerfen (lieber keiner als ein falscher).
- **„Links ergänzen“** (Bestand): Button im Menü der Guide-Übersicht → `POST /api/ai/guide-links` (leerer Body = alle
  Guides) → Job `kind = "guide-links"`, pro Guide ein Item: ein Aufruf des Textmodells auf den vorhandenen Text → Links →
  `LinkCheck` → schreibt **nur** das Feld `links` (`ServerEdit` mit einem Feld). Guides ohne erwähnte Apps/Seiten: Item
  DONE „keine Links“.

### Anzeige (App)

- Guide-Detail: Block „Apps & Links“ unter der Zusammenfassung, Chips mit Icon (App: `Icons.Filled.Apps`, Web:
  `Icons.Filled.Language`) und Name; Notiz als zweite Zeile bzw. Beschreibung.
- `GuidesSection` (Ort, Unterkunft, Fahrt, Flug, Tag) und „Für heute“: unter den Guide-Chips eine Zeile
  `GuideLinkChips` mit den Links aller dort gezeigten Guides, ohne Doppelte (Schlüssel: Paket-ID, sonst URL).
  Reine Funktion `GuideLinks.union(guides): List<GuideLink>` in `shared` (testbar).

### Öffnen

`openGuideLink(context, link)` in `app/links/`:

- APP mit Paket: `packageManager.getLaunchIntentForPackage(pkg)` → starten. Sonst `market://details?id=pkg` → sonst
  `https://play.google.com/store/apps/details?id=pkg` → sonst `url`.
- APP ohne Paket / WEB: `ACTION_VIEW` auf `url`.
- Manifest: `<queries><intent><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent></queries>`
  damit installierte Apps sichtbar sind.
- Die Entscheidungsreihenfolge als reine Funktion `guideLinkTargets(link, installed: Boolean): List<LinkTarget>` mit
  `sealed interface LinkTarget { data class Launch(val pkg: String); data class View(val uri: String) }`; `openGuideLink`
  probiert die Ziele der Reihe nach (testbar ohne Android).

## Fehlerbehandlung

- gallery-dl fehlt/scheitert → `download_failed` wie bisher.
- Tipp-Abgleich scheitert → alle Tipps ohne Abgleich in den Faktencheck (Ziel: neuer Guide je Thema), Warnung im Log.
- Faktencheck eines Tipps scheitert → `UNVERIFIED`.
- Ergänzen eines Guides scheitert → Guide unverändert, Item FAILED.
- Link-Prüfung → im Zweifel verwerfen.
- Logs ohne Nutzer-/Beitragsinhalte in Fehlermeldungen (nur Klassennamen), wie bisher.

## Tests

- `ToolReelMedia`: Ausweichen auf gallery-dl, Sammeln von Bildern/Videos/Caption (Fake-Skripte bzw. vorbereitete
  Verzeichnisse), Bild-Limit und Vorrang.
- `Assistant.analyze`: Parsen von `tips` neben `locations`; Beitrag nur mit Tipps.
- `TipCheck`: covered/new-Zuordnung, Verwerfen unbekannter Ziele, Urteile parsen, Fehler → UNVERIFIED.
- `AiJobs`: Reel-Job mit Tipps füllt `tips`; Galerie-Input.
- `GuideWriter`: Ergänzen-Modus ohne Recherche, Schutz gegen verlorene Abschnitte, Quellen-Merge, Links parsen.
- `LinkCheck`: gültige/ungültige Paket-IDs und URLs (Fake-HTTP), Dedup, Limit.
- Job „guide-links“ schreibt nur `links`.
- `shared`: `Guide.links` (De-)Serialisierung mit alten Daten, `GuideLinks.union`.
- App: Tipp-Auswahl und Gruppierung zu `GuideAsk`s (ViewModel), `guideLinkTargets`.

## Nicht enthalten

- Inline-Verlinkung im Fließtext der Guides (nur Link-Chips).
- iOS-/App-Store-Links (App ist Android-only).
- Direktes Teilen von Bilddateien (nur Links; der Video-Upload-Weg bleibt wie er ist).
