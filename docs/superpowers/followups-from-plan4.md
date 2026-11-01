# Follow-ups aus Plan 4 (App-Ausbau)

Stand: 2026-11-01. Ergebnis der Task-Reviews und des Gesamt-Reviews von Plan 4. Offene Punkte aus `followups-from-plan2.md` und `followups-from-plan3.md` gelten weiter, soweit sie hier nicht als erledigt markiert sind.

## Pflicht für den nächsten Plan

- **Test auf echten Geräten (weiterhin nie erfolgt):**
  1. 0.1.0 installieren, dann auf 0.2.0 aktualisieren. Dabei wird die Room-Migration 1→2 ausgeführt.
  2. Ein PDF aus Gmail über das Teilen-Menü anhängen und auf dem zweiten Handy öffnen.
  3. Den Einrichtungslink aus der Kamera-App öffnen.
  4. Prüfen, ob Benachrichtigungen bei Stornofristen erscheinen.
  5. Die Karte mit API-Key ansehen (ersetzt durch MapLibre in Plan 5: kein Key mehr nötig).
  6. Den PDF-Viewer mit einem langen PDF testen.

  Ungetestet sind bisher alle Teile, die nur auf einem Gerät laufen: PdfRenderer, FileProvider-Intent, Dokumentauswahl, Teilen-Intent, QR-Scanner, GoogleMap, Notifications.
- **Integrationstest für die ganze Anhang-Kette:** Robolectric mit MockEngine soll durchspielen: `AppContainer.addAttachment` → Upload → zweiter Container pullt → Download → Datei verfügbar. Bisher ist jedes Glied nur für sich getestet.

## Bekannte Grenzen (bewusst so)

- **Zeitzone:** Standard ist JST (Ruling R1). Bevor der DataStore geladen ist, gilt kurz JST, auch wenn „Gerätezeit“ eingestellt ist. Nach höchstens einer Minute oder beim nächsten Fortsetzen der App stimmt es wieder.
- **Anhänge offline (Ruling R2):**
  - Der Datensatz synct sofort, die Datei folgt beim nächsten Datei-Sync. Bis dahin zeigen andere Geräte „nicht offline verfügbar“.
  - Lokale Dateien gelöschter Anhänge werden nie aufgeräumt (inhaltsadressiert, Spec schweigt).
  - Derselbe Anhang zweimal am selben Ziel ergibt zwei Datensätze.
- **Externe Apps sehen als Dateinamen den Hash ohne Endung.** Manche Apps lehnen deshalb .pkpass- oder .docx-Dateien ab. Lösung wäre eine Kopie mit Originalnamen im Cache.
- **Tabelog-Link** gibt es nur für die Kategorie Essen (Ruling R3).
- ~~**Karte** erscheint nur mit eigenem `MAPS_API_KEY` in `local.properties` (Ruling R4). Die Konditionen von Google Maps bleiben offener Punkt der Spec.~~ Ersetzt durch MapLibre in Plan 5: OpenStreetMap über OpenFreeMap, ohne Key; `MAPS_API_KEY` entfällt.
- **Notiz im Programm** wird inline statt im Dialog eingegeben (Ruling R7, Robolectric-Problem mit einem TextField im Dialog).
- **Tags:** Die Schalter ⭐ und ⚠️ erfassen alle Tags, die mit dem Emoji beginnen. Ein gespeichertes „⚠“ ohne Variationszeichen wird nicht erkannt.
- **Erinnerungen:**
  - Der Unterkunftsname steht im Titel der Notification und ist damit auf dem Sperrbildschirm sichtbar. Bestätigungsnummern und Namen der Reisenden stehen nicht darin.
  - Verpasste frühere Erinnerungsstufen werden nicht nachgeholt.
- **Downloads** laufen auch über mobile Daten (Constraint CONNECTED, nicht UNMETERED).
- **Die Schema-JSONs liegen als Debug-Assets in der Debug-APK.** Robolectric liest Assets nur aus der Debug-Variante.
- **Deep-Link-Token** landet im Browserverlauf, das ergibt sich aus dem Linkformat. Die README warnt davor.
- **Mehrere Day-Objekte für ein Datum:** Legt Wanderlog später eine Section für ein Datum an, für das schon ein App-Day existiert, entstehen zwei Days. Es gilt `dayFor` mit der größten ID (aus Plan 3).

## Offen

- Der Trip-Key steht weiterhin in den Dokumenten zu Spec und Plan 1. Vor einem öffentlichen Push entfernen.
- Live-Status (`LiveStatusProvider`) ist weiter nur ein Platzhalter. Die Datenquelle ist offen und braucht einen eigenen Spike.
- Kein Test für die Link-Knöpfe in „Heute“, kein UI-Test für den Schalter „Erinnerungen an Fristen“.
