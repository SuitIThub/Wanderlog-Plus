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

## Karte

Karten zeigen OpenStreetMap-Daten über [MapLibre](https://maplibre.org/) mit dem freien Stil von [OpenFreeMap](https://openfreemap.org/) (`https://tiles.openfreemap.org/styles/liberty`). Ein API-Key ist nicht nötig.

- **Orte pro Region:** Umschalter „Liste | Karte“, interaktive Karte mit farbigen Punkten nach Status; ein Tipp auf einen Punkt öffnet den Ort. Kacheln landen im Cache von MapLibre.
- **Routenkarten** für Flüge (Bogen) und Züge (Linie): einmal als Bild gerendert und im App-Cache (`cache/route-maps`) abgelegt, danach offline verfügbar. Ohne Netz und ohne Cache erscheint eine stilisierte Grafik; ohne Koordinaten gibt es keine Karte. Die Route ist eine Luftlinie zwischen den Endpunkten, nicht der echte Verlauf.
- Attribution „© OpenStreetMap“ ist auf jeder Karte sichtbar.

## Server

Betrieb und Konfiguration: `server/README.md`.

## Echte Daten

Echte Wanderlog-Exporte enthalten Buchungsdaten und gehören ausschließlich nach `testdata/private/` (per `.gitignore` ausgeschlossen). Tests, die diese Datei brauchen, werden ohne sie übersprungen.
