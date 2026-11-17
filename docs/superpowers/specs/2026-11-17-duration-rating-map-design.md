# Aufenthaltsdauer, Karte beim Bewerten, einklappbare Kartenliste – Design (kurz)

**Warum:** In der Tagesansicht soll man auf einen Blick sehen, wie lange man an einem Ort bleibt; beim Bewerten hilft die Lage
(Ort + Recherche-Punkte); in der Vollbild-Karte nimmt die Liste Platz weg.

**Approaches:** Nur ein sinnvoller Weg – alles baut auf Vorhandenem auf (Schätzer `DaySchedule`, `PlaceMapCard`,
`PlaceMapScreen`). Eigene neue Karten/Listen wären doppelte Arbeit.

**Design (vom Nutzer bestätigt):**
1. Programmkarte in der Zeitleiste: Untertitel bekommt die geplante Dauer angehängt („Sehenswürdigkeiten · 1 Std 30 Min“),
   Dauer = `end - start` des Schätzers (eigene Dauer oder typische). Format „45 Min“, „2 Std“, „1 Std 30 Min“. Notizen ohne.
2. Bewerten-Screen: `PlaceMapCard` (Ort + Recherche-Punkte) unter Beschreibung und KI-Text, vor „Andere: …“; Tippen öffnet
   die Vollbild-Ortskarte (`placeMapRoute`). Ohne Koordinaten keine Karte.
3. Vollbild-Ortskarte: Leiste „n Punkte ▾/▴“ über der Liste klappt sie ein/aus; eingeklappt bekommt die Karte den Platz.
   Gilt schmal (Liste unten) und breit (Liste rechts). Zustand pro Screen gemerkt (rememberSaveable), Start: offen.

**Nicht-Ziele:** keine Bearbeitung der Dauer an der Karte; keine neue Kartenansicht.

**Version:** 0.77.0 mit CHANGELOG-Abschnitt.
