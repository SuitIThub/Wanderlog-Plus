# Eine Fotosammlung für Orte und Unterkünfte – Design

## Warum

Fotos werden heute an vielen Stellen unterschiedlich behandelt:
- Beim Ort steht „Fotos vom Ort“ (Wanderlog + Google, eigener Viewer) getrennt über dem Raster mit den eigenen Fotos (zweiter Viewer).
- Die Unterkunft hat zusätzlich einen Foto-Streifen in der Übersicht und die Zimmerfotos im Zimmer-Tab.
- Das Titelbild wird in einem eigenen Fenster gewählt, mit eigenem Raster, eigenem „Mehr von Google“, eigenem Upload und eigener Online-Suche.

Ziel: Ein Ort oder eine Unterkunft hat **eine** Fotosammlung. Die Herkunft eines Fotos spielt keine Rolle. Ort und Unterkunft zeigen die Sammlung gleich, und das Titelbild wird direkt aus ihr gewählt.

## Ziele

- Ein Raster mit allen Fotos: eigene Fotos (hochgeladen oder per Online-Suche), Wanderlog-Bilder und Google-Fotos.
- **Google-Fotos sind geliehen (A):** Sie stehen im selben Raster mit einem kleinen Abzeichen „Google“. Sie werden weiterhin erst auf Knopfdruck geladen, wegen der Kosten, und danach wie heute auf dem Gerät zwischengespeichert. Sie werden nicht synchronisiert.
- **Übernehmen (B):** Mit „Behalten“ oder „Als Titelbild“ wird ein Google-Foto zu einem eigenen Foto. Es wird dann synchronisiert, ist offline verfügbar und trägt im Dateinamen den Urheber („<Ort> (Foto: <Autor> · Google).jpg“).
- Ein einziger Vollbild-Viewer für alle Fotos. Er hat diese Aktionen:
  - **Als Titelbild:** für alle Fotos.
  - **Behalten:** nur für Google-Fotos.
  - **Zimmerfoto an/aus:** nur bei Unterkünften und nur für eigene Fotos.
  - **Löschen:** nur für eigene Fotos.
- Der Viewer zeigt bei Google-Fotos den Urheber und markiert das aktuelle Titelbild.
- „Titelbild ändern“ (✎ im Header) öffnet keine eigene Auswahl mehr, sondern dieselbe Galerie im Auswahlmodus. Ein Tippen setzt das Titelbild. „Standardbild“ setzt es zurück.
- Ort und Unterkunft folgen demselben Muster:
  - **Übersicht:** ein Foto-Streifen (max. 8, Titelbild zuerst) mit „Alle“, das in den Fotos-Tab springt.
  - **Fotos-Tab:** die volle Galerie.
  - Ein Tippen auf den Header öffnet den Viewer beim Titelbild.
- Der Zimmer-Tab der Unterkunft zeigt weiterhin die Zimmerfotos, aber als gefilterte Ansicht derselben Sammlung im selben Raster und Viewer. „Zimmerfotos wählen“ (mehrere auf einmal ankreuzen) bleibt im Fotos-Tab.

## Nicht-Ziele

- Keine automatische Übernahme von Google-Fotos (das würde Kosten und Sync aufblähen).
- Wanderlog-Bilder werden nicht übernommen. Sie sind Teil der importierten Reisedaten, als Titelbild bleibt die Referenz `wl:`.
- Die Fotos der Tage bleiben inhaltlich wie heute. Sie nutzen nur dasselbe Raster und denselben Viewer, ohne Wanderlog- oder Google-Quelle.
- Am Server ändert sich nichts.

## Anti-Ziele

- Ein Google-Foto darf nach dem Übernehmen nicht doppelt erscheinen (einmal geliehen, einmal eigen).
- Bestehende Titelbilder (`wl:`, `att:`, `g:`) müssen weiter funktionieren.
- Kein zusätzlicher Google-Abruf, ohne dass jemand „Mehr von Google“ tippt.

