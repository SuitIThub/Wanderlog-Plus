# Wanderlog Plus – UI-Redesign

Stand: 2026-11-01 · Status: zur Freigabe · Baut auf `2026-10-31-wanderlog-plus-design.md` auf (Funktionsumfang unverändert)

## 1. Ziel

Die App wirkt bisher karg (fast nur Text und Buttons), und einzelne Screens sind überladen, allen voran das Tagesdetail. Das Redesign macht sie ansehnlich und übersichtlich:

- echte Bilder statt Text,
- Karten, Zeitleiste und Tabs statt langer Textspalten,
- weniger auf einmal pro Screen.

Funktionen und Datenmodell ändern sich nicht. Ausnahme ist die Kartenquelle (§5).

## 2. Stil

Mix aus den drei abgestimmten Richtungen (Visual Companion, 2026-11-01):

- **Kopfbereich wie „Reise-Journal“:** großes Bild mit dunklem Verlauf nach unten. Darauf stehen Kicker (Datum, Tag n), Titel in Serifenschrift und Chips.
- **Abläufe wie „Clean Dashboard“:** eine vertikale Zeitleiste mit Punkten und Karten.
- **Übersichten und Listen wie „Material You, warm“:** abgerundete Karten mit dezentem Schatten und Abschnitts-Labels in Akzentfarbe.
- **Farben:** warmer Orange-Grundton (Material-3-Schema, hell und dunkel), Regionsfarben als Akzent (Streifen, Punkte, Farbverlauf ohne Bild).
- **Bilder vor Icons:** Orte und Unterkünfte zeigen ihr Wanderlog-Foto, Fahrten eine Routenkarte. Icons und Emoji nur als kleines Symbol in Badges oder wo es kein Bild gibt.

## 3. Gemeinsame Bausteine

| Baustein | Beschreibung |
|---|---|
| `HeroHeader` | Bild, sonst Farbverlauf in Regionsfarbe; Zurück-Knopf, Kicker, Titel (Serif), Chips, optional ✎. Höhe 170 dp (Detail 140 dp). |
| `PhotoCard` | Karte mit Foto: kompakt (Thumbnail links) oder groß (Bildstreifen oben). Titel, Unterzeile, Badges. Ohne Foto kommt ein Platzhalter-Verlauf mit Kategorie-Symbol. |
| `Timeline` / `TimelineItem` | Linie mit Punkten links; Marke (Uhrzeit, Nummer oder „Nacht“) in Akzentfarbe. Items sind nach Bedarf höher: Ort mit Fotostreifen, Fahrt mit Routenkarte, Notiz als Textkarte. |
| `RouteMap` | Kartenbild der Fahrt: Start- und Ziel-Pin, Linie (Zug) bzw. Bogen (Flug). Gerendert und gecacht (§5). |
| `SectionLabel`, `Badge`, `ActionCircle` | Kleines Label in Großbuchstaben; Status-Badges („offen“, „🎫 Ticket“, „nicht offline“); runde Aktionsknöpfe (Navigation, ÖPNV, Website, Anruf, Tabelog). |
| `EditSheet` | Bottom-Sheet für Bearbeitungen (Tagestitel/Region; Tags/Region/Kategorie eines Orts). |

Bildquelle: `Place.imageKeys` über die bestehende Wanderlog-Bild-URL (Coil, gecacht). Ein fehlgeschlagenes Bild fällt still auf den Verlauf zurück.

## 4. Screens

- **Heute:**
  - während der Reise: `HeroHeader` (Bild des ersten Orts im Tagesprogramm), Unterkunft als `PhotoCard`, „Tagesablauf“ als `Timeline` (Fahrten mit Routenkarte und Ticket-Badge, Orte mit Foto, Unterkunft als Abschluss), „Jetzt geöffnet“ als kompakte `PhotoCard`s;
  - vor der Reise: Kopf mit großem Countdown, Fristen als Karten.
