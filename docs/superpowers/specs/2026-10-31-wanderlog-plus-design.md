# Wanderlog Plus – Design

Stand: 2026-10-31 · Status: Entwurf zur Freigabe

## 1. Ziel

Eine native Android-App als Reise-Companion für zwei Reisende (Japanreise 08.–29.05.2027), mit eigenem Server zur Synchronisation. Die App ist nach dem Import die **führende Datenquelle**; Wanderlog bleibt eine **Importquelle**, aus der jederzeit erneut importiert werden kann, ohne in der App gemachte Änderungen zu verlieren.

### Kernfunktionen (V1)

- Datumsübersicht der Reise; pro Tag: Aufenthaltsort, Unterkunft, Fahrten, Tagesprogramm.
- Pro Tag Vorschläge aus den gesammelten Orten der Region, in der man sich an dem Tag aufhält (inkl. Tagesausflugs-Regionen).
- Logistik-Übersicht: Flüge, Züge, Unterkünfte mit Buchungsdetails.
- Dateianhänge (Tickets, Buchungsbestätigungen) an beliebigen Objekten, offline verfügbar.
- Navigation per Deep-Link in Google Maps.
- Vollständig offline nutzbar; Sync zwischen beiden Geräten über eigenen Server.
- Vollimport und Re-Import aus Wanderlog mit feldweisem 3-Wege-Merge.

### Nicht in V1 (mit Erweiterungspunkt vorgesehen)

- Live-Tracking von Zügen und Flügen (Schnittstelle vorhanden, Datenquelle offen).
- KI-Planvorschläge und KI-Bewertungen von Orten.
- Tiefe Tabelog-Integration (V1: nur Such-Link).
- UI für Budget/Ausgaben (Daten werden aber importiert).

### Bewusst ausgeschlossen

- iOS, Web-Oberfläche, eigene Turn-by-Turn-Navigation, Benutzerkonten/Login, Rückschreiben nach Wanderlog (keine Schreib-API vorhanden).

## 2. Rahmenbedingungen

- **Nutzer:** zwei Personen, beide Android.
- **Auth:** statische Tokens pro Person, kein Login.
- **Server:** beliebiger Linux-Host, ausgeliefert als Docker-Image. Hosting-Ort noch offen. Server-URL und Token werden in der App konfiguriert.
- **Datenquelle Wanderlog:** inoffizieller, lesender Endpoint ohne Login:
  `GET https://wanderlog.com/api/tripPlans/{key}?clientSchemaVersion=2&registerView=false`
  (`clientSchemaVersion` ist Pflicht). Liefert ~1,8 MB JSON. Bilder: `https://itin-dev.wanderlogstatic.com/{freeImageSmall|freeImageMedium|freeImage}/{imageKey}`. Fallback: dieselben Daten sind in der HTML-Share-Seite als `window.__MOBX_STATE__` eingebettet.
- **Sensible Daten:** Die Wanderlog-Daten enthalten Namen, Buchungs-/Bestätigungsnummern und Budget. Echte Exporte gehören nie ins Repo.

## 3. Architektur

Ein Git-Repo, Gradle-Multi-Projekt, durchgehend Kotlin.

```
wanderlog-plus/
├── shared/    Kotlin/JVM-Modul, von App und Server genutzt
│              Datenmodell, Sync-Protokoll (DTOs), HLC, Merge-Funktion, Sortierschlüssel
├── importer/  Wanderlog-JSON → eigenes Modell, Quill-Delta → Markdown,
│              Regionen-Heuristik, Erzeugung von Änderungen für den Re-Import
├── server/    Ktor + SQLite; Sync-API, Datei-API, Import-Auslösung; Docker-Image
└── app/       Android, Jetpack Compose, Material 3, Room, WorkManager
```

**Datenfluss:**

1. Server ruft Wanderlog-Endpoint ab → `importer` übersetzt und vergleicht mit dem Bestand → erzeugt Feld-Änderungen → werden wie Änderungen eines Geräts angewendet.
2. App schreibt Änderungen lokal (Room) und in einen Ausgang → Push zum Server → Pull aller Änderungen seit der letzten bekannten Server-Sequenz.
3. Dateien werden separat hoch-/heruntergeladen und lokal dauerhaft gespeichert.

## 4. Datenmodell

Alle Objekte besitzen:

