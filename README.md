# Wanderlog Plus

Begleit-App für Wanderlog mit eigenem Sync-Server: Reiseplan offline ansehen und bearbeiten, Änderungen zwischen Geräten synchronisieren, Wanderlog-Importe ohne Datenverlust zusammenführen.

## Module

- `shared` – Datenmodell, Sync-Logik (HLC, Feld-Merge) und Abfragen; reines Kotlin.
- `importer` – liest einen Wanderlog-Export und erzeugt daraus Records.
- `server` – Ktor-Sync-Server mit SQLite und Dateiablage (siehe `server/README.md`).
- `client` – Sync-Client, lokaler Editor und Trip-Snapshot (ohne Android-Abhängigkeit).
- `app` – Android-App (Jetpack Compose).

## App-Funktionen

- **Heute, Reise, Orte, Logistik, Mehr**: Tagesübersicht, Tagesprogramm mit Vorschlägen, Orte pro Region, Flüge/Züge/Unterkünfte, Notizen, Import und Konflikte.
- **Bearbeiten**: Tagestitel, Tagesnotiz, Region eines Tages (Override), Notizen im Programm; bei Orten Notiz, Status, Tags (⭐/⚠️), Region und Kategorie.
- **Anhänge**: Tickets und Buchungsbestätigungen an Flügen, Zügen, Unterkünften, Orten und Tagen. Bilder und PDFs werden in der App angezeigt, alles andere über eine externe App. Alle Anhänge werden im Hintergrund heruntergeladen und sind offline verfügbar (max. 25 MB pro Datei).
- **Teilen-Menü**: Eine Datei aus einer anderen App teilen → Ziel wählen → angehängt.
- **Fahrten mit Zwischenhalten**: Züge und Flüge lassen sich bearbeiten. Zwischenhalte wählst du per Ortssuche (OpenStreetMap), und sie erscheinen auf der Routenkarte. Zwei Teilstrecken lassen sich zu einer Fahrt zusammenführen, überflüssige löschen.
- **Wege im Tagesprogramm**: Ein Schalter blendet zwischen den Zielen Wegzeit und Strecke ein. Aufgeklappt zeigen sie die Route auf der Karte, das Verkehrsmittel ist pro Abschnitt wählbar. Das braucht einen Google-Routes-Key auf dem Server (siehe `server/README.md`).
- **Links**: Navigation und ÖPNV (Google Maps), Website, Anrufen, Tabelog-Suche (nur Essen).
- **Einrichtung per QR-Code** oder Link `wlp://setup?url=…&token=…` (siehe `server/README.md`).
- **Zeitzone**: „Heute“ und „Jetzt geöffnet“ rechnen standardmäßig in japanischer Zeit (JST); in den Einstellungen auf Gerätezeit umstellbar.
- **Erinnerungen** an Stornofristen (7, 3 und 1 Tag vorher sowie am Tag selbst), abschaltbar in den Einstellungen.

## Bauen und testen

```
./gradlew test
./gradlew :app:assembleDebug
./gradlew :app:installDebug   # mit angeschlossenem Gerät
```

## Screenshots erzeugen

```
./gradlew :app:testDebugUnitTest --tests '*Screenshot*'
```

Die Screenshot-Tests (Roborazzi auf Robolectric) rendern jeden Hauptscreen hell und dunkel, auch mit langen Texten, und legen die PNGs nach `app/build/outputs/roborazzi/`. Referenzbilder werden nicht committet. Unter Robolectric erscheinen keine Emoji, Fotos und Karten zeigen ihre Ersatzgrafik.

Liegt der echte Export in `testdata/private/`, entstehen zusätzlich Screenshots mit echten Daten. Diese enthalten Buchungsdaten (Bestätigungsnummern, Reisende) und werden nicht geteilt; sie bleiben unter `app/build/`.

## Karte

Karten zeigen OpenStreetMap-Daten über [MapLibre](https://maplibre.org/) mit dem freien Stil von [OpenFreeMap](https://openfreemap.org/) (`https://tiles.openfreemap.org/styles/liberty`). Ein API-Key ist nicht nötig.

- **Orte pro Region:** Umschalter „Liste | Karte“, interaktive Karte mit farbigen Punkten nach Status; ein Tipp auf einen Punkt öffnet den Ort. Kacheln landen im Cache von MapLibre.
- **Routenkarten** für Flüge (Bogen) und Züge (Linie): einmal als Bild gerendert und im App-Cache (`cache/route-maps`) abgelegt, danach offline verfügbar. Ohne Netz und ohne Cache erscheint eine stilisierte Grafik; ohne Koordinaten gibt es keine Karte. Die Route ist eine Luftlinie zwischen den Endpunkten, nicht der echte Verlauf.
- **Flüge ohne Koordinaten** nutzen den IATA-Code der Endpunkte (kleine Flughafen-Tabelle in `RouteGeometry.kt`).
- Attribution „OpenFreeMap © OpenMapTiles Data from OpenStreetMap“ ist auf jeder Karte sichtbar.

## Server

Betrieb und Konfiguration: `server/README.md`.

## Echte Daten

Echte Wanderlog-Exporte enthalten Buchungsdaten und gehören ausschließlich nach `testdata/private/` (per `.gitignore` ausgeschlossen). Tests, die diese Datei brauchen, werden ohne sie übersprungen.
