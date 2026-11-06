# Changelog

Jede Version bekommt hier einen Abschnitt `## <versionName>`. Die GitHub-Action übernimmt ihn als Release-Text, und die
App zeigt ihn beim Update an. Fehlt der Abschnitt, stehen dort die Commit-Titel seit dem letzten Release.

## 0.40.0

- **Guides:** Allgemeine Reise-Guides wie Onsen-Knigge, Im Restaurant bestellen (mit Omakase, Izakaya, Ramen), Shinkansen, Tempel & Schreine oder Japanisch für unterwegs. Jeder Guide hat das Wichtigste vorn, Tun/Lassen-Listen und Sätze zum Zeigen auf Japanisch mit Lautschrift, zum Vorlesen und groß zum Hinhalten. Alles liegt offline auf dem Gerät.
- **Vorschläge aus der Reise:** Unter Mehr → Guides schlägt die App die Themen vor, die eure Orte, Unterkünfte und Fahrten berühren. Abhaken, „Ausgewählte erstellen“, fertig. Kommt später etwas Neues dazu, erscheint der passende Vorschlag. Abgelehnte Themen kommen nicht wieder.
- **Am richtigen Ort:** Orte, Unterkünfte, Fahrten und Tage zeigen ihre Guides als Chips (lange drücken: hier ausblenden, Plus: anheften). Auf der Heute-Seite steht „Für heute“ mit den Guides in der Reihenfolge des Tages und einer kurzen Vorschau.
- **Guides im Chat:** Der Chat kann Guides recherchieren, überarbeiten und zuordnen („häng den Omakase-Guide an das Sushi-Restaurant“, „zeig den Onsen-Guide bei allen Ryokans“).
- **Bessere Ortsrecherche:** Parallel sammelt jetzt nur Fakten mit Quellen, Claude Sonnet bewertet sie mit dem Reisekontext.
- Benötigt das Server-Update.

## 0.39.0

- **Stärkeres Modell für Chat und Einschätzungen:** Chat und „Orte einschätzen“ laufen jetzt auf Claude Sonnet 5.5 (`WLP_TEXT_MODEL`), Reels und Videos weiter auf Gemini Flash (`WLP_AI_MODEL`).
- **Chat kennt die ganze Reise:** Neben dem Reiseplan stehen jetzt alle hinterlegten Orte (mit Region, Einschätzung, reserviert, eingeplant am …) und alle Fahrten mit Nummern, Zeiten, Teilstrecken und gewählter Verbindung im Kontext. Details holt er weiter per Werkzeug.
- **Günstiger durch Prompt-Cache:** Der feste Teil des Kontexts ändert sich nur mit der Reise und wird zwischengespeichert; Datum, Wetter und der offene Bildschirm stehen dahinter.
- Benötigt das Server-Update.

## 0.38.0

- **Tagesablauf von Unterkunft zu Unterkunft:** Der Tag beginnt bei der Unterkunft der letzten Nacht und endet bei der Unterkunft der Nacht. Mit „Wege“ gibt es einen einstellbaren Weg zum ersten Programmpunkt und vom letzten zurück, mit Verkehrsmittel und „Verbindung abfragen“.
- **Fahrten an ihrer Uhrzeit:** Züge und Flüge stehen im Ablauf zwischen den Programmpunkten, mit Weg zum Abfahrtsbahnhof und vom Zielbahnhof weiter. Was zur Region der alten Unterkunft gehört, steht vor der Fahrt, was zur neuen gehört, danach; ohne Region entscheiden Uhrzeit und Lage.
- **Check-in und Check-out:** Unterkünfte haben „Check-in ab“ und „Check-out bis“. Der Abreisetag beginnt mit „Check-out bis 10:00“. Am Anreisetag steht „Check-in ab 15:00“ an der Unterkunft im Programm, sonst vor der Nacht, und „erst ab“, wenn sie früher geplant ist. Der Check-out steht auch unter „Fristen“ auf der Heute-Seite.
- **Warnung:** Reicht ein Weg zum Bahnhof nicht bis zur Abfahrt, sagt die Wege-Zeile das.

## 0.37.0

- **Chat-Umplanung räumt auf:** Ein neuer Tagesplan aus dem Chat ersetzt den ganzen Tag. Orte, die er weglässt, stehen auf der Plan-Karte als „Entfällt“ (abwählbar), statt still stehen zu bleiben. Notizen bleiben.
- **Keine doppelten Orte mehr:** Bevor der Chat einen Ort neu anlegt, prüft er Programm, Sammlung und Unterkünfte nach Name und Lage („Magome“ = „Magome-juku“). Ein schon geplanter Ort wird verschoben statt kopiert, eine Unterkunft nie als neuer Ort angelegt. Die App prüft das beim Übernehmen noch einmal.
- **Ort löschen:** Im ✎ eines Orts gibt es „Ort löschen“ (mit Rückfrage). Der Ort verschwindet aus der Sammlung und aus dem Programm aller Tage; ein Wanderlog-Import holt ihn nicht zurück.
- Benötigt das Server-Update.