- `id`: UUID (v7), auf dem erzeugenden Gerät generiert.
- Pro Feld einen HLC-Zeitstempel (siehe §6).
- `deleted`: Löschmarker (Tombstone).
- Optional `source` (`wanderlog` | `app` | `assistant`) und `sourceId` (Wanderlog-Block-ID bzw. Google `place_id`).
- Für importierte Objekte: pro Feld den **Wert des letzten Imports** (`importBase`) für den 3-Wege-Merge.
- Optional `suggestionState` (`none` | `proposed` | `accepted` | `rejected`) für spätere KI-Entwürfe.

| Objekt | Felder (Auszug) | Wanderlog-Quelle |
|---|---|---|
| Trip | title, startDate, endDate, wanderlogKey | `tripPlan` |
| Region | name, color, dayTripFromRegionId? | Überschriften „… Sehenswürdigkeiten“ / „… Tagesausflug (X)“ |
| Place | googlePlaceId, name, lat/lng, address, openingPeriods, rating, numRatings, description, minMinutes/maxMinutes, categories, website, phone, priceLevel, imageKeys | Block `place` + `resources.placeMetadata` |
| SavedPlace | placeId, regionId, category (sight/food/shopping/nightlife/other), note (Markdown), tags (Emoji), status (idea/planned/visited/dropped) | Orte in Listen-Sections, `reactions` |
| Stay | placeId, checkIn, checkOut, confirmationNumber, travelerNames, guests, cancellationDeadline, cancellationPolicy, note | Hotels-Section, `hotel`-Feld |
| Flight | airline, number, depart/arrive (Flughafen, Datum, Zeit), confirmationNumber, travelerNames, note | Flights-Section |
| Train | carrier, depart/arrive (Ort, Datum, Zeit), confirmationNumber, travelerNames, routeText, note | Verkehr-Section |
| Day | date, title, note, regionOverrideId? | Sections mit `mode=dayPlan` |
| DayItem | dayId, sortKey, kind (place/note), placeId?, text?, time? | Blöcke der Tagespläne |
| Note | title, body (Markdown), sortKey | Notiz-Sections (z. B. „Useful Apps“, „Notizen“) |
| Attachment | targetType, targetId, fileHash, fileName, mimeType, size | – (nur aus der App) |
| Expense | amount, currency, category, description, paid, date, relatedId? | `itinerary.budget.expenses` |

**Rich Text:** Quill-Delta (`text.ops`) wird nach Markdown konvertiert (Fett, Kursiv, Listen, Links, Zeilenumbrüche). Nicht unterstützte Attribute werden als reiner Text übernommen.

**Abgeleitet, nicht gespeichert:**

- Region eines Tages = `Day.regionOverrideId` ?: Region des Stays, dessen `checkIn ≤ Datum < checkOut`; Abreisetag ohne folgenden Stay (letzter Tag) → Region des letzten Stays; Tage vor dem ersten Stay (Anreise) → keine Region, Anzeige als Reisetag ohne Vorschläge. An Wechseltagen gilt die Region des neuen Stays.
- Tage ohne Wanderlog-Section werden für jedes Datum zwischen `startDate` und `endDate` angezeigt; ein `Day`-Objekt wird erst beim ersten Bearbeiten angelegt.
- Vorschläge eines Tages = SavedPlaces der Tagesregion und ihrer Tagesausflugs-Regionen mit Status `idea`, die noch keinem Tag zugeordnet sind.

## 5. Import und Re-Import

### Ablauf

1. Abruf des Endpoints (Timeout, Retry 1×).
2. **Validierung** gegen die erwarteten Strukturen; bei Abweichung Abbruch **vor** jedem Schreibvorgang, Fehler wird in der App angezeigt.
3. Übersetzung in das eigene Modell (Abgleich über `sourceId`; Places über `googlePlaceId` dedupliziert).
4. Merge pro Feld (unten), Ergebnis als Änderungen mit Absender `importer`.
5. Import-Protokoll (Zeit, Anzahl neu/geändert/Konflikte/entfernt) wird gespeichert.

### Merge-Regeln pro Feld

Sei `W` = neuer Wanderlog-Wert, `B` = `importBase`, `A` = aktueller App-Wert.

