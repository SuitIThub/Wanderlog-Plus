# Startbildschirm-Widgets – Design

Datum: 2026-11-13 · Version: 0.74.0

## Ziel

Sieben Android-Startbildschirm-Widgets, die auf der Reise das Wichtigste ohne App-Start zeigen und frei in der Größe
platziert werden können: Jede Größe zeigt so viel Inhalt, wie sauber hineinpasst – nie gequetscht, nie abgeschnitten.
Sie funktionieren auch auf dem Außendisplay des Galaxy Z Fold (normaler Startbildschirm, keine Sonderbehandlung).

## Grundsätze

- **Dieselbe Wahrheit wie die App:** Widgets rechnen nichts Eigenes. Eine reine Kotlin-Schicht baut aus den lokalen
  Reisedaten dieselben Ergebnisse, die die Heute-Seite nutzt (`TodayQueries`, `DayNow.layout`, `upcomingMove`,
  `Deadlines`, `TripWeather.forDate`, Budget-Rechnung).
- **Offline:** Widgets lesen nur, was auf dem Gerät liegt (Room-Store, `weather.json`, gespeicherter Kurs). Kein
  Netzzugriff beim Zeichnen.
- **Zeit:** „Jetzt“ wie die Heute-Seite: `settings.tripZone.now()`, im Debug-Modus mit der simulierten Zeit.
- **Responsiv:** Glance `SizeMode.Responsive` mit festen Größenstufen; jede Stufe hat eine festgelegte Inhaltsmenge.
  Wenn Platz fehlt, fallen die unwichtigsten Zeilen zuerst weg; Texte haben `maxLines` und nie abgeschnittene
  Pflichtangaben (Uhrzeit, Name).
- **Ehrliche Countdowns:** Relative Angaben stehen immer neben der festen Uhrzeit („14:30 · in 25 min“).
- **Systemthema:** `GlanceTheme` (Material You, hell/dunkel); Akzent wie in der App die Regionsfarbe, wo vorhanden.
- **Keine sensiblen Daten im Log;** Widgets zeigen nichts, was die App nicht ohnehin auf der Heute-Seite zeigt.

## Größenstufen

Gemeinsame Stufen (dp), ein Widget nutzt die, die für es sinnvoll sind:

| Stufe | Größe (B×H) | typisch |
|---|---|---|
| TINY | 57×57 | 1×1 |
| STRIP | 180×57 | 3×1 / 4×1 |
| SMALL | 110×110 | 2×2 |
| MEDIUM | 180×110 | 3×2 |
| WIDE | 250×110 | 4×2 |
| TALL | 180×250 | 3×4 |
| LARGE | 250×250 | 4×4 |

`resizeMode="horizontal|vertical"`, sinnvolle `minResizeWidth/Height` je Widget, `targetCellWidth/Height` als Vorschlag.

## Die Widgets

### 1. Als Nächstes (`NextUp`)
- Während der Reise:
  - TINY: Uhrzeit des nächsten Punkts + Symbol (Ort/Zug/Flug).
  - STRIP: „14:30 Fushimi Inari · in 25 min“.
  - SMALL/MEDIUM/WIDE: was gerade läuft (falls), der nächste und der übernächste Punkt; WIDE mit Wegzeit.
  - TALL/LARGE: die nächsten bis zu 5 Punkte mit Wegzeiten.
- Vor der Reise: „noch 42 Tage“ + Reisename; ab SMALL die Zahl offener Fristen, ab MEDIUM die nächste Frist.
- Nach der Reise: „Willkommen zurück“.
- Tippen: öffnet den Tag (während), die Heute-Seite (sonst).

### 2. Nächste Fahrt (`NextRide`)
- Zug/Flug innerhalb der nächsten 36 h (`upcomingMove`).
- TINY: Abfahrtszeit + Zug/Flug-Symbol. STRIP: „08:12 Nozomi 21 · Gleis 14“.
- SMALL/MEDIUM: dazu Ziel, Ankunft, Wagen/Sitz. WIDE/TALL/LARGE: dazu Umstiege und Ticket-Knöpfe (öffnen das Ticket).
- Keine Fahrt: „Keine Fahrt in den nächsten 36 Stunden“.
- Tippen: öffnet die Fahrt in Logistik; Ticket-Knopf öffnet die Datei.

### 3. Heute kompakt (`TodayOverview`)
- MEDIUM/WIDE: Datum, Region, Unterkunft heute Nacht, nächster Punkt.
- TALL/LARGE: dazu das ganze Tagesprogramm als (scrollbare) Liste, Erledigtes ausgegraut, Wetter-Zeile.
- Vor/nach der Reise wie „Als Nächstes“.
- Tippen: Heute-Seite; ein Listeneintrag öffnet den Tag.

### 4. Unterkunft heute (`Stay`)
- Unterkunft der heutigen Nacht; am Abreisetag mit Check-out-Hinweis (Zeit, falls bekannt).
- SMALL: Name, Check-in/Check-out. MEDIUM/WIDE: dazu Adresse (`Place.address`).
  TALL/LARGE: dazu Buchungsnummer und Knöpfe „Karte“ und „Adresse zeigen“.
