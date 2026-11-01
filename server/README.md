# Wanderlog Plus – Sync-Server

Kleiner Ktor-Server (SQLite) für den Abgleich der Reisedaten zwischen Geräten,
Dateiablage (Anhänge) und den einmaligen Wanderlog-Import.

## Umgebungsvariablen

| Variable | Default | Bedeutung |
|---|---|---|
| `WLP_TOKENS` | – (Pflicht) | `name:token,name:token`; jeder Token mind. 16 Zeichen, Namen eindeutig. Der Name ist der Urheber (`author`) von Änderungen. |
| `WLP_DATA_DIR` | `/data` | Enthält `wanderlog-plus.db` und `files/`. |
| `WLP_PORT` | `8080` | Port des Servers. |
| `WLP_MAX_FILE_MB` | `25` | Maximale Dateigröße beim Upload. |
| `WLP_TRIP_KEY` | – | Optional; Reise-Schlüssel für `POST /api/import/wanderlog`. |
| `WLP_IMPORT_FILE` | – | Optional; Pfad zu einer Wanderlog-JSON-Datei statt Abruf (hat Vorrang vor `WLP_TRIP_KEY`). |
| `WLP_DOMAIN` | – | Nur für Caddy: Domain für HTTPS. |

Ohne `WLP_TRIP_KEY` und `WLP_IMPORT_FILE` antwortet der Import mit `400 import_not_configured`.
`WLP_TRIP_KEY` gibt Zugriff auf Buchungsdaten und wird nie committet.

## Tokens erzeugen

```
openssl rand -hex 24
```

## Start mit Docker Compose

```
cd server
cp Caddyfile.example Caddyfile
cat > .env <<'ENV'
WLP_TOKENS=alice:<token-1>,bob:<token-2>
WLP_DOMAIN=sync.example.org
# WLP_TRIP_KEY=<trip-key>
ENV
docker compose up -d --build
```

Für das automatische HTTPS muss `WLP_DOMAIN` per DNS auf den Host zeigen, und die Ports 80 und 443 müssen offen sein. Im Container bleibt `WLP_PORT` auf 8080 (Healthcheck und Compose setzen das voraus).

`.env` steht in `.gitignore`. Der Server ist nur auf `127.0.0.1:8080` veröffentlicht, von außen erreichbar ist er über Caddy (HTTPS).

## API

Alle Routen außer `GET /api/health` brauchen `Authorization: Bearer <token>` und `X-Protocol-Version: 1`.

- `GET /api/health`
- `GET /api/whoami`
- `POST /api/sync/push`, `GET /api/sync/pull?since=&limit=`
- `POST /api/files` (multipart `file`), `GET /api/files/{sha256}`
- `POST /api/import/wanderlog`, `GET /api/import/status`
- `GET /api/export`

## Backup

Das Volume `wlp-data` sichern (Datenbank und `files/`). Dazu vorher den Container stoppen oder die Datenbank mit `sqlite3 wanderlog-plus.db ".backup backup.db"` kopieren; eine einfache Dateikopie der laufenden WAL-Datenbank kann inkonsistent sein.

`GET /api/export` ist ein reiner Lese-Export und kein Weg zur Wiederherstellung.

Nach einem Restore behaelt die Datenbank ihre Epoche (nur eine neu angelegte Datenbank bekommt eine neue). Danach muss der Sync-Zustand der App zurueckgesetzt werden (Plan 3 bietet das an).
