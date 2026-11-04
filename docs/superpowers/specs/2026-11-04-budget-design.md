# Budget – Design

Datum: 2026-11-04 · Status: zur Freigabe

## Ziel

Das Budget aus Wanderlog übernehmen und ausbauen: Kosten an Unterkünften, Flügen, Zügen und Orten (oder frei)
eintragen, in einem eigenen Tab „Budget“ alle Kosten sehen, sie flexibel unter den Reisenden aufteilen und
festhalten, wer ursprünglich gezahlt hat, was beim Anbieter noch offen ist und wer wem schon wie viel
zurückgezahlt hat.

## Entscheidungen (mit dem Nutzer abgestimmt)

- „Bezahlt“ heißt beides: Status beim Anbieter **und** Rückzahlungen unter den Reisenden (pro Kosten), dazu
  globale Ausgleichszahlungen wie in Wanderlog.
- Währungen: jede Kosten hat ihre Währung und einen gespeicherten Kurs zu Euro; Übersicht in Euro.
  Kursvorschlag beim Eintragen; beim Wechsel auf „bezahlt“ wird der Kurs **einmal** auf den Tageskurs
  aktualisiert. Betrag, Währung und Kurs bleiben jederzeit von Hand änderbar; ein von Hand gesetzter Kurs wird
  nicht automatisch überschrieben.
- Wanderlog: Kosten, Mitreisende, Zahler, Aufteilung und Ausgleichszahlungen werden importiert; neue
  Wanderlog-Kosten kommen weiter dazu, Änderungen in der App haben Vorrang (bestehende Reimport-Regeln).
- Übersicht als eigener Tab „Budget“ (zwischen Logistik und Mehr).

## Datenmodell (shared, synchronisiert feldweise wie bisher)

**Trip** bekommt `travelers: List<Traveler>` mit `Traveler(id, name)`. Aus Wanderlog: `contributors` →
`Traveler("wl:<userId>", name)`. In der App: Personen hinzufügen, umbenennen, entfernen (nur wenn an keiner
Kosten beteiligt). Eine Basiswährung `EUR` (Konstante; keine Einstellung in v1).

**Expense** (vorhanden: id, amount, currency, category, description, date, relatedId) bekommt:

| Feld | Typ | Bedeutung |
|---|---|---|
| `kind` | `"expense"` \| `"transfer"` | Kosten oder Ausgleichszahlung (Default `expense`) |
| `paidBy` | String? | Traveler-id: wer ursprünglich zahlt/gezahlt hat (bei transfer: von wem) |
| `splitMode` | `EQUAL` \| `PERCENT` \| `AMOUNT` | Art der Aufteilung (Default `EQUAL`) |
| `shares` | List<`ExpenseShare(travelerId, value, repaid)`> | beteiligte Personen; `value` = Prozent bzw. Betrag (bei EQUAL ignoriert); `repaid` = schon an `paidBy` zurückgezahlt (in der Währung der Kosten) |
| `status` | `OPEN` \| `DEPOSIT` \| `PAID` | Status beim Anbieter (Default `PAID` für transfer) |
| `deposit` | Double? | angezahlter Betrag bei `DEPOSIT` |
| `rate` | Double? | Euro pro Einheit der Währung (1.0 bei EUR); null = unbekannt |
| `rateDate` | String? | Datum des Kurses (ISO) |
| `rateManual` | Boolean | Kurs von Hand gesetzt → keine automatische Aktualisierung |
| `toTraveler` | String? | nur transfer: an wen |

`relatedId` darf zusätzlich auf einen SavedPlace zeigen (Kosten an Orten).

Kategorien (Schlüssel wie Wanderlog): `lodging`, `flights`, `publicTransit`, `carRental`, `food`, `drinks`,
`sightseeing`, `activities`, `shopping`, `gas`, `groceries`, `other` – mit deutschen Labels; unbekannte
Schlüssel werden als „Sonstiges“ angezeigt.

Keine neue EntityType: Ausgleichszahlungen sind Expenses mit `kind = transfer` (ältere Server/Clients kennen
den Typ bereits).

## Rechenlogik (shared, `BudgetMath`, reine Funktionen, voll getestet)

- `eur(e) = amount × rate` (rate null → nicht umrechenbar: wird separat als „ohne Kurs“ gezählt, nie still 0).
- Anteile: EQUAL → Betrag / Anzahl Beteiligte (Rundungsrest auf den ersten Anteil, Cent-genau);
  PERCENT → Betrag × p / 100; AMOUNT → value. `validate(e)` meldet: Prozent ≠ 100, Beträge ≠ Betrag,
  keine Beteiligten, Zahler fehlt, repaid > Anteil.
- Saldo pro Person (in EUR): + Summe der von ihr gezahlten Kosten − eigene Anteile; Rückzahlungen: Schuldner
  `+repaid`, Zahler `−repaid`; transfer A→B mit Betrag x: A `+x`, B `−x`. Summe aller Salden = 0.
- Ausgleichsvorschlag: Gläubiger und Schuldner gierig paaren (größter Schuldner an größten Gläubiger), Beträge
  unter 0,50 € weglassen → Liste „A zahlt B x €“.
- Übersicht: Summe gesamt (nur `expense`), nach Kategorie, nach Person (gezahlt / Anteil / Saldo), „beim
  Anbieter offen“ = Summe von OPEN plus (Betrag − deposit) bei DEPOSIT.

