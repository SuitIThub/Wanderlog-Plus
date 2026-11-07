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
| `WLP_GOOGLE_ROUTES_KEY` | – | Optional; API-Key der Google Routes API für die Wegzeiten zwischen den Zielen eines Tages. Ohne Key zeigt die App „Wegzeiten brauchen einen Routen-Key“. |
| `WLP_GOOGLE_PLACES_KEY` | `WLP_GOOGLE_ROUTES_KEY` | Optional; Key der Places API (New): Google-Fotos der Orte, Suche nach Reel-Orten und Chat-Funden (Textsuche) und Details (Öffnungszeiten, Bewertung, Preis, Kontakt) für Recherche und übernommene Orte, zwischengespeichert. Ohne Key: Photon für die Lage, keine Google-Details. |
| `WLP_OPENROUTER_KEY` | – | Optional; OpenRouter-Key für die KI: Reels auswerten (Bilder, Ton, Beschreibung) und Orte einschätzen. Ohne Key antworten diese Funktionen mit `ai_not_configured`. |
| `WLP_AI_MODEL` | `google/gemini-2.5-flash` | Modell bei OpenRouter für Reels und Videos; muss Bilder und Ton verstehen. |
| `WLP_TEXT_MODEL` | `anthropic/claude-sonnet-5-5` | Modell bei OpenRouter für Textarbeit: Chat, Orte einschätzen, Recherche auswerten. Der feste Teil des Chat-Kontexts wird für den Prompt-Cache markiert. |
| `WLP_PARALLEL_KEY` | – | Optional; Parallel-Key für die Web-Recherche zu Orten aus Reels (Search + Task API). Ohne Key recherchiert das OpenRouter-Modell selbst. |
| `WLP_RESEARCH_PARALLELISM` | `10` | Optional; wie viele Orte eine Recherche gleichzeitig bearbeitet (1–20). Recherche-Aufträge selbst laufen nacheinander. Bei Drosselung (429, OpenRouter-„in-flight budget“) wartet der Server und versucht es bis zu 3-mal erneut. |
| `WLP_PARALLEL_PROCESSOR` | `base` | Parallel-Task-Prozessor (`lite`, `base`, `core`, …): gründlicher = langsamer und teurer. |
| `WLP_ADMIN_TOKEN` | – | Optional; Admin-Token (mind. 16 Zeichen, nicht gleich einem Sync-Token) für die Zugangsverwaltung (`/api/admin/tokens`). Er gilt nur dort, nie für den Sync. Ohne ihn: `admin_not_configured`. |
| `WLP_APIFY_TOKEN` | – | Optional; Apify-Token für ÖPNV-Verbindungen in Japan (`POST /api/transit`, Actor „Japan Train Route & Fare Finder“, Daten von Yahoo! 乗換案内). Ohne Token: `transit_not_configured`, die App fällt auf Google zurück. |
| `WLP_APIFY_TRANSIT_ACTOR` | `FjxdTwrnBwvjDSBe0` | Optional; anderer Apify-Actor mit derselben Ein- und Ausgabe. |
| `WLP_INSTAGRAM_COOKIES` | – | Optional; Pfad (im Container) zu einer Cookies-Datei für yt-dlp, falls Instagram Reels nur angemeldet zeigt. Die Datei enthält eine Anmeldung, also wie ein Passwort behandeln. |
| `WLP_DOMAIN` | – | Nur für Caddy: Domain für HTTPS. |
| `WLP_HOST_PORT` | `8080` | Nur Compose: Port auf dem Host (`127.0.0.1:<port>`), falls 8080 belegt ist. |

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
docker compose --profile caddy up -d --build
```

Ohne `--profile caddy` startet nur der Server (z. B. wenn schon ein anderer Reverse Proxy oder `tailscale serve` HTTPS übernimmt). Beide Dienste haben `restart: unless-stopped` und starten nach einem Neustart des Geräts automatisch mit, solange der Docker-Dienst beim Booten startet (`sudo systemctl enable docker`).

Für das automatische HTTPS muss `WLP_DOMAIN` per DNS auf den Host zeigen, und die Ports 80 und 443 müssen offen sein. Im Container bleibt `WLP_PORT` auf 8080 (Healthcheck und Compose setzen das voraus).

`.env` steht in `.gitignore`. Der Server ist nur auf `127.0.0.1:8080` veröffentlicht, von außen erreichbar ist er über Caddy (HTTPS).

### Reverse Proxy und Upload-Größe

Caddy begrenzt die Größe einer Anfrage standardmäßig nicht. Ein anderer Reverse Proxy muss Uploads bis `WLP_MAX_FILE_MB` plus etwas Puffer für den Multipart-Rahmen durchlassen, sonst lehnt er große Anhänge mit 413 ab, bevor sie den Server erreichen. Für nginx (Standard 1 MB) zum Beispiel:

```
client_max_body_size 26m;
```

Die App behält einen so abgelehnten Upload in der Warteschlange und versucht ihn bei der nächsten Synchronisation erneut. Nur die Antwort `file_too_large` vom Server selbst verwirft ihn.

## API

Alle Routen außer `GET /api/health` brauchen `Authorization: Bearer <token>` und `X-Protocol-Version: 1`.

- `GET /api/health`
- `GET /api/whoami`
- `POST /api/sync/push`, `GET /api/sync/pull?since=&limit=`
- `POST /api/files` (multipart `file`), `GET /api/files/{sha256}`
- `POST /api/routes` (`{fromLat, fromLng, toLat, toLng, mode}` mit `mode` = WALK/BICYCLE/DRIVE/TRANSIT) → `{durationSeconds, distanceMeters, polyline}`; 404 `no_route`, 503 `routes_not_configured`
- `GET /api/admin/tokens`, `POST /api/admin/tokens` (`{name}`), `DELETE /api/admin/tokens/{name}`: Zugänge (nur mit `WLP_ADMIN_TOKEN`). In der App angelegte Tokens speichert der Server in der Datenbank (Tabelle `app_tokens`); sie gelten sofort für den Sync, ihr Name ist der Autor der Änderungen. Tokens aus `WLP_TOKENS` lassen sich nur in der `.env` ändern.
- `POST /api/transit` (`{from, to | fromStation, toStation, datetime, searchType, preference, use*, fresh}`), `GET /api/transit/stations?lat&lng`: Verbindungen und Bahnhöfe. Bahnhöfe findet der Server über Google Places (Umkreis- bzw. Textsuche nach Bahn-, U-Bahn- und Stadtbahnhöfen, japanischer und lateinischer Name, dauerhaft zwischengespeichert; braucht `WLP_GOOGLE_PLACES_KEY`), sonst und als Ersatz über Photon (OpenStreetMap, nur `railway=station/halt`); Verbindungen speichert er 7 Tage (auch „keine Verbindung“); `fresh` fragt neu. Eine Suche kostet bei Apify etwa 0,7 Cent und dauert 10–20 s.
- `POST /api/ai/reels` (`{url}`), `POST /api/ai/reels/upload` (multipart `file`, optional `caption`), `POST /api/ai/assess` (`{savedPlaceIds, config}`), `POST /api/ai/research` (`{savedPlaceIds, config}`, 1–300 Orte), `POST /api/ai/guides` (`{asks: [{topic | title, parentId, wish, guideId}]}`, 1–30 Guides), `POST /api/ai/stay` (`{stayId, links, fresh}`: Zimmer & Ausstattung einer Unterkunft), `GET /api/ai/jobs?kind=reel|assess|research|guides|stay`, `GET /api/ai/jobs/{id}`
- `POST /api/import/wanderlog`, `GET /api/import/status`
- `GET /api/export`

## Backup

Das Volume `wlp-data` sichern (Datenbank und `files/`). Dazu vorher den Container stoppen oder die Datenbank mit `sqlite3 wanderlog-plus.db ".backup backup.db"` kopieren; eine einfache Dateikopie der laufenden WAL-Datenbank kann inkonsistent sein.

`GET /api/export` ist ein reiner Lese-Export und kein Weg zur Wiederherstellung.

Nach einem Restore behaelt die Datenbank ihre Epoche (nur eine neu angelegte Datenbank bekommt eine neue). Danach muss der Sync-Zustand der App zurueckgesetzt werden (Plan 3 bietet das an).

## QR-Code für die Einrichtung

Die App kann Server-Adresse und Token per QR-Code übernehmen („QR-Code scannen“ auf dem Einrichtungsbildschirm). Der Code enthält einen Link der Form `wlp://setup?url=<url-codiert>&token=<url-codiert>`:

