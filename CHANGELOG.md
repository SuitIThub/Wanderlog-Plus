# Changelog

Jede Version bekommt hier einen Abschnitt `## <versionName>`. Die GitHub-Action übernimmt ihn als Release-Text, und die
App zeigt ihn beim Update an. Fehlt der Abschnitt, stehen dort die Commit-Titel seit dem letzten Release.

## 0.19.0

- **Fotogalerie** für jeden Ort und jeden Tag: neuer Tab „Fotos“ mit Vorschaubildern, „Fotos hinzufügen“ wählt mehrere Bilder auf einmal aus der Galerie. Ein Tipp öffnet die Vollbildansicht zum Durchwischen, dort lässt sich ein Foto auch löschen.
- Fotos werden vor dem Hochladen auf höchstens 2560 Pixel verkleinert und aufrecht gedreht; Metadaten wie der Aufnahmeort werden dabei entfernt. Sie synchronisieren wie Anhänge und sind danach auf allen Geräten da, auch offline.
- Fotos und Anhänge (Tickets, PDFs) bleiben getrennt.

## 0.18.1

- Ein Tag, der aus „Heute“ geöffnet wurde (Kopf oder Ziel antippen), gehört jetzt zum Reise-Tab: Der Tab „Heute“ führt wieder zurück.
- In der Tagesansicht öffnet ein Tipp auf einen Ort dessen Details; zurück geht es zum Tag. Orte, die nur im Programm stehen, werden dabei als „geplant“ in der nächsten Region gespeichert.

## 0.18.0

- **Zugänge verwalten** (Einstellungen → Server → „Zugänge verwalten (Admin)“): Mit dem Admin-Token des Servers (`WLP_ADMIN_TOKEN`) siehst du alle Zugänge, legst neue an und widerrufst sie. Jeder Zugang hat einen QR-Code, mit dem sich ein weiteres Gerät direkt einrichtet. Den Admin-Token gibst du einmal ein oder scannst ihn; er bleibt auf dem Gerät und wird nie zum Synchronisieren benutzt.

## 0.17.0

- **Heute simulieren** (nur im Debug-Modus, Einstellungen → Entwicklung): ein Datum und auf Wunsch eine Uhrzeit, die der Heute-Screen vorgibt, um ihn vor der Abreise zu testen. Eine Leiste über „Heute“ zeigt den simulierten Tag, schaltet einen Tag vor oder zurück oder die Simulation aus.
- **QR-Code anzeigen** (Einstellungen → Server, in jedem Profil): der Einrichtungs-Code mit Adresse und Token der aktuellen Verbindung, um ein weiteres Gerät einzurichten.

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
