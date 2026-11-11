# Tabelog für Restaurants — Design

Datum: 2026-11-11 · Version: 0.63.0

## Ziel

Für gespeicherte Restaurants (und Bars/Izakaya) holt der Server einmalig für den Bestand und danach automatisch für
neue Orte die passenden Tabelog-Daten: Score, Anzahl Bewertungen, Budget, Genre, Öffnungszeiten, Reservierung, eine
deutsche KI-Zusammenfassung der neuesten Bewertungen und den direkten Link. Die App zeigt das am Ort und öffnet den
Eintrag möglichst in der mehrsprachigen Tabelog-App.

Entscheidungen des Nutzers: Abruf **einmalig für alle + automatisch für neue**; Bewertungen als **KI-Zusammenfassung**;
Treffer nur bei **≤ 300 m und ähnlichem Namen**; Score-Chip in der Regionsliste; Testlauf vor der Umsetzung (erledigt).

## Ergebnisse des Testlaufs (Apify, `huggable_quote/tabelog-scraper`, 2026-11-11)

- Eingabe: `searchKeyword`, `searchArea` (Präfektur-Slug wie `tokyo`, `kyoto`, Standard `all`), `maxRestaurants`,
  `includeReviews`, `maxReviewsPerRestaurant`, `sortBy` (`newest` …), `startUrls` (`[{"url": …}]`).
- **Suche nur auf Japanisch:** „一蘭 渋谷“ → Treffer, „Ichiran Shibuya“ → 0 Treffer.
- **Eine Zeile pro Bewertung**, Restaurantfelder wiederholt; mit `includeReviews: false` eine Zeile pro Restaurant.
- Felder (Auswahl): `restaurantId`, `restaurantName`, `restaurantUrl` (japanische Seite), `restaurantRating` (String oder
  Zahl), `totalReviewCount`, `restaurantGenre`, `address`, `latitude`/`longitude` (Strings), `businessHours`,
  `budgetLunch`/`budgetDinner` („￥1,000～￥1,999“), `reservation` („予約不可“), `nearestStation`, `paymentMethods`;
  je Bewertung `reviewTitle`, `reviewText`, `reviewDate`.
- **Kosten:** 0,3 ct pro Ergebniszeile + Start (~0,005 ct). Zweistufig (Suche ohne Bewertungen, max. 3 Treffer; dann
  3 Bewertungen per `startUrls` für den Treffer) ≈ **1,5–2 ct pro Restaurant**.
- Endpunkt `run-sync-get-dataset-items?timeout=240` liefert die Zeilen direkt (wie beim Transit-Actor).

## A. Daten

```kotlin
enum class TabelogStatus { FOUND, NOT_FOUND, REQUESTED }

@Serializable
data class TabelogInfo(
    val status: TabelogStatus,
    /** Japanische Seite, z. B. https://tabelog.com/kyoto/A2601/A260201/26032368/; bei REQUESTED optional die von Hand gesetzte. */
    val url: String = "",
    val name: String = "",          // Name bei Tabelog (japanisch)
    val score: Double? = null,      // 0–5
    val reviewCount: Int? = null,
    val genre: String = "",
    val budgetLunch: String = "",
    val budgetDinner: String = "",
    val hours: String = "",
    val reservation: String = "",
    val station: String = "",
    /** 2–3 Sätze auf Deutsch aus den neuesten Bewertungen. */
    val summary: String = "",
    val checkedAt: Long = 0,
)
```

`Place.tabelog: TabelogInfo? = null` (am Ort, nicht am gespeicherten Ort: es beschreibt das Lokal selbst).
`fun TabelogInfo.englishUrl()` setzt `/en` hinter den Host.

Restaurant = gespeicherter Ort (nicht DROPPED) mit Kategorie `FOOD` oder `NIGHTLIFE` und Koordinaten.

## B. Server

### Japanischer Name und Präfektur — `TabelogNames`

- Mit `googlePlaceId`: Google Place Details `languageCode=ja`, Felder `displayName,formattedAddress`.
- Ohne: Google Text Search `languageCode=ja` mit Name und `locationBias` (Kreis 300 m um den Ort), erster Treffer
  ≤ 300 m.
- Präfektur aus der japanischen Adresse (`東京都`, `京都府`, `大阪府`, `北海道`, `…県`) → Tabelog-Slug über eine feste
  Tabelle der 47 Präfekturen; sonst `all`.
- Ohne Google-Key: lateinischer Name, Präfektur `all` (findet oft nichts → NOT_FOUND).

### Abruf — `TabelogSource` (Apify)