```sh
qrencode -t ansiutf8 'wlp://setup?url=https%3A%2F%2Fwlp.example.org&token=…'
```

Wird ein solcher Link in der Android-Kamera oder im Browser geöffnet, startet die App die Einrichtung ebenfalls. Die Felder werden nur ausgefüllt; die App speichert nichts automatisch, die Verbindung muss getestet und bestätigt werden.

**Der Code enthält das Token und ist wie ein Passwort zu behandeln:** nicht teilen, nicht in Chats oder Tickets posten, nach der Einrichtung nicht aufbewahren.

## Wegzeiten (Google Routes API)

Die App fragt Wegzeiten und Routen zwischen den Zielen eines Tages beim Server an. Der Server leitet die Anfrage an die Google Routes API weiter (`routes.googleapis.com/directions/v2:computeRoutes`), damit der Key nicht in der App steckt. Jede Strecke wird in der Datenbank (Tabelle `route_cache`) zwischengespeichert. Beide Geräte teilen sich so den Cache, und jede Strecke kostet nur einmal Kontingent. ÖPNV-Routen verfallen nach 7 Tagen, weil sich Fahrpläne ändern.

Einrichtung:

1. In der Google Cloud Console ein Projekt anlegen und die **Routes API** aktivieren. Für das Freikontingent ist ein Zahlungsprofil nötig.
2. Einen API-Key erstellen und auf die Routes API beschränken.
3. `WLP_GOOGLE_ROUTES_KEY=<key>` in `.env` eintragen und den Container neu starten.