- **Reise:** Tage als Karten mit Regionsfarbstreifen, Datum, Titel, kleinem Foto des ersten Orts und Hinweis („3 geplant · 🚄“).
- **Tagesdetail:**
  - `HeroHeader` mit ✎ (öffnet `EditSheet` für Titel und Region), darunter die Tabs:
    1. **Programm:** `Timeline` aller Einträge; jeder Eintrag hat ein ⋮-Menü (Nach oben, Nach unten, Entfernen); FAB „＋ Hinzufügen“ für Ort aus Vorschlägen oder Notiz.
    2. **Vorschläge (n):** Filter-Chips (Kategorie, „an diesem Wochentag geöffnet“), kompakte `PhotoCard`s, Tipp auf ＋ übernimmt den Ort.
    3. **Notizen & Dateien:** Tagesnotiz (Markdown, bearbeiten) und Anhänge.
- **Orte:**
  - Regionen als große Bildkacheln (Foto des bestbewerteten Orts, Regionsfarbe, Anzahl).
  - In einer Region: Kategorie-Filter-Chips und Liste/Karte; `PhotoCard`s mit Status-Punkt.
- **Ortsdetail:**
  - `HeroHeader` (Foto), Bewertung, Status-Chip, Reihe `ActionCircle`s.
  - Tabs: **Info** (Beschreibung, Öffnungszeiten als kompakte Tabelle, Dauer), **Notiz**, **Dateien**.
  - Tags, Region und Kategorie über ✎ im `EditSheet`.
- **Logistik:** Tabs **Fahrten** und **Unterkünfte**.
  - Fahrten: nach Datum gruppiert; Karte mit `RouteMap`-Streifen, Strecke, Zeiten und 📎-Zahl.
  - Unterkünfte: `PhotoCard` groß mit Zeitraum und Stornofrist-Badge.
  - Ein Tipp öffnet eine **Detailseite** mit Buchungsdaten, Anhängen und Navigation. Bestätigungsnummer und Reisende stehen nur dort, nicht in den Listen.
- **Mehr:** Liste mit Icons; Einstellungen, Import und Konflikte als Karten-Abschnitte.

Unverändert bleiben die fünf Tabs unten, die Routen, ViewModels und Schreibpfade.

## 5. Karten: OpenStreetMap statt Google Maps

- **Bibliothek:** MapLibre Android SDK mit dem freien Stil von OpenFreeMap (`https://tiles.openfreemap.org/styles/liberty`), ohne Key.
- **Routenkarte:** Ein `MapSnapshotter` rendert sie einmal als Bitmap, mit Start/Ziel-Markern und Linie bzw. Bogen. Die Bitmap wird als PNG im App-Cache abgelegt; der Schlüssel ergibt sich aus den Koordinaten und der Größe. Danach ist sie offline verfügbar.
  - Ohne Netz und ohne Cache erscheint eine stilisierte Grafik: Verlauf, zwei Punkte und Linie.
  - Ohne Koordinaten gibt es keine Karte, nur die Textzeile.
- **Orte-Karte pro Region:** Sie läuft ebenfalls über MapLibre (interaktive `MapView`) und ersetzt Google Maps. Damit entfallen `maps-compose`, `play-services-maps` und `MAPS_API_KEY`, und der Umschalter „Liste | Karte“ ist immer da.
- **Lizenz:** Die Attribution „© OpenStreetMap“ ist sichtbar (MapLibre-Standard bzw. eine Textzeile im Snapshot).
- **Grenze:** Die Route ist eine Luftlinie zwischen den Endpunkten, nicht der echte Schienenverlauf, weil Wanderlog keine Geometrie liefert.

## 6. Tests und Abnahme

- **Bestehende UI-Tests** werden an die neuen Strukturen angepasst. Tabs und Bottom-Sheets kommen dazu. Die Test-Tags bleiben, wo möglich, erhalten.
- **Screenshot-Tests:** Roborazzi auf Robolectric rendert jeden Hauptscreen hell und dunkel, mit einer anonymisierten Fixture. Wenn `testdata/private` vorhanden ist, laufen sie zusätzlich mit echten Daten.
  - Die PNGs aus echten Daten bleiben lokal unter `build/` und werden nicht committet.
  - Karten und Netzbilder erscheinen in Robolectric als Platzhalter.
- **Abnahme:** Die Screenshots gehen an den Nutzer, bevor die APK gebaut wird.

## 7. Nicht im Umfang

- Sortieren per Ziehen; das ⋮-Menü bleibt.
- Echte Bahn-Geometrie.
- Neue Funktionen.
- Änderungen an Server oder Sync.
