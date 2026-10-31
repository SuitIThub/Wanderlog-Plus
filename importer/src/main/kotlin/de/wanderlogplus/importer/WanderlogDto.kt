package de.wanderlogplus.importer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class WlResponse(
    val success: Boolean,
    val tripPlan: WlTripPlan,
    val resources: WlResources = WlResources(),
)

@Serializable
data class WlTripPlan(
    val key: String,
    val title: String,
    val startDate: String,
    val endDate: String,
    val itinerary: WlItinerary,
)

@Serializable
data class WlItinerary(
    val sections: List<WlSection>,
    val budget: WlBudget? = null,
)

@Serializable
data class WlSection(
    val id: Long,
    val type: String = "",
    val mode: String,
    val date: String? = null,
    val heading: String? = null,
    val text: JsonObject? = null,
    val blocks: List<WlBlock> = emptyList(),
)

@Serializable
data class WlBlock(
    val id: Long,
    val type: String,
    val place: WlPlace? = null,
    val text: JsonObject? = null,
    val hotel: WlHotel? = null,
    val guests: WlGuests? = null,
    val cancellationDeadline: String? = null,
    val cancellationPolicy: String? = null,
    val reactions: Map<String, List<Long>> = emptyMap(),
    val flightInfo: WlFlightInfo? = null,
    val depart: WlEndpoint? = null,
    val arrive: WlEndpoint? = null,
    val carrier: String? = null,
    val confirmationNumber: String? = null,
    val travelerNames: List<String> = emptyList(),
)

@Serializable
data class WlLocation(val lat: Double, val lng: Double)

@Serializable
data class WlGeometry(val location: WlLocation)

@Serializable
data class WlAddressComponent(
    @SerialName("long_name") val longName: String? = null,
    @SerialName("short_name") val shortName: String? = null,
    val types: List<String> = emptyList(),
)

@Serializable
data class WlPlace(
    @SerialName("place_id") val placeId: String,
    val name: String,
    val geometry: WlGeometry,
    @SerialName("formatted_address") val formattedAddress: String? = null,
    val rating: Double? = null,
    @SerialName("user_ratings_total") val userRatingsTotal: Int? = null,
    val website: String? = null,
    @SerialName("international_phone_number") val internationalPhoneNumber: String? = null,
    @SerialName("price_level") val priceLevel: Int? = null,
    val url: String? = null,
    @SerialName("address_components") val addressComponents: List<WlAddressComponent> = emptyList(),
)

@Serializable
data class WlAirport(val iata: String? = null, val name: String? = null)

@Serializable
data class WlAirline(val iata: String? = null, val name: String? = null)

@Serializable
data class WlFlightInfo(val airline: WlAirline? = null, val number: Int? = null)

@Serializable
data class WlEndpoint(
    val date: String? = null,
    val time: String? = null,
    val airport: WlAirport? = null,
    val place: WlPlace? = null,
)

@Serializable
data class WlHotel(
    val checkIn: String,
    val checkOut: String,
    val travelerNames: List<String> = emptyList(),
    val confirmationNumber: String? = null,
)

@Serializable
data class WlGuests(val adultCount: Int? = null, val roomCount: Int? = null)

@Serializable
data class WlBudget(val expenses: List<WlExpense> = emptyList())

@Serializable
data class WlAmount(val amount: Double, val currencyCode: String)

@Serializable
data class WlExpense(
    val id: Long,
    val amount: WlAmount,
    val category: String? = null,
    val description: String = "",
    val associatedDate: String? = null,
    val blockId: Long? = null,
)

@Serializable
data class WlResources(val placeMetadata: List<WlPlaceMetadata> = emptyList())

@Serializable
data class WlOpeningTime(val day: Int, val time: String)

@Serializable
data class WlOpeningPeriod(val open: WlOpeningTime, val close: WlOpeningTime? = null)

@Serializable
data class WlPlaceMetadata(
    val placeId: String,
    val description: String? = null,
    val generatedDescription: String? = null,
    val categories: List<String> = emptyList(),
    val minMinutesSpent: Int? = null,
    val maxMinutesSpent: Int? = null,
    val rating: Double? = null,
    val numRatings: Int? = null,
    val imageKeys: List<String> = emptyList(),
    val openingPeriods: List<WlOpeningPeriod> = emptyList(),
)

class ImportFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

object WanderlogParser {
    // coerceInputValues: the real export has explicit nulls for list fields (e.g. openingPeriods).
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun parse(json: String): WlResponse {
        val success = try {
            (this.json.parseToJsonElement(json) as? JsonObject)?.get("success")?.jsonPrimitive?.booleanOrNull
        } catch (e: SerializationException) {
            throw ImportFormatException("Invalid Wanderlog JSON: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw ImportFormatException("Invalid Wanderlog JSON: ${e.message}", e)
        }
        if (success != true) throw ImportFormatException("Wanderlog response has success != true")
        val response = try {
            this.json.decodeFromString<WlResponse>(json)
        } catch (e: SerializationException) {
            throw ImportFormatException("Invalid Wanderlog JSON: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw ImportFormatException("Invalid Wanderlog JSON: ${e.message}", e)
        }
        if (!response.success) throw ImportFormatException("Wanderlog response has success != true")
        for (section in response.tripPlan.itinerary.sections) {
            if (section.mode == "dayPlan" && section.date == null) {
                throw ImportFormatException("Section ${section.id} is a dayPlan without date")
            }
            for (block in section.blocks) {
                when (block.type) {
                    "place" -> if (block.place == null) {
                        throw ImportFormatException("Block ${block.id} of type place has no place")
                    }
                    "flight" -> if (block.depart == null || block.arrive == null) {
                        throw ImportFormatException("Block ${block.id} of type flight lacks depart/arrive")
                    }
                }
            }
        }
        return response
    }
}
