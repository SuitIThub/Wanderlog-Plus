# Changelog

Jede Version bekommt hier einen Abschnitt `## <versionName>`. Die GitHub-Action übernimmt ihn als Release-Text, und die
App zeigt ihn beim Update an. Fehlt der Abschnitt, stehen dort die Commit-Titel seit dem letzten Release.

## 0.26.1

- Behoben: Der Budget-Tab zeigte nur einen leeren Platzhalter statt der Übersicht.

## 0.26.0

- **Budget (neuer Tab):** Alle Kosten der Reise mit Gesamtsumme, Summen nach Kategorie, was beim Anbieter noch offen ist, und pro Person „bezahlt / Anteil / Saldo“. „Ausgleich“ schlägt vor, wer wem wie viel zahlt; „Als bezahlt eintragen“ hält die Zahlung fest. Liste aller Kosten mit Filter nach Kategorie und Person.
- **Kosten eintragen:** Betrag und Währung (mit Kurs zu Euro), Kategorie, Datum, wozu sie gehören, wer bezahlt hat und die Aufteilung – gleich, nach Prozent oder nach Beträgen – mit dem, was jede Person schon zurückgezahlt hat, und dem Status beim Anbieter (offen, angezahlt, bezahlt). Die Aufteilung wird geprüft (100 % bzw. voller Betrag).
- **Kosten an Unterkunft, Flug, Zug und Ort:** Jede Detailseite zeigt ihre Kosten mit Summe; „Kosten hinzufügen“ füllt Bezug, Kategorie und Datum vor.
- **Kurse:** Für Fremdwährungen schlägt die App den EZB-Kurs vor; wechselt eine Kosten auf „bezahlt“, wird der Kurs dieses Tages einmal übernommen. Ein von Hand eingetragener Kurs bleibt.
- **Personen:** Die Mitreisenden lassen sich im Budget verwalten (hinzufügen, umbenennen; wer an Kosten beteiligt ist, bleibt).
- **Aus Wanderlog übernommen:** Mitreisende, wer bezahlt hat, die Aufteilung, die Ausgleichszahlungen und „nicht bezahlt“ aus der Beschreibung als Status. Neue Kosten in Wanderlog kommen weiter dazu, Änderungen in der App haben Vorrang.
- Benötigt das Server-Update (Import und Kurse).

## 0.25.0

- **Wetter:** Für jeden Reisetag das Wetter an der Unterkunft – die Vorhersage des japanischen Wetterdienstes (JMA, 5-km-Modell für die nächsten Tage) über Open-Meteo, weiter voraus andere Modelle als Tendenz und ohne Vorhersage das Mittel der letzten 10 Jahre („Typisches Wetter“). Zu sehen im Tag (antippen für die Stunden), auf der Heute-Seite (heute und morgen, vor der Reise der erste Tag) und als Symbol mit Temperatur in der Tagesliste.
- **Regen-Hinweis:** Regnet es an einem Tag mit Wanderung, Garten, Aussichtspunkt & Co., warnt der Tag und „Umplanen“ öffnet den Chat. Der Chat kennt das Wetter aller Tage, schlägt bei Regen Orte für drinnen oder einen Tagestausch vor, und der Plan-Check meldet Regen bei Outdoor-Programm.
- **Reservierungen:** Eingeplante Orte, die laut Recherche („Buchbarkeit“, Hinweise), Tag oder Notiz eine Reservierung oder Tickets im Voraus brauchen, zeigen „Reservierung nötig“ mit einem Schalter „Reserviert“. Solange er aus ist, erinnert die App 30, 14, 7 und 2 Tage vor dem Tag – direkt in der Benachrichtigung lässt sich „Reserviert“ tippen. Offene Reservierungen stehen auch auf der Heute-Seite.
- Benötigt das Server-Update.

## 0.24.0

