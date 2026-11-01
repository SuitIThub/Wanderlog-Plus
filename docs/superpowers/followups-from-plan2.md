# Follow-ups aus Plan 2 (Server)

Stand: 2026-11-01. Ergebnis der Task-Reviews und des Gesamt-Reviews von Plan 2. Die Punkte aus `followups-from-plan1.md` („Pflicht für Plan 3“) gelten weiter.

## Pflicht für Plan 3 (App)

- **Header:** Jede `/api/*`-Anfrage außer `/api/health` trägt `Authorization: Bearer <token>` und `X-Protocol-Version: 1`. Das gilt auch für Datei-Downloads, also auch für den Bild-Loader. Push-Bodies werden mit `Content-Type: application/json` gesendet.
- **Pull-Cursor:** Gespeichert wird `PullResponse.nextSeq`, nie `PushResponse.serverSeq`. Sonst würden Änderungen anderer Personen übersprungen, die zwischen den Anfragen geschrieben wurden. Pull wird wiederholt, solange `hasMore` gilt.
- **Uhr:** Für jede gepullte HLC und für `PushResponse.maxHlc` wird `clock.receive` aufgerufen.
- **nodeId:** Pro Installation wird eine zufällige nodeId erzeugt und dauerhaft gespeichert. `importer` und `server` sind reserviert.
- **Epoch:** Der Server liefert `epoch` (Health, Pull, Push). Ändert sie sich gegenüber dem gespeicherten Wert, setzt die App `since = 0` und pusht ihren vollständigen lokalen Feldstand erneut. Per LWW ist das idempotent. Bei `409 cursor_ahead` gilt dasselbe. Zusätzlich bietet die App „Sync-Zustand zurücksetzen“ an, für den Fall eines eingespielten Backups (gleiche Epoch, niedrigere `seq`).
- **Fehlerbehandlung im Ausgang:**
  - Bei 400 mit Index (`change #n`) und den Codes `invalid_change`, `invalid_hlc`, `reserved_field` oder `reserved_node` wird nur diese Änderung verworfen und protokolliert. Danach wird erneut gepusht.
  - Bei `clock_drift` bleibt der Ausgang erhalten, und die App warnt mit Verweis auf `serverTime` aus Health.
  - Bei 401, 409 `protocol_mismatch` und 5xx bleibt der Ausgang erhalten, und es wird mit Backoff erneut versucht.
- **Konflikte auflösen:** Für „App behalten“ wird `_conflict.<f> = null` geschrieben. Für „Wanderlog übernehmen“ werden `<f> = Konfliktwert` und `_conflict.<f> = null` geschrieben. Die Felder `_base.*`, `_source` und `_sourceId` dürfen Geräte nicht schreiben, der Server lehnt sie ab.
- **HlcClock-Drift im Client:** `HlcClock.receive` in `shared` kappt ferne Zukunfts-HLCs noch nicht (Follow-up aus Plan 1). Der Server lässt beim Push höchstens +24 h zu, daher kommen von ihm keine extremen Werte. Die App sollte beim Pull trotzdem HLCs ignorieren, die mehr als 24 h vor der Gerätezeit liegen, oder das Kappen in `shared` ergänzen.

## Bekannte Grenzen (bewusst so)

- **Zwei Geräte offline, dasselbe Feld, dazwischen ein Import:** Das zuerst pushende Gerät gewinnt den Importer-Konflikt und bekommt eine Server-HLC. Die spätere Änderung des zweiten Geräts verliert dagegen still per LWW. Das ist selten. Eine Erweiterung der Konfliktregel hätte keinen eindeutigen Gewinner.
- **Änderung nach einem Re-Import, aber vor dem Pull:** Ein Gerät ändert ein Feld nach einem Re-Import, hat den neuen Wanderlog-Wert aber noch nicht gepullt. Seine Änderung hat dann die neuere HLC und überschreibt den Wanderlog-Wert ohne Konflikt. Laut Spec §1 ist die App führend.
- **Löschen im selben Push:** Enthält ein Push eine `deleted`-Änderung für ein Objekt, wird es nicht wiederbelebt, auch wenn diese Änderung gegen den Importer verliert.
- **Dateien:** Es gibt keine Bereinigung verwaister Dateien und keine Dateiliste. Laut Spec gibt es nur Upload und Download.

## Offen

- Das Docker-Image ist noch nie gebaut worden, weil der Docker-Daemon zur Umsetzungszeit nicht lief. Vor dem ersten Deployment: `docker build -f server/Dockerfile -t wanderlog-plus-server .` und den Smoke-Test aus Plan 2, Task 7 ausführen.
- Der Trip-Key steht weiterhin in den Dokumenten zu Spec und Plan 1. Vor einem öffentlichen Push entfernen.
