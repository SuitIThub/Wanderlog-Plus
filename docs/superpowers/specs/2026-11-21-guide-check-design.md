# Guide-Prüfung: Inhalte auf Richtigkeit prüfen (0.85.0)

## Warum

Guides sollen stimmen, nicht nur plausibel klingen. Ein frisch geschriebener Guide „Kleinigkeiten“ begann mit „nicht im
Gehen essen“ – eine Pauschalregel, die so nicht gilt (wo ein Schild steht, hält man sich dran; dann steht da aber ein
Schild). Der `GuideWriter` recherchiert heute nur grob zum Thema und vertraut dann dem Modell; einzelne Aussagen prüft
niemand. Bei Reel-Tipps gibt es das schon (`TipCheck`).

## Ziele

- Jeder neu geschriebene oder überarbeitete Guide wird beim Erstellen geprüft; Korrekturen werden **automatisch**
  eingearbeitet. Ein Guide soll nach Möglichkeit immer korrekt sein.
- Bestehende Guides lassen sich nachprüfen: alle (Dreipunkte-Menü der Guide-Übersicht), einer (Menü im Guide), per Chat.
- Was unsicher bleibt, bekommt ein kleines ⚠ an der Aussage; Antippen zeigt die Begründung (kein Klammertext).
- Eine Korrekturübersicht wie beim Reel-Tipp-Import zeigt, was geändert wurde, warum und mit welchen Quellen – als
  Protokoll, mit der Möglichkeit, eine einzelne Korrektur zurückzunehmen.

## Nicht-Ziele / Anti-Ziele

- Keine Abhak-Pflicht: Korrekturen gelten sofort, die Übersicht ist zum Nachsehen und Eingreifen.
- Kein Klammer-Disclaimer im Text, keine „laut manchen Quellen…“-Floskeln überall.
- Phrasen (Japanisch/Romaji) werden nicht inhaltlich geprüft (Sprache, keine Behauptung).
- Kein Verlust von belegtem Inhalt: „stimmt“ bleibt wortgleich.

## Ansätze

- **A – jede Aussage recherchieren:** gründlich, pro Guide 20–40 Aussagen.
- **B – erst aussortieren, dann gezielt recherchieren (gewählt, Standard):** ein Modell-Durchgang liest den Guide
  kritisch und markiert Pauschales, Übertriebenes, Veraltetes, Zweifelhaftes; nur diese werden recherchiert.
- **C – nur strengere Schreibregeln:** billig, unzuverlässig; kommt zusätzlich, nicht allein.

Entscheidung: B als Standard, A wählbar („Gründlich prüfen“). Die Schreibregeln werden zusätzlich geschärft.

## Kosten: Recherche bei Parallel bündeln

Heute kostet jede geprüfte Aussage einen Search- und einen Task-Aufruf. Neu prüft ein **Task-Lauf mehrere Aussagen auf
einmal** (bis zu 8 pro Lauf) mit einem strukturierten Ergebnis je Aussage; eine Search-Anfrage pro Bündel mit bis zu 5
Suchbegriffen aus den Aussagen liefert Vorab-Quellen.

- B: meist 1 Bündel pro Guide (Search + Task).
- A: ⌈Aussagen/8⌉ Bündel pro Guide, also etwa 3–5 statt 20–40 Läufe.
- Beim Erstellen eines Guides fließen die Fakten der Themenrecherche in die Vorauswahl mit ein: Was dort klar belegt
  ist, muss nicht nochmal recherchiert werden.
- Ohne Parallel-Schlüssel: die Modell-Websuche (`ModelFacts`-Weg), ebenfalls gebündelt.

## Ablauf (Server, neue Klasse `GuideCheck`)

1. **Aussagen sammeln:** summary-Zeilen, je Abschnitt text, dos, donts, details. Jede Aussage hat einen Ort
   (`summary:2`, `s1.dos:0`, …) und den Wortlaut.
2. **Vorauswahl (nur B):** ein Modell-Aufruf mit dem ganzen Guide (und ggf. den Themenfakten). Ergebnis: Liste
   verdächtiger Aussagen mit kurzem Grund. Ausdrücklicher Prüfauftrag: Pauschalregeln, die nur örtlich, zeitweise oder
   für manche gelten; Übertreibungen; veraltete Regeln/Preise; in Touristen-Blogs abgeschriebene „Mythen“.
