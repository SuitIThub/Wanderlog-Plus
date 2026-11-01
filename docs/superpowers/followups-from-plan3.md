# Follow-ups aus Plan 3 (App-Grundgerüst)

Stand: 2026-11-01. Ergebnis der Task-Reviews und des Gesamt-Reviews von Plan 3. Offene Punkte aus `followups-from-plan1.md` und `followups-from-plan2.md` gelten weiter, soweit sie hier nicht als erledigt markiert sind.

## Pflicht für Plan 4 (App-Ausbau)

- **Erster Test auf einem echten Gerät:** Die App lief bisher nur unter Robolectric, es gab weder Emulator noch Gerät. Vor dem Ausbau mit `./gradlew :app:installDebug` installieren und prüfen: Einrichtung, erster Sync (~533 Objekte), Flugmodus mit Änderungen, App-Abbruch mitten im Sync, Dunkelmodus und Edge-to-Edge-Ränder.
- **Room-Migrationen:** Das Schema wird ab jetzt exportiert (`app/schemas/`). Jede Änderung braucht eine Migration, sonst stürzt die App beim Start ab.
- **Dateien und Anhänge:** Downloads vom Server (`/api/files/{hash}`) brauchen `Authorization` und `X-Protocol-Version`, auch im Bild-Loader und im Download-Worker.
- **Zeitzone:** „Heute“ und „Jetzt geöffnet“ nutzen die Gerätezeit. Steht das Handy in Japan nicht auf JST, stimmen Tag und Öffnungsstatus nicht. Option: Zeitzone aus den Reisedaten oder eine Einstellung „Reise-Zeitzone“.
- **ServerUrl:** Pfad-Präfixe (`https://host/wlp`) werden abgelehnt. Wird hinter einem Reverse Proxy ein Unterpfad nötig, muss `ServerUrl.normalize` das zulassen und `SyncApi` den Pfad übernehmen.
- **Neu importierte Einträge** bekommen weiter positionsbasierte `sortKey`s. Einsortieren zwischen gespeicherte Nachbarn nur bei Bedarf (aus Plan 1).

## Bekannte Grenzen (bewusst so)

- **Server-DB ersetzt, Geräte syncen vor dem Import:** Der Importer übernimmt solche Datensätze. Ein abweichender Gerätewert gilt dabei als App-Änderung und erscheint nicht als Konflikt (Ruling R8).
- **Tage:** Gibt es mehrere `Day`-Objekte für dasselbe Datum, gilt überall das mit der größten ID (`TripQueries.dayFor`).
- **Ungültiger Token, falsche Protokollversion:** Es wird mit Backoff erneut versucht. Bei Uhrzeit-Abweichung zeigt die App Server- und Gerätezeit und versucht es erst beim nächsten regulären Sync wieder.
- **Abgelehnte Änderungen:** Vom Server einzeln abgelehnte Änderungen werden verworfen. Die Anzahl erscheint im Sync-Status.

## Offen (aus früheren Plänen)

- Der Trip-Key steht weiterhin in den Dokumenten zu Spec und Plan 1. Vor einem öffentlichen Push entfernen.
