# Google-Suche, Online-Fotos und Recherche-Warteschlange – Design

Stand: 2026-11-06. Sechs Teile, nacheinander gebaut, jeder mit eigener App-Version und CHANGELOG-Abschnitt.
Reihenfolge: A (Google-Textsuche, Grundlage) → 5 → 4 → 1 → 6 → 2 → 3.

## Ziel

Orte bekommen ihre Google-ID von Anfang an (Ortssuche über Google) oder nachträglich (Verknüpfen). Überall, wo man
Fotos hinzufügt, kann man sie auch online suchen. Mehrere Recherchen laufen nacheinander statt durcheinander, schneller
pro Auftrag, und die Orte-Übersicht startet eine volle Recherche für gewählte Regionen.

## A. Baustein: Google-Textsuche (Server)

`GooglePlacesSearch` (server): `POST https://places.googleapis.com/v1/places:searchText` mit `textQuery`,
`languageCode=de`, `pageSize` ≤ 10 und entweder `locationRestriction.rectangle` (bbox) oder `locationBias.circle`
(near, Radius 30 km). Feldmaske: `places.id,places.displayName,places.formattedAddress,places.location,places.types,
places.rating,places.userRatingCount,places.photos`. Key: `WLP_GOOGLE_PLACES_KEY` (wie die Ortsfotos). Ergebnisse 24 h
im bestehenden Cache (`cachedRoute`, Schlüssel `gsearch:<hash>`), damit dieselbe Suche nichts kostet.

