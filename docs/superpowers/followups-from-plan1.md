# Follow-ups aus Plan 1 (shared + importer)

Stand: 2026-10-31. Ergebnis der Task-Reviews und des Gesamt-Reviews von Plan 1. Diese Punkte müssen in die Pläne 2 und 3 einfließen.

## Pflicht für Plan 2 (Server)

- **Drift-Schutz für HLC:** `HlcClock.receive` muss entfernte Zeitstempel ablehnen oder kappen, die zu weit in der Zukunft liegen (z. B. > 1 Tag). Der Server prüft die HLCs eingehender Pushes.
- **Verlust gegen den Importer wird zum Konflikt:** Verliert eine Geräteänderung LWW nur gegen einen Schreibvorgang des Importers (nodeId `importer`) und ist ihr Wert anders, behält der Server den Gerätewert (neue HLC) und speichert `_conflict.<f>` = Importer-Wert. Sonst gehen Offline-Änderungen still verloren.
- **Import und Pushes serialisieren:** Merge und Anwendung des Imports dürfen nicht mit eingehenden Pushes verschränkt laufen.
- **Batch vor dem Anwenden validieren:** `applyChanges` wendet bis zum ersten Fehler an. Der Server prüft deshalb den ganzen Push vorher (HLC-Format, Typ, ID).
- **Eindeutige nodeId pro Installation** (bei gleicher HLC gibt es keinen Tie-Break über den Wert).
- **Retry bei HTTP 5xx**, wenn der Server Importe auslöst. Der aktuelle Client wiederholt nur bei IOException und folgt keinen Redirects.
- **CLI/Runner:** Fehler beim Laden des Zustands und fehlende Dateien sauber melden, statt einen Stacktrace auszugeben.

## Pflicht für Plan 3 (App)

- **Deterministische Day-ID pro Datum** für in der App angelegte Tage (z. B. UUID aus `day:<tripId>:<date>`). Sonst legen zwei Geräte offline zwei Days für dasselbe Datum an.
- **TripQueries muss leere Stay-Daten tolerieren:** Der Model-Default für `checkIn`/`checkOut` ist `""`, und `LocalDate.parse` wirft dabei eine Exception.
- **Referenzprüfung vor dem Soft-Delete von Places:** Ein Place, der aus Wanderlog verschwindet, aber noch von App-Daten genutzt wird, darf nicht ausgeblendet werden.
- **Markdown-Renderer:** Weiche Zeilenumbrüche werden als harte dargestellt. Der Importer erzeugt bewusst keine harten Umbrüche.
- **Neue importierte Einträge:** Sie bekommen beim Re-Import positionsbasierte `sortKey`s, weil `sortKey` nur beim Anlegen gesetzt wird. Bei Bedarf stattdessen per `SortKey.between` zwischen die gespeicherten Nachbarn einsortieren.

## Getroffene Entscheidungen (zur Kenntnis)

- Orte aus Regions-Listen erhalten die Region der Liste. Hotel-Blöcke werden nur zu Stays, nie zu SavedPlaces.
- `coerceInputValues = true` beim Dekodieren: unbekannte Enum-Werte und `null` fallen auf den Default zurück.
- `sortKey` (DayItem, Note) und die heuristische `regionId` (Stay, SavedPlace aus Listen ohne eigene Region) werden nur beim Anlegen gesetzt. Beim Re-Import werden sie nie aktualisiert und lösen keinen Konflikt aus.
- Die Regionen-Heuristik läuft bei jedem Import. Durch die Create-only-Regel wirkt sie aber nur auf neue Objekte.

## Aufgeschobene Kleinigkeiten (Auswahl)

- Markdown: kein Escaping von `*`, `_`, `#` am Zeilenanfang; verschachtelte Listen werden flach; leere Aufzählungspunkte ergeben `- `.
- Mapper: dayPlan-Orte bekommen immer die Kategorie SIGHT; bei doppelten Regionsnamen gewinnt der letzte; ein Hotel ohne Place ergibt `placeId ""`.
- Testlücken: Parser-Regeln „Flug ohne depart“ und „dayPlan ohne date“; SortKey-Randfälle; ein CLI-Test fehlt.
- Der Trip-Key steht noch in Spec und Plan. Vor einem Push zu einem öffentlichen Remote entfernen.
