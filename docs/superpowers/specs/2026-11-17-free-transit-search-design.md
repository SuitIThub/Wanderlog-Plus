# Freie ÖPNV-Suche – Design (kurz)

**Warum:** Die Verbindungssuche ist nur aus einem Weg, einer Fahrt oder dem Bearbeiten erreichbar. Man will einfach so
nach Verbindungen suchen und sie bei Bedarf speichern.

**Approaches:** (a) eigener Einstieg in die vorhandene `TransitScreen` mit Speichern-Dialog (gewählt); (b) die Suche in
den Bearbeiten-Modus der Logistik hängen (vom Nutzer als „overkill“ abgelehnt); (c) externe App öffnen (verliert das
Speichern).

**Design (vom Nutzer bestätigt):**
- Logistik, Reiter Fahrten: Button „Verbindung suchen“ oben neben „Hinzufügen“ → Suche leer, Datum/Uhrzeit jetzt.
- 📍 neben „Von“: aktueller Standort → Bahnhöfe in der Nähe wie bei einem Ort; fragt einmal nach der Freigabe.
- „Übernehmen“ fragt „Wohin speichern?“:
  - **In die Logistik:** als Fahrt (bei Umstiegen eine Reise mit Teilstrecken, Gleise/Zeiten/Nummern) – wie „Andere
    Fahrt“; danach öffnet sich die neue Fahrt.
  - **Als Weg in einem Tag:** Tag wählen (vorbelegt: Datum der Suche), dann den Programmpunkt, zu dem der Weg führt,
    oder „zur Unterkunft“; gespeichert wie „Verbindung abfragen“ an diesem Weg (ÖPNV + gewählte Verbindung).
- Ohne Übernehmen wird nichts gespeichert.

**Nicht-Ziele:** Verlauf/Favoriten/Heute-Karte (Paket 3), Romaji (Paket 4).

**Version:** 0.79.0.
