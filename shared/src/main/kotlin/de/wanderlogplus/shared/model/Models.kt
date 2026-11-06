package de.wanderlogplus.shared.model

import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.TravelMode
import de.wanderlogplus.shared.sync.Worth
import kotlinx.serialization.Serializable

/** Dates are ISO strings ("2027-05-09"), times are "HH:mm". */

@Serializable
data class Trip(
    val id: String,
    val title: String = "",
    val startDate: String = "",
    val endDate: String = "",
    val wanderlogKey: String? = null,
    /** The people travelling (for the budget: who paid, who shares). */
    val travelers: List<Traveler> = emptyList(),
)

@Serializable
data class Traveler(val id: String, val name: String = "")

@Serializable
data class Region(
    val id: String,
    val name: String = "",
    val color: String? = null,
    val dayTripFromRegionId: String? = null,
)

/** Day 0 = Sunday, times "HH:mm". */
@Serializable
data class OpeningPeriod(
    val openDay: Int = 0,
    val openTime: String = "",
    val closeDay: Int? = null,
    val closeTime: String? = null,
)

@Serializable
data class Place(
    val id: String,
    val googlePlaceId: String = "",
    val name: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val address: String? = null,
    val description: String? = null,
    val rating: Double? = null,
    val numRatings: Int? = null,
    val minMinutes: Int? = null,
    val maxMinutes: Int? = null,
    val categories: List<String> = emptyList(),
    val website: String? = null,
    val phone: String? = null,
    val priceLevel: Int? = null,
    val openingPeriods: List<OpeningPeriod> = emptyList(),
    val imageKeys: List<String> = emptyList(),
    val googleMapsUrl: String? = null,
)

enum class PlaceCategory { SIGHT, FOOD, SHOPPING, NIGHTLIFE, OTHER }

/** IDEA → SHORTLIST („Vorgemerkt“: we would like to) → PLANNED (on a day); VISITED, DROPPED. */
enum class PlaceStatus { IDEA, SHORTLIST, PLANNED, VISITED, DROPPED }

@Serializable
data class SavedPlace(
    val id: String,
    val placeId: String = "",
    val regionId: String? = null,
    val category: PlaceCategory = PlaceCategory.SIGHT,
    val note: String = "",
    val tags: List<String> = emptyList(),
    val status: PlaceStatus = PlaceStatus.IDEA,
    /** The assistant's judgement of how worthwhile a visit is; null = not judged yet. */
    val worth: Worth? = null,
    val worthReason: String = "",
    /** Multi-criteria judgement and research results; null = never researched or judged by criteria. */
    val dossier: PlaceDossier? = null,
    /** The travellers booked it (a reservation or tickets): no more reservation reminders. */
    val reserved: Boolean = false,
    /** When to reserve, set by hand; null: worked out from the research and the plan ([de.wanderlogplus.shared.query.ReservationDates]). */
    val reservationWindow: ReservationWindow? = null,
    /** Own header image: "wl:<image key>", "att:<attachment id>" or "g:<Google photo name>"; null: the first place image. */
    val headerImage: String? = null,
    /** Own links shown as buttons on the page (tickets, booking, info). */
    val links: List<CustomLink> = emptyList(),
)

@Serializable
data class Stay(
    val id: String,
    val placeId: String = "",
    val regionId: String? = null,
    val checkIn: String = "",
    val checkOut: String = "",
    val confirmationNumber: String? = null,
    val travelerNames: List<String> = emptyList(),
    val adults: Int? = null,
    val rooms: Int? = null,
    val cancellationDeadline: String? = null,
    val cancellationPolicy: String? = null,
    val note: String = "",
    /** Own header image: "wl:<image key>", "att:<attachment id>" or "g:<Google photo name>"; null: the first place image. */
    val headerImage: String? = null,
    /** Own links shown as buttons on the page (tickets, booking, info). */
    val links: List<CustomLink> = emptyList(),
    /** "HH:mm" from which one can check in, and until which one has to check out; null: unknown. */
    val checkInTime: String? = null,
    val checkOutTime: String? = null,
    /** The booked room and what the stay offers (research, corrected by hand). */
    val info: StayInfo? = null,
    /** Pages to read when researching the room and amenities (hotel site, booking page). */
    val infoLinks: List<String> = emptyList(),
    /** When it can and must be booked (e.g. a ryokan dinner); null: no deadline. */
    val reservation: ReservationWindow? = null,
)