## Reels auswerten (KI)

Teilt man ein Instagram-Reel an die App, läuft die Auswertung als Job auf dem Server. Die App wartet im Hintergrund und meldet sich mit einer Benachrichtigung.

1. **Laden:** yt-dlp lädt das Video samt Beschreibung (`WLP_INSTAGRAM_COOKIES` nur, wenn nötig). Scheitert das, teilt man stattdessen die gespeicherte Videodatei.
2. **Erkennen:** ffmpeg zieht bis zu 12 Einzelbilder und den Ton heraus. Das OpenRouter-Modell findet darin und in der Beschreibung die erwähnten Orte.
3. **Verorten:** Photon (OpenStreetMap) liefert Koordinaten. Liegt ein Ort höchstens 50 km von einer Unterkunft oder einem gespeicherten Ort entfernt, gilt er als „auf der Route“.
4. **Recherchieren:** Mit `WLP_PARALLEL_KEY` holt die Parallel Search API Quellen, die Task API schreibt daraus Beschreibung, Tipps, Hinweise und die Einschätzung (alles auf Deutsch). Ohne Parallel-Key übernimmt das OpenRouter-Modell mit Websuche.

Die Ortsrecherche läuft in zwei Schritten: Parallel (ohne Key die Websuche des Textmodells) sammelt Fakten mit Quellen, das Textmodell (`WLP_TEXT_MODEL`) bewertet sie mit dem Reisekontext (Region, dort Hinterlegtes) und schreibt das Dossier. Guides (Knigge, Sprache, Abläufe) entstehen genauso; der Server legt sie als Assistent an, ein Guide zum selben Thema wird überarbeitet statt doppelt angelegt.

Alle KI-Anfragen nehmen ein optionales `config` mit (`{criteria: ["crowds", …], extraPrompt}`; beim Upload als Formularfeld `config` mit JSON). Die Kriterien stehen in `shared/…/model/Research.kt`; jedes bekommt 1–5 Punkte, dazu ein Gesamtfazit (`verdict`). Die Recherche (`/api/ai/research`) liefert pro Ort ein Dossier mit Kriterien, Fazit, Geheimtipps, versteckten Ecken, Gut-zu-wissen, Tipps, Hinweisen und Quellen; der Job meldet `progress`/`total`. Ergebnisse von Recherche und Einschätzung schreibt der Server selbst in die Reise (Autor „KI-Recherche“, als normale Sync-Änderungen mit neuerer HLC), jeden Ort sobald er fertig ist; die App synchronisiert nur. Eine neuere Recherche eines Orts wird nie durch eine ältere überschrieben, eine vorhandene Beschreibung bleibt. Unterbricht ein Neustart eine Recherche, läuft sie mit den restlichen Orten weiter (`savedPlaceIds`, `config`); fertige Ergebnisse älterer Versionen, die nie übernommen wurden, trägt der Server beim Start nach. Jeder Job führt ein Protokoll (`steps`: Zeit, Text, Stufe INFO/OK/WARN/ERROR, höchstens 300) und den Stand je Ort (`items`: WAITING/RUNNING/DONE/FAILED mit aktuellem Schritt und Zeiten); die Texte enthalten Ortsnamen und Reel-Inhalt, nie Buchungsdaten. Dazu kommen bis zu 6 Punkte vor Ort (`pois`: weitere Eingänge, Aussichtspunkte, versteckte Ecken, Bahnhof); ihre Lage wird geprüft: Koordinaten des Modells nur bis 3 km vom Ort, sonst per Photon gesucht, sonst verworfen.

Ortspositionen kommen von Photon (öffentlicher OpenStreetMap-Dienst, lehnt Lastspitzen mit 503/429 ab): Der Server stellt alle Anfragen nacheinander mit mindestens 400 ms Abstand und wiederholt abgelehnte nach 1, 2 und 4 s. Antwortet Photon trotzdem nicht, steht das im Ablauf und in der Vorschau („Ortsdienst hat nicht geantwortet“), nicht als „nicht gefunden“. Findet der volle Name nichts, versucht der Server kürzere Namen mit der Stadt und nimmt einen Treffer nur bis 25 km von der Stadt. In der Vorschau lässt sich die Position eines Orts ohne Treffer über „Position suchen“ von Hand setzen.

An die KI-Dienste gehen nur Reel-Inhalte, Ortsnamen und Regionen, nie Buchungsdaten. Kosten grob: unter 1 Cent für das Erkennen (Gemini Flash) und gut 1 Cent pro Ort für die Parallel-Recherche (`base`).

yt-dlp kommt beim Bau des Images in der aktuellen Version mit. Wenn Instagram-Downloads plötzlich scheitern, das Image neu bauen.
