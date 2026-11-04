# Reise-Chat (KI-Assistent) – Design

Stand 2026-11-04, vom Nutzer im Chat freigegeben.

## Ziel
Ein Chat, der überall in der App geöffnet werden kann, über OpenRouter arbeitet und anhand der hinterlegten
Vorschläge (SavedPlace + Place + Dossier) passende Vorschläge macht. Vorschläge erscheinen als antippbare Cards.
Auf Wunsch sucht er per Parallel (Search API) im Web nach Orten oder beantwortet allgemeine Fragen. Je nach
Öffnungsort wird passender Kontext (Tag, Region, Ort, Bildschirm) mitgegeben.

## Entscheidungen
- Cards nur öffnen: gespeicherte Orte → Ortsdetail; Web-Funde → Link im Browser. Der Chat ändert keine Reisedaten.
- Mehrere Chats, auf dem Server gespeichert und damit zwischen Geräten geteilt (alle Nutzer der Reise sehen sie).
- Einstieg: Chat-Symbol in der Kopfzeile (HeroHeader-Button bzw. schmale Leiste auf den Tab-Startseiten).
- Ganze Antwort + Statuszeile, kein Token-Streaming; die App fragt während einer laufenden Antwort regelmäßig nach.

## Server
- Tabelle `chats(id, updated_at, json)`; `ChatService` hält Chats als `ChatInfo` (Nachrichten, `pending`, `status`).
- API (geschützt): `GET /api/chats`, `POST /api/chats`, `GET /api/chats/{id}`, `DELETE /api/chats/{id}`,
  `POST /api/chats/{id}/messages {text, context}` → antwortet sofort mit `pending = true`, rechnet im Hintergrund.
- Modell-Schleife mit OpenAI-kompatiblem Tool-Calling über OpenRouter (höchstens 6 Werkzeugrunden):
  `search_saved_places`, `get_place`, `web_search` (Parallel Search, nur mit Key), `show_place_cards`, `show_web_cards`.
  Card-IDs werden gegen den Bestand geprüft, Web-Links gegen die Ergebnisse der Websuche.
- Kontext je Nutzernachricht (`ChatContext(screen, date?, regionId?, savedPlaceId?, label?)`), serverseitig aufgelöst:
  Tag → Region + Programm; Region → Name + Zahl der Vorschläge; Ort → Dossier. Immer: Reiseübersicht (Regionen mit Daten).
  Keine Buchungsdaten (Bestätigungsnummern, Namen) im Prompt.
- Neustart: laufende Antworten werden als fehlgeschlagen markiert.

## App
- `ChatViewModel` (eine Instanz für die App), `ChatScreen` (Route `chat`) mit Kontext-Chip, Nachrichten, Cards,
  Eingabe, „Neuer Chat“, Chatliste (Bottom Sheet), Löschen.
- Kontext aus der aktuellen Route: Heute, Tag (`trip/day/{date}…`), Region, Ort, Tab-Startseiten.

## Fehler
Kein Key → „KI ist auf dem Server nicht eingerichtet“. Fehlgeschlagene Antwort → Fehlerblase + „Erneut senden“.
Websuche fehlgeschlagen → Werkzeugergebnis meldet das, Modell antwortet aus den Vorschlägen.

## Tests
Server: Fake-Modell mit geskripteten Tool-Calls, Fake-Parallel, Kontextauflösung, Store, Endpunkte.
App: ViewModel (Senden, Polling, Fehler), Route→Kontext, ChatScreen (Cards öffnen).
