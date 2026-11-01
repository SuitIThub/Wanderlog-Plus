# Wanderlog Plus

Begleit-App für Wanderlog mit eigenem Sync-Server: Reiseplan offline ansehen und bearbeiten, Änderungen zwischen Geräten synchronisieren, Wanderlog-Importe ohne Datenverlust zusammenführen.

## Module

- `shared` – Datenmodell, Sync-Logik (HLC, Feld-Merge) und Abfragen; reines Kotlin.
- `importer` – liest einen Wanderlog-Export und erzeugt daraus Records.
- `server` – Ktor-Sync-Server mit SQLite und Dateiablage (siehe `server/README.md`).
- `client` – Sync-Client, lokaler Editor und Trip-Snapshot (ohne Android-Abhängigkeit).
- `app` – Android-App (Jetpack Compose).

## Bauen und testen

```
./gradlew test
./gradlew :app:assembleDebug
./gradlew :app:installDebug   # mit angeschlossenem Gerät
```

## Server

Betrieb und Konfiguration: `server/README.md`.

## Echte Daten

Echte Wanderlog-Exporte enthalten Buchungsdaten und gehören ausschließlich nach `testdata/private/` (per `.gitignore` ausgeschlossen). Tests, die diese Datei brauchen, werden ohne sie übersprungen.
