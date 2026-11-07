# ÖPNV-Suche: Orte als Start/Ziel, Datum/Uhrzeit-Auswahl, Verbindungen als Reise – Design

Stand: 2026-11-07. Zwei Teile, nacheinander gebaut, jeder mit eigener App-Version und CHANGELOG-Abschnitt.
Datenquelle bleibt: Japan Yahoo! 乗換案内 (Apify), außerhalb Japans Transitous. Die Google Routes API liefert für
Japan keinen ÖPNV (Test vom 2026-11-01: leere Antwort) und kommt deshalb nicht in Frage.

## Teil 1 – Eingabe der ÖPNV-Suche

Gilt für die eine Maske `TransitScreen`, die an allen Stellen benutzt wird (Weg im Tagesprogramm, Zug bearbeiten,
neue Teilstrecke einer Reise).

### Von/Nach: Ort, Hotel oder Bahnhof

- `TransitForm` bekommt `fromPoint: TransitPoint?` und `toPoint: TransitPoint?` (bisher feste Konstruktorwerte im
  ViewModel; die Startwerte bleiben die Punkte des Wegs bzw. der Fahrt).
- Tippen ins Feld zeigt bis zu 8 Vorschläge unter dem Feld (`transit-from-hit-<i>`):
  1. Reise-Treffer zuerst, lokal und sofort: gespeicherte Orte, Unterkünfte und Orte des Tagesprogramms, deren Name
     den Text enthält (ohne Groß/Klein, ohne Akzente); je Ort einmal. Zweite Zeile: Region bzw. „Unterkunft“.
  2. Danach Treffer der Ortssuche (`PlaceFinder`: Google über den Server, sonst Photon), ab 3 Zeichen, 600 ms nach
     der letzten Eingabe, mit Adresse.
- Auswahl eines Treffers: `fromPoint`/`toPoint` = dessen Koordinaten und Name, der Bahnhofstext wird geleert, die
  Bahnhöfe in der Nähe dieses Punkts werden neu geladen (Chips wie bisher). Das Feld zeigt den Ort als Chip
  (`transit-from-place`) mit ✕ (`transit-from-place-clear`); ✕ entfernt den Punkt.
- Ein angetippter Bahnhofs-Chip setzt den Bahnhofsnamen; der Punkt bleibt (für den Fußweg).
- Freier Text ohne Auswahl wird wie bisher als Bahnhofsname geschickt.
- Anfrage: `from`/`to` = die Punkte, `fromStation`/`toStation` = Bahnhofstext oder null. Ohne Punkt und ohne
  Bahnhof: Hinweis wie bisher („Abfahrtsbahnhof eintragen …“, jetzt „Start eintragen (Ort, Hotel oder Bahnhof)“).

### Datum und Uhrzeit

- Beide Felder sind nur lesbar; ein Tipp öffnet `DatePickerDialog` (Material 3) bzw. einen `TimePicker` im Dialog
  (24 h). Anzeige: Datum „Do., 20. Mai 2027“, gespeichert bleibt ISO `yyyy-MM-dd`; Uhrzeit „08:05“.
- Behebt den Fehler, dass auf der Zifferntastatur kein „:“ eingegeben werden kann.
- Tags: `transit-date`, `transit-time` (Felder), `transit-date-ok`, `transit-time-ok` (Bestätigen).

## Teil 2 – Gefundene Verbindung speichern

Wohin gespeichert wird, hängt vom Startpunkt der Suche ab:

| Start der Suche | Speichern |
|---|---|
| Weg im Tagesprogramm („Verbindung abfragen“) | wie bisher: die ganze Verbindung am Weg |
| Logistik: neuer Zug / „ÖPNV-Verbindung suchen“ | eine **Reise**: jede Fahrt eine Teilstrecke |
| Logistik: Zug bearbeiten → „Verbindung suchen“ | bei mehreren Fahrten: Reise, der Zug wird die erste Teilstrecke |
| Logistik: Reise → „＋ Teilstrecke → Zug/ÖPNV“ | alle Fahrten an dieser Stelle in die bestehende Reise |

### Umwandlung Verbindung → Teilstrecken (shared, getestet)

Reine Funktion `TransitRides.ridesOf(result: TransitResult): List<Train>` (eine Verbindung mit genau einer Route):

- Nur Fahrten (`walk = false`); Fußwege werden keine Einträge (die Reise-Ansicht zeigt Umstiege/Fußwege).
- Je Fahrt: `number` = lesbarer Linienname (`leg.line`, sonst `lineJa`), `carrier` = Betreiber aus dem Linien-
  namen (JR, Tokyo Metro, Toei, Ōsaka Metro, Kintetsu, …; sonst null), Von/Nach = Bahnhöfe mit Namen
  (`stationLabel`), Koordinaten (falls bekannt), Datum und Zeit; Gleise; Haltestellen bleiben in der Verbindung.
- Datum: Start mit dem Datum der Suche (`result.datetime`); liegt eine Zeit vor der vorigen, ist es der Folgetag.
- `transit` jeder Teilstrecke = die Verbindung mit nur dieser Fahrt (Gleise, Haltestellen, Richtung); der
  Gesamtpreis steht an der ersten Teilstrecke (`fareYen` der ganzen Verbindung), die übrigen ohne Preis.
- Eine Verbindung mit genau einer Fahrt ergibt einen Zug ohne Reise (wie bisher).

### Speichern im Formular

- Im Zug-Formular wird die gewählte Verbindung wie bisher übernommen; hat sie mehrere Fahrten, zeigt das Formular
  „Wird als Reise mit 3 Teilstrecken gespeichert“ (`transit-as-journey`).
- Beim Speichern (`LocalEditor.saveTransitJourney`): die erste Fahrt geht in den bearbeiteten bzw. neuen Zug
  (Buchungsnummer, Notiz, Reisende, Klasse, Links bleiben dort), die weiteren werden neue Züge; alle bekommen eine
  `journeyId`:
  - neue Teilstrecke einer Reise: die Fahrten werden der Reihe nach an der Einfügestelle platziert;
  - der Zug war schon in einer Reise: die neuen Fahrten folgen direkt auf ihn;
  - sonst eine neue Reise.
- Logistik „Hinzufügen“ bekommt den Eintrag „ÖPNV-Verbindung suchen“ (`logistics-add-transit`): öffnet das neue
  Zug-Formular mit gleich geöffneter Verbindungssuche.

## Tests (Auswahl)

- Vorschläge: Reise-Orte vor Suchtreffern, ohne Doppelte; Auswahl setzt den Punkt, leert den Bahnhof, Anfrage
  enthält Koordinaten und keinen Bahnhofsnamen; ✕ entfernt den Punkt.
- Uhrzeit aus der Auswahl → „08:05“; Datum bleibt ISO, Anzeige deutsch.
- `ridesOf`: Fußwege fallen weg, Folgetag nach Mitternacht, Preis nur an der ersten, Betreiber erkannt.
- Speichern: neuer Zug mit 3 Fahrten → 3 Züge in einer Reise in Fahrtreihenfolge; bearbeiteter Zug behält
  Buchungsnummer; neue Teilstrecke in bestehender Reise landet zwischen den Nachbarn; eine Fahrt → keine Reise.
