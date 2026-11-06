# Zimmer & Ausstattung der Unterkünfte, Sitzplätze & Sitzpläne – Design

Datum: 2026-11-06 · Status: zur Freigabe

## Ziel

1. Pro Unterkunft das gebuchte **Zimmer** (Typ, Infos, Fotos, Ausstattung) und die **Ausstattung der Unterkunft**
   hinterlegen – befüllt aus Buchungsbestätigungen, Links und Websuche, jederzeit von Hand korrigierbar.
2. Bei Flügen und Zügen **Klasse und Sitzplätze** angeben und die Plätze in einem **selbst gezeichneten Sitzplan**
   zeigen.

## Entscheidungen (abgestimmt)

- Quellen fürs Zimmer: **Buchungsbestätigungen** (Anhänge der Unterkunft, PDF/Bild) + **Links**; die **Websuche** nur für
  die Ausstattung der Unterkunft.
- Bestätigungen dürfen samt Namen/Buchungsnummer an Gemini (OpenRouter) gehen; Namen/Nummern werden nie zurückgegeben
  oder gespeichert (Server verwirft sie zusätzlich).
- Alles ist von Hand bearbeitbar; Handänderungen überstehen eine neue Recherche.
- Sitzpläne werden **als Daten** abgeschrieben (Katalog) und in der App gezeichnet, nicht als Bilder eingebunden.
- Zuordnung Fahrt → Sitzplan über Flug-/Zugnummer, von Hand überschreibbar.

## Teil A: Klasse & Sitzplätze

`Flight` und `Train` bekommen (am Ende, mit Standardwert):

```kotlin
val travelClass: String? = null          // Freitext mit Vorschlägen
val seats: List<Seat> = emptyList()
val seatMap: String? = null              // Katalog-Schlüssel von Hand; null = nach Nummer
@Serializable data class Seat(val car: String? = null, val seat: String, val traveler: String? = null)
```

Vorschläge Klasse: Flug „Economy, Premium Economy, Business, First“; Zug „2. Klasse, 1. Klasse, Gewöhnlich, Green Car,
Gran Class, Ohne Reservierung“.

Eingabe in den bestehenden Formularen (Zug/Flug bearbeiten): Klasse, Sitzliste (Wagen + Platz, optional Person),
Sitzplan-Auswahl („automatisch“ oder ein Katalog-Eintrag). Anzeige: Details, Karte „Nächste Fahrt“/Jetzt-Karte
(„2. Klasse · Wagen 12, 111 + 114“) und Chat-Kontext (Logistik-Zeile).

## Teil B: Sitzplan-Katalog und Zeichner

### Datenformat (`shared/.../seatmap/SeatMaps.kt`, fest im Code)

```kotlin
data class SeatMapModel(val key: String, val name: String, val kind: Kind /* TRAIN_CAR, AIRCRAFT */,
    val matches: List<String>,          // Flug-/Zugnummern, z. B. "CI 62", "ICE 571"
    val sections: List<SeatSection>)    // Kabinenabschnitte bzw. der Wagen
data class SeatSection(val name: String /* "Business", "Premium Economy", "2. Klasse" */, val rows: List<SeatRow>)
data class SeatRow(val label: String /* "43" bzw. Reihenindex */, val cells: List<Cell>)
sealed Cell: Seat(code: "43E" | "111", window: Boolean, extras: Set<Extra>) | Aisle | Gap | Facility(Toilet, Galley, Exit, Luggage, Baby, Wheelchair, Table)
```

Flugzeuge: Reihen von vorn nach hinten, Sitze quer (A … K). Züge: der Wagen als Draufsicht, Sitzpaare entlang zweier
Seiten mit Gang in der Mitte (ICE: Nummern wie 111/114).

### Erste Einträge (abgeschrieben aus den Plänen des Nutzers)

| Schlüssel | Modell | Zuordnung (`matches`) |
|---|---|---|
| `db-ice4-2kl` | ICE 4 (BR 412), Wagen 2. Klasse | ICE 571, ICE 1272 |
| `ci-a359` | China Airlines A350-900 (Business 10–18, Premium Economy 30–34, Economy 40–52, 60–75) | CI 61, CI 62, CI 100 |
| `ci-a21n` | China Airlines A321neo (Business 10–12, Economy 40–68) | CI 105 |
| `nh-a21n` | ANA A321neo (Reihen 1–37, Exits bei 11 und 24) | NH 1861 |
| `nh-789` | ANA 787-9 (Business 1–11, Premium Economy 15–16, Economy 20–42) | NH 468 |

Weitere Modelle (Shinkansen N700S/E5/E7 usw.) kommen später als Einträge dazu; kein neuer Zeichencode nötig.

### Logik (`SeatMaps`, getestet)

- `modelFor(ride)`: `seatMap` von Hand, sonst Treffer über Flug-/Zugnummer (normalisiert: Leerzeichen/Großschreibung,
  „CI62“ = „CI 62“), sonst null.
- `locate(model, seat)`: Abschnitt, Reihe, Fenster/Mitte/Gang, Nachbarsitz; unbekannter Platz → null (Anzeige ohne Plan).

### Anzeige (App)

- Karte „Sitzplätze“ auf der Detailseite einer Fahrt: Klasse, Plätze mit Lage („43E · Mitte, 43G · Gang, nebeneinander“),
  darunter der gezeichnete Plan (Compose `Canvas`), auf den Abschnitt mit euren Plätzen gescrollt, eure Plätze
  hervorgehoben; ganzer Plan per Tippen im Vollbild.
