# Follow-ups aus Plan 5 (UI-Redesign)

Stand: 2026-11-01. Ergebnis der Task-Reviews und des Screenshot-Gesamtlaufs von Plan 5. Offene Punkte aus den früheren Follow-up-Dateien gelten weiter, soweit sie hier nicht als erledigt markiert sind.

## Pflicht vor dem nächsten Ausbau

- **Test auf dem Gerät:** Das Redesign lief nur unter Robolectric. Auf dem Handy prüfen:
  - Fotos laden (Coil) und fallen ohne Netz still auf den Verlauf zurück.
  - Routenkarten werden mit Netz einmal gerendert und sind danach offline da; die Attribution „OpenFreeMap © OpenMapTiles Data from OpenStreetMap“ ist lesbar.
  - Die Regionskarte springt nach einem Filterwechsel auf die neuen Punkte, Pan und Zoom bleiben sonst erhalten.
  - Dunkelmodus, Edge-to-Edge-Ränder und Emoji (unter Robolectric unsichtbar).
- **Flughafen-Tabelle:** `AIRPORTS` in `app/.../maps/RouteGeometry.kt` deckt nur die Flughäfen der Reise und einige japanische ab. Der im Review genannte Code „HNJ“ ist kein bekannter IATA-Code und fehlt deshalb; bei Bedarf mit echten Koordinaten ergänzen.

## Bekannte Grenzen (bewusst so)

- **Routen sind Luftlinien** zwischen den Endpunkten, keine echte Bahn- oder Flugroute (Wanderlog liefert keine Geometrie).
- **Eine kaputte Routenkarte im Cache** wird beim Anzeigen gelöscht und erst beim nächsten Aufbau des Screens neu gerendert, nicht sofort.
- **Langer Ortsname:** Der einzeilige Kopf kürzt ihn; der volle Name steht dann oben im Info-Tab.
- **Screenshots mit echten Daten** enthalten Buchungsdaten und bleiben unter `app/build/`.
