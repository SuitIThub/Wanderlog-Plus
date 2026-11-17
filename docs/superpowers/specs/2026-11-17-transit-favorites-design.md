# Verlauf und Favoriten der ÖPNV-Suche, „Schnell hin“ auf Heute – Design (kurz)

**Warum:** Häufige Wege (Hotel, Lieblingsorte, Pendelstrecken) sollen ohne Tippen gesucht werden können – in der Suche
und mit einem Tipp von „Heute“ aus.

**Approaches:** Favoriten (a) als eigener synchronisierter Datentyp (gewählt: kein Verlust, wenn beide gleichzeitig
anlegen; braucht Server-Neubau) oder (b) als Liste in einem Feld der Reise (kein Server-Neubau, aber
Last-Writer-Wins über die ganze Liste). Verlauf lokal pro Handy (vom Nutzer so gewünscht).

**Design (vom Nutzer bestätigt):**
- **Verlauf (pro Handy):** jede Suche der freien Suche merkt „Von → Nach“; „Zuletzt gesucht“ zeigt die letzten 10
  Routen (Antippen füllt beide Felder); ihre Orte erscheinen auch als Vorschläge beim Tippen in Von/Nach.
- **Favoriten (gemeinsam, synchronisiert):** zwei getrennte Arten – **Ziel** (ein Ort/Bahnhof) und **Route** (Von → Nach).
  ☆ an „Von“ und an „Nach“ merkt jeweils nur diesen Ort als Ziel; ☆ an der Routenzeile (aktuelle Route und Einträge
  in „Zuletzt gesucht“) merkt nur die Route. Nochmal antippen entfernt. In der Suche stehen Favoriten oben: Route füllt
  beide Felder, Ziel füllt „Nach“.
- **„Schnell hin“ auf Heute:** Favoriten, deren Ort (bei Routen: ein Ende) in der Region des Tages liegt (nächste Region;
  Tagesausflugs-Regionen zählen zu ihrer Ausgangsregion und umgekehrt). Ziel antippen → Suche mit Von = aktuelle
  Position, Nach = Ziel, jetzt, startet sofort; Route antippen → beide Enden, jetzt, startet sofort. Ohne passende
  Favoriten keine Karte.
- Nicht-Ziel: Favoriten umbenennen (Name kommt vom Ort bzw. „A → B“).

**Betrieb:** Neuer Entity-Typ → Server neu bauen, bevor die App 0.80.0 auf einem Handy läuft; beide Handys aktualisieren.

**Version:** 0.80.0.