## 0.36.1

- **Videozugriff nach dem Update:** Wenn die Fotos-Erlaubnis schon da war, fragt „Upload starten“ jetzt den Videozugriff an – sonst blieben Videos unsichtbar.
- **Pause gilt auch für Fotos:** „Uploads pausieren“ stoppt die Warteschlange vor dem nächsten Bild, nicht nur zwischen Video-Teilen.
- **Statuszeile:** „n ausstehend“ sinkt mit den Uploads, statt an der Zahl vom Scan-Start hängen zu bleiben.
- Benötigt das Server-Update.

## 0.36.0

- **Bilder-Upload für Videos:** Videos erscheinen als Vorschaubild; das volle Video lädt nur, wenn man es auf der Webseite anfordert. Der Upload geht in 8-MB-Stücken und lässt sich fortsetzen, ohne Größenlimit.
- **Web-Übersicht:** Tabs Fotos/Videos, Sortierung, Mehrfachlöschung, Wiedergabe, Geräte-Status und Pause/Fortsetzen.
- **Alle Fotos:** Der Upload nimmt jedes Bild mit, nicht nur vom alten Filter ausgewählte.
- Benötigt das Server-Update.

## 0.35.0

- **ÖPNV-Suche auch in Deutschland:** Liegen Start und Ziel nicht in Japan, sucht die Verbindungssuche über Transitous (offene Fahrplandaten) – Deutsche Bahn, üstra und der übrige Nah- und Fernverkehr in einer Auskunft, mit Gleis/Bahnsteig und Umstiegen, ohne Umschalten. Bahnhöfe gibt man mit ihrem Namen ein (z. B. „Hannover Kröpcke“). Fahrpläne gibt es dort meist erst einige Wochen im Voraus; vorher sagt die Suche das.
- **Routenvorschau im Reise-Detail:** unter dem Kopf die durchgehende Route aller Teilstrecken.
- Benötigt das Server-Update.

## 0.34.0

- **Teilstrecken sortieren:** Im Reise-Detail verschieben Pfeile eine Teilstrecke nach oben oder unten; die Reihenfolge wird gespeichert (ohne sie gilt die Abfahrtszeit). Eine über „＋ Teilstrecke“ eingefügte Strecke landet genau an der gewählten Stelle.
- **Routenvorschau für Reisen:** Die Reise-Karte in der Logistik zeigt wieder die Karte – als durchgehende Route über alle Teilstrecken.

## 0.33.0

- **Reisen aus Teilstrecken:** Zusammenführen löscht nichts mehr – Züge, Flüge und ÖPNV-Fahrten werden zu einer Reise gruppiert und behalten ihre eigenen Nummern, Zeiten, Gleise, Tickets und ihr eigenes Live-Tracking.
  - **Liste:** eine Karte pro Reise mit Gesamtstrecke und den Teilstrecken, dazwischen „Umstieg 45 Min.“ (rot, wenn knapp).
  - **Reise-Detail:** kompakte Timeline – pro Teilstrecke Nummer, Zeiten, Gleis/Terminal/Gate und Live-Status; dazwischen Umstiegszeit, „Gleis 8 → Gleis 9“ bzw. Terminal/Gate, „gleicher Bahnhof/Flughafen“ oder der Fußweg zwischen zwei Orten, Warnung bei knappen oder überschneidenden Umstiegen.
  - **„＋ Teilstrecke“** vor, zwischen und nach den Strecken: Zug/ÖPNV (Formular und Verbindungssuche vorausgefüllt – vor einem Flug mit „Ankunft bis 90 Min. vor Abflug“), Flug oder eine vorhandene Fahrt. Teilstrecken lassen sich aus der Reise entfernen, die Reise auflösen.
  - **„Zu Reise zusammenführen“** in jeder Fahrt: beliebige Fahrten und Flüge per Häkchen auswählen (zeitlich nächste zuerst).
  - Früher zusammengeführte Einträge: **„In Teilstrecken aufteilen“**.
  - „Nächste Fahrt“ auf der Heute-Seite nennt die folgende Teilstrecke und den Umstieg.

## 0.32.0

- **Eigene Titelbilder:** Bei Orten und Unterkünften öffnet das Bild-Symbol im Kopf „Titelbild“ – Wanderlog-Fotos, eigene Fotos oder Google-Fotos wählen, ein Foto hochladen oder zurück zum Standardbild. Das Bild erscheint im Kopf, in den Ortslisten, in der Logistik und als Tagesbild; es wird synchronisiert und vom Wanderlog-Import nicht überschrieben. Unterkünfte haben dafür jetzt auch einen Fotos-Bereich.
- **Eigene Links:** Orte, Unterkünfte, Züge, Flüge und Tage können Links bekommen (Name und URL, z. B. Tickets, Buchung, Infos). Sie stehen als Buttons neben Navigation, Website & Co.; „Link“ in der Reihe fügt sie hinzu oder ändert sie. Ohne „https://“ wird es ergänzt.