- **Verbindungen im Chat:** „Wann fährt der letzte Zug zurück?“, „Wie lange laufe ich vom Onsen zur Unterkunft?“ – der Chat fragt die Fahrplanauskunft (Abfahrt, Umstiege, Gleis, Preis) und Fuß-/Autorouten ab, statt Zeiten zu raten.
- **Plan-Check:** „Passt mein Programm morgen?“ – der Chat rechnet den Tag durch (Wege, Aufenthaltsdauer, Öffnungszeiten und Ruhetage, Zug- und Flugzeiten) und nennt Probleme mit Lösungsvorschlag.
- **Tag nach Wünschen planen:** „Plan mir den Kiso-Tag: Wanderung, danach Onsen, abends gut essen“ oder „Füll die Lücke 12–15 Uhr“ – der Chat zeigt eine Plan-Karte mit dem ganzen Ablauf. Jede Änderung hat ein Häkchen; neue Punkte sind als „Neu“ markiert, wegfallende rot, durchgestrichen und mit „Entfällt“. „Plan übernehmen“ schreibt die angehakten Änderungen, „Zurücksetzen“ macht es rückgängig.
- **Verlauf eines Tages:** Vor jedem übernommenen Plan (und jedem Wiederherstellen) speichert der Tag sein Programm. Unter „Verlauf“ im Programm lässt sich jede Version wiederherstellen (die letzten 10, auf allen Geräten).
- **Standort mitsenden:** Ein Schalter im Chat schickt deinen Standort mit („Was ist hier in der Nähe offen?“, „Wie komme ich von hier zurück?“). Die Berechtigung wird erst beim Einschalten abgefragt; der Standort wird nur für die jeweilige Antwort genutzt und nicht im Chat gespeichert.
- Benötigt das Server-Update.

## 0.23.0

- **Aus dem Chat einplanen:** Unter den Karten im Reise-Chat gibt es jetzt „Einplanen“. Ein Dialog fragt Tag und Stelle im Programm (am Anfang, nach einem Programmpunkt, am Ende); hat der Chat einen Tag oder eine Stelle vorgeschlagen, ist das vorausgewählt.
- **Webfunde zu Orten hinzufügen:** Findet der Chat im Web einen konkreten Ort, schlägt der Server dessen Lage nach; die Karte bekommt dann „Zu Orten“ (in die Region der nächsten Unterkunft, mit dem Link als Notiz) und „Einplanen“. Ist der Ort schon hinterlegt, steht dort „Hinterlegt“ statt eines Duplikats.
- Der Chat selbst ändert weiterhin nichts: geschrieben wird erst, wenn du tippst – offline möglich und synchronisiert wie jede andere Änderung. Benötigt das Server-Update.

## 0.22.1

- **Reise-Chat kennt mehr Kontext:** alle Unterkünfte mit Adresse, den ganzen Reiseplan (Übernachtung, Programm, Fahrten je Tag) und Entfernungen. Auf einem Tag sieht er Unterkunft, Programm und offene Vorschläge samt Entfernung zur Unterkunft; „in der Nähe“ sucht er im Umkreis statt in der ganzen Region (Server-Update nötig).
- Im Querformat mit zwei Spalten öffnet der Chat mit dem Tag bzw. Ort, der gerade rechts angezeigt wird, und es gibt nur noch ein Chat-Symbol.

## 0.21.0

- **Reise-Chat** mit KI: über das Chat-Symbol oben (im Bildkopf von Heute, Tag, Region, Ort und Buchung, auf den Tab-Startseiten in einer schmalen Leiste) öffnet sich der Chat. Frag nach Vorschlägen oder nenne Wünsche („Regentag“, „gutes Essen, nicht touristisch“): Er sucht in euren hinterlegten Orten, beachtet Region und Öffnungstage des Tages und zeigt seine Empfehlungen als Karten, die die Ortsdetails öffnen.
- Auf Wunsch sucht er im Web (Parallel) nach neuen Orten oder beantwortet allgemeine Fragen; Webfunde erscheinen als Karten mit Link und dem Hinweis „nicht hinterlegt“.
- Der Bildschirm, von dem aus du den Chat öffnest, geht als Kontext mit (z. B. „Kontext: Tag Fr 02.04. · Kyoto“); antippen entfernt ihn.
- Chats liegen auf dem Server: mehrere Chats, auf allen Geräten und für alle Mitreisenden sichtbar, mit Liste, „Neuer Chat“ und Löschen. Eine Antwort läuft auch weiter, wenn die App geschlossen wird. Benötigt `WLP_OPENROUTER_KEY` auf dem Server, für die Websuche zusätzlich `WLP_PARALLEL_KEY`.

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