- „Adresse zeigen“ öffnet in der App einen Vollbild-Screen mit Name und Adresse in großer Schrift (zum Vorzeigen,
  Bildschirm bleibt an). Die Daten haben keine japanische Adresse; gezeigt wird `Place.address`.
- Keine Unterkunft: „Heute keine Unterkunft eingetragen“.

### 5. Yen & Budget (`Budget`)
- Kurs: zuletzt bekannter JPY-Kurs – gespeichert bei jedem erfolgreichen Kursabruf der App, sonst der jüngste Kurs
  einer Yen-Ausgabe; mit Datum („Kurs vom 12.04.“). Ohne Kurs: „Kein Kurs bekannt“.
- TINY: „1000 ¥ ≈ 6,10 €“ (zweizeilig). STRIP/SMALL: dazu „heute 4.300 ¥“.
- MEDIUM/WIDE: Umrechnungstabelle 500 / 1.000 / 5.000 / 10.000 ¥ und heute Ausgegebenes.
- TALL/LARGE: dazu Ausgaben gesamt gegenüber dem geplanten Gesamtbetrag (wie die Budget-Seite).
- Knopf „+“: öffnet die App direkt im Formular für eine neue Ausgabe in JPY mit heutigem Datum.

### 6. Wetter (`Weather`)
- Wetter der Region des Tages aus der gespeicherten Vorhersage (`TripWeather`); vor der Reise der erste Reisetag.
- TINY: Symbol + Höchsttemperatur. SMALL/STRIP: heute (min/max, Regen %).
- MEDIUM/WIDE: heute und morgen. TALL/LARGE: dazu Stundenverlauf (soweit vorhanden) und Regen-Hinweis für
  Outdoor-Orte des Tages.
- Quelle Klima-Mittel wird wie in der App gekennzeichnet. Kein Wetter: „Noch keine Vorhersage“.
- Tippen: Heute-Seite.

### 7. Reise-Chat mit „Was jetzt?“ (`Chat`)
- Knöpfe: TINY nur Chat. Ab STRIP drei Knöpfe: **Chat**, **Sehenswürdigkeit**, **Essen**.
- Ohne Ergebnis zeigt STRIP/WIDE „Frag den Reise-Chat …“ + Titel des letzten Gesprächs (falls bekannt).
- Chat-Knopf: öffnet den Chat mit Bezug „heute“ (aufgeklappt als Spalte rechts, sonst Vollbild); gibt es ein
  aktuelles Ergebnis, öffnet er genau das Gespräch, das es geliefert hat.
- **Sehenswürdigkeit/Essen auf STRIP/SMALL** (zu klein für ein Ergebnis): öffnet die App mit einem neuen Chat, in
  dem die Frage sofort gesendet wird.
- **Sehenswürdigkeit/Essen ab MEDIUM:** die Anfrage läuft im Hintergrund (WorkManager, sofort, mit Netz); das Widget
  zeigt „Suche passende Ziele …“, danach das Ergebnis.
- Anfrage: neuer Chat, feste Frage („Was kann ich jetzt in der Nähe besichtigen?“ / „Wo kann ich jetzt gut
  essen?“), Kontext `TODAY` mit Datum, Standort, und das neue Feld `quick` (s. u.).
- Standort: mit Standortfreigabe der zuletzt bekannte Standort des Geräts (best effort); sonst der aktuelle/nächste
  Ort des Programms (`DayNow`), ersatzweise die Unterkunft. Dieser Ort ist auch Bezug der Entfernung im Widget.
- **Freie Zeit:** Die App bestimmt mit `DayNow`, wann der nächste geplante Programmpunkt (Ort, Fahrt) beginnt und wo
  er liegt, und schickt das mit. Der Chat soll Ziele vorschlagen, die samt Hin- und Weiterweg ungefähr in diese Zeit
  passen (großzügig, Urlaub – keine strenge Regel); Ziele in Richtung des nächsten Punkts sind ein Plus. Ohne
  weiteren Punkt heute entfällt die Vorgabe.
- Ergebnis: alle Karten der Antwort, die erste gilt als Top-Empfehlung.
  - MEDIUM/WIDE: Top-Empfehlung mit Name, Entfernung, einer Zeile Begründung (Untertitel der Karte); WIDE mit Bild,
    wenn es ein gespeicherter Ort mit Foto ist.
  - TALL/LARGE: Top-Empfehlung groß mit Bild, darunter bis zu 3 weitere als kurze Zeilen.
  - Tippen auf einen gespeicherten Ort öffnet den Ort; auf einen Web-Fund das Gespräch.
  - Ergebnis zeigt „von 14:05“; nach 2 h oder am nächsten Tag ausgegraut, nach 6 h bzw. ab dem nächsten Tag weg.
  - Fehler (offline, Server, Zeitüberschreitung nach 3 min): kurzer Hinweis „Keine Antwort – im Chat erneut
    versuchen“; der Chat-Knopf öffnet dann das Gespräch.
  - Antwort ohne Karten: der Antworttext (gekürzt) statt Karten; Tippen öffnet das Gespräch.