@Serializable
data class Endpoint(
    val name: String = "",
    val code: String? = null,
    val placeId: String? = null,
    val date: String? = null,
    val time: String? = null,
    /** Coordinates picked in the app (place search); win over [placeId]'s place. */
    val lat: Double? = null,
    val lng: Double? = null,
)

@Serializable
data class Flight(
    val id: String,
    val airline: String? = null,
    val airlineCode: String? = null,
    val number: String? = null,
    val depart: Endpoint = Endpoint(),
    val arrive: Endpoint = Endpoint(),
    val confirmationNumber: String? = null,
    val travelerNames: List<String> = emptyList(),
    val note: String = "",
    /** Stopovers between [depart] and [arrive], in travel order. */
    val via: List<Endpoint> = emptyList(),
    /** Own links shown as buttons on the page (tickets, booking, info). */
    val links: List<CustomLink> = emptyList(),
    /** Legs with the same id form one journey (changes between them); null: a ride on its own. */
    val journeyId: String? = null,
    /** Position in its journey when sorted by hand; null: by departure time. */
    val journeyOrder: Int? = null,    /** "Economy", "2. Klasse", "Green Car" …; null: not given. */
    val travelClass: String? = null,
    val seats: List<Seat> = emptyList(),
    /** Key of the seat map chosen by hand ([de.wanderlogplus.shared.seatmap.SeatMaps]); null: found by the number. */
    val seatMap: String? = null,
    /** When seats can and must be reserved; null: no deadline. */
    val reservation: ReservationWindow? = null,
)

@Serializable
data class Train(
    val id: String,
    val carrier: String? = null,
    val depart: Endpoint = Endpoint(),
    val arrive: Endpoint = Endpoint(),
    val confirmationNumber: String? = null,
    val travelerNames: List<String> = emptyList(),
    val note: String = "",
    /** Changes or stops between [depart] and [arrive] (e.g. Frankfurt Hbf), in travel order. */
    val via: List<Endpoint> = emptyList(),
    /** Train name and number, e.g. "Nozomi 21" (for live status later). */
    val number: String? = null,
    /** The timetable connection taken over from the transit search (platforms, changes); null when entered by hand. */
    val transit: de.wanderlogplus.shared.sync.TransitResult? = null,
    /** Own links shown as buttons on the page (tickets, booking, info). */
    val links: List<CustomLink> = emptyList(),
    /** Legs with the same id form one journey (changes between them); null: a ride on its own. */
    val journeyId: String? = null,
    /** Position in its journey when sorted by hand; null: by departure time. */
    val journeyOrder: Int? = null,    /** "Economy", "2. Klasse", "Green Car" …; null: not given. */
    val travelClass: String? = null,
    val seats: List<Seat> = emptyList(),
    /** Key of the seat map chosen by hand ([de.wanderlogplus.shared.seatmap.SeatMaps]); null: found by the number. */
    val seatMap: String? = null,
    /** When seats can and must be reserved; null: no deadline. */
    val reservation: ReservationWindow? = null,
)

@Serializable
data class Day(
    val id: String,
    val date: String = "",
    val title: String = "",
    val note: String = "",
    val regionOverrideId: String? = null,
    /** Show travel legs (time, distance, map) between the program's places. */
    val showLegs: Boolean = false,
    /** Earlier versions of the program (before a chat plan was applied or a version restored), newest last. */
    val history: List<DayVersion> = emptyList(),
    /** Own links shown as buttons on the page (tickets, booking, info). */
    val links: List<CustomLink> = emptyList(),
    /** Ways of the day that end at no program item: "ride:<TYPE>:<id>" (to a departure) and "night" (to the stay). */
    val legs: Map<String, DayLeg> = emptyMap(),
)

