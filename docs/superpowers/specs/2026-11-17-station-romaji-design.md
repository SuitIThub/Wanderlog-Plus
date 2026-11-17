# Stationsnamen in Romaji – Design (kurz)

**Warum:** Bahnhöfe und Haltestellen stehen oft nur auf Japanisch da; in Romaji kann man sie auseinanderhalten und
aussprechen.

**Approaches:** (a) Umschrift auf dem Server mit Kuromoji (Japanisch-Wörterbuch, offline, kostenlos) – gewählt;
(b) KI-Umschrift (genauer bei seltenen Lesungen, kostet je Name und ist langsam); (c) nur die Romaji des Fahrplandienstes
(fehlt oft – heutiger Zustand).

**Design (vom Nutzer bestätigt):**
- Der Server füllt jeden leeren lateinischen Namen von Stationen, Halten und Bahnhofsvorschlägen aus dem japanischen
  Namen: Kuromoji-Lesung → Hepburn mit Längenstrichen (Kyōto, Tōkyō), Wortteile mit Bindestrich (Shin-Ōsaka).
  Vorhandene Namen (Fahrplandienst, OpenStreetMap) bleiben.
- Korrekturliste für Eigennamen mit besonderer Lesung (u. a. Kiso-Tal: Nagiso, Tsumago, Magome, Narai).
- Unlesbares (unbekannte Kanji) bleibt leer statt geraten; die App zeigt dann wie bisher nur Japanisch.
- App zeigt wie bisher „Romaji (日本語)“.
- Server muss neu gebaut werden; keine App-Änderung nötig außer der Version.

**Version:** 0.81.0.
