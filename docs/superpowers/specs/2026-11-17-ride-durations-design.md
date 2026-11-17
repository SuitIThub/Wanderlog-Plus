# Fahrt- und Flugdauer mit Zeitzonen – Design (kurz)

**Warum:** In der Logistik soll man bei jeder Teilstrecke sehen, wie lange man fährt oder fliegt – auch über Nacht und
über Zeitzonen hinweg. Ab- und Ankunft sind in Ortszeit ohne Zone gespeichert; die heutige „gesamt“-Dauer einer Reise
ignoriert Zonen und ist bei Flügen Frankfurt → Japan falsch.

**Approaches:** (a) Zeitzone pro Ende speichern, automatisch vorbelegt (gewählt – vom Nutzer so gewünscht);
(b) nur eine feste Flughafentabelle ohne Eingabe (unbekannte Flughäfen hätten nie eine Dauer); (c) Zone aus
Koordinaten (braucht eine große Zeitzonen-Datenbank, unverhältnismäßig).

**Design (vom Nutzer bestätigt):**
- `Endpoint.timeZone: String? = null` – IANA-Zone („Europe/Berlin“, „Asia/Tokyo“); dadurch stimmt die Sommerzeit
  (Deutschland) automatisch, Japan hat keine.
- Wirksame Zone eines Endes: eigenes Feld → Flughafentabelle nach `code` → bei Zügen/Bussen die Reise-Zone
  (Asia/Tokyo) → sonst unbekannt.
- Flughafentabelle (`AIRPORTS`) bekommt die Zone; ergänzt um gängige Umsteige-Flughäfen (u. a. MUC, BER, DUS, HAM, ZRH,
  VIE, AMS, CDG, LHR, HEL, IST, DOH, DXB, AUH, ICN, PVG, PEK, HKG, SIN, TPE).
- Bearbeiten: Feld „Zeitzone“ je Ende als Auswahl mit lesbaren Namen („Deutschland“, „Japan“, „Finnland“ …),
  automatisch gefüllt, wenn die Zone aus Code/Ort bekannt ist; leer lassen erlaubt.
- Dauer = Ankunft (Datum+Zeit+Zone) − Abfahrt (Datum+Zeit+Zone); fehlt das Ankunftsdatum, gilt das Abfahrtsdatum (+1 Tag,
  wenn die Ankunft sonst vor der Abfahrt läge). Fehlt eine Zone oder Zeit: keine Dauer.
- Anzeige in jeder Zeile einer Teilstrecke (Logistik-Liste, Reise-Ansicht, Detail): „11:15 – 06:15 +1 · 11 Std 50 Min“.
- „gesamt …“ einer Reise und Umstiegszeiten rechnen mit Zonen.

**Nicht-Ziele:** keine Zeitzonen-Anzeige in der Tageszeitleiste; keine automatische Zone aus Koordinaten.

**Test-Pflicht:** Frankfurt → Haneda im Mai (7 h Unterschied) und im November (8 h).

**Version:** 0.78.0 mit CHANGELOG.
