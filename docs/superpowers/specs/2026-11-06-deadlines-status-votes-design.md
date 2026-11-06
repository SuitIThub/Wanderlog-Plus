# Fristen, „Vorgemerkt“ und Abstimmen – Design

Stand: 2026-11-06. Drei Teile, nacheinander gebaut, jeder mit eigener App-Version und CHANGELOG-Abschnitt.

## Ziel

Die Heute-Seite zeigt nur Fristen, die jetzt zählen, und keine falschen. Fristen gibt es für Orte (aus der Recherche
oder von Hand) und für Logistik (Züge, Flüge, Unterkünfte). Ein neuer Ortszustand „Vorgemerkt“ trennt „interessiert
uns“ von „bloße Idee“, und Stimmen der Mitreisenden (je Zugang) schieben Orte automatisch dorthin oder zurück.

## Teil 1 – Fristen

### 1a. Fehler „Reservierung nötig“ trotz „nicht nötig“

Ursache: `Reservations.reason` sucht im Text mit `NEED`/`NOT_NEEDED`. `NOT_NEEDED` kennt „Eine Reservierung **ist**
nicht nötig“ nicht, und bei der Bewertung „booking 1–2/5“ wird die Notiz gar nicht gegen `NOT_NEEDED` geprüft.

- Die Recherche liefert künftig ein ausdrückliches Ergebnis (`PlaceDossier.reservation`, s. 1c). Ist es vorhanden,
  entscheidet nur es; die Textsuche ist dann aus.
- Ohne dieses Ergebnis (ältere Recherchen) bleibt die Textsuche, aber `NOT_NEEDED` erkennt Verneinungen mit
  Füllwörtern („ist/wird/muss nicht“, „nicht erforderlich“, „not required“, „no need“) und gilt auch für die
  booking-Notiz.

### 1b. Datenmodell

`ReservationWindow` (shared, serialisierbar), gleich für Orte und Logistik:

| Feld | Bedeutung |
|---|---|
| `opens: String?` | „Reservierbar ab“, yyyy-MM-dd |
| `by: String?` | „Reservieren bis“, yyyy-MM-dd |
| `done: Boolean` | erledigt – keine Frist mehr anzeigen/erinnern |
| `byHand: Boolean` | Daten von Hand gesetzt – die Recherche überschreibt sie nicht |

- `Train`, `Flight`, `Stay`: neues Feld `reservation: ReservationWindow? = null`.
- `SavedPlace`: neues Feld `reservationWindow: ReservationWindow? = null`. Das bestehende `reserved` bleibt das
  „erledigt“-Häkchen für Orte (kein zweites Feld); `reservationWindow.done` wird für Orte nicht benutzt.
- Bearbeiten: In den Formularen für Zug, Flug, Unterkunft zwei Datumsfelder „Reservierbar ab“, „Reservieren bis“ und
  das Häkchen „erledigt“. Am Ort (Detailseite, Karte „Reservierung“) dieselben zwei Felder zum Ändern; Ändern setzt
  `byHand`.
- Detailseiten zeigen die Fristen („Reservierbar ab 14.04.“, „Reservieren bis 20.04.“, „erledigt“).

### 1c. Recherche legt Fristen fest

`PlaceDossier.reservation: ReservationAdvice? = null`:

| Feld | Bedeutung |
|---|---|
| `need` | `REQUIRED`, `RECOMMENDED`, `NOT_NEEDED`, `UNKNOWN` |
| `opensDaysBefore: Int?` | Buchung öffnet so viele Tage vor dem Besuch (z. B. 30) |
| `opensOn: String?` | feste Freischaltung, yyyy-MM-dd (z. B. Ghibli: am 10. des Vormonats) |
| `byDaysBefore: Int?` | spätestens so viele Tage vorher reservieren |
| `note: String` | ein deutscher Satz („Tickets erscheinen am 10. des Vormonats um 10 Uhr JST.“) |

- Der Prompt der Ortsrecherche fragt das ausdrücklich ab (JSON-Teil `reservation`), Sonnet schreibt es; die
  Prüfung `mostlyJapanese`/Deutsch gilt auch für `note`.
- Aus Regel und Besuchstag wird ein `ReservationWindow` berechnet (`ReservationDates.windowFor`, shared):
  Besuchstag = erster geplanter Tag des Orts; ohne geplanten Tag der erste Reisetag in der Region des Orts
  (Unterkunfts- bzw. Tagesregion) – dann gilt die Frist als **geschätzt** und wird so beschriftet.
- Von Hand gesetzte Daten (`byHand`) gewinnen immer.

### 1d. Eine gemeinsame Fristenliste

`Deadlines.list(snapshot, today)` (shared): alle Fristen als `Deadline(kind, date, title, target, state, estimated)`:

- `kind`: `CANCEL` (Stornofrist), `OPENS` (reservierbar ab), `BY` (reservieren bis), `PLACE` (Ort braucht
  Reservierung, ohne Daten: Besuchstag als Frist).