3. **Recherche gebündelt** (s. o.), Urteil je Aussage wie bei Reel-Tipps:
   - `TRUE` – bleibt unverändert.
   - `PARTLY` – wird umformuliert (z. B. „Gilt nicht allgemein; wo Schilder darauf hinweisen, daran halten.“).
   - `FALSE` – wird gestrichen (oder durch die richtige Aussage ersetzt, wenn es eine gibt).
   - `UNVERIFIED` / Quellen widersprechen sich – bleibt (ggf. vorsichtiger formuliert) und bekommt einen **Hinweis**.
4. **Einarbeiten:** die Korrekturen werden direkt in den Guide geschrieben (ohne neuen Schreib-Durchgang des ganzen
   Guides, damit nichts anderes verrutscht). Abschnitte, die dadurch leer werden, fallen weg.
5. **Protokoll:** jede Änderung als `GuideCorrection` im Job-Ergebnis.

Beim Erstellen/Überarbeiten (`GuideWriter.write`, auch Ergänzungen aus Reels) läuft Schritt 1–5 direkt nach dem
Schreiben im selben Job. „Straffen“ prüft nicht (ändert keinen Inhalt).

## Datenmodell (shared)

- `Guide.notes: List<GuideNote>` – `GuideNote(statement: String, note: String)`: Hinweis zu der Aussage mit genau diesem
  Wortlaut. Wird eine Aussage bearbeitet, passt der Wortlaut nicht mehr und der Hinweis verschwindet von selbst; beim
  Speichern im Editor werden verwaiste Hinweise entfernt. (Meine Entscheidung: kein Index, weil der beim Umsortieren
  verrutscht.)
- `Guide.checkedAt: Long` – wann zuletzt geprüft (0 = nie); zeigt „geprüft am …“ im Guide.
- `GuideCorrection(guideId, place, before, after /* leer = gestrichen */, verdict, reason, sources, note?)` in
  `AiJobInfo.corrections`.
- `GuideCheckRequest(guideIds: List<String> = emptyList() /* leer = alle */, thorough: Boolean = false)`,
  `POST /api/ai/guide-check`, Job-Art `"guides"` wie Straffen (damit der Lauf am Guide angezeigt wird).

## App

- **⚠-Symbol:** kleines Warn-Icon am Ende der betroffenen Zeile (summary-Zeile, Kernsatz, Do/Don't, Details).
  Antippen öffnet eine kleine Sprechblase/Dialog mit dem Hinweis.
- **Guide-Übersicht, Dreipunkte-Menü:** „Alle prüfen“ und „Alle gründlich prüfen“ (mit Bestätigung, da teurer).
- **Guide, Dreipunkte-Menü:** „Prüfen“, „Gründlich prüfen“.
- **Korrekturübersicht:** im Job-Ergebnis (wie Reel-Tipps): Karte je Korrektur mit neuem Text, Abzeichen
  (korrigiert / gestrichen / unsicher ⚠), „Vorher: …“, Begründung, Quellen (aufklappbar), Knopf „Zurücknehmen“
  (setzt die alte Fassung wieder ein und entfernt den Hinweis; lokale Änderung, wird synchronisiert). Gruppiert nach
  Guide. Auch nach dem Erstellen eines Guides erreichbar („3 Korrekturen“ am fertigen Job).
- Ohne Korrekturen: „Alles stimmt“.

## Chat

- Neues Werkzeug `check_guides(guideIds?, thorough?)`: startet die Prüfung im Hintergrund.
- `list_guides` liefert `checkedAt` mit, damit der Chat sagen kann, was ungeprüft ist.

## Schreibregeln (GuideWriter)

Zusätzlich: keine Pauschalregeln, die nur örtlich oder für manche gelten, als allgemeine Regel; nur, was die Fakten
belegen; im Zweifel weglassen statt behaupten.

## Dem Bauenden überlassen

- Genaue Prompts, Bündelgröße (≈8), Grenzen (höchstens etwa 12 verdächtige Aussagen in B).
- Wie Zurücknehmen eine Aussage findet, die inzwischen bearbeitet wurde (dann: Knopf aus, „inzwischen geändert“).
- Tests nach dem Muster von `TipCheckTest` / `GuideWriterTest` mit Fake-Transport.
