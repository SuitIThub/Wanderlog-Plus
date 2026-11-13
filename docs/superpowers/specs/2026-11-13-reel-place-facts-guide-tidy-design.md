# Reel-Infos zu Orten, kurze Guide-Titel, straffe Guides — Design

Datum: 2026-11-13 · Branch `feat/reel-place-facts-guide-tidy` · Ziel-Version 0.72.0

## Ziel

- Infos aus Reels, die nur einen oder zwei konkrete Orte betreffen, landen in der **Recherche des Orts** statt in einem
  neuen Guide. Guides bleiben allgemein, es gibt weniger davon.
- Infos aus einem Reel zu einem **bereits gespeicherten Ort** werden knapp in seine Recherche eingearbeitet: nur die
  Aussage, kein Kontext des Beitrags, nichts doppelt.
- **Guide-Titel sind kurz** („Shinkansen & Suica“, „Yakiniku & Grill“): kein Inhalt im Titel, den man aus der Reise
  ohnehin kennt (Orte, Strecken, „in Japan“).
- **Guides taugen für beides**: Vorbereitung (Hintergründe) und schnelles Nachschlagen vor Ort (Kern sofort sichtbar).
- Vorhandene Guides lassen sich ins neue Format bringen: alle auf einmal oder einzeln.

Erfolg: Ein Reel über ein Restaurant mit „bestes Gericht ist X, Montag zu“ schlägt als Ziel den Ort vor, nicht
„Neuer Guide: Restaurant X in Osaka“. Ein gestraffter Guide zeigt oben 3–5 Stichpunkte und eingeklappte Abschnitte mit
je einem Kernsatz.

## 1. Guide-Format

### Modell (`shared/.../model/Guides.kt`)

- `GuideSection` bekommt `details: String = ""`: Hintergründe zum Vorbereiten, in der App eingeklappt („Mehr dazu“).
  `text` ist der Kern: 1–2 Sätze.
- `Guide.summary` bleibt ein String, wird aber als **„Auf einen Blick“** geschrieben: 3–5 Zeilen (durch `\n` getrennt),
  je höchstens ~80 Zeichen, ohne Aufzählungszeichen. Ältere Guides mit Fließtext funktionieren weiter (eine Zeile).
- `EntityCodec` / Sync: das neue Feld läuft über die vorhandene Serialisierung (Default `""`, ältere Daten bleiben gültig).

### Schreibregeln (`GuideWriter`, beide Prompts `write` und `merge`)

Gemeinsamer Regelblock `FORMAT_RULES`:
- Titel: höchstens 3 Wörter / ~25 Zeichen; keine Orte, Strecken, Städte, kein „in Japan“, kein Untertitel nach „:“.
- summary: 3–5 Zeilen, je eine Kernaussage, das vor Ort Wichtigste zuerst.
- Höchstens 6 Abschnitte; heading 1–4 Wörter; text 1–2 Sätze; höchstens 4 dos und 4 donts, je ein kurzer Satz.
- details: alles, was zum Vorbereiten nützt, vor Ort aber nicht gebraucht wird (Hintergrund, Ausnahmen, Beispiele);
  höchstens ~600 Zeichen; sonst leer.
- Ergänzungen aus Reels: nur die Aussage selbst — nichts über den Beitrag, die Person, die Geschichte drumherum.

Zusätzlich serverseitige Absicherung (`normalizeTitle`): alles ab „:“ oder „ – “/„ - “ entfernen, „in Japan“ am Ende
entfernen, trimmen; bleibt nichts übrig, gilt der Original-Titel. JSON-Format der Antwort: `sections[].details`.

`keepsContent` (für `merge`) zählt Do's/Don'ts weiter wie bisher; Abschnitte dürfen beim Mergen nicht weniger werden.

### Straffen (`GuideWriter.tidy`)

Neue Methode `tidy(existing: Guide, say): Guide`: das Modell baut den Guide ins neue Format um, ohne neue Recherche.
Regel: kein Inhalt geht verloren — was nicht in den Kern passt, wandert nach `details`; Doppeltes darf zusammengefasst
werden. Prüfung `tidyKeeps(before, after)`: Abschnitte nicht leer, Anzahl `phrases` gleich, summary nicht leer und
nicht überwiegend Japanisch. Titel durch `normalizeTitle`. Pins, Hides, Topic, Trigger, Links, Quellen, parentId bleiben.
Dos/Don'ts dürfen weniger werden (sie wandern ggf. nach `details`).

### Job + API

- `POST /api/ai/guide-tidy` mit `GuideTidyRequest(guideIds: List<String> = emptyList())` (leer = alle; höchstens 200),
  wie `guide-links`. `AiJobs.startGuideTidy` erzeugt einen Job `kind = "guides"` (die Guide-Screens verfolgen ihn wie die
  anderen), ein Item pro Guide, schreibt jeden fertigen Guide sofort über `writeResults`, Fehler eines Guides stoppen die
  anderen nicht.
- Client: `SyncApi.startGuideTidy(ids)`.

### App

- Guide-Screen:
  - „Auf einen Blick“-Karte: summary-Zeilen als Stichpunkte.
  - Abschnitte eingeklappt: Überschrift + erster Satz von `text`; aufgeklappt `text`, Do's/Don'ts, „Mehr dazu“
    (klappt `details` auf, nur wenn nicht leer). Sätze zum Zeigen als eingeklappter Block mit Anzahl.
  - Menü-Eintrag **„Straffen“** startet `guide-tidy` für diesen Guide (Fortschritt wie bei „Links ergänzen“).