- Ohne Katalog-Treffer: nur Klasse und Plätze, Hinweis „Sitzplan wählen“ (Formular).

## Teil C: Zimmer & Ausstattung der Unterkunft

### Datenmodell

`Stay` bekommt `info: StayInfo? = null` und `infoLinks: List<String> = emptyList()`:

```kotlin
@Serializable data class StayInfo(
    val room: RoomInfo = RoomInfo(), val property: PropertyInfo = PropertyInfo(),
    val sources: List<String> = emptyList(), val researchedAt: Long = 0,
    val editedByHand: Set<String> = emptySet(),   // Feld-Schlüssel, z. B. "room.type", "property.amenities.Bad"
)
@Serializable data class RoomInfo(val type: String = "", val description: String = "", val sizeSqm: Double? = null,
    val beds: String = "", val bath: String = "", val view: String = "", val maxGuests: Int? = null,
    val amenities: List<AmenityGroup> = emptyList())
@Serializable data class PropertyInfo(val description: String = "", val meals: String = "", val houseRules: String = "",
    val amenities: List<AmenityGroup> = emptyList())
@Serializable data class AmenityGroup(val name: String, val items: List<Amenity>)   // Bad, Schlafen, Technik, Essen & Trinken, Onsen & Spa, Service, Sonstiges
@Serializable data class Amenity(val name: String, val note: String = "")
```

Zimmerfotos: vorhandene Fotogalerie der Unterkunft; `Attachment` bekommt `label: String? = null` („room“ = Zimmer).

### Zusammenführen (`StayInfoMerge.merge(old, new)`, shared, getestet)

Neue Recherche ersetzt alle Felder/Gruppen außer denen in `old.editedByHand`; `editedByHand` bleibt. „Alles neu
recherchieren“ (mit Rückfrage) leert `editedByHand` vorher. Jede Bearbeitung in der App trägt den Schlüssel ein.

### Recherche-Auftrag (Server, `AiJobs` Art `"stay"`, `POST /api/ai/stay` `{stayId, links, fresh}`)

1. **Bestätigungen**: PDF-/Bild-Anhänge der Unterkunft (`targetType = STAY`) gehen an das Medienmodell (Gemini) mit der
   Anweisung, nur Zimmertyp, Betten, Verpflegung, Zeiten, Zimmerausstattung zu nennen – keine Namen, Nummern, Preise.
   Der Server streicht zusätzlich Bestätigungsnummer und Reisendennamen der Unterkunft aus der Antwort.
2. **Links**: jeder Link über Parallel (Seite lesen → Fakten mit Quelle); die passende Parallel-Schnittstelle (Extract
   bzw. Task mit vorgegebener URL) wird bei der Umsetzung geprüft. Bilder des Zimmertyps (höchstens 8) werden, wo die
   Seite es erlaubt, geladen und als `label = "room"`-Fotos angehängt.
3. **Websuche** (nur Ausstattung der Unterkunft): `ParallelFacts` über den Namen + Google-Details (Kapitel Google
   Places).
4. **Schreiben**: Sonnet → `StayInfo`-JSON; Zimmerfakten aus der Bestätigung haben Vorrang (der gebuchte Zimmertyp,
   nicht irgendeiner). Deutsch-Absicherung wie bei der Recherche. Speichern über `StayInfoMerge`.

Fehler: eine unlesbare Quelle wird im Ablauf vermerkt, der Rest läuft weiter; ohne jede Angabe wird nichts gespeichert.

### Anzeige & Bearbeiten (App)

- Unterkunftsseite: Karte „Zimmer“ (Typ, Größe, Betten, Bad, Aussicht, Ausstattung in Gruppen, Fotostreifen „Zimmer“),
  Karte „Unterkunft“ (Beschreibung, Verpflegung, Hausregeln, Ausstattung), Quellen; Knöpfe „Recherchieren“ (Dialog mit
  Links, gemerkt in `infoLinks`) und „Bearbeiten“; laufender Auftrag mit Fortschritt; leer: Hinweis „Zimmer & Ausstattung
  recherchieren“.
- „Unterkunft bearbeiten“: Bereiche „Zimmer“ und „Ausstattung“ (Textfelder, Ausstattungsgruppen mit Einträgen
  hinzufügen/löschen/verschieben); Foto in der Galerie als „Zimmer“ markieren.
- Chat: Zimmer- und Ausstattungsinfos in der Unterkunftszeile des festen Kontexts.

## Tests

- shared: Codec-Roundtrip `StayInfo`, `Seat`; `StayInfoMerge` (Handänderung bleibt, „alles neu“ ersetzt);
  `SeatMaps.modelFor` (Nummer normalisiert, Handwahl gewinnt), `locate` (43E Mitte, 43G Gang im CI-A350; 34A Fenster;
  111/114 im ICE 4; unbekannter Platz → null); Katalog-Plausibilität (keine doppelten Sitzcodes je Modell).
- server: Stay-Auftrag mit Fakes (Gemini, Parallel, Sonnet): eine kaputte Quelle bricht nicht ab; Namen und
  Buchungsnummer erscheinen nie im gespeicherten Ergebnis; Handfelder bleiben.
- app: Sitzkarte zeigt Lage und hebt Plätze hervor (Compose-Test über Semantik), Bearbeiten von Klasse/Plätzen,
  Bearbeiten der Ausstattung setzt `editedByHand`.
- manuell: eine echte Unterkunft mit Link und Bestätigung; Sitzpläne gegen die Vorlagen abgleichen.
