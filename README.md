# Wanderlog Plus

Begleit-App für Wanderlog mit eigenem Sync-Server: Reiseplan offline ansehen und bearbeiten, Änderungen zwischen Geräten synchronisieren, Wanderlog-Importe ohne Datenverlust zusammenführen.

## Module

- `shared` – Datenmodell, Sync-Logik (HLC, Feld-Merge) und Abfragen; reines Kotlin.
- `importer` – liest einen Wanderlog-Export und erzeugt daraus Records.
- `server` – Ktor-Sync-Server mit SQLite und Dateiablage (siehe `server/README.md`).
- `client` – Sync-Client, lokaler Editor und Trip-Snapshot (ohne Android-Abhängigkeit).
- `app` – Android-App (Jetpack Compose).

## App-Funktionen

Die Leiste hat **Heute**, **Reise**, **Orte**, **Logistik**, **Budget** und **Mehr**. Aufgeklappte Foldables und Tablets (ab 600 dp Breite) zeigen sie links; Reise, Logistik und Region Liste und Detail nebeneinander, die Tagesroute die Karte auf zwei Dritteln mit der Liste rechts (einklappbar), „Heute“ und die Ortssuche je zwei Spalten. Der Chat öffnet sich von fast jedem Bildschirm über das Chat-Symbol. Reiseplan, Anhänge und Fotos liegen lokal und sind offline nutzbar; Änderungen synchronisieren sich zwischen den Geräten.

### Heute

- Vor der Reise: Countdown, die erste Fahrt sobald sie näher rückt, Wetter zum Start und die Fristen. Nach der Reise: „Willkommen zurück“.
- Während der Reise ein Dashboard, das nur Karten zeigt, die etwas zu sagen haben. Der aktuelle bzw. nächste Programmpunkt (mit „bis ca.“, Öffnungsstatus, Weg, Tickets und „Reservierung offen“), darunter der restliche Tag in Zeilen. **Nächste Fahrt** bzw. **Unterwegs** mit Countdown, Gleis und Tickets. Wetter und Unterkunft nebeneinander, dazu ein Regen-Hinweis. **Jetzt geöffnet** steht unten.
- **Verpasst? Spätere Fahrt** an der nächsten bzw. laufenden Zugfahrt.
- **Für heute**: die Guides des Tages in Programmreihenfolge, mit kurzer Vorschau.
- **Fristen** der nächsten 30 Tage, „Jetzt reservierbar“ und überfällige Fristen. Ein Tipp öffnet Ort, Unterkunft, Zug oder Flug. Fällt ein geplanter Ort unter 2 Stimmen, erscheint „Kaum noch Zustimmung“.

### Reise und Tagesprogramm

- Tagesliste der ganzen Reise, pro Tag Region, Unterkunft und ein Wettersymbol mit Temperatur.
- Tabs **Programm**, **Vorschläge** und **Notizen & Anhänge**. Tagestitel, Tagesnotiz und die Region eines Tages lassen sich überschreiben; Notizen im Programm ebenfalls.
- Der Ablauf läuft von Unterkunft zu Unterkunft. Check-in und Check-out stehen mit Uhrzeit im Programm, Züge und Flüge an ihrer Uhrzeit, mit Weg zum Bahnhof und vom Zielbahnhof weiter. Reicht der Weg nicht bis zur Abfahrt, sagt die Wege-Zeile das.
- **Wege**: Wegzeit und Strecke zwischen den Zielen, Verkehrsmittel pro Abschnitt, die Route auf der Karte. Bei „ÖPNV“ eine echte Verbindung (siehe unten). Braucht einen Google-Routes-Key auf dem Server (siehe `server/README.md`).
- **Karte**: alle Orte des Tages nummeriert auf einer Vollbildkarte, verbunden über die gewählte Route, gestrichelt wo es keine gibt, mit Gesamtzeit und -strecke.
- Ein ＋ zwischen den Programmpunkten fügt an genau dieser Stelle einen Ort aus den Vorschlägen, einen gesuchten Ort, eine Unterkunft (z. B. als Start oder Ziel) oder eine Notiz ein. Die Ortssuche hat Suchfeld und Karte: zuerst im sichtbaren Ausschnitt, sonst die Umgebung; nach dem Verschieben „In diesem Bereich suchen“; langes Drücken wählt einen beliebigen Punkt. Gefundene Orte landen als geplante Orte in der Region des Tages.
- Vorschläge sind Ideen und vorgemerkte Orte der Tagesregion und ihrer Tagesausflüge, die noch keinem Tag zugeordnet sind.
- **Verlauf**: vor jedem übernommenen Plan speichert der Tag sein Programm. Die letzten 10 Stände lassen sich wiederherstellen, auf allen Geräten.
- Ein Tipp auf einen Ort öffnet seine Details; Orte, die nur im Programm stehen, werden dabei als geplant in der nächsten Region gespeichert.

