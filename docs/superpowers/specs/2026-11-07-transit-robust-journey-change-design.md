# ÖPNV: robuste erste Suche, ganze Reise ändern, Start/Ziel vor und nach den Bahnhöfen – Design

Stand: 2026-11-07. Drei Teile, je eigene App-Version und CHANGELOG-Abschnitt.

## Teil 1 – Erste Suche robust

Beobachtung: Bei neuen Zielen bricht die erste Suche mit „Server nicht erreichbar“ ab (Verbindungsabbruch), die
zweite klappt. Ursache vermutlich eine lange erste Anfrage (kalter Apify-Lauf, Bahnhofssuche für alle Teilstrecken),
die eine Stelle zwischen App und Heimserver früher trennt; der Server rechnet zu Ende.

- Server, `TransitService.search`: gleiche Suche (gleicher Cache-Schlüssel) läuft nur einmal gleichzeitig; eine zweite
  wartet auf das Ergebnis der ersten (auch auf ihren Fehler).
- Server: `fresh` nimmt ein Ergebnis, das höchstens 10 Minuten alt ist (`FRESH_WINDOW`), statt immer neu zu fragen.
  „Keine Verbindung“ gilt ebenso.
- App, `TransitViewModel.search`: bei `ApiErrorKind.NETWORK` bis zu 2 weitere Versuche nach 5 bzw. 10 Sekunden
  (Text während der Wiederholung „Verbindung unterbrochen, frage erneut …“), erst danach „Server nicht erreichbar.“.

## Teil 2 – Start und Ziel vor bzw. nach den Bahnhöfen

- `Train` bekommt `walkFrom: Endpoint? = null` (Ort vor dem ersten Bahnhof) und `walkTo: Endpoint? = null` (Ort nach
  dem letzten), beide mit Name und Koordinaten (ggf. `placeId`).
- Gesetzt beim Übernehmen einer Verbindung, wenn in der Suche ein Ort (Punkt mit Namen) statt nur eines Bahnhofs als
  Start bzw. Ziel stand: Start an der ersten Fahrt, Ziel an der letzten (bei einer Reise: erste/letzte Teilstrecke).
  `TransitResult` bekommt dafür `fromPlace: TransitPoint? = null`, `toPlace: TransitPoint? = null` (setzt die App aus
  der Anfrage, der Server reicht sie durch).
- Zug-Formular: zwei Felder „Start (vor dem Bahnhof)“ / „Ziel (nach dem Bahnhof)“ mit Ortssuche (`edit-walk-from`,
  `edit-walk-to`), leerbar.
- Anzeige (Zug-Detailseite und Reise-Ansicht): vor dem ersten Bahnhof eine Zeile „<Ort> · ca. 12 Min. zu Fuß zu
  <Bahnhof>“ (`walk-from`), nach dem letzten „<Bahnhof> · ca. 25 Min. zu Fuß zu <Ort>“ (`walk-to`); Fußweg über den
  vorhandenen Routendienst, sonst „ca. N Min.“ aus der Luftlinie (80 m/Min., Faktor 1,3), ab 3 km Luftlinie nur
  „<Ort> (4,2 km)“.
- „Andere Fahrt suchen“ übernimmt `walkFrom`/`walkTo` als Punkte, wenn die Teilstrecke die erste/letzte ist.

## Teil 3 – Ganze Reise ändern

- Reise-Ansicht: „Verbindung ändern“ (`journey-change`), nur wenn alle Teilstrecken Züge sind.
- Öffnet die ÖPNV-Suche: Start = `walkFrom` der ersten Teilstrecke (sonst deren Abfahrtsbahnhof als Punkt/Name),
  Ziel = `walkTo` der letzten (sonst deren Ankunftsbahnhof), Datum/Uhrzeit = Abfahrt der ersten, „Abfahrt um“.
- Übernehmen (`LocalEditor.replaceJourney`): die erste alte Teilstrecke wird wie bei „Andere Fahrt“ zur ersten neuen
  Fahrt (Buchung, Notiz, Sitzplätze bleiben, Sitzplatz-Hinweis), die übrigen alten Teilstrecken werden gelöscht, die
  weiteren neuen Fahrten folgen in derselben Reise; `walkFrom`/`walkTo` gehen an die neue erste/letzte Fahrt.

## Tests (Auswahl)

- Server: zwei gleichzeitige gleiche Suchen → ein Apify-Lauf, beide erhalten das Ergebnis; `fresh` innerhalb 10 Min.
  aus dem Speicher, danach neu.
- App: erster Versuch NETWORK, zweiter ok → Ergebnis ohne Fehlermeldung; dreimal NETWORK → „Server nicht erreichbar.“
- Übernehmen mit Ort als Start/Ziel → `walkFrom`/`walkTo` an erster/letzter Fahrt; ohne Ort → null.
- Anzeige der Zeilen mit Fußweg; Formularfelder setzen/leeren.
- `replaceJourney`: 3 alte → 2 neue: erste behält Buchung, dritte alte gelöscht, Reihenfolge, walkFrom/walkTo.
