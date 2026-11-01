package de.wanderlogplus.shared.model

import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.TravelMode
import kotlinx.serialization.Serializable

/** Dates are ISO strings ("2027-05-09"), times are "HH:mm". */

@Serializable
data class Trip(
    val id: String,
    val title: String = "",
    val startDate: String = "",
    val endDate: String = "",
    val wanderlogKey: String? = null,
)

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

enum class PlaceStatus { IDEA, PLANNED, VISITED, DROPPED }

@Serializable
data class SavedPlace(
    val id: String,
    val placeId: String = "",
    val regionId: String? = null,
    val category: PlaceCategory = PlaceCategory.SIGHT,
    val note: String = "",
    val tags: List<String> = emptyList(),
    val status: PlaceStatus = PlaceStatus.IDEA,
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
)

@Serializable
data class Expense(
    val id: String,
    val amount: Double = 0.0,
    val currency: String = "",
    val category: String? = null,
    val description: String = "",
    val date: String? = null,
    val relatedId: String? = null,
)