### Orte

- Regionen mit Umschalter Liste | Karte. Bearbeitbar sind Notiz, Status, Tags (⭐/⚠️), Region und Kategorie. Status: Idee, Vorgemerkt, Geplant, Besucht, Verworfen.
- **Abstimmen**: jeder Zugang gibt 👍 (+1), 👍👍 (+2, „will ich unbedingt“) oder 👎 (−2); nochmal tippen nimmt die Stimme zurück. Ab 2 Punkten wird aus einer Idee „Vorgemerkt“, darunter wieder eine Idee. Die Punktzahl steht in der Liste, die Namen unter dem Ort.
- **Ort suchen** fragt Google Places über den Server (Öffnungszeiten, Bewertung und Google-Fotos kommen mit). Ohne Google-Key oder wenn der Server nicht erreichbar ist, sucht die App über OpenStreetMap. Neue Orte landen in der nächstgelegenen Region.
- **Google-Eintrag** nachträglich suchen, verknüpfen, die Lage übernehmen oder lösen. In der Regionsliste markiert ein Symbol Orte ohne Verknüpfung.
- Im Ort: kleine Karte, per Tipp oder Vollbild groß (aufgeklappt mit Liste daneben). Der Ort ist ein Pin; Punkte aus der Recherche (weitere Eingänge, Aussichtspunkte, versteckte Ecken, Bahnhof, weitere Filialen) sind runde Symbole und stehen im Tab „Recherche“ unter „Punkte vor Ort“.
- Tabs **Einschätzung** (Gesamtfazit, Punkte je Kriterium: Muss man sehen, Lohnt sich, Wenn Zeit ist, Eher auslassen) und **Recherche** (Geheimtipps, versteckte Ecken, Gut zu wissen, Tipps, Hinweise, Quellen, ob eine Reservierung nötig, empfohlen oder nicht nötig ist).
- **Recherchieren (KI)** für einen Ort, für eine Region oder aus der Übersicht für mehrere Regionen. Dort wählt man die Regionen und „Nur unrecherchierte“ oder „Alle“, mit Anzahl und Kostenhinweis. Die Regionen laufen nacheinander; verworfene Orte bleiben außen vor. „Fristen ändern“ am Ort bleibt bei einer neuen Recherche stehen.
- **Ort löschen** nimmt ihn aus der Sammlung und aus allen Tagen; ein Wanderlog-Import holt ihn nicht zurück. Auf der Detailseite wischt man das Titelbild zum vorigen oder nächsten Ort derselben Region.
- Links: Navigation und ÖPNV (Google Maps), Website, Anrufen, Tabelog (Essen und Nachtleben), dazu eigene Links.
- **Tabelog** für Restaurants und Bars: Der Server sucht jeden gespeicherten Ort der Kategorie Essen oder Nachtleben bei Tabelog (über Apify, japanischer Name über Google) – einmal für den Bestand, danach automatisch für neue Orte, höchstens 20 je Viertelstunde. Treffer nur, wenn der Eintrag höchstens 300 m entfernt liegt und der Name passt. Am Ort steht eine Karte mit Score, Anzahl Bewertungen, Budget mittags/abends, Genre, Reservierung, Öffnungszeiten und einer deutschen Zusammenfassung der neuesten Bewertungen; „In Tabelog öffnen“ versucht zuerst die mehrsprachige Tabelog-App, sonst die englische Seite im Browser. Falscher oder fehlender Treffer: „Tabelog-Link setzen“. In der Regionsliste zeigt ein Chip den Score („★ 3,7“). Kosten bei Apify etwa 1,5–2 Cent pro Restaurant.

### Reels (KI)

