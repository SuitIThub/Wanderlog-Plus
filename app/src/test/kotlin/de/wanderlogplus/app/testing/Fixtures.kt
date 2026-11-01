package de.wanderlogplus.app.testing

import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.importer.ImportRunner
import de.wanderlogplus.shared.model.*
import de.wanderlogplus.shared.sync.HlcClock
import de.wanderlogplus.shared.sync.InMemoryEntityStore
import java.io.File

/**
 * Anonymized test trip (fictional names, no real bookings): 3 regions, 6 days, places with image keys,
 * a train and a flight whose endpoints point at places with coordinates, stays and notes.
 */
object Fixtures {
    const val START = "2027-04-01"
    const val END = "2027-04-06"

    private const val LONG = " mit einem ausgesprochen langen Namen, der sicher umbricht"

    fun trip(longTexts: Boolean = false): TripSnapshot {
        fun n(s: String) = if (longTexts) s + LONG else s

        val regions = listOf(
            Region("r-berg", "Bergstadt", "#C0603A"),
            Region("r-kueste", "Küstenort", "#2E86AB"),
            Region("r-see", "Seenland", "#5B8C51", dayTripFromRegionId = "r-berg"),
        )
        val places = listOf(
            Place("p-hotel-berg", name = n("Gasthaus am Hang"), lat = 35.01, lng = 135.76, rating = 4.4, imageKeys = listOf("img-hotel-berg")),
            Place("p-hotel-kueste", name = n("Hotel Brandung"), lat = 34.69, lng = 135.50, rating = 4.1, imageKeys = listOf("", "img-hotel-kueste")),
            Place(
                "p-tempel", name = n("Alter Bergtempel"), lat = 35.02, lng = 135.78, rating = 4.8, numRatings = 1200,
                minMinutes = 60, maxMinutes = 120, categories = listOf("temple"), imageKeys = listOf("img-tempel"),
                description = if (longTexts) "Ein ruhiger Tempel auf dem Hügel. ".repeat(12) else "Ein ruhiger Tempel auf dem Hügel.",
                openingPeriods = (0..6).map { OpeningPeriod(it, "08:00", it, "17:00") },
            ),
            Place("p-markt", name = n("Fischmarkt"), lat = 34.68, lng = 135.51, rating = 4.3, imageKeys = listOf("img-markt"),
                openingPeriods = (1..6).map { OpeningPeriod(it, "06:00", it, "14:00") }),
            Place("p-nudel", name = n("Nudelküche Weizenhalm"), lat = 35.00, lng = 135.75, rating = 4.6, imageKeys = listOf("img-nudel")),
            Place("p-garten", name = n("Moosgarten"), lat = 35.03, lng = 135.79, rating = 4.5),
            Place("p-ufer", name = n("Uferpromenade"), lat = 35.20, lng = 136.05, rating = 4.0, imageKeys = listOf("img-ufer")),
            Place("p-bhf-berg", name = "Bahnhof Bergstadt", lat = 34.98, lng = 135.75),
            Place("p-bhf-kueste", name = "Bahnhof Küstenort", lat = 34.70, lng = 135.49),
            Place("p-flughafen-a", name = "Flughafen Nord", lat = 35.55, lng = 139.78),
            Place("p-flughafen-b", name = "Flughafen Süd", lat = 34.43, lng = 135.23),
        ).associateBy { it.id }
        val saved = listOf(
            SavedPlace("s-tempel", "p-tempel", "r-berg", PlaceCategory.SIGHT, tags = listOf("⭐ Muss man sehen"), status = PlaceStatus.PLANNED),
            SavedPlace("s-nudel", "p-nudel", "r-berg", PlaceCategory.FOOD, note = "Mittags oft Schlange."),
            SavedPlace("s-garten", "p-garten", "r-berg", PlaceCategory.SIGHT, tags = listOf("⚠️ Nur mit Reservierung")),
            SavedPlace("s-markt", "p-markt", "r-kueste", PlaceCategory.FOOD, status = PlaceStatus.PLANNED),
            SavedPlace("s-ufer", "p-ufer", "r-see", PlaceCategory.SIGHT),
        )
        val stays = listOf(
            Stay(
                "st-berg", "p-hotel-berg", "r-berg", "2027-04-01", "2027-04-04",
                confirmationNumber = "TEST-0001", travelerNames = listOf("Person A", "Person B"),
                cancellationDeadline = "2027-03-20", note = "Check-in ab 15 Uhr",
            ),
            Stay("st-kueste", "p-hotel-kueste", "r-kueste", "2027-04-04", "2027-04-06", confirmationNumber = "TEST-0002"),
        )
        val trains = listOf(
            Train(
                "tr-1", carrier = "Schnellzug 101",
                depart = Endpoint("Bahnhof Bergstadt", placeId = "p-bhf-berg", date = "2027-04-04", time = "10:12"),
                arrive = Endpoint("Bahnhof Küstenort", placeId = "p-bhf-kueste", date = "2027-04-04", time = "10:41"),
            ),
        )
        val flights = listOf(
            Flight(
                "fl-1", airline = "Testflug", airlineCode = "TF", number = "123",
                depart = Endpoint("Flughafen Nord", "NRD", "p-flughafen-a", "2027-04-01", "08:30"),
                arrive = Endpoint("Flughafen Süd", "SUD", "p-flughafen-b", "2027-04-01", "09:45"),
            ),
            Flight(
                "fl-2", airline = "Testflug", airlineCode = "TF", number = "456",
                depart = Endpoint("Flughafen Süd", "SUD", "p-flughafen-b", "2027-04-06", "18:00"),
                arrive = Endpoint("Flughafen Nord", "NRD", "p-flughafen-a", "2027-04-06", "19:20"),
            ),
        )
        val days = listOf(
            Day("d-1", "2027-04-01", n("Ankunft"), "Leicht starten."),
            Day("d-2", "2027-04-02", n("Tempel und Gärten"), if (longTexts) "**Wichtig:** früh los. ".repeat(10) else "**Wichtig:** früh los."),
            Day("d-3", "2027-04-03", n("Ausflug ans Wasser"), regionOverrideId = "r-see"),
            Day("d-4", "2027-04-04", n("Weiter an die Küste")),
            Day("d-5", "2027-04-05", n("Markt und Strand")),
            Day("d-6", "2027-04-06", n("Heimreise")),
        )
        val items = listOf(
            DayItem("i-1", "d-2", "a0", DayItemKind.NOTE, text = "Tickets am Eingang kaufen"),
            DayItem("i-2", "d-2", "a1", DayItemKind.PLACE, "p-tempel", time = "09:00"),
            DayItem("i-3", "d-2", "a2", DayItemKind.PLACE, "p-nudel", time = "12:30"),
            DayItem("i-4", "d-3", "a0", DayItemKind.PLACE, "p-ufer"),
            DayItem("i-5", "d-5", "a0", DayItemKind.PLACE, "p-markt", time = "07:00"),
        )
        val notes = listOf(
            Note("n-1", "Packliste", "- Adapter\n- Regenjacke", "a0"),
            Note("n-2", "Sätze", if (longTexts) "Danke. ".repeat(40) else "Danke.", "a1"),
        )
        return TripSnapshot(
            trip = Trip("t-1", n("Testreise"), START, END),
            regions = regions,
            places = places,
            savedPlaces = saved,
            stays = stays,
            flights = flights,
            trains = trains,
            days = days,
            dayItems = items,
            notes = notes,
            conflicts = emptyList(),
        )
    }

    /** The private real export run through the importer, or null when `testdata/private/wanderlog-real.json` is absent. */
    fun realOrNull(): TripSnapshot? {
        val root = System.getProperty("repoRoot") ?: return null
        val file = File(root, "testdata/private/wanderlog-real.json")
        if (!file.isFile) return null
        val store = InMemoryEntityStore()
        ImportRunner.run(file.readText(), store, HlcClock("fixtures"))
        return TripSnapshot.from(store.all())
    }
}