- `search(keyword, area): List<TabelogHit>` — `includeReviews:false`, `maxRestaurants:3`.
- `reviews(url): List<TabelogReview>` — `startUrls:[url]`, `includeReviews:true`, `maxReviewsPerRestaurant:3`,
  `sortBy:newest`; liefert auch die Restaurantdaten (für den Handschuss per URL).
- Parser tolerant: Zahlen als String oder Zahl, fehlende Felder leer; gruppiert nach `restaurantId`.
- Actor über `WLP_APIFY_TABELOG_ACTOR` (Standard `huggable_quote~tabelog-scraper`), Token `WLP_APIFY_TOKEN`.

### Treffer — `TabelogMatch.pick(place, jaName, hits)`

Treffer mit Koordinaten ≤ **300 m** vom Ort **und** ähnlichem Namen: nach NFKC, ohne Leerzeichen und Kleinschreibung
enthält einer den anderen, oder der Teil vor dem ersten Leerzeichen (Ladenname ohne Filiale) stimmt überein. Von
mehreren der nächste. Sonst null → NOT_FOUND.

### Zusammenfassung — `TabelogSummary`

Textmodell: Bewertungen (Titel + Text, je max. 1500 Zeichen) → JSON `{"summary": "2–3 Sätze auf Deutsch: was gelobt
wird, was kritisiert wird, ein Tipp"}`. Fehler → leere Zusammenfassung, der Rest wird trotzdem gespeichert.

### Abgleich — `TabelogSweeper`

- Läuft beim Start nach 1 Minute, dann alle 15 Minuten, und sofort nach `POST /api/tabelog/refresh`. Nie zwei
  gleichzeitig.
- Wählt Restaurants mit `tabelog == null` oder `status == REQUESTED`; höchstens **20 pro Durchlauf** (Kosten verteilen).
- REQUESTED mit `url` → nur `reviews(url)` (Handschuss); sonst Name → Suche → Treffer → `reviews(treffer.url)`.
- Schreibt `Place.tabelog` per `ServerEdit` (Feld `tabelog`). NOT_FOUND wird nicht automatisch wiederholt.
- Fehler bei Apify (Netz, Kontingent): Ort bleibt unverändert und kommt im nächsten Durchlauf wieder dran; nach einem
  Fehler endet der Durchlauf (kein Kosten-Loop).
- Ohne Apify-Token: Sweeper startet nicht, `/api/tabelog/refresh` antwortet `tabelog_not_configured`.
- Log ohne Inhalte, nur Zähler.

## C. App

- **Karte „Tabelog“** auf der Ortsseite (nur Restaurants):
  - FOUND: Score-Badge „3,71 · 1.159 Bewertungen“, Genre, Budget mittags/abends, Öffnungszeiten (einklappbar),
    Reservierung, Zusammenfassung, Knopf **„In Tabelog öffnen“**. Menü: „Aktualisieren“, „Tabelog-Link setzen“.
  - NOT_FOUND: „Bei Tabelog nicht gefunden“ + „Tabelog-Link setzen“ + „In Tabelog suchen“ (bisherige Suche).
  - null/REQUESTED: „Tabelog wird geladen …“.
  - „Aktualisieren“ setzt `status = REQUESTED` (URL bleibt), „Link setzen“ setzt `REQUESTED` mit der eingegebenen
    URL (nur `https://tabelog.com/…`), beide rufen danach `POST /api/tabelog/refresh`.
- **Öffnen** (`tabelogTargets(url, installed)`): 1. `ACTION_VIEW` der englischen URL mit Paket
  `com.kakaku.tabelog.tourists`; 2. Start der App; 3. englische URL im Browser. Manifest-Sichtbarkeit über das
  vorhandene `<queries>` (LAUNCHER).
- Der bisherige Link „Tabelog“ (Suche) in den Ortslinks führt bei FOUND direkt zum Eintrag.
- **Regionsliste:** Chip „★ 3,7“ an Restaurants mit FOUND.

## Tests

- Parser (gespeicherte Testlauf-Antworten, gekürzt), Präfektur-Tabelle und -Erkennung, `TabelogMatch` (Filialen 200 m
  auseinander, Name ähnlich/unähnlich, > 300 m), `englishUrl`.
- Sweeper mit Fakes: wählt nur Restaurants ohne Daten/REQUESTED, max. 20, Handschuss per URL, NOT_FOUND bleibt,
  Fehler bricht ab ohne zu schreiben, schreibt nur das Feld `tabelog`.
- Zusammenfassung (Fake-Modell), Fehler → leer.
- App: `tabelogTargets`, Karte in den drei Zuständen, „Link setzen“ prüft die URL, Chip in der Regionsliste.

## Nicht enthalten

- Reservieren über Tabelog, Fotos, einzelne Bewertungen in der App.
- Einfluss des Tabelog-Scores auf die KI-Einschätzung (kann später als Fakt in die Recherche).