- Ein Instagram-Reel an die App teilen; ein kleines Popup bestätigt, man bleibt in Instagram. Der Server erkennt die Orte in Bild, Ton und Beschreibung, trennt das eigentliche Ziel von Bahnhöfen, Vierteln und Kulisse („Nur erwähnt“) und von weiteren Filialen, prüft die Lage auf der Route und recherchiert (Parallel, OpenRouter). Eine Benachrichtigung führt zur Vorschau, dort übernimmt man die gewünschten Orte.
- Auch Posts mit einem Bild oder einer Bildergalerie gehen (gallery-dl auf dem Server); Text auf den Bildern wird mitgelesen.
- **Tipps:** Allgemeine Tipps im Beitrag (Etikette, Verkehr, Geld, Apps …) werden mit den Guides abgeglichen. Neue prüft der Server auf Wahrheit (stimmt / übertrieben – korrigiert / falsch / nicht belegt, mit Begründung und Quellen). In der Vorschau wählt man sie aus und bestimmt den Guide; übernommen werden sie in einen bestehenden Guide eingearbeitet oder als neuer Guide geschrieben.
- Ist ein Ort schon gespeichert (gleicher Google-Eintrag oder ähnlicher Name in höchstens 300 m), wird die Recherche übersprungen. „Trotzdem importieren“ übernimmt ihn doch als eigenen Ort. Fehlt die Position, setzt „Position suchen“ sie. Die Liste aller Auswertungen steht unter Orte → Reels und unter Mehr → Reels auswerten.

### Logistik

- Flüge, Züge, ÖPNV und Unterkünfte anlegen, bearbeiten und löschen: Nummer, Buchungsnummer, Notiz, Reisende, Stornofrist und -bedingungen, „Reservierbar ab“, „Reservieren bis“ und „Reservierung erledigt“. Unterkünfte zusätzlich Ort (Suche mit Adresse und Position), Region, Check-in und Check-out, Erwachsene und Zimmer.
- **Reisen aus Teilstrecken**: Züge, Flüge und ÖPNV-Fahrten gruppieren, Reihenfolge mit Pfeilen ändern, „＋ Teilstrecke“ davor, dazwischen oder danach, eine Strecke herausnehmen oder die Reise auflösen. Die Liste zeigt eine Karte pro Reise mit Umstiegszeit (rot, wenn knapp); das Detail eine Timeline mit Gleis, Terminal und Gate, „Gleis 8 → Gleis 9“ und einer Warnung bei knappen oder überschneidenden Umstiegen. Jede Teilstrecke behält Nummer, Ticket und Live-Status. „Zu Reise zusammenführen“ wählt die Fahrten per Häkchen; früher zusammengeführte Einträge lassen sich in Teilstrecken aufteilen. Zwischenhalte wählst du per Ortssuche, sie erscheinen auf der Routenkarte.
- **Klasse und Sitzplätze** (Wagen, Platz, Person) in den Details, auf „Nächste Fahrt“ und im Chat. Für den ICE 4 sowie die A350-900 und A321neo von China Airlines und die A321neo und 787-9 von ANA zeichnet die App den Sitzplan und hebt eure Plätze hervor.
- **Live-Status für Flüge** (AirLabs, ab etwa 24 Stunden vor Abflug): Status, Verspätung, Terminal, Gate und Gepäckband, in der Detailansicht und auf „Nächste Fahrt“.
- **Live-Status für Shinkansen** (ab einer Stunde vor Abfahrt, nur Züge mit Shinkansen-Nummer): wo der Zug gerade ist, ob er pünktlich ist, und der Status der Linie.
- **Zimmer und Ausstattung** einer Unterkunft: Reiter Übersicht, Zimmer & Ausstattung und Fotos. „Auf einen Blick“ (Zimmertyp, Größe, Betten, Bad, Aussicht, Personen, Verpflegung) und die Ausstattung nach „Im Zimmer“ und „In der Unterkunft“. „Zimmer & Ausstattung recherchieren“ liest Buchungsbestätigungen, Links und das Web; Namen und Buchungsnummern werden nie übernommen. Was du von Hand änderst, bleibt; „Alles neu recherchieren“ ersetzt auch das. Fotos lassen sich als Zimmerfoto markieren.
- Die Kosten einer Buchung stehen auf ihrer Detailseite.

### ÖPNV-Suche

