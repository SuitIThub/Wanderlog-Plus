# ÖPNV: Namensvettern sicher, Karte mit Zwischenhalten und Farben, frühere/spätere Verbindungen – Design

Stand: 2026-11-08. Version 0.58.0 (Server + App; Heimserver neu deployen).

## 1. Namensvettern (Server)

Befund (live): `日本橋` → Yahoo nimmt 日本橋(東京都); `日本橋(大阪府)` → richtig. `十日市町(広島県)` wird angenommen.
Die Präfektur-Korrektur griff nicht, weil der dauerhafte Bahnhofsspeicher (0.54.1) Einträge ohne Präfektur hat.

- Speicherschlüssel `gstations2:` / `gstation2:` (alte Einträge werden einmal neu geholt).
- Der Name geht immer als `<Name>(<Präfektur>)` an Yahoo, wenn die Präfektur bekannt ist; die Nachfrage bei anderer
  Präfektur bleibt als Ersatz.

## 2. Zwischenhalte mit Koordinaten (Server)

- `TransitStop` bekommt `lat`, `lng` (optional). `StationFinder.point(nameJa, lat, lng)`: Lage eines Halts nahe einem
  Punkt (Google: nur japanische Textsuche, Speicher `gpoint:` dauerhaft; höchstens 200 km vom Bezugspunkt; Photon: wie
  `locate`).
- Nach dem Abruf: alle Zwischenhalte aller Teilstrecken, je mit dem vorigen bekannten Punkt als Bezug, parallel (bis 8)
  verorten; unbekannte bleiben ohne Koordinaten.

## 3. Karte mit Teilstrecken (App)

- `RouteGeometry` bekommt `segments: List<RouteSegment>?` (`points`, `color`, `walk`) und `minorStops: List<LatLngD>`.
  Der Renderer zeichnet jede Teilstrecke in ihrer Farbe (Fußwege grau `#8A8A8A`, gestrichelt), Zwischenhalte als
  kleine Punkte (Radius 3, weiß umrandet), Ein-/Um-/Ausstiege groß. Render-Version erhöht.
- Farben der Fahrten der Reihe nach: `#D32F2F`, `#1565C0`, `#2E7D32`, `#6A1B9A`, `#EF6C00`, `#00838F`.
- `transitGeometry(route, fromPlace, toPlace, colorOffset)`: Hotel → (Fußweg) → Bahnhöfe mit Zwischenhalten je Fahrt
  → (Fußweg) → Ziel. Ohne Haltkoordinaten gerade Linien zwischen Ein- und Ausstieg.
- Genutzt für: Suchergebnis-Karte, Zug-Detailseite und Reise-Ansicht (Züge mit Verbindung; Farbe läuft über die
  Reise weiter), Wege im Tagesprogramm mit gewählter Verbindung.

## 4. Suchergebnisse: Karte, frühere/spätere

- „Karte“ (`transit-map-<i>`) klappt unter der Verbindung die Karte auf.
- „Frühere“ (`transit-earlier`): „Ankunft bis“ = früheste gezeigte Ankunft an der Zieltür − 1 Min.;
  „Spätere“ (`transit-later`): „Abfahrt um“ = früheste gezeigte Losgehzeit + 1 Min.; jeweils neue Suche.

## Tests

- Server: Name mit Präfektur an Yahoo; alter Speicher ohne Präfektur wird ignoriert; Halte bekommen Koordinaten mit
  Bezug auf den vorigen Halt; Halt > 200 km wird verworfen.
- App: Geometrie mit Segmenten/Farben/kleinen Halten/Fußwegen, Farbe läuft über eine Reise weiter, Cache-Schlüssel
  ändert sich mit Farben; Karte-Knopf; Frühere/Spätere setzen Zeit und Suchart.
