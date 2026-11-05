# Reisen aus Teilstrecken – Design

Datum: 2026-11-05 · Status: zur Freigabe

## Problem

„Mit nächster zusammenführen“ löscht heute die zweite Fahrt und macht ihren Start zum Zwischenhalt der ersten.
Dabei gehen Nummer, Airline/Gesellschaft, Abfahrtszeit am Umstieg, Gleis/Terminal und damit auch das Live-Tracking
der zweiten Strecke verloren. Die Detailansicht zeigt nur eine lange Fahrt mit Zwischenhalten.

## Entscheidungen (abgestimmt)

- Zusammenführen ist **nicht destruktiv**: Teilstrecken bleiben eigene Züge/Flüge mit allen Daten und werden zu
  einer **Reise** gruppiert. Flüge und Züge/ÖPNV dürfen gemischt werden.
- In der Logistik-Liste erscheint eine Reise als **eine Karte** mit den Teilstrecken als Zeilen und den Umstiegen.
- Früher zusammengeführte Einträge bekommen **„In Teilstrecken aufteilen“**.

## Datenmodell

`Flight` und `Train` bekommen `journeyId: String? = null` (am Ende der Klasse; synchronisiert, vom Import nicht
überschrieben). Eine Reise = alle Züge/Flüge mit derselben `journeyId`, sortiert nach Abfahrt (Datum + Zeit; ohne
Zeit nach Datum, dann id). Eine Reise mit nur noch einer Teilstrecke gilt als keine Reise (die id wird beim
Entfernen gelöscht). Keine neue Entity.

## Logik (client/shared, getestet)

- `journeys(snapshot)`: Reisen mit Teilstrecken in Reihenfolge, Gesamtstart/-ende, Gesamtdauer.
- `transfer(a, b)`: Umstiegszeit = Abfahrt b − Ankunft a (mit Datumswechsel), dazu:
  - Ort: gleicher Bahnhof/Flughafen (Name oder Code gleich, oder < 300 m) oder Ortswechsel mit Luftlinie.
  - Gleis/Terminal/Gate: Züge aus der übernommenen Verbindung (Ankunftsgleis der letzten Fahrt von a,
    Abfahrtsgleis der ersten von b); Flüge aus dem Live-Status, falls vorhanden (Terminal/Gate).
  - Hinweis „knapp“: unter 8 Min. zwischen Zügen, unter 60 Min. wenn ein Flug beteiligt ist; „überschneidet sich“
    bei negativer Zeit.
- Fußweg zwischen zwei verschiedenen Punkten (≥ 300 m, beide mit Koordinaten): Fuß-Route über den vorhandenen
  Routen-Dienst (Dauer, Entfernung); sonst nur die Luftlinie.
- `LocalEditor`:
  - `joinJourney(ids)`: alle bekommen dieselbe journeyId (die einer schon vorhandenen Reise darunter, sonst neu).
  - `leaveJourney(id)`: entfernt eine Teilstrecke aus der Reise (bleibt eine übrig, verliert auch sie die id).
  - `splitLegacy(type, id)`: eine alte Fahrt mit Zwischenhalten wird in Teilstrecken zerlegt (Start→Halt 1,
    Halt 1→Halt 2, …→Ziel), alle in einer neuen Reise. Zeiten am Halt: bekannte Zeit als Ankunft der Strecke davor;
    Abfahrtszeit der nächsten bleibt leer. Nummer, Buchung, Notiz, Anhänge bleiben an der ersten Strecke.
- Das bisherige `mergeMoves` entfällt in der Oberfläche (bleibt nur für Tests/Altlasten bestehen oder wird entfernt).

## Oberfläche

**Liste (Fahrten):** Reise-Karte: Symbol, Gesamtstrecke („Frankfurt → Osaka“), Start–Ende, darunter je Teilstrecke
eine Zeile (Symbol, Nummer, Von Zeit → Nach Zeit) und zwischen ihnen „Umstieg 2 Std. 10 Min.“ (rot bei knapp).
Tippen öffnet die Reise. Einzelne Fahrten wie bisher.

**Reise-Detail (neu):** Kopf mit Gesamtstrecke, Datum und Gesamtdauer; darunter eine kompakte Timeline:
- je Teilstrecke eine Karte: Typ, Nummer/Gesellschaft, Von → Nach mit Zeiten, Gleis/Terminal/Gate, eine Zeile
  Live-Status (Flug: AirLabs, Shinkansen: Cyberstation – je Teilstrecke unabhängig), Ticket-Chips; Tippen öffnet
  die Detailseite der Teilstrecke.
- zwischen zwei Karten der Umstieg: Wartezeit, „Gleis 14 → Gleis 2“ bzw. „Terminal 3 → Terminal 2 · Gate 64“,
  Fußweg („ca. 12 Min. zu Fuß, 850 m“) oder „gleicher Bahnhof“, Warnung bei knapp/überschneidend.
- „＋ Teilstrecke“ vor der ersten, zwischen zwei und nach der letzten: Auswahl **Zug/ÖPNV (mit Verbindungssuche)**,
  **Flug** oder **Vorhandene Fahrt hinzufügen**. Neue Strecken öffnen das bekannte Formular, vorausgefüllt (Start = Ziel
  der Strecke davor, Ziel = Start der Strecke danach, Datum/Zeit = Ankunft davor), und landen in der Reise; die
  ÖPNV-Suche startet mit diesen Punkten (z. B. Hotel → Flughafen vor einem Flug: Ankunft bis Abflug − Puffer).
- je Teilstrecke „Aus Reise entfernen“; „Reise auflösen“.

**Detail einer Teilstrecke:** Hinweis „Teil der Reise Frankfurt → Osaka (2/3)“ mit Link zur Reise.
**Zusammenführen (ersetzt „Mit nächster zusammenführen“):** „Zu einer Reise zusammenführen…“ öffnet eine Auswahl
der Fahrten/Flüge (nach Zeit sortiert, ±3 Tage zuerst, mit Häkchen); bestätigen gruppiert sie (eine schon
bestehende Reise wird erweitert).
**Alte Einträge:** hat ein Zug/Flug Zwischenhalte und gehört keiner Reise an, gibt es „In Teilstrecken aufteilen“.

**Heute:** „Nächste Fahrt“ und die Jetzt-Karte nennen bei einer Reise die folgende Teilstrecke und den Umstieg
(„Danach: NH 23 um 10:55 · Umstieg 2 Std. 10 Min. · Gleis/Gate …“).

## Nicht in v1

Eigener Titel für Reisen, Umbuchungsvorschläge bei verpassten Anschlüssen, automatische Fußweg-Beschreibung
innerhalb großer Bahnhöfe/Flughäfen (nur Gleis/Terminal und Weg zwischen verschiedenen Orten).

## Tests

- Logik: Gruppierung/Sortierung, Umstiegszeit über Mitternacht, knapp/überschneidend, Gleis/Terminal-Ermittlung,
  join (neu und Erweiterung), leave (Rest-Reise), splitLegacy (Segmente, Zeiten, Anhänge bleiben).
- Oberfläche: Reise-Karte in der Liste, Reise-Detail mit Umstiegen, Teilstrecke einfügen (vorausgefüllt, in der
  Reise), Auswahl-Dialog zum Zusammenführen, Aufteilen alter Einträge, Live-Zeile je Teilstrecke.

## Umsetzung

1. Modell + Logik + Editor mit Tests.
2. Liste und Reise-Detail.
3. Einfügen (Formular/ÖPNV vorausgefüllt), Zusammenführen-Dialog, Aufteilen, Heute-Widgets; Version, CHANGELOG.