- In Japan echte Verbindungen (JR, Shinkansen, Privatbahnen, U-Bahn, Bus, Straßenbahn Hiroshima) mit Abfahrt, Ankunft, Dauer, Umstiegen, Gleisen und Preis samt Aufschlüsselung (Yahoo! 乗換案内 über einen Apify-Actor). Außerhalb Japans über Transitous (offene Fahrpläne, u. a. Deutsche Bahn); Fahrpläne gibt es dort meist erst einige Wochen im Voraus.
- Erreichbar aus einem ÖPNV-Weg im Tagesprogramm, aus „Hinzufügen“ in der Logistik und aus einer Fahrt („Verbindung suchen“, „Andere Fahrt suchen“, „Verbindung ändern“). Bahnhöfe, Orte, Hotels und Programmpunkte als Start und Ziel, nahe Bahnhöfe zur Auswahl, Datum und Uhrzeit per Kalender und 24-Stunden-Wahl, Abfahrt oder Ankunft, Sortierung (schnellste, günstigste, wenig Umstiege) und Verkehrsmittel.
- **Mehrere Bahnhöfe vergleichen** (abschaltbar): bis zu 4 Bahnhöfe in Gehweite am Start und 2 am Ziel, Fußweg eingerechnet, sortiert nach Tür-zu-Tür-Zeit, Umstiegen und Gehzeit; die beste trägt „Beste Balance“.
- „‹ Frühere“ und „Spätere ›“ unter den Ergebnissen. Reißt die erste Suche ab, fragt die App selbst noch einmal.
- **Start und Ziel vor und nach den Bahnhöfen**, z. B. das Hotel vor dem ersten und das Ryokan nach dem letzten, mit Fußweg in der Anzeige.
- **Andere Fahrt suchen** ersetzt Linie, Zeiten, Gleise und Haltestellen; Buchungsnummer, Notiz und Sitzplätze bleiben. **Verbindung ändern** an einer Zugreise ersetzt alle Teilstrecken (Reisen mit einem Flug nicht). Passten die Sitze zur alten Fahrt, steht das dabei.
- Jede Verbindung hat **Karte**: Teilstrecken in eigener Farbe durch alle Zwischenhalte, Ein-, Um- und Ausstieg als große Punkte, Fußwege gestrichelt. Die Zugseite listet die Zwischenhalte mit Uhrzeit.
- Eine Verbindung mit Umstiegen wird beim Speichern aus der Logistik eine Reise, jede Fahrt eine Teilstrecke. Im Tagesprogramm bleibt sie am Weg. Die übernommene Verbindung gilt auf allen Geräten, inklusive Gleis auf „Nächste Fahrt“.

### Budget

- Eigener Tab: Gesamtsumme in Euro, was beim Anbieter noch offen ist, Summen nach Kategorie, pro Person bezahlt, Anteil und Saldo („bekommt“ / „schuldet“ / „ausgeglichen“).
- **Ausgleich** schlägt vor, wer wem wie viel zahlt. „Als bezahlt eintragen“, „Teilbetrag“ oder „Komplett bezahlt“ hält die Zahlung fest; „Zahlung eintragen“ geht auch ohne Vorschlag.
- **Kosten hinzufügen**: Betrag und Währung, Kategorie, Datum, Bezug (Unterkunft, Flug, Zug, Ort oder frei), wer bezahlt hat, Aufteilung gleich, nach Prozent oder nach Beträgen, schon zurückgezahlt, Status offen, angezahlt oder bezahlt. Die Aufteilung wird geprüft.
- Für Fremdwährungen schlägt die App den EZB-Kurs vor (Euro pro Einheit). Wechselt eine Kosten auf „bezahlt“, wird der Kurs dieses Tages einmal übernommen; ein von Hand gesetzter Kurs bleibt. Kosten ohne Kurs zählen nicht in die Summe.
- **Personen** hinzufügen und umbenennen. Mitreisende, Zahler, Aufteilung, Ausgleichszahlungen und offene Kosten kommen aus Wanderlog; Änderungen in der App haben Vorrang, neue Wanderlog-Kosten kommen dazu.

### Chat

