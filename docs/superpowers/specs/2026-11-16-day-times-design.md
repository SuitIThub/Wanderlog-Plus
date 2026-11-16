# Geschätzte Tageszeiten – Design

## Warum

In der Tagesplanung hängt die Uhrzeit am Programmpunkt (`DayItem.time`). Beim Umsortieren wandert sie mit, die
Zeitleiste zeigt dann z. B. 13:00 → 16:30 → 14:00. Bearbeiten lässt sich die Zeit nirgends; gesetzt wird sie nur vom
Wanderlog-Import und von Chat-Plänen. Gewünscht: Zeiten, die zur Reihenfolge passen, selbst setzbare Zeiten und eine
Schätzung der Zeitleiste.

## Ziele

- Die Zeitleiste eines Tages zeigt für jeden Ort eine Ankunftszeit, die aus Reihenfolge, Wegen und Aufenthaltsdauer
  geschätzt wird („~13:00“) und sich beim Umsortieren sofort neu rechnet.
- Einzelne Punkte können eine **feste Uhrzeit** bekommen (Reservierung, Führung). Sie bleibt beim Ort; die Schätzung
  plant um sie herum.
- Pro Punkt lässt sich die **Aufenthaltsdauer** überschreiben, pro Tag der **Tagesbeginn**.
- Die App warnt, wenn die Schätzung nicht aufgeht: zu spät für eine feste Zeit oder eine Abfahrt, Ort bei Ankunft zu.

## Nicht-Ziele

- Keine KI-Schätzung der Dauern (der Chat-Planvorschlag bleibt dafür da).
- Kein automatisches Umsortieren, um Warnungen aufzulösen.
- Keine End-Uhrzeit pro Punkt (die Dauer reicht).

## Anti-Ziele

- Nichts darf beim Umsortieren eine Zeit „mitnehmen“, die der Nutzer nicht ausdrücklich fest gesetzt hat.
- Die Schätzung darf nie auf das Netz warten: sie rechnet mit dem, was lokal bekannt ist.

## Ansätze

1. **In der App rechnen (gewählt).** Der vorhandene Schätzer `DayNow.layout` (Heute-Ansicht, Widgets) wird ausgebaut
   und überall benutzt. Wege: bekannte echte Wegzeiten (Wege-Cache, gewählte Verbindung), sonst Luftlinien-Schätzung.
   Sofort, offline, live beim Umsortieren.
2. **Server/KI schätzt.** Realistischere Dauern, aber langsam, kostet pro Aufruf, offline nicht, nicht live. Verworfen.

## Entscheidungen des Nutzers

- Variante A: Zeiten sind grundsätzlich geschätzt, nur ausdrücklich gesetzte Zeiten sind fest.
- Weg 1: Schätzung lokal in der App.
- Bisherige Zeiten (Wanderlog-Import, alte Chat-Pläne) gelten als Schätzung, nicht als fest.
- Chat-Planvorschläge setzen eine feste Zeit nur, wenn die KI sie ausdrücklich als Termin markiert.
- Bearbeitung über „Zeit & Dauer“ im ⋮-Menü des Programmpunkts; Tagesbeginn pro Tag einstellbar.

## Design

### Daten (shared)

- `DayItem.fixedTime: String? = null` – feste Uhrzeit „HH:mm“; null: geschätzt.
- `DayItem.stayMinutes: Int? = null` – überschriebene Aufenthaltsdauer; null: typische Dauer des Orts
  (`minMinutes`/`maxMinutes`), sonst nach Kategorie (wie `DayNow.stayMinutes`).
- `Day.startTime: String? = null` – Tagesbeginn; null: 09:00.
- `DayItem.time` bleibt im Modell (alte Daten, Verlauf), wird aber **nirgends mehr als Zeit gelesen**. Keine Migration,
  keine Schreibvorgänge an bestehenden Punkten. `DayVersionItem` bekommt `fixedTime` und `stayMinutes`, damit
  Wiederherstellen sie zurückholt.

### Schätzer (shared, `DayNow` ausgebaut bzw. `DaySchedule`)

Eingabe: Programm in Reihenfolge, Orte, gespeicherte Orte, Fahrten des Tages, Unterkunft (Start), Tagesbeginn und eine
Funktion `wayMinutes(item) -> Int?` für bekannte echte Wegzeiten.

- Uhr startet beim Tagesbeginn an der Unterkunft (bzw. ersten Ort ohne Unterkunft).
- Pro Ort: Ankunft = Uhr + Weg (bekannt, sonst Schätzung wie heute). Hat der Ort eine feste Zeit: Start = feste Zeit;
  liegt die geschätzte Ankunft danach, Warnung `LATE(min)`. Ist man früher da, wartet die Uhr bis zur festen Zeit.
