# Changelog

Jede Version bekommt hier einen Abschnitt `## <versionName>`. Die GitHub-Action übernimmt ihn als Release-Text, und die
App zeigt ihn beim Update an. Fehlt der Abschnitt, stehen dort die Commit-Titel seit dem letzten Release.

## 0.16.0

- **Debug-Modus** (Einstellungen → Entwicklung): ein eigenes Profil mit eigenen Verbindungsdaten und eigenen lokalen Daten (Reise, Sync-Stand, Anhänge, Wege, Erinnerungen). Die normale Verbindung zum Heimserver bleibt unberührt; beim Zurückschalten ist alles wie vorher. Umschalten startet die App neu, im Debug-Modus zeigt ein rotes „DEBUG“ oben, mit welchem Server die App gerade spricht.

## 0.15.0

- **ÖPNV-Verbindungen für ganz Japan:** Wege mit „ÖPNV“ in der Tagesansicht zeigen jetzt echte Verbindungen aus dem japanischen Fahrplan (JR, Shinkansen, Privatbahnen, U-Bahn, Busse): Abfahrt und Ankunft, Dauer, Umstiege und den **Preis** mit Aufschlüsselung (Fahrpreis und Zuschläge). Aufgeklappt stehen jede Teilstrecke mit Linie, Richtung und Gleisen sowie die Fußwege zum und vom Bahnhof; die Karte führt über die Umstiegsbahnhöfe.
- **„Verbindung abfragen“:** ein eigener Screen, erreichbar über den Lupen-Button am ÖPNV-Weg in der Tagesansicht und über „Abfragen“ in der Tageskarte. Bahnhöfe (nahe Bahnhöfe zur Auswahl oder japanisch eingetippt), Datum, Uhrzeit, Abfahrt/Ankunft, Sortierung (schnellste, günstigste, wenig Umstiege) und Verkehrsmittel lassen sich anpassen. Die Suche nutzt aktuelle Daten. Eine Verbindung lässt sich für den Weg übernehmen und gilt dann auf allen Geräten.

## 0.14.0

- **Updates in der App:** Die App sucht selbst nach neuen Versionen. Gibt es eine, zeigt sie diese Liste an und bietet das Update an. Nach dem Bestätigen lädt sie es herunter und startet die Installation.
- Unter Einstellungen stehen die installierte Version und „Nach Updates suchen“.
- Neue Versionen werden automatisch auf GitHub gebaut und veröffentlicht.

## 0.13.4

- Sync-Button funktioniert wieder direkt nach einem fehlgeschlagenen Sync.

## 0.13.3

- Ortssuche findet wieder Orte, auch wenn die Karte weit herausgezoomt ist oder der Ortsdienst kurz hakt.

## 0.13.2

- Orte aus Reels verlieren seltener ihre Kartenposition; fehlt sie, lässt sie sich über „Position suchen“ setzen.

## 0.13.1

- Recherche- und Einschätzungsergebnisse schreibt der Server direkt in die Orte; die App synchronisiert nur noch.

## 0.13.0

- „Ablauf“ zeigt Reel-Auswertungen, Recherchen und Einschätzungen live, dazu die Liste „KI-Aufträge“.