- Über das Chat-Symbol. Er kennt den Reiseplan, alle Orte (Region, Einschätzung, Status, eingeplant am …), Fahrten, Unterkünfte mit Zimmer und Ausstattung, das Wetter und den Bildschirm, von dem aus du ihn öffnest (der Kontext-Chip lässt sich entfernen). Mehrere Chats liegen auf dem Server, für alle Mitreisenden sichtbar, mit Liste, „Neuer Chat“ und Löschen. Eine Antwort läuft weiter, wenn die App zu ist.
- **@** vor einem Namen: Vorauswahl von Orten, Tagen, Unterkünften, Zügen, Flügen, Guides und Regionen. Das Gewählte geht als genaue Referenz mit und steht unter der Nachricht als Chip.
- Karten zu hinterlegten Orten und zu Webfunden. „Einplanen“ fragt Tag und Stelle; „Zu Orten“ legt einen Webfund an (ist er schon da, steht „Hinterlegt“). Ein Tagesplan aus dem Chat zeigt jede Änderung mit Häkchen, Neue als „Neu“, Wegfallende als „Entfällt“; „Plan übernehmen“ schreibt nur das Angehakte. Der Plan-Check rechnet Wege, Aufenthaltsdauer, Öffnungszeiten, Ruhetage und Fahrten durch.
- Er fragt die Fahrplanauskunft und Fuß- bzw. Autorouten ab, statt Zeiten zu raten. Ein Schalter schickt den Standort nur für die jeweilige Antwort mit und speichert ihn nicht. Regen und „Umplanen“ am Tag öffnen den Chat mit diesem Kontext.
- Guides kann er recherchieren, überarbeiten und zuordnen; unter der Antwort steht dann eine Karte mit dem Stand des Auftrags. Geschrieben wird erst, wenn du übernimmst.

### Guides

- Unter Mehr → Guides: Themen wie Japanisch für unterwegs, Onsen, Im Restaurant bestellen (mit Omakase, Izakaya, Ramen), Shinkansen, Tempel & Schreine. Die App schlägt vor, was Orte, Unterkünfte und Fahrten berühren; abhaken und „Ausgewählte erstellen“. Abgelehnte Themen kommen nicht wieder. Alles liegt offline auf dem Gerät.
- Jeder Guide hat das Wichtigste vorn, Tun- und Lassen-Listen und Sätze auf Japanisch mit Lautschrift, zum Vorlesen und groß zum Hinhalten. Unten steht „Zuletzt recherchiert“.
- Chips an Orten, Unterkünften, Fahrten und Tagen (lange drücken blendet ihn hier aus, Plus heftet ihn an). Auf der Heute-Seite steht „Für heute“.
- **Apps & Links:** Erwähnt ein Guide eine App oder Webseite, steht sie als Chip im Guide. Eine installierte App öffnet sich direkt, sonst ihr Play-Store-Eintrag; Webseiten im Browser. Der Server prüft jeden Link (Seite erreichbar, Play-Store-Eintrag vorhanden). „Links ergänzen“ (Menü der Guide-Übersicht) trägt sie für bestehende Guides nach.

### Guthaben & Limits

- Unter Mehr → Guthaben & Limits: Guthaben und Kontingente der bezahlten Dienste, live vom Server abgefragt (Keys bleiben auf dem Server): OpenRouter, Apify (Monatsbudget), AirLabs (Abfragen, Ablauf des Keys). Parallel nur als Link aufs Dashboard; für Google zählt der Server seine eigenen Aufrufe pro Monat (ohne Karten und Aufrufe direkt aus der App), dazu ein Link zur Cloud Console. Markiert ab 80 % Verbrauch (OpenRouter: unter 5 $ übrig) oder 14 Tage vor Ablauf.

### KI-Kriterien und Ablauf

- Unter Mehr → KI-Kriterien wählt man, wonach die KI bewertet (18 Kriterien wie Andrang, Authentisch statt touristisch, Buchbarkeit, Wartezeiten, Preis-Leistung, Reisezeit Mai …), und kann eigene Anweisungen ergänzen. Jedes Kriterium bekommt 1–5 Punkte, dazu ein Gesamtfazit. Die Kriterien gelten für Einschätzung, Recherche und Reels.
- Reel-Auswertungen, Recherchen, Einschätzungen und Guides haben einen Ablauf-Screen: Status, Laufzeit, Fortschritt, jeder Ort mit seinem aktuellen Schritt (Lage, Websuche mit Quellen, Recherche-Auftrag, Punkte vor Ort, Ergebnis) und das ganze Protokoll. Erreichbar aus der Reel-Auswertung, bei der Recherche im Ort und in der Region und unter Mehr → KI-Aufträge. Dort stehen auch Aufträge, die der Chat gestartet hat.