## Kurse (Server)

`GET /api/rates?from=JPY&date=YYYY-MM-DD` → `{from, to:"EUR", rate, date}` über die Frankfurter-API
(EZB-Referenzkurse, kostenlos, ohne Schlüssel), in der vorhandenen Cache-Tabelle gespeichert (Tageskurse
unveränderlich, „latest“ 6 h). EUR → 1.0 ohne Abruf. Fehler: 502 `rates_unavailable`; die App lässt dann
den Kurs leer bzw. unverändert und zeigt einen Hinweis.

App-Regeln:
- Neue Kosten: Kurs vom Tag des Eintragens vorschlagen (`rateDate` = heute).
- Status wechselt auf `PAID` und `rateManual == false`: einmal den Tageskurs holen und speichern.
- Kurs von Hand geändert → `rateManual = true`. Währung geändert → Kurs neu vorschlagen, `rateManual = false`.

## Wanderlog-Import

- `contributors` → `Trip.travelers` (Namen; keine weiteren Profildaten).
- Expense: `paidByUserId` → `paidBy`; `splitWith.users` → `shares` (EQUAL); `splitWith.type` ≠ individuals →
  alle Reisenden. Status aus der Beschreibung: „nicht bezahlt“ (beliebige Schreibweise) → OPEN, sonst
  „bezahlt“ → PAID, sonst PAID. Text bleibt unverändert. `currencyRateDate` + EUR → `rate = 1.0`; andere
  Währungen ohne Kurs (die App schlägt ihn beim Öffnen nicht automatisch vor – „ohne Kurs“ sichtbar).
- `budget.payments` → transfer-Expenses (`fromUser` → `paidBy`, `toUser` → `toTraveler`, `paidAt` → date).
- Reimport: bestehende Merger-Regeln (App-Änderungen behalten), neue Kosten/Zahlungen kommen dazu.

## Oberfläche (App)

**Tab „Budget“** (Icon Wallet/Payments), 6. Tab zwischen Logistik und Mehr:
1. Kopf: Gesamtsumme in €, darunter „beim Anbieter offen: x €“ und ggf. „n Kosten ohne Kurs“.
2. Personen-Karten: gezahlt / Anteil / Saldo („bekommt 120 €“ / „schuldet 120 €“).
3. „Ausgleich“: Vorschlagszeilen „Selina → Christian 123,45 €“ mit Button „Als bezahlt eintragen“ (legt einen
   transfer an); darunter die bisherigen Ausgleichszahlungen.
4. Kategorien mit Summe und Anteil (Balken).
5. Liste aller Kosten (neueste Reisedaten zuerst, gruppiert nach Kategorie umschaltbar), Filter nach
   Kategorie und Person; Zeile: Beschreibung/Bezug, Betrag (Originalwährung + €), Zahler, Status-Badge.
6. FAB „Kosten hinzufügen“; „Personen“ im Kopf (Personen verwalten).

**Kosten-Formular** (Sheet, auch für Bearbeiten/Löschen): Beschreibung; Betrag + Währung (EUR, JPY, weitere
per Auswahl); Kurs (aufklappbar, mit Datum und „Kurs aktualisieren“); Kategorie; Datum; Bezug (Unterkunft,
Flug, Zug, Ort – Auswahl, optional); „bezahlt von“; Aufteilung Gleich / Prozent / Beträge mit Zeile pro
Person (Häkchen beteiligt, Wert, „zurückgezahlt“) und Live-Prüfung; Status offen / angezahlt (+Betrag) /
bezahlt. Speichern nur bei gültiger Aufteilung.

**Abschnitt „Kosten“** in Unterkunft-, Flug-, Zug- und Ort-Detail: verknüpfte Kosten mit Summe in €,
„Kosten hinzufügen“ mit vorausgefülltem Bezug, Kategorie (lodging/flights/publicTransit/passend zur
Ortskategorie) und Datum.

## Nicht in v1

Automatisches Nachziehen von Kursen offener Kosten, Export (CSV/PDF), Budget im Chat-Kontext, Budget-Limits
pro Kategorie, Belegfotos an Kosten (Anhänge bleiben über das verknüpfte Objekt möglich).

## Tests

- shared: BudgetMath (Anteile alle Modi inkl. Rundung, Validierung, Salden = 0, Rückzahlungen, Transfers,
  Ausgleichsvorschlag, offen beim Anbieter, ohne Kurs).
- importer: echte Struktur (contributors, paidBy, splitWith, payments, Status aus Beschreibung).
- server: `/api/rates` mit Fake-HTTP, Cache, EUR ohne Abruf, Fehler.
- app (Robolectric): Budget-Tab (Summen, Salden, Ausgleich eintragen), Formular (Moduswechsel, Validierung,
  Kurswechsel bei „bezahlt“, manueller Kurs bleibt), Kosten-Abschnitt an Unterkunft/Ort, Personen verwalten.

## Umsetzung in Schritten

1. Modell + BudgetMath (shared) mit Tests.
2. Import (importer) mit Tests.
3. Kurs-Endpunkt (server) + Client-Aufruf.
4. App: Tab, Formular, Kosten-Abschnitte, Personen; Version 0.26.0, CHANGELOG.
