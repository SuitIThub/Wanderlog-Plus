# ÖPNV: Smarte Suche über mehrere Bahnhöfe – Design

Stand: 2026-11-08. Eine App-Version (0.57.0), Server-Änderung (Heimserver neu deployen). Nur Japan (Yahoo/Apify).

## Ziel

Bei einem Ort als Start oder Ziel (Hotel in Akasaka mit vielen Bahnhöfen in Gehweite) vergleicht der Server mehrere
Bahnhöfe und sortiert die Verbindungen nach einer Balance aus Fußweg, Fahrzeit und Umstiegen.

## Server

- `TransitRequest.smart: Boolean = false`. Gilt nur, wenn Start bzw. Ziel ein Punkt ohne Bahnhofsnamen ist; sonst wie bisher.
- Kandidaten: `finder.near(point)` bis 1500 m Luftlinie, am Start höchstens 4, am Ziel höchstens 2 (die nächsten).
  Paare nach Summe der Entfernungen, höchstens 6. Ohne Kandidaten: normale Suche.
- Fußweg je Bahnhof: Luftlinie × 1,3 / 80 m pro Minute, aufgerundet.
- Je Paar eine Suche mit den Bahnhofsnamen; „Abfahrt um“: Zeit + Fußweg zum Startbahnhof; „Ankunft bis“: Zeit −
  Fußweg vom Zielbahnhof. Die Paare laufen gleichzeitig (höchstens 6 Threads) über den normalen Weg (Zwischenspeicher,
  gemeinsame Ausführung, 10-Minuten-Fenster, Präfektur-Korrektur).
- Paare ohne Verbindung oder mit Fehler fallen weg; scheitern alle, gilt der erste Fehler.
- Jede Verbindung bekommt `accessMeters/accessMinutes`, `egressMeters/egressMinutes`, `doorMinutes` und `score`:
  - „Abfahrt um“: `doorMinutes` = Ankunft an der Zieltür − gewünschte Zeit; „Ankunft bis“: gewünschte Zeit −
    Abfahrt an der Starttür.
  - `score` = `doorMinutes` + 5 × Umstiege + (Fußweg gesamt / 2), gerundet.
- Doppelte Verbindungen (gleiche Zeiten und Linien) einmal; sortiert nach `score` (bei „Günstigste“ nach Preis, bei
  „Wenig Umstiege“ nach Umstiegen, jeweils dann nach `score`); die besten 5; die erste bekommt `best = true`.
- Ergebnis: Start-/Zielbahnhof und Fußwege der besten Verbindung; `smart = true`.

## App

- `TransitForm.smart = true`; geschickt nur, wenn ein Ort (Punkt ohne Bahnhof) Start oder Ziel ist.
- Schalter „Mehrere Bahnhöfe vergleichen (bis zu 6 Abfragen, ≈ 4 Cent)“ (`transit-smart`), sichtbar in diesem Fall.
- Verbindungskarte: Abzeichen „Beste Balance“ (`transit-best-<i>`), Zeilen „ab <Bahnhof> · N Min. zu Fuß“ /
  „an <Bahnhof> · N Min. zu Fuß“ und „Tür zu Tür N Min.“ (bei „Ankunft bis“: „losgehen N Min. vorher“).
- Übernehmen: die gewählte Verbindung mit ihren eigenen Bahnhöfen und Fußwegen (`chosenRoute(result, route)`).

## Tests

- Server: Kandidaten bis 1500 m, Zeitverschiebung je Startbahnhof, höchstens 6 Abfragen, Reihenfolge nach Score,
  `best` nur an der ersten, Paar ohne Verbindung fällt weg, alle scheitern → Fehler; „Ankunft bis“ zieht Zielweg ab.
- App: `smart` nur mit Ort; Karte zeigt Abzeichen und Fußwege; `chosenRoute` setzt Bahnhöfe und Fußwege.