| Fall | Ergebnis |
|---|---|
| Objekt neu in Wanderlog | anlegen, `importBase` = W |
| `A == B`, `W != B` | A := W, `importBase` := W |
| `A != B`, `W == B` | A bleibt |
| `A != B`, `W != B`, `A != W` | A bleibt, **Konflikt** gespeichert (W hinterlegt), `importBase` := W |
| Objekt fehlt in Wanderlog, nie in App geändert | `deleted` = true (wiederherstellbar) |
| Objekt fehlt in Wanderlog, in App geändert | bleibt, Markierung „in Wanderlog entfernt“ |
| `source = app` | vom Import nie berührt |
| Stammdatenfelder (Öffnungszeiten, Bewertung, Beschreibung, Bilder) | immer W |

Konflikte werden in der App aufgelöst: „App behalten“ oder „Wanderlog übernehmen“.

### Regionen-Heuristik (nur Erstimport)

- Listen-Sections, deren Überschrift auf „Sehenswürdigkeiten“ endet → Region mit dem Namen davor.
- „X Tagesausflug (Y)“ → Region X mit `dayTripFromRegionId` = Y.
- Sections ohne Regionsbezug (z. B. „Food-Spots“, „Shopping“, „Nightlife“) → SavedPlaces mit passender Kategorie, Region per nächstgelegenem Regions-Schwerpunkt (≤ 50 km), sonst ohne Region.
- Stays → Region, deren Orte-Schwerpunkt am nächsten liegt.
- Day-Sections mit eigenen Orten (z. B. „Kiso-Tal“) → DayItems; die Orte werden zusätzlich als SavedPlace (Status `planned`) in der Region des Tages geführt.
- Regionszuordnungen sind in der App änderbar; Änderungen bleiben bei Re-Importen erhalten (normale Merge-Regeln).

## 6. Sync

- **Uhren:** Hybrid Logical Clock pro Gerät (physische Zeit + Zähler + Geräte-ID als Tie-Breaker).
- **Konfliktauflösung:** Last-Writer-Wins pro Feld nach HLC. Die Merge-Funktion liegt in `shared` und wird von App und Server identisch genutzt.
- **Reihenfolgen:** fraktionale Sortierschlüssel (String), damit gleichzeitiges Umsortieren konvergiert.
- **Push:** `POST /api/sync/push` mit einer Liste von Feld-Änderungen `{entityType, id, field, value, hlc}`; der Server wendet an und vergibt eine monotone `serverSeq`.
- **Pull:** `GET /api/sync/pull?since={seq}` liefert geänderte Objekte inkl. Feld-Zeitstempel und die neue Sequenz; paginiert.
- **Auslöser:** App-Start, ~5 s nach lokaler Änderung (debounced), periodisch via WorkManager (~15 min, nur mit Netz), Pull-to-Refresh.
- **Versionierung:** Jede Anfrage trägt die Protokollversion; bei Inkompatibilität antwortet der Server mit `409` und Hinweis, ohne Daten anzunehmen.

## 7. Dateien

- Upload: `POST /api/files` (Multipart), Speicherung unter SHA-256-Hash; Duplikate werden erkannt.
- Download: `GET /api/files/{hash}`.
- Die Metadaten (Attachment) werden normal synchronisiert. Die App lädt **alle** Anhänge im Hintergrund herunter und behält sie dauerhaft lokal.
- Eingang über das Android-Teilen-Menü: Datei teilen → Ziel-Objekt (Flug, Zug, Unterkunft, Ort, Tag) auswählen.
- Bilder und PDFs werden in der App angezeigt; sonstige Typen über eine externe App geöffnet.
- Größenlimit pro Datei: 25 MB (konfigurierbar).

## 8. Server

- Ktor (JVM), SQLite (eine Datei), Dateien in einem Verzeichnis; beides in einem Docker-Volume.
- Konfiguration per Datei/Umgebung: Tokens pro Person (`name → token`), Wanderlog-Trip-Key, Größenlimits.
- Auth: `Authorization: Bearer <token>`; der Token-Name dient als Urheber von Änderungen.
- Endpoints: Sync (Push/Pull), Dateien, `POST /api/import/wanderlog` (Import auslösen), `GET /api/import/status`, `GET /api/export` (vollständiger JSON-Export), `GET /api/health`.
- TLS über einen vorgeschalteten Reverse Proxy (Empfehlung: Caddy); Beispielkonfiguration und `docker-compose.yml` werden mitgeliefert.

## 9. App