- Guides-Liste: Vorschau zeigt die ersten zwei summary-Zeilen.
- Mehr → KI: **„Guides straffen“** startet `guide-tidy` für alle.
- Bearbeiten-Dialog: Label „Auf einen Blick (eine Zeile pro Punkt)“.

## 2. Reel: Infos zu Orten

### Analyse (`Assistant.analyze`)

- Jede `location` bekommt `facts`: bis zu 4 Aussagen, die der Beitrag über genau diesen Ort macht (Gericht, Ruhetag,
  Wartezeit, bester Zeitpunkt, Trick vor Ort) — je ein kurzer Satz auf Deutsch, ohne Beitragskontext.
- Regel geschärft: Aussagen zu einem oder zwei konkreten Orten sind **facts dieser Orte**, keine allgemeinen Tipps.
- `FoundPlace.facts: List<String>` (≤ 4, je ≤ 200 Zeichen).
- `ReelCandidate.reelFacts: List<String>` trägt sie in die App.
- Neue (nicht gespeicherte) Orte mit `deep`: die facts gehen als Teil von `evidence` in die Recherche
  („Laut Beitrag: …“), damit die Recherche sie prüft und im Dossier aufnimmt.

### Tipp-Abgleich (`TipCheck.match`)

- Bekommt zusätzlich die gespeicherten Orte der Reise (`id | Name | Stadt`, höchstens 300, nicht DROPPED).
- Neues mögliches Ziel `placeIds` (1–2 Ids): wenn der Tipp nur diese Orte betrifft. Regel im Prompt: einen neuen Guide
  (title/topic) nur für allgemeine Themen, die viele Orte oder die ganze Reise betreffen; nie für einen einzelnen Ort.
- Vorgeschlagene Guide-Titel durch `normalizeTitle`.
- `ReelTip.placeIds: List<String> = emptyList()`; mit placeIds sind guideId/targetTopic/targetTitle null.
- `verify` bleibt unverändert (auch Ort-Tipps werden geprüft); `text` 1–2 Sätze, nur die Aussage.

### Ort ergänzen (Server-Job)

- `POST /api/ai/place-facts` mit `PlaceFactsRequest(items: List<PlaceFactsItem>)`,
  `PlaceFactsItem(savedPlaceId, facts: List<String>, sources: List<String>)` (1–20 Items, je 1–12 facts).
- `PlaceFacts.merge(dossier: PlaceDossier?, place name, facts)`: Modell sortiert die facts in `goodToKnow` / `tips` /
  `hints`, lässt weg, was dort inhaltlich schon steht, kürzt jede auf einen Satz. Bestehende Einträge bleiben
  unverändert (Prüfung: jede alte Liste ist Teilmenge der neuen). Ein vorhandenes Dossier behält sein `researchedAt`;
  ohne Dossier entsteht eines nur mit diesen Listen und `researchedAt` = 0.
- Zusätzlich speichert `PlaceDossier.reelFacts: List<String>` die übernommenen Aussagen (knapp, dedupliziert).
  Eine spätere Recherche dieses Orts bekommt sie als evidence („Laut Reel: …“) und übernimmt `reelFacts` ins neue
  Dossier — so gehen Reel-Infos beim Neu-Recherchieren nicht verloren.
- Schreibt das Dossier über `writeResults` (wie Research-Ergebnisse); Job `kind = "research"`, damit der Recherche-Tab
  ihn verfolgt; ein Item pro Ort.

### App (`ReelScreens`, `ReelsViewModel`)

- Kandidat „bereits gespeichert“ mit `reelFacts`: Abschnitt „Neu aus dem Reel“ (Stichpunkte) + Checkbox
  „Zum Ort hinzufügen“ (vorausgewählt). Import startet `place-facts` dafür.
- Tipp-Dropdown: zusätzlich „In Recherche: <Ort>“ für jeden Ort aus `tip.placeIds` und jeden gespeicherten Kandidaten
  des Reels. Tipps mit Ort-Ziel gehen in denselben `place-facts`-Aufruf (Text = geprüfter `text`, Quellen mit).
- `importLabel` zählt Ort-Infos als Tipps mit; Meldung „… werden in Guides und Orte übernommen“.

## 3. Fehler

- Modellantwort ungültig → wie bisher `completeJson`-Retries, danach `AiFailed("ai_bad_answer")`; der Guide/Ort bleibt
  unverändert, Item FAILED, andere laufen weiter.
- Unbekannte savedPlaceId → Item FAILED „Ort nicht gefunden“.
- Abgleich mit Guides fehlgeschlagen → wie bisher alle Tipps neu (Ziel Guide), keine Ort-Ziele.

## 4. Tests (TDD)

- `GuideWriterTest`: details geparst; normalizeTitle (Doppelpunkt, Gedankenstrich, „in Japan“, leer → Original);
  tidy behält Phrasen/Pins/Links, lehnt Phrasenverlust ab.
- `TipCheckTest`: placeIds-Ziel wird übernommen, unbekannte Ids verworfen; Titel normalisiert.
- `AssistantTest`: facts je Ort geparst, gekürzt, in evidence der Recherche.
- `PlaceFactsTest`: Merge behält alte Einträge, lehnt Verlust ab, ohne Dossier neues mit researchedAt 0.
- `AiJobs`/Route-Tests für `guide-tidy` und `place-facts` wie bei `guide-links`.
- App: `ReelTipsTest` (Ort-Ziele, Aufteilung Guides/Orte), Guide-Summary-Zeilen.

## Nicht im Umfang

- Keine Prüfung der Reel-facts gespeicherter Orte gegen das Web (schneller Import; Tipps mit Ort-Ziel sind geprüft).
- Kein Umschalter Vor Ort/Vorbereitung.