## Ansätze

1. **Gemeinsame Galerie** (gewählt): Ein Galerie-Modell und ein Baustein für Ort und Unterkunft, dazu ein Viewer mit Aktionen. Das Titelbild-Fenster entfällt.
2. Nur flicken: Die Google-Zeile wandert ins Raster, und das Titelbild-Fenster nutzt das Raster mit. Das ist schneller, aber es bleiben zwei Viewer und doppelte Code-Wege, die Inkonsistenz kommt also wieder.
3. Google-Fotos automatisch übernehmen. Das verursacht Kosten pro geöffnetem Ort, bläht den Sync auf und widerspricht A.

Gewählt wurde Ansatz 1, weil er die Ursache behebt (drei getrennte Darstellungen) und nicht nur die Symptome.

## Bauweise (Entscheidungen)

- **Modell:** `GalleryPhoto` als sealed type mit drei Varianten: `Own(Attachment)`, `Wanderlog(key)` und `Google(PlacePhoto)`. Jede Variante liefert eine Titelbild-Referenz (`att:`/`wl:`/`g:`).
- **Reihenfolge:** Eine reine Funktion `galleryPhotos(own, wanderlogKeys, google, header)` legt fest:
  1. das Titelbild,
  2. eigene Fotos (bei Unterkünften die Zimmerfotos zuerst),
  3. Wanderlog-Bilder,
  4. Google-Fotos ohne die bereits übernommenen.

  Die Funktion wird unit-getestet.
- **Doppelte vermeiden:** Das `Attachment` bekommt ein optionales Feld `origin: String? = null`. Beim Übernehmen wird dort `g:<Google-Fotoname>` gespeichert, und die Galerie blendet das geliehene Gegenstück aus. Das Feld ist optional und hat einen Default, ältere Geräte und der Server kommen also damit zurecht.
- **Übernehmen:** Die Datei in voller Größe aus dem `PlacePhotoRepository` wird als `AttachmentSource` über das bestehende `addPhotos` gespeichert. Bei „Als Titelbild“ wird danach der Header auf `att:<hash>` gesetzt, über den bestehenden Weg `headerFromAttachmentEdits`.
- **Alte `g:`-Titelbilder** werden weiter aufgelöst wie bisher. Ab jetzt entstehen keine neuen mehr.
- **Viewer:** `PhotoViewerScreen` arbeitet künftig mit der Galerie-Liste statt nur mit Attachments. Damit entfällt der Dialog-Viewer in `PlacePhotos.kt`. Die Route trägt Typ und ID, die Galerie wird im Viewer aus Snapshot und Google-Cache neu gebildet.
- **Fallback-Titelbild bei Unterkünften:** Die Regel „Zimmerfoto, sonst erstes Hotelbild“ bleibt. Für Orte gilt weiter: erstes eigenes Foto, sonst das erste Wanderlog-Bild. Das ist eine kleine Angleichung, denn bisher bekam ein Ort ohne Wanderlog-Bild auch mit eigenen Fotos kein Bild.
- **Bewerten-Seite** (`PlaceRatingScreen`): Statt `PlacePhotosRow` zeigt sie den gleichen Streifen.
- **Entfallen:** `HeaderImagePicker.kt`, `PlacePhotosRow` und der separate Streifen bzw. das Raster der Unterkunft. Tests werden entsprechend angepasst.

## Dem Umsetzer überlassen

- Genaues Aussehen des Herkunfts-Abzeichens und der Kacheln „Mehr von Google“ und „Fotos hinzufügen“ (im Stil des bestehenden Rasters).
- Ob die Viewer-Aktionen als Icon-Leiste oder als Überlaufmenü erscheinen. Wichtig ist nur, dass sie sichtbar und erreichbar sind.
- Version: Minor-Bump plus Abschnitt im CHANGELOG.