### Fotos, Anhänge und Links

- Fotogalerie an Orten, Tagen und Unterkünften: Vorschaubilder, Vollbild zum Durchwischen, Löschen. „Fotos hinzufügen“ fragt „Aus Galerie“ oder „Online suchen“ (Google und Wikimedia Commons, mit Ortsname und Region vorbefüllt, mehrere auswählbar). Die Quelle steht im Dateinamen. „Titelbild ändern“ nimmt ein Wanderlog-Foto, ein eigenes, ein Google-Foto, einen Upload oder ein Foto aus der Online-Suche; das Titelbild wird synchronisiert und vom Wanderlog-Import nicht überschrieben.
- Über den eigenen Fotos eines Orts die Bilder aus Wanderlog, mit „Mehr von Google“ weitere von Google Places. Einmal geladen bleiben sie auf dem Gerät.
- Fotos werden vor dem Hochladen auf höchstens 2560 Pixel verkleinert; Aufnahmeort und andere Metadaten fallen dabei weg. Sie synchronisieren wie Anhänge und sind danach offline da. Fotos und Anhänge bleiben getrennt.
- Anhänge (Tickets, Buchungsbestätigungen, PDFs) an Flügen, Zügen, Unterkünften, Orten und Tagen, höchstens 25 MB. Bilder und PDFs zeigt die App, alles andere eine externe App. Alle Anhänge lädt sie im Hintergrund herunter. Eine Datei aus einer anderen App teilen, Ziel wählen, angehängt.
- Eigene Links (Name und URL) an Orten, Unterkünften, Zügen, Flügen und Tagen, als Buttons neben Navigation, Website und Telefon.

### Notizen, Import und Sync

- Mehr → Notizen: freie Notizen in Markdown, unabhängig vom Tagesprogramm.
- Wanderlog-Import und erneuter Import. Eigene Änderungen bleiben; wo beide Seiten denselben Wert geändert haben, entsteht ein Konflikt. Unter Mehr → Konflikte löst man ihn mit „App behalten“ oder „Wanderlog übernehmen“. Was in der App angelegt wurde, fasst der Import nicht an.
- Sync feldweise (die letzte Änderung gewinnt), beim Start, kurz nach einer lokalen Änderung, im Hintergrund und per Ziehen zum Aktualisieren. Einstellungen zeigen den letzten Sync, wartende Änderungen und den Fehler, dazu „Jetzt synchronisieren“ und „Sync-Zustand zurücksetzen“.
- Einrichtung per QR-Code oder Link `wlp://setup?url=…&token=…` (siehe `server/README.md`). In den Einstellungen zeigt „QR-Code anzeigen“ die aktuelle Verbindung. „Zugänge verwalten (Admin)“ legt mit dem Admin-Token Zugänge an, widerruft sie und zeigt jedem Zugang einen eigenen QR-Code.
- Release-Builds suchen höchstens alle 6 Stunden beim Start nach einer neuen Version und zeigen das Changelog. Unter Einstellungen geht das auch von Hand. Nach dem Bestätigen lädt die App die APK, prüft Größe und SHA-256 und übergibt sie an den Android-Installer.

### Einstellungen

- **Zeitzone**: „Heute“ und „Jetzt geöffnet“ rechnen standardmäßig in japanischer Zeit (JST), umstellbar auf die Gerätezeit.
- **Erinnerungen** an Stornofristen (7, 3 und 1 Tag vorher sowie am Tag selbst) und an Reservierungen („Jetzt reservierbar“ und 30, 14, 7 und 2 Tage vor „reservieren bis“), mit „Erledigt“ bzw. „Reserviert“ in der Benachrichtigung. Ideen bekommen keine Erinnerung; Ideen mit naher Frist stehen eingeklappt unter „n Ideen mit Fristen“. Abschaltbar; ist die Benachrichtigungserlaubnis aus, sagt die Einstellung das.
- **Kartenbilder neu laden** verwirft die gespeicherten Routenkarten, damit sie neu gezeichnet werden.
- **Debug-Modus**: eigenes Profil mit eigenen Verbindungsdaten und eigenen lokalen Daten; die normale Verbindung bleibt unberührt, Umschalten startet die App neu. Nur dort lässt sich „Heute“ auf ein Datum und eine Uhrzeit stellen.