- Ende = Start + Aufenthaltsdauer; Uhr = Ende.
- Ankunft fällt in eine geschlossene Zeit laut Öffnungszeiten (Wochentag des Tages) → Warnung `CLOSED`.
- Fahrten (Zug/Flug) stehen an ihrer Zeit; kommt die Uhr später als die Abfahrt an, Warnung `MISSES_RIDE` am letzten
  Ort davor; nach der Fahrt läuft die Uhr ab ihrer Ankunft.
- Notizen bekommen keine Zeit.
- Ergebnis pro Punkt: Start, Ende, `fixed`, Weg-Minuten/-Art, Warnungen. Die Heute-Ansicht (Zustände
  DONE/CURRENT/NEXT) baut darauf auf; ihr bisheriges Verhalten „geplante Zeit wird übernommen“ gilt jetzt nur für feste
  Zeiten.
- `DayFlow` sortiert Programmpunkte zu Fahrten nur noch nach `fixedTime` (sonst wie bisher nach Region/Lage).

### App

- **Zeitleiste (Reise → Tag):** Am Punkt steht „~13:00“ für geschätzt, „13:00“ mit Stecknadel-Symbol für fest. Die
  Startkarte (Unterkunft) zeigt „ab 09:00“. Warnungen als Badge am Punkt: „~15 Min zu spät“, „bei Ankunft geschlossen“,
  „Zug knapp“.
- **„Zeit & Dauer“** im ⋮-Menü (Bottom-Sheet): Schalter „Feste Uhrzeit“ mit Zeitwahl (vorbelegt mit der Schätzung),
  Aufenthaltsdauer (Vorgabe angezeigt, Schritte ±15 Min, „Zurücksetzen“). Speichern schreibt das Item.
- **Tagesbeginn:** Antippen der Startkarte (oder Eintrag im Tagesmenü) öffnet eine Zeitwahl; „Zurücksetzen“ = 09:00.
- Wegzeiten für die Schätzung kommen aus dem Wege-Cache (`LegRepository.peekWay`) für die Wege, die die Zeitleiste
  ohnehin lädt; fehlt einer, gilt die Luftlinie. Lädt ein Weg nach, rechnet die Zeitleiste neu.
- Heute-Ansicht, Widgets, „Was jetzt?“ (freie Zeit bis zum nächsten Punkt) und Verbindungs-Abfragen
  (`DayRoute`, Abfahrtszeit eines Wegs) nehmen die geschätzten bzw. festen Zeiten statt `time`.
- Chat-Plan anwenden: Schritte mit `fixed` setzen `fixedTime`, andere Zeiten werden ignoriert.
- Version 0.76.0 mit CHANGELOG-Abschnitt.

### Server

- `ChatPlanStep.fixed: Boolean = false`. Das Planwerkzeug bekommt pro Stopp ein Feld „fest“; der Prompt sagt: nur für
  echte Termine (Reservierung, Führung, Ticket mit Zeitfenster) setzen.
- Der Tageskontext im Chat zeigt feste Zeiten als „18:00 (fest)“ und sonst die Schätzung als „~13:00“ (der Server
  rechnet mit dem shared-Schätzer, ohne Wege-Cache).
- Plan-Prüfung (`ChatDayCheck`/`PlanStop`) nimmt `fixedTime` statt `time`.
- Server muss neu gebaut werden.

## Fehlerfälle

- Ort ohne Koordinaten: Weg unbekannt → 0 Min, Schätzung läuft weiter.
- Zwei feste Zeiten in falscher Reihenfolge: die spätere im Programm bekommt `LATE`; nichts wird umsortiert.
- Ungültige Eingaben sind über die Zeitwahl ausgeschlossen; `fixedTime` wird beim Lesen mit `DayNow.mins` geprüft,
  ungültig = nicht fest.
- Schätzung über Mitternacht: Zeiten laufen weiter (Anzeige modulo 24 h, wie `hhmm`).

## Dem Umsetzer überlassen

- Ob der Schätzer `DayNow` erweitert oder als eigenes `DaySchedule` daneben steht (DayNow nutzt ihn dann).
- Genaue Darstellung der Badges und des Sheets im vorhandenen Design-System.
- Wie die Zeitleiste die Wegzeiten einsammelt (vorhandene Wege-Ladelogik wiederverwenden).

## Tests

- shared: Schätzer (Reihenfolge ändern ändert Zeiten, feste Zeit hält und wartet, `LATE`, `CLOSED`, `MISSES_RIDE`,
  Tagesbeginn, Dauer-Override, bekannte Wegzeit schlägt Luftlinie); `DayFlow` ignoriert `time`.
- app: Zeitleiste zeigt „~“ und fest; Umsortieren ändert die Zeiten; Sheet setzt/entfernt feste Zeit und Dauer;
  Tagesbeginn; Heute/Widgets nutzen den Schätzer.
- server: `fixed` wird gelesen, Kontext zeigt „(fest)“/„~“.