- Jetpack Compose, Material 3, Hell/Dunkel; Room als lokale DB; WorkManager für Sync und Datei-Downloads.
- **Ersteinrichtung:** Server-URL + Token eingeben oder QR-Code scannen; Verbindungstest. Warnung bei `http://` außerhalb privater Netzbereiche.
- **Tabs:**
  1. **Heute:** vor der Reise Countdown + anstehende Fristen (z. B. Stornofristen); während der Reise aktueller Tag mit Region, Unterkunft (Check-in/-out, Navigation), Fahrten mit Ticket-Schnellzugriff, Tagesprogramm, Vorschläge in der Nähe, die jetzt geöffnet sind.
  2. **Reise:** Liste aller Tage, farbig nach Region; Tagesdetail mit sortierbarem Programm, Notizen und Vorschlägen (Filter: Kategorie, „an diesem Wochentag geöffnet“; Sortierung: Tags ⭐ vor neutral vor ⚠️, dann Bewertung). Vorschlag per Tipp in den Tag übernehmen.
  3. **Orte:** pro Region als Liste oder Karte; Ortsdetail mit Notiz (editierbar), Tags, Status, Öffnungszeiten, Dauer, Bewertung, Fotos; Buttons Navigation (Google-Maps-Intent, ÖPNV), Website, Telefon, Tabelog-Suche.
  4. **Logistik:** Flüge, Züge, Unterkünfte chronologisch mit Buchungsdaten, Anhängen, Live-Status-Platzhalter.
  5. **Mehr:** Notizen, Import (auslösen, Protokoll, Konflikte auflösen), Einstellungen (Server, Token, Sync-Status).
- **Karte:** Google Maps SDK for Android (API-Key erforderlich; Kosten/Konditionen bei Umsetzung prüfen).
- **Bilder:** Wanderlog-Bild-URLs werden geladen und gecacht; nicht erreichbare Bilder sind kein Fehler.

## 10. Erweiterungspunkte

| Schnittstelle | Ort | V1 | Später |
|---|---|---|---|
| `LiveStatusProvider` | Server | leere Implementierung | ODPT (Züge, sofern verfügbar), Flug-Status-API |
| `ExternalLinkProvider` | App | Google Maps, Website, Tabelog-Suche | weitere Plattformen |
| `AssistantService` | Server | nicht vorhanden | KI-Vorschläge als Entwürfe (`suggestionState = proposed`), schreibt nie direkt |
| Importquelle | importer | Wanderlog | z. B. Buchungsmails; nutzt denselben Merge |

## 11. Fehlerbehandlung

- **Offline:** volle Funktion, Änderungen im Ausgang; Anzeige „zuletzt synchronisiert vor …“.
- **Server nicht erreichbar / Token ungültig:** verständliche Meldung, lokale Daten unverändert, automatischer Neuversuch mit Backoff.
- **Protokollversion inkompatibel:** `409` mit Hinweis, kein Datenaustausch.
- **Wanderlog-Format geändert oder Endpoint weg:** Import bricht vor dem Schreiben ab; Fehler im Import-Protokoll.
- **Datei-Download fehlgeschlagen:** Neuversuch im Hintergrund; Anhang zeigt Status „nicht offline verfügbar“.

## 12. Tests

- **importer:** echter Wanderlog-Export als lokale Fixture (per `.gitignore` ausgeschlossen); kleine anonymisierte Fixtures für alle Re-Import-Fälle (neu, geändert, in App geändert, Konflikt, entfernt, Stammdaten); Quill→Markdown-Konvertierung; Regionen-Heuristik.
- **shared:** Unit-Tests für HLC, Feld-Merge, Tombstones, Sortierschlüssel; Simulationstest mit zwei Geräten + Importer und zufälliger Reihenfolge von Änderungen und Syncs → alle konvergieren.
- **server:** API-Tests gegen echte SQLite-Datei (Auth, Push/Pull, Pagination, Versionsprüfung, Datei-Upload/-Download, Import-Abbruch bei ungültigem Format).
- **app:** Unit-Tests für Tagesregion und Vorschlagslogik, Room-DAO-Tests; UI-Tests nur für Ersteinrichtung und „Ticket öffnen“.

## 13. Offene Punkte

- Hosting-Ort des Servers.
- Datenquelle für Live-Status (eigener Spike vor der Umsetzung des Erweiterungspunkts).
- Konditionen des Google Maps SDK / API-Key.