## Bauen und testen

```
./gradlew test
./gradlew :app:assembleDebug
./gradlew :app:installDebug   # mit angeschlossenem Gerät
```

## Screenshots erzeugen

```
./gradlew :app:testDebugUnitTest --tests '*Screenshot*'
```

Die Screenshot-Tests (Roborazzi auf Robolectric) rendern jeden Hauptscreen hell und dunkel, auch mit langen Texten, und legen die PNGs nach `app/build/outputs/roborazzi/`. Referenzbilder werden nicht committet. Unter Robolectric erscheinen keine Emoji, Fotos und Karten zeigen ihre Ersatzgrafik.

Liegt der echte Export in `testdata/private/`, entstehen zusätzlich Screenshots mit echten Daten. Diese enthalten Buchungsdaten (Bestätigungsnummern, Reisende) und werden nicht geteilt; sie bleiben unter `app/build/`.

## Karte

Karten zeigen OpenStreetMap-Daten über [MapLibre](https://maplibre.org/) mit dem freien Stil von [OpenFreeMap](https://openfreemap.org/) (`https://tiles.openfreemap.org/styles/liberty`). Ein API-Key ist nicht nötig.

- **Orte pro Region:** Umschalter „Liste | Karte“, interaktive Karte mit farbigen Punkten nach Status; ein Tipp auf einen Punkt öffnet den Ort. Kacheln landen im Cache von MapLibre.
- **Routenkarten** für Flüge (Bogen) und Züge (Linie): einmal als Bild gerendert und im App-Cache (`cache/route-maps`) abgelegt, danach offline verfügbar. Ohne Netz und ohne Cache erscheint eine stilisierte Grafik; ohne Koordinaten gibt es keine Karte. Ohne übernommene Verbindung ist die Route eine Luftlinie zwischen den Endpunkten. Eine ÖPNV-Verbindung wird durch alle Halte gezeichnet, jede Teilstrecke in eigener Farbe, Fußwege gestrichelt.
- **Flüge ohne Koordinaten** nutzen den IATA-Code der Endpunkte (kleine Flughafen-Tabelle in `RouteGeometry.kt`).
- Attribution „OpenFreeMap © OpenMapTiles Data from OpenStreetMap“ ist auf jeder Karte sichtbar.

## Server

Betrieb und Konfiguration: `server/README.md`.

## Echte Daten

Echte Wanderlog-Exporte enthalten Buchungsdaten und gehören ausschließlich nach `testdata/private/` (per `.gitignore` ausgeschlossen). Tests, die diese Datei brauchen, werden ohne sie übersprungen.

## Releases und Updates

- **Release bauen:** `versionName`/`versionCode` in `app/build.gradle.kts` hochzählen, einen Abschnitt `## <version>` in `CHANGELOG.md` schreiben und nach `main` pushen. Die Action `.github/workflows/release.yml` baut dann eine signierte APK und veröffentlicht sie als Release `v<version>`. Den Changelog-Abschnitt übernimmt sie als Release-Text. Gibt es den Tag schon, tut sie nichts. Über „Run workflow“ lässt sie sich auch von Hand starten.
- **Signatur:** Die Action braucht die Repository-Secrets `WLP_KEYSTORE_BASE64`, `WLP_KEYSTORE_PASSWORD`, `WLP_KEY_ALIAS` und `WLP_KEY_PASSWORD`. Lokal liest Gradle den Schlüssel aus `keystore.properties`. Diese Datei und `keystore/` sind gitignored. Den Keystore sicher aufbewahren: Geht er verloren, lassen sich keine Updates mehr über die installierte App spielen.
- **In der App:** Release-Builds suchen höchstens alle 6 Stunden beim Start nach einer neuen Version und zeigen dann das Changelog. Unter Einstellungen geht das auch von Hand („Nach Updates suchen“). Nach dem Bestätigen lädt die App die APK, prüft Größe und SHA-256 und übergibt sie an den Android-Installer. Beim ersten Mal muss „Apps aus dieser Quelle installieren“ einmal erlaubt werden.