## 0.31.0

- **Live-Status für Shinkansen:** Für Züge mit Shinkansen-Nummer (z. B. „Nozomi 21“, auch „Nozomi 21 / Shinano 7“) zeigen Detailansicht und „Nächste Fahrt“, wo der Zug gerade ist und ob er pünktlich ist („Running between KYOTO and SHIN-OSAKA · Delayed 5 min“), dazu den Status der Linie. Abgefragt nur ab 1 Stunde vor Abfahrt bis kurz nach der Ankunft. Quelle: JR Cyberstation über den inoffiziellen Dienst shinkansen-live.vercel.app – kann sich ändern oder wegfallen. Andere Züge (Limited Express, Nahverkehr) haben keinen Live-Status.

## 0.30.0

- **Live-Status für Flüge** (AirLabs): In der Flug-Detailansicht und in „Nächste Fahrt“ stehen Status (planmäßig, in der Luft, gelandet, annulliert), Verspätung, geplante und erwartete Zeiten, Terminal, Gate und Gepäckband. Abgefragt wird erst ab etwa 24 Stunden vor Abflug, dann stündlich, ab 3 Stunden vorher alle 10 Minuten, in der Luft alle 20 Minuten; nach der Landung nicht mehr. Der Server speichert alles zwischen, damit das Monatskontingent reicht.
- Benötigt das Server-Update und `WLP_AIRLABS_KEY`.

## 0.29.0

- **Logistik verwalten:** „Hinzufügen“ in der Logistik legt einen Zug, einen Flug oder eine Unterkunft neu an.
- **Unterkünfte** lassen sich jetzt anlegen, bearbeiten und löschen: Ort über die Suche (mit Adresse und Position für Karte, Wege und Wetter), Region automatisch aus der Lage oder gewählt, Check-in/-out, Stornofrist und -bedingungen, Buchungsnummer, Erwachsene, Zimmer und Notiz.
- **Züge und Flüge:** Zugnummer (z. B. „Nozomi 21“) bzw. Flugnummer, Buchungsnummer und Notiz sind bearbeitbar.
- **ÖPNV-Suche in der Fahrt:** „Verbindung suchen“ im Zug öffnet die Fahrplanauskunft mit Start, Ziel und Zeit; „Übernehmen“ setzt Bahnhöfe, Zeiten, Umstiege, Linie und Zugnummer. Die Verbindung bleibt an der Fahrt gespeichert – Detailansicht, „Nächste Fahrt“ und die Jetzt-Karte zeigen damit das **Gleis**.

## 0.28.0

- **Fotos vom Ort:** Im Fotos-Tab eines Ortes steht über den eigenen Fotos eine Zeile mit Fotos des Ortes – sofort die Bilder aus Wanderlog, mit „Mehr von Google“ bis zu 10 weitere von Google Places (mit Nennung der Fotografen). Antippen öffnet die Fotos im Vollbild zum Durchwischen. Einmal geladene Google-Fotos bleiben auf dem Gerät und auf dem Server gespeichert.
- Benötigt das Server-Update; der Server nutzt den Google-Schlüssel der Routen (oder `WLP_GOOGLE_PLACES_KEY`), die Places API muss dafür freigegeben sein.

## 0.27.0

- **Heute als Dashboard:** Die Heute-Seite besteht jetzt aus kompakten Widgets, die nur erscheinen, wenn sie etwas zu zeigen haben.
  - **Tagesablauf:** eine große Karte für das, was gerade dran ist (oder als Nächstes kommt) – mit „bis ca.“, Öffnungsstatus („offen bis 18:00“, „schließt in 25 Min.“, „geschlossen – öffnet 10:00“), Weg, Tickets, „Reservierung offen“ und Route-Button, darunter „Danach: … · los in 20 Min.“. Darunter der ganze Tag in einzeiligen Zeilen (✓ erledigt, ~ geschätzte Zeiten, Wege, Öffnungszeiten), Züge und Flüge mittendrin, am Ende die Unterkunft mit Entfernung.
  - **Nächste Fahrt:** der nächste Zug oder Flug in den nächsten 36 Stunden mit Countdown und Tickets (steht die Fahrt gerade an, zeigt sie die große Karte).
  - **Wetter | Unterkunft** als Kacheln nebeneinander (Wetter antippen: Stunden), Regen-Hinweis darunter.
  - **Fristen:** offene Reservierungen und Stornofristen.
  - **Jetzt geöffnet:** klein ganz unten.
- Vor der Reise: Countdown, die erste Fahrt, sobald sie näher rückt, das Wetter zum Start und die Fristen.

## 0.26.2

- **Teilweise Rückzahlung:** Unter jedem Ausgleichsvorschlag gibt es „Teilbetrag“ (Formular mit dem noch offenen Betrag als Hinweis) und „Komplett bezahlt“ (trägt den vollen Betrag sofort ein). „Zahlung eintragen“ im Abschnitt Ausgleich geht auch ohne Vorschlag.

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