Treffer (shared, `GooglePlaceHit`): `googlePlaceId, name, address, lat, lng, types, rating, ratingCount, photos:
List<PlacePhoto>` (Foto-Namen, nicht die Bilder). Kategorie aus den Typen (`PlaceCategories.fromGoogle(types)`):
restaurant/cafe/bakery/food → FOOD, bar/night_club → NIGHTLIFE, store/shopping_mall/*_store → SHOPPING,
tourist_attraction/museum/temple/shrine/park/place_of_worship/… → SIGHT, lodging → OTHER, sonst OTHER.

Endpunkt `GET /api/places/search?q=&near=lat,lng&bbox=w,s,e,n` → `List<GooglePlaceHit>`. Ohne Key `503
places_search_not_configured`, Google-Fehler `502 places_search_unavailable`. `q` 2–200 Zeichen.

## Teil 5 – „Ort suchen“ über Google

- Neue Schnittstelle `PlaceSearch` (client) mit `search(q, near, bbox): PlaceSearchResult` (Treffer + `viaGoogle`).
  `ServerPlaceSearch` fragt den Server; bei `*_not_configured`, Netzfehler oder fehlendem Server fällt sie auf das
  bisherige `GeoSearch` (Photon) zurück und meldet `viaGoogle = false`.
- `GeoPlace` bekommt `googlePlaceId: String? = null` und `category` überschreibbar (Google-Typen entscheiden, sonst
  OSM-Tag wie bisher).
- Alle Suchen nutzen sie: Orte-Übersicht/Region („Ort suchen“), Tag („Ort hinzufügen“), Reel „Lage finden“.
- Hinzufügen setzt `Place.googlePlaceId` (`addPlaceToCollection(..., googlePlaceId)` gibt es schon).
- Ohne Google zeigt die Suche unter dem Feld „Suche ohne Google (OpenStreetMap)“.
- Anfragen: erst ab 3 Zeichen, 600 ms nach der letzten Eingabe, gleiche Anfrage nicht doppelt.

## Teil 4 – Google-Eintrag verknüpfen

- Ortsseite und Unterkunftsseite: Zeile „Google-Eintrag“ (`google-link`).
  - Verknüpft: Name und Adresse aus den gecachten Google-Details (sonst nur „Verknüpft“), Tipp öffnet Google Maps;
    Knopf „Ändern“.
  - Nicht verknüpft: „Kein Google-Eintrag“, Knopf „Suchen“.
- „Suchen/Ändern“ öffnet ein EditSheet: Suchfeld vorbefüllt mit `Name + Region`, Suche mit `near` = Lage des Orts.
  Treffer: Name, Adresse, Entfernung zum bisherigen Punkt („1,2 km“), Bewertung. Schalter „Lage übernehmen“ (aus;
  an setzt `lat/lng` auf den Treffer). Auswahl setzt `Place.googlePlaceId` (und ggf. Lage). „Verknüpfung lösen“ setzt
  sie auf "".
- Die Server-Anreicherung (Öffnungszeiten, Bewertung) greift danach von selbst; Google-Fotos zeigt die Titelbild-
  Auswahl sofort.
- Ortsliste der Region: kleines Symbol (`no-google-<id>`, Icon „LinkOff“) bei Orten ohne Google-ID.
- Ohne Google-Suche am Server: Zeile bleibt, „Suchen“ ist ausgeblendet.

## Teil 1 – Online-Fotosuche

- Server `GET /api/photos/search?q=&near=` → `List<WebPhoto>` mit `id, source (GOOGLE|WIKIMEDIA), title, credit,
  width, height`. Google: die Fotos der ersten 5 Treffer der Textsuche (A), je bis zu 4. Wikimedia Commons:
  `action=query&generator=search&gsrnamespace=6&gsrsearch=<q> filetype:bitmap&prop=imageinfo&iiprop=url|size|extmetadata
  &iiurlwidth=1600`, bis zu 12, nur JPEG/PNG/WebP; `credit` = Künstler + Lizenz (HTML entfernt). Zusammen ≤ 30,
  Google zuerst. Fehlt der Google-Key, nur Wikimedia; scheitert eine Quelle, kommt die andere trotzdem.
- `GET /api/photos/media?id=&w=` liefert das Bild (Vorschau `w=400`, Übernahme `w=1600`). Google-IDs gehen über den
  bestehenden Foto-Proxy; Wikimedia-IDs sind der Dateiname, der Server lädt nur von `upload.wikimedia.org`
  (kein offener Proxy). Größe ≤ 15 MB.
- App: `WebPhotoSearchSheet(initialQuery, near, multiple, onPick: (List<WebPhoto>) -> Unit)`: Suchfeld, Raster
  (3 Spalten) mit Vorschauen, Auswahl mit Häkchen, Quelle/Urheber unter dem gewählten Bild, „Übernehmen (n)“.
- Übernahme: `WebPhotoSource : AttachmentSource` lädt `/api/photos/media?w=1600`; die Fotos gehen über
  `AttachmentsViewModel.addPhotos` wie Galerie-Fotos. Dateiname `<Titel>.jpg`, die Herkunft steht im
  Anhang-Titel/Notiz („Foto: Wikimedia · Autor, CC BY-SA 4.0“ bzw. „Foto: Google“).
- Einstiege:
  - Überall mit „Fotos hinzufügen“ (Ort, Logistik, Unterkunft, Tag): kleine Auswahl „Aus Galerie“ / „Online suchen“.
    Vorbefüllung: Ortsname + Region; Unterkunftsname + Stadt; Tag: Name der Region des Tages.
  - Titelbild-Auswahl: Knopf „Online suchen“ (Einzelauswahl); das Bild wird eigenes Foto des Orts und gleich
    Titelbild.
- Ohne Server: Option ausgeblendet.

## Teil 6 – Mehr Orte gleichzeitig

- `RESEARCH_PARALLELISM` wird Konfiguration: `WLP_RESEARCH_PARALLELISM`, Standard 10, gültig 1–20 (sonst Standard).
- Antwortet OpenRouter (oder Parallel) mit 429 oder 402 `openrouter_in_flight_budget`, wartet der Server `Retry-After`
  (höchstens 60 s, sonst 10 s) und versucht es bis zu 3-mal, bevor der Ort fehlschlägt. Vorhandene Wiederholung wird
  wiederverwendet, nicht verdoppelt.
- Ablauf-Zeile nennt den tatsächlichen Wert.

## Teil 2 – Recherche-Warteschlange

- Server: Recherche-Aufträge (`research`) laufen auf einer eigenen Spur mit einem Platz; weitere warten als `QUEUED`
  mit Schritt „Wartet auf die laufende Recherche …“. Reels, Guides, Unterkünfte, Einschätzungen bleiben im Pool mit 2.
  Neustart: unterbrochene Recherchen werden in der alten Reihenfolge wieder eingereiht.
- App (`PlacesViewModel`): `research(ids, label)` hängt einen Auftrag an eine geordnete Liste `queue:
  StateFlow<List<QueuedResearch(jobId?, label, ids, progress, total, state)>>`. Jeder Auftrag wird sofort an den
  Server geschickt (der reiht ein); gepollt wird nur der erste nicht fertige, die anderen nach ihm.
  - Statuszeile: „<label>: 3 von 12 Orten …“ + „ · danach: Osaka, Nara“ wenn weitere warten. Ergebnis wie bisher.
  - „Ablauf“ öffnet den laufenden Auftrag (sonst den zuletzt fertigen).
  - Orte, die in einem offenen Auftrag stecken, werden nicht nochmals eingereiht (`researching` enthält alle).
  - `label`: Regionsname; mehrere Regionen/sonst „Orte“.

## Teil 3 – Recherchieren in der Orte-Übersicht

- „Einschätzen (KI)“ entfällt; an seiner Stelle „Recherchieren (KI)“ (`places-research`).
- Tipp öffnet EditSheet „Regionen recherchieren“: je Region eine Zeile mit Häkchen „Kyoto · 12 Orte, 5 neu“
  (`research-region-<key>`), „Ohne Region“ wenn vorhanden, „Alle auswählen“. Gezählt werden dieselben Orte wie in der
  Regionsliste (nicht verworfen).
- Darunter zwei Knöpfe wie in der Region: „Nur unrecherchierte (n · ≈ x Cent)“ (`research-new`, aus wenn n = 0) und
  „Alle (m · ≈ y Cent)“ (`research-all`), aus solange nichts gewählt.
- Bestätigen: je gewählter Region ein Auftrag (Teil 2), in der angezeigten Reihenfolge. Leere Regionen werden
  übersprungen.
- Der Server-Endpunkt für Einschätzungen bleibt; die App ruft ihn nicht mehr auf.

## Tests (Auswahl)

- Server: Textsuche baut Rechteck/Kreis richtig, mappt Typen, cached; Fotosuche mischt Quellen, überlebt Ausfall einer
  Quelle, Media-Proxy lehnt fremde Hosts ab; Recherche-Spur: zweiter Auftrag startet erst nach dem ersten;
  Retry bei 429.
- App: Suche fällt auf Photon zurück und zeigt den Hinweis; Google-Zeile verknüpft/löst; Fotosuche übernimmt Auswahl
  als Anhänge; Statuszeile zeigt nur den laufenden Auftrag + „danach“; Regionsauswahl startet je Region einen Auftrag.