- `state`: `UPCOMING`, `OPEN_NOW` (reservierbar, noch nicht erledigt), `OVERDUE` (Bis-Datum vorbei, nicht erledigt).
- Erledigte (`done`/`reserved`) und vergangene Ereignisse (Fahrt/Besuch vorbei) fallen weg. Überfällige bleiben,
  bis erledigt oder das Ereignis vorbei ist.

### 1e. Heute-Seite

- „Fristen“ zeigt Fristen mit Datum bis heute + 30 Tage, dazu alle `OVERDUE` (rot, oben) und `OPEN_NOW`.
- Höchstens 4 Zeilen, darunter „Alle Fristen (n)“ zum Aufklappen.
- Gilt vor und während der Reise gleich (bisherige 14-Tage-Sonderregel entfällt).
- Ein Tipp öffnet Ort, Unterkunft, Zug oder Flug.

### 1f. Erinnerungen

Der bestehende Erinnerungs-Worker nutzt `Deadlines.list`: Stornofristen wie bisher; `BY` mit denselben Abständen
(30/14/7/2 Tage); `OPENS` am Tag der Freischaltung. Ein „Erledigt“-Knopf in der Benachrichtigung setzt `done` bzw.
`reserved`.

## Teil 2 – Zustand „Vorgemerkt“

- `PlaceStatus` bekommt `SHORTLIST` zwischen `IDEA` und `PLANNED`, deutsch „Vorgemerkt“. Überall, wo Zustände
  gewählt, gefiltert oder angezeigt werden (Chips, Filter, Chat-Kontext, Export), kommt er dazu.
- Fristen von Orten erscheinen auf der Heute-Seite nur bei „Vorgemerkt“ und „Geplant“.
- Gegen das Verpassen: Haben Ideen eine Frist in den nächsten 14 Tagen (oder sind jetzt reservierbar), steht eine
  eingeklappte Zeile „n Ideen mit Fristen“ in den Fristen; aufgeklappt die Liste, jeder Eintrag öffnet den Ort.
  Erinnerungen (Benachrichtigungen) gibt es für Ideen nicht.

## Teil 3 – Abstimmen

- Jeder Zugang (Token-Name) gibt pro Ort höchstens eine Stimme: Like (+1), Doppel-Like (+2), Dislike (−2), oder
  nimmt sie zurück.
- Neue Entität `VOTE`, id `"<savedPlaceId>|<name>"`, Felder `savedPlaceId`, `voter`, `value` (−2, 1, 2; 0 = keine).
  Eine Stimme je Datensatz, damit gleichzeitige Stimmen sich nicht überschreiben.
- Der Server nimmt eine `VOTE`-Änderung nur an, wenn `voter` der eigene Token-Name ist (sonst wird diese Änderung
  verworfen, der Rest des Pushs bleibt).
- Punktzahl = Summe der Stimmen.
  - `IDEA` und Punktzahl ≥ 2 → `SHORTLIST`.
  - `SHORTLIST` und Punktzahl < 2 → `IDEA`.
  - `PLANNED` und Punktzahl < 2 → nichts ändern, aber Hinweis auf der Heute-Seite und in der Tagesansicht des
    geplanten Tags („Kaum noch Zustimmung: Daniel 👎“).
  - Andere Zustände (`VISITED`, `DROPPED`) bleiben unberührt.
- Die App wendet die Regel beim Abstimmen sofort an (offline); der Server prüft nach jedem Push mit Stimmen erneut
  und korrigiert den Zustand, falls zwei Stimmen gleichzeitig kamen.
- Von Hand gesetzte Zustände bleiben, bis die nächste Stimme zu diesem Ort fällt.
- Am Ort: Stimmknöpfe (👍, 👍👍, 👎) und die Stimmen mit Namen („Daniel 👍👍, Selina 👍“). In Ortslisten die
  Punktzahl als kleines Abzeichen.
- Die App kennt den eigenen Namen über `/api/whoami` (beim Einrichten schon abgefragt; wird gespeichert).

## Nicht enthalten

- Abstimmen über Unterkünfte/Fahrten.
- Fristen für Ideen als Benachrichtigung.
- Automatisches „Geplant“ durch Stimmen.

## Tests

- Teil 1: `Reservations` mit „ist nicht nötig“ und booking-Notiz; Dossier-`reservation` gewinnt über Text;
  `ReservationDates.windowFor` (geplanter Tag, geschätzt über Region, fest, von Hand); `Deadlines.list`
  (Zustände, erledigt, vorbei, überfällig); Heute-Widget (4 Zeilen + aufklappen, rot); Formulare speichern;
  Erinnerungen; Recherche-Prompt/Parsing.
- Teil 2: Status in Codec/Filter; Fristen nur für Vorgemerkt/Geplant; Ideen-Sammelzeile.
- Teil 3: Server verwirft fremde Stimmen; Regel (inkl. −2 gegen Doppel-Like); Server korrigiert Zustand; Hinweis
  bei geplantem Ort; Stimmknöpfe in der App.
