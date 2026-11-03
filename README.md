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
- **Einfügen im Tagesprogramm**: Ein kleines ＋ zwischen den Programmpunkten fügt an genau dieser Stelle einen Ort aus den Vorschlägen, einen gesuchten Ort, eine Unterkunft (z. B. als Start oder Ziel des Tages) oder eine Notiz ein. Die Ortssuche hat Suchfeld und Karte: Sie sucht zuerst im sichtbaren Ausschnitt und weicht auf die Umgebung aus, wenn dort nichts ist. Nach dem Verschieben der Karte kann man „In diesem Bereich suchen“, und langes Drücken auf die Karte wählt einen beliebigen Punkt. Gefundene Orte landen als geplante Orte in der Region des Tages.
- **Tagesroute auf der Karte**: Der Knopf „Karte“ im Programm zeigt alle Orte des Tages nummeriert auf einer Vollbildkarte. Verbunden sind sie über die Routen im jeweils gewählten Verkehrsmittel, gestrichelt, wo es keine Route gibt. Dazu kommen Gesamtzeit und -strecke.
- **Aufgeklappte Foldables und Tablets** (ab 600 dp Breite): Die Navigationsleiste sitzt links. Reise, Logistik und Region zeigen Liste und Detail nebeneinander, die Tagesroute die Karte auf zwei Dritteln mit der Liste rechts (einklappbar), „Heute“ und die Ortssuche je zwei Spalten.
- **Reels auswerten (KI)**: Ein Instagram-Reel an die App teilen; ein kleines Popup bestätigt, man bleibt in Instagram. Der Server erkennt die Orte in Bild, Ton und Beschreibung, prüft, ob sie auf der Route liegen, und recherchiert sie (Parallel, OpenRouter). Eine Benachrichtigung führt zur Vorschau, dort übernimmt man die gewünschten Orte. Die Liste aller Auswertungen gibt es unter Orte → Reels.
- **Lohnt sich?**: „Einschätzen (KI)“ in der Orte-Übersicht bewertet alle Orte (Muss man sehen, Lohnt sich, Wenn Zeit ist, Eher auslassen); die Regionslisten sind dann danach gruppiert.
- **KI-Kriterien**: Unter Mehr → KI-Kriterien wählt man, wonach die KI bewertet (18 Kriterien wie Andrang, Authentisch statt touristisch, Buchbarkeit, Wartezeiten, Preis-Leistung, Reisezeit Mai …), und kann eigene Anweisungen ergänzen. Jedes Kriterium bekommt 1–5 Punkte, dazu ein Gesamtfazit.
- **Ablauf live**: Reel-Auswertungen, Recherchen und Einschätzungen haben einen Ablauf-Screen: Status, Laufzeit, Fortschritt, jeder Ort mit seinem aktuellen Schritt (Lage, Websuche mit Quellen, Recherche-Auftrag, Punkte vor Ort, Ergebnis) und das ganze Protokoll. Erreichbar aus der Reel-Auswertung („Ablauf“), bei der Recherche im Ort und in der Region und unter Mehr → KI-Aufträge.
- **Karte im Ort**: Im Ort steht eine kleine Karte, ein Tipp oder der Vollbild-Button öffnet sie groß (aufgeklappt mit Liste daneben). Der Ort ist ein Pin; Punkte aus der Recherche (weitere Eingänge, Aussichtspunkte, versteckte Ecken, Bahnhof …) erscheinen als runde Symbole je Art und stehen auch im Tab „Recherche“ unter „Punkte vor Ort“.
- **Recherche (KI)**: Im Ort gibt es die Tabs „Einschätzung“ (Gesamtfazit, Punkte je Kriterium) und „Recherche“ (Geheimtipps, versteckte Ecken, Gut zu wissen, Tipps, Hinweise, Quellen). „Recherchieren (KI)“ startet die Web-Recherche für einen Ort, in der Regionsliste für alle (oder nur noch nicht recherchierte) Orte der Region, mit Kostenhinweis.
- **Ort suchen in „Orte“**: Suche mit Karte wie im Tagesprogramm; neue Orte landen in der nächstgelegenen Region.
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

## Releases und Updates

- **Release bauen:** `versionName`/`versionCode` in `app/build.gradle.kts` hochzählen, einen Abschnitt `## <version>` in `CHANGELOG.md` schreiben und nach `main` pushen. Die Action `.github/workflows/release.yml` baut dann eine signierte APK und veröffentlicht sie als Release `v<version>`. Den Changelog-Abschnitt übernimmt sie als Release-Text. Gibt es den Tag schon, tut sie nichts. Über „Run workflow“ lässt sie sich auch von Hand starten.
- **Signatur:** Die Action braucht die Repository-Secrets `WLP_KEYSTORE_BASE64`, `WLP_KEYSTORE_PASSWORD`, `WLP_KEY_ALIAS` und `WLP_KEY_PASSWORD`. Lokal liest Gradle den Schlüssel aus `keystore.properties`. Diese Datei und `keystore/` sind gitignored. Den Keystore sicher aufbewahren: Geht er verloren, lassen sich keine Updates mehr über die installierte App spielen.
- **In der App:** Release-Builds suchen höchstens alle 6 Stunden beim Start nach einer neuen Version und zeigen dann das Changelog. Unter Einstellungen geht das auch von Hand („Nach Updates suchen“). Nach dem Bestätigen lädt die App die APK, prüft Größe und SHA-256 und übergibt sie an den Android-Installer. Beim ersten Mal muss „Apps aus dieser Quelle installieren“ einmal erlaubt werden.

