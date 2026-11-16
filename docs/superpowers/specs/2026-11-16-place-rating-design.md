# Bewerten-Modus für Orte (0.75.0)

## Wozu
Jeder Reisende soll alle offenen Orte schnell nacheinander bewerten können, ohne jeden Ort einzeln zu öffnen. Pro Ort ein kompakter Eindruck, unten die Vote-Buttons, dann sofort der nächste.

## Ziele
- Button „Bewerten (n)“ im Orte-Screen; n = Orte, die ich noch nicht bewertet habe. Bei n = 0 ausgeblendet.
- Durchlauf über alle Orte mit Status IDEA, SHORTLIST, PLANNED ohne eigene Stimme.
- Neue Stimme **Neutral** (Wert 0): zählt als bewertet, ändert weder Score noch Status.
- Entfernung zur Unterkunft zur schnellen Einordnung, bei Tagesausflügen zusätzlich vom Zentrum der Region.

## Nicht-Ziele
- Kein Zurückblättern zum vorigen Ort, kein Rückgängig im Modus (Korrektur über das Ort-Detail).
- Keine Server-Änderung (Stimmen mit Wert 0 werden schon akzeptiert und richtig verrechnet).
- Keine echte Fahrzeit, nur Luftlinie.

## Ansätze
Nur einer sinnvoll: reine App-Funktion im `places`-Paket, Logik als reine Funktionen (testbar), Stimmen über das bestehende `voteEdits`. Ein neues Datenfeld (z. B. `neutral`) oder ein eigener Wert für Neutral wurde verworfen: Ein Vote-Eintrag mit Wert 0 existiert bereits (zurückgezogene Stimme) und bedeutet inhaltlich dasselbe.

## Entscheidungen
- **Bewertet** = für mich existiert ein `Vote`-Eintrag (`Votes.id(place, me)`), egal welcher Wert. Folge: früher zurückgezogene Stimmen gelten als neutral (gewollt).
- **Reihenfolge:** Regionen in `regionTileOrder` (Hauptregion, dann ihre Tagesausflüge), Orte ohne bekannte Region zuletzt; innerhalb einer Region stabil (Name) – Builder-Entscheidung.
- **Karte:** wischbare Fotos, Name, Region + Kategorie, Google-Sterne/Tabelog, Kurzbeschreibung, KI-Einschätzung (`worth` + `worthReason`) falls vorhanden, Stimmen der anderen, Link „Mehr“ ins Ort-Detail, Fortschritt „12 / 87“.
- **Buttons:** 👎 · Neutral · 👍 · 👍👍, darunter „Überspringen“ (Ort bleibt offen, kommt beim nächsten Mal wieder). Nach einer Stimme sofort der nächste Ort. System-Zurück verlässt den Modus. Am Ende „Alles bewertet 🎉“.
- **Entfernung zur Unterkunft:** Unterkünfte (`Stay.regionId`) der Region des Orts; bei Tagesausflug-Regionen (`dayTripFromRegionId`) die der Ausgangsregion. Mehrere → die nächstgelegene. Anzeige „🏨 38 km zur Unterkunft (Osaka)“, unter 1 km in Metern. Keine Unterkunft → Zeile entfällt.
- **Entfernung vom Zentrum:** nur bei Tagesausflug-Regionen, Zentrum = `regionCentres` (Mittel der gespeicherten Orte). „4 km vom Zentrum Kyoto“.
- **VoteBar:** bekommt einen Neutral-Chip (ausgewählt, wenn mein Eintrag den Wert 0 hat). Erneutes Tippen auf die eigene Stimme setzt weiterhin auf 0 = neutral.
- Version 0.75.0 + CHANGELOG.

## Dem Builder überlassen
Layout-Details der Karte, Testtags, ob die Warteschlange beim Start eingefroren wird (empfohlen: ja, damit Sync-Updates nicht springen lassen).