#### Server: `quick`
- `ChatSendRequest.quick: QuickAsk? = null` mit `QuickAsk(kind: "sight"|"food", freeUntil: "HH:mm"? ,
  nextLabel: String?, nextLat/nextLng: Double?)`.
- Mit `quick` hängt `ChatAgent` an den Prompt dieser Antwort: kurz antworten, mehrere Ziele (2–5) als Karten, das
  beste zuerst; Uhrzeit, Öffnungszeiten, Wetter, Restprogramm und Entfernung beachten; gespeicherte Orte bevorzugen,
  Web-Funde erlaubt; bei `freeUntil` der Absatz zur freien Zeit (wie oben, ausdrücklich als Richtwert).
- Ohne `quick` ändert sich nichts. `quick` wird nicht im Chat gespeichert (wie `location`).

## Architektur

- `app/widgets/WidgetModels.kt` – reine Modelle + Builder `WidgetData.build(input: WidgetInput): WidgetModels`
  (`WidgetInput` = Snapshot, now, Wetter, Kurs, letzter Chat-Titel). Ohne Android-Abhängigkeit, voll unit-getestet.
- `app/widgets/WidgetSizes.kt` – die Größenstufen und `bucketOf(DpSize)`.
- `app/widgets/*Widget.kt` – je ein `GlanceAppWidget` + `GlanceAppWidgetReceiver`; Inhalt pro Stufe.
- `app/widgets/WidgetUpdater.kt` – aktualisiert alle Widgets:
  - nach Änderungen am lokalen Snapshot (`SnapshotRepository.changes()`, entprellt) und nach einem Sync;
  - periodisch alle 15 min (WorkManager);
  - einmalig zur nächsten Grenze (Beginn des nächsten Programmpunkts, Abfahrt, Mitternacht) per OneTime-Work.
- Einsprung: Tippen startet `MainActivity` mit einem Extra `wlp.widget.target` (z. B. `day:<date>`,
  `logistics:TRAIN:<id>`, `attachment:<id>`, `expense:new`, `chat`, `stay-address:<stayId>`, `today`); die
  Navigation wertet es aus wie die bisherigen Extras.
- `app/widgets/QuickAsk.kt` – die „Was jetzt?“-Anfrage: freie Zeit aus `DayNow`, Standort, Senden über die
  Chat-API, Abwarten der Antwort (Polling wie `ChatViewModel`), Ergebnis in einem kleinen Datei-Speicher
  (`widget-quick.json`: Art, Chat-Id, Karten, Zeit, Zustand läuft/fertig/Fehler); als `CoroutineWorker`.
- Einsprung „Chat mit sofort gesendeter Frage“: Ziel `ask:sight` / `ask:food`; `ChatViewModel` legt einen neuen Chat
  an und sendet die Frage samt `quick`.
- Kurs-Speicher: `SettingsStore` bekommt `lastJpyRate` (Kurs + Datum), geschrieben bei jedem erfolgreichen
  `BudgetViewModel.rate("JPY", …)`.
- Neue Abhängigkeiten: `androidx.glance:glance-appwidget`, `glance-material3`, Test: `glance-appwidget-testing`.

## Leere- und Fehlerfälle

- Keine Server-Einrichtung / keine Reise: jedes Widget zeigt „Reise in der App einrichten“ und öffnet die App.
- Daten fehlen für ein Widget: ein ruhiger Hinweistext (s. o.), keine leere Fläche.
- Ein Fehler beim Laden lässt den letzten Stand stehen (Glance behält ihn); keine Abstürze im Widget-Prozess.

## Tests

- Unit-Tests für `WidgetData.build`: vor/während/nach der Reise, Tageswechsel um Mitternacht, gerade laufender
  Punkt, keine Fahrt, Fahrt in 30 h, Unterkunft am Abreisetag, Kurs aus Abruf vs. aus Ausgabe vs. keiner,
  Wetter vorhanden/fehlt, nächste Aktualisierungsgrenze.
- Glance-Tests (`runGlanceAppWidgetUnitTest`) je Widget für mindestens TINY/STRIP, MEDIUM und LARGE: erwarteter
  Inhalt da, Inhalt einer größeren Stufe nicht da.
- Robolectric: die Ziel-Extras öffnen die richtigen Screens (Tag, Fahrt, Ausgabe neu, Chat, Adresse zeigen,
  Chat mit sofort gesendeter Frage).
- Server: mit `quick` (mit/ohne `freeUntil`) steht der Zusatz im Prompt; ohne `quick` ist der Prompt unverändert.
- App: freie Zeit aus `DayNow` (nächster Punkt, kein weiterer Punkt); Ergebnis-Auswahl (Top-Karte, Entfernung,
  ohne Karten); Ablauf und Fehlerzustand; Hintergrund-Anfrage bis zum Ergebnis mit Fake-Backend.

## Nicht enthalten

- Eingaben im Widget selbst (Glance kann keine Textfelder); „+“ öffnet die App.
- Eine japanische Adresse (gibt es in den Daten nicht).
- Konfigurations-Dialog pro Widget.