/** How a way of the day is travelled; [transit] is a connection picked in "Verbindung abfragen". */
@Serializable
data class DayLeg(
    val mode: de.wanderlogplus.shared.sync.TravelMode? = null,
    val transit: de.wanderlogplus.shared.sync.TransitResult? = null,
)

/** A saved state of a day's program: which items, in which order, at which times. */
@Serializable
data class DayVersion(val at: Long, val label: String, val items: List<DayVersionItem> = emptyList())

/** One program item of a [DayVersion]; its other fields stay on the (possibly deleted) item itself. */
@Serializable
data class DayVersionItem(
    val id: String,
    val sortKey: String,
    val time: String? = null,
    val kind: DayItemKind = DayItemKind.PLACE,
    val placeId: String? = null,
)

enum class DayItemKind { PLACE, NOTE }

@Serializable
data class DayItem(
    val id: String,
    val dayId: String = "",
    val sortKey: String = "",
    val kind: DayItemKind = DayItemKind.PLACE,
    val placeId: String? = null,
    val text: String = "",
    val time: String? = null,
    /** How to get here from the previous place of the day; null = on foot. */
    val legMode: TravelMode? = null,
    /** The public-transport connection chosen for that way ("Verbindung abfragen"); null = found automatically. */
    val legTransit: de.wanderlogplus.shared.sync.TransitResult? = null,
)

@Serializable
data class Note(
    val id: String,
    val title: String = "",
    val body: String = "",
    val sortKey: String = "",
)

@Serializable
data class Attachment(
    val id: String,
    val targetType: EntityType = EntityType.TRIP,
    val targetId: String = "",
    val fileHash: String = "",
    val fileName: String = "",
    val mimeType: String = "",
    val size: Long = 0,
    /** "file" (Anhänge: tickets, PDFs …) or "photo" (the gallery of a place or a day). */
    val kind: String = "file",
    /** What a photo shows, e.g. [LABEL_ROOM] for the booked room of a stay; null: nothing special. */
    val label: String? = null,
) {
    companion object {
        const val LABEL_ROOM = "room"
    }
}

/**
 * A cost of the trip, or with [kind] [Expense.KIND_TRANSFER] a settlement payment from [paidBy] to [toTraveler].
 * [relatedId] links a stay, flight, train or saved place. [rate] is euros per unit of [currency] (null: unknown).
 */
@Serializable
data class Expense(
    val id: String,
    val amount: Double = 0.0,
    val currency: String = "",
    val category: String? = null,
    val description: String = "",
    val date: String? = null,
    val relatedId: String? = null,
    val kind: String = KIND_EXPENSE,
    /** Traveler id of who paid (or pays) the cost; for a transfer: who paid. */
    val paidBy: String? = null,
    val splitMode: SplitMode = SplitMode.EQUAL,
    val shares: List<ExpenseShare> = emptyList(),
    /** Status with the hotel, airline, shop …; a transfer is always paid. */
    val status: PaymentStatus = PaymentStatus.PAID,
    /** Amount paid in advance when [status] is [PaymentStatus.DEPOSIT]. */
    val deposit: Double? = null,
    val rate: Double? = null,
    val rateDate: String? = null,
    /** The rate was typed in: never replaced automatically. */
    val rateManual: Boolean = false,
    /** For a transfer: who received it. */
    val toTraveler: String? = null,
) {
    val isTransfer: Boolean get() = kind == KIND_TRANSFER

    companion object {
        const val KIND_EXPENSE = "expense"
        const val KIND_TRANSFER = "transfer"
    }
}

/**
 * One traveller's part of an [Expense]: [value] is the percentage ([SplitMode.PERCENT]) or the amount
 * ([SplitMode.AMOUNT]), unused for [SplitMode.EQUAL]; [repaid] is what they already gave back to the payer (in the
 * expense's currency).
 */
@Serializable
data class ExpenseShare(val travelerId: String, val value: Double = 0.0, val repaid: Double = 0.0)

enum class SplitMode { EQUAL, PERCENT, AMOUNT }

enum class PaymentStatus { OPEN, DEPOSIT, PAID }

/** A link of the travellers: [label] shown on the button, [url] opened. */
@Serializable
data class CustomLink(val label: String = "", val url: String = "")
