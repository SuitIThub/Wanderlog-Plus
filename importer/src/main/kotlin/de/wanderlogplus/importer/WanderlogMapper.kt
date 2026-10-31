package de.wanderlogplus.importer

import de.wanderlogplus.shared.model.*
import de.wanderlogplus.shared.query.TripQueries
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.SortKey
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class ImportedEntity(
    val type: EntityType,
    val sourceId: String,
    val fields: Map<String, JsonElement>,
    val masterFields: Set<String> = emptySet(),
) {
    val id: String get() = EntityCodec.importedId(type, sourceId)
}

data class ImportSnapshot(val entities: List<ImportedEntity>)

object WanderlogMapper {
    private const val NEAR_KM = 50.0
    private const val SIGHTS = "Sehenswürdigkeiten"
    private val dayTripPattern = Regex("^(.+?) Tagesausflug \\((.+)\\)$")

    private fun pid(placeId: String) = EntityCodec.importedId(EntityType.PLACE, "place:$placeId")
    private fun sectionId(type: EntityType, section: WlSection) = EntityCodec.importedId(type, "section:${section.id}")

    private fun md(text: JsonObject?): String = QuillMarkdown.convert(text?.get("ops")?.jsonArray).trim()

    private fun regionName(heading: String?): String? {
        if (heading == null) return null
        dayTripPattern.matchEntire(heading)?.let { return it.groupValues[1] }
        if (heading.endsWith(SIGHTS)) return heading.removeSuffix(SIGHTS).trim()
        return null
    }

    private fun haversineKm(a: WlLocation, b: WlLocation): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * 6371.0 * asin(min(1.0, sqrt(h)))
    }

    private fun categoryFor(heading: String?) = when (heading) {
        "Food-Spots" -> PlaceCategory.FOOD
        "Shopping" -> PlaceCategory.SHOPPING
        "Nightlife" -> PlaceCategory.NIGHTLIFE
        else -> PlaceCategory.SIGHT
    }

    private fun time(raw: String) =
        if (raw.length == 4 && raw.all { it.isDigit() }) "${raw.take(2)}:${raw.drop(2)}" else raw

    private fun airportEndpoint(e: WlEndpoint) =
        Endpoint(name = e.airport?.name ?: "", code = e.airport?.iata, date = e.date, time = e.time)

    private fun stationEndpoint(e: WlEndpoint) =
        Endpoint(name = e.place?.name ?: "", placeId = e.place?.let { pid(it.placeId) }, date = e.date, time = e.time)

    private class RegionInfo(val section: WlSection, val name: String, val dayTripFrom: String?) {
        val id = EntityCodec.importedId(EntityType.REGION, "section:${section.id}")
        private val locations = section.blocks.filter { it.hotel == null }.mapNotNull { it.place?.geometry?.location }
        val centroid: WlLocation? =
            if (locations.isEmpty()) null else WlLocation(locations.map { it.lat }.average(), locations.map { it.lng }.average())
    }

    fun map(response: WlResponse): ImportSnapshot {
        val plan = response.tripPlan
        val sections = plan.itinerary.sections
        val metadata = response.resources.placeMetadata.associateBy { it.placeId }
        val out = mutableListOf<ImportedEntity>()

        fun add(type: EntityType, sourceId: String, fields: Map<String, JsonElement>, master: Set<String> = emptySet()) {
            out += ImportedEntity(type, sourceId, fields, master)
        }

        val trip = Trip(EntityCodec.importedId(EntityType.TRIP, "trip:${plan.key}"), plan.title, plan.startDate, plan.endDate, plan.key)
        add(EntityType.TRIP, "trip:${plan.key}", EntityCodec.fields(trip))

        // Regions
        val infos = sections.filter { it.mode != "dayPlan" }.mapNotNull { s ->
            regionName(s.heading)?.let { RegionInfo(s, it, dayTripPattern.matchEntire(s.heading!!)?.groupValues?.get(2)) }
        }
        val regionIdByName = infos.associate { it.name to it.id }
        val regions = infos.map {
            val r = Region(it.id, it.name, it.section.placeMarkerColor, it.dayTripFrom?.let { n -> regionIdByName[n] })
            add(EntityType.REGION, "section:${it.section.id}", EntityCodec.fields(r))
            r
        }
        val infoBySection = infos.associateBy { it.section.id }
        val homeRegions = infos.filter { it.dayTripFrom == null && it.centroid != null }

        fun nearest(loc: WlLocation): Pair<RegionInfo, Double>? =
            homeRegions.map { it to haversineKm(loc, it.centroid!!) }.minByOrNull { it.second }

        // Places, once per place_id
        val seen = mutableSetOf<String>()
        fun addPlace(p: WlPlace) {
            if (!seen.add(p.placeId)) return
            val m = metadata[p.placeId]
            val place = Place(
                id = "",
                googlePlaceId = p.placeId,
                name = p.name,
                lat = p.geometry.location.lat,
                lng = p.geometry.location.lng,
                address = p.formattedAddress,
                description = m?.generatedDescription ?: m?.description,
                rating = m?.rating ?: p.rating,
                numRatings = m?.numRatings ?: p.userRatingsTotal,
                minMinutes = m?.minMinutesSpent,
                maxMinutes = m?.maxMinutesSpent,
                categories = m?.categories ?: emptyList(),
                website = p.website,
                phone = p.internationalPhoneNumber,
                priceLevel = p.priceLevel,
                openingPeriods = m?.openingPeriods?.map {
                    OpeningPeriod(it.open.day, time(it.open.time), it.close?.day, it.close?.time?.let(::time))
                } ?: emptyList(),
                imageKeys = m?.imageKeys ?: emptyList(),
                googleMapsUrl = p.url,
            )
            val f = EntityCodec.fields(place)
            add(EntityType.PLACE, "place:${p.placeId}", f, f.keys)
        }
        sections.forEach { s ->
            s.blocks.forEach { b ->
                b.place?.let(::addPlace)
                b.depart?.place?.let(::addPlace)
                b.arrive?.place?.let(::addPlace)
            }
        }

        // Stays (hotel blocks only)
        val stays = mutableListOf<Stay>()
        sections.flatMap { it.blocks }.filter { it.hotel != null }.forEach { b ->
            val h = b.hotel!!
            val stay = Stay(
                id = "",
                placeId = b.place?.let { pid(it.placeId) } ?: "",
                regionId = b.place?.let { nearest(it.geometry.location)?.first?.id },
                checkIn = h.checkIn,
                checkOut = h.checkOut,
                confirmationNumber = h.confirmationNumber ?: b.confirmationNumber,
                travelerNames = h.travelerNames.ifEmpty { b.travelerNames },
                adults = b.guests?.adultCount,
                rooms = b.guests?.roomCount,
                cancellationDeadline = b.cancellationDeadline,
                cancellationPolicy = b.cancellationPolicy,
                note = md(b.text),
            )
            stays += stay
            add(EntityType.STAY, "block:${b.id}", EntityCodec.fields(stay))
        }

        // Days
        val daySections = sections.filter { it.mode == "dayPlan" }
        val days = daySections.map {
            val d = Day(sectionId(EntityType.DAY, it), it.date!!, it.heading ?: "", md(it.text))
            add(EntityType.DAY, "section:${it.id}", EntityCodec.fields(d))
            d
        }
        val queries = TripQueries(trip, stays, days, regions, emptyList(), emptyList())

        // Saved places
        sections.forEach { s ->
            val isDay = s.mode == "dayPlan"
            s.blocks.filter { it.type == "place" && it.hotel == null && it.place != null }.forEach { b ->
                val place = b.place!!
                val regionId = when {
                    isDay -> queries.regionFor(s.date!!)
                    infoBySection[s.id] != null -> infoBySection[s.id]!!.id
                    else -> nearest(place.geometry.location)?.takeIf { it.second <= NEAR_KM }?.first?.id
                }
                val sp = SavedPlace(
                    id = "",
                    placeId = pid(place.placeId),
                    regionId = regionId,
                    category = if (isDay) PlaceCategory.SIGHT else categoryFor(s.heading),
                    note = md(b.text),
                    tags = b.reactions.keys.toList(),
                    status = if (isDay) PlaceStatus.PLANNED else PlaceStatus.IDEA,
                )
                add(EntityType.SAVED_PLACE, "block:${b.id}", EntityCodec.fields(sp))
            }
        }

        // Flights and trains
        sections.flatMap { it.blocks }.forEach { b ->
            val depart = b.depart
            val arrive = b.arrive
            if (depart == null || arrive == null) return@forEach
            when (b.type) {
                "flight" -> {
                    val f = Flight(
                        id = "",
                        airline = b.flightInfo?.airline?.name,
                        airlineCode = b.flightInfo?.airline?.iata,
                        number = b.flightInfo?.number?.toString(),
                        depart = airportEndpoint(depart),
                        arrive = airportEndpoint(arrive),
                        confirmationNumber = b.confirmationNumber,
                        travelerNames = b.travelerNames,
                        note = md(b.text),
                    )
                    add(EntityType.FLIGHT, "block:${b.id}", EntityCodec.fields(f))
                }
                "train" -> {
                    val t = Train(
                        id = "",
                        carrier = b.carrier,
                        depart = stationEndpoint(depart),
                        arrive = stationEndpoint(arrive),
                        confirmationNumber = b.confirmationNumber,
                        travelerNames = b.travelerNames,
                        note = md(b.text),
                    )
                    add(EntityType.TRAIN, "block:${b.id}", EntityCodec.fields(t))
                }
            }
        }

        // Day items
        daySections.forEach { s ->
            val dayId = sectionId(EntityType.DAY, s)
            val blocks = s.blocks.filter { (it.type == "place" && it.place != null && it.hotel == null) || it.type == "note" }
            val keys = SortKey.sequence(blocks.size)
            blocks.forEachIndexed { i, b ->
                val item = if (b.type == "note") {
                    DayItem("", dayId, keys[i], DayItemKind.NOTE, null, md(b.text))
                } else {
                    DayItem("", dayId, keys[i], DayItemKind.PLACE, pid(b.place!!.placeId))
                }
                add(EntityType.DAY_ITEM, "block:${b.id}", EntityCodec.fields(item))
            }
        }

        // Notes
        val noteSections = sections.filter { it.mode != "dayPlan" }.mapNotNull { s ->
            val parts = listOf(md(s.text)) + s.blocks.filter { it.type == "note" }.map { md(it.text) }
            val body = parts.filter { it.isNotBlank() }.joinToString("\n\n")
            if (body.isBlank()) null else s to body
        }
        val noteKeys = SortKey.sequence(noteSections.size)
        noteSections.forEachIndexed { i, (s, body) ->
            add(EntityType.NOTE, "section:${s.id}", EntityCodec.fields(Note("", s.heading ?: "", body, noteKeys[i])))
        }

        // Expenses
        plan.itinerary.budget?.expenses?.forEach { e ->
            val x = Expense(
                id = "",
                amount = e.amount.amount,
                currency = e.amount.currencyCode,
                category = e.category,
                description = e.description,
                date = e.associatedDate,
                relatedId = e.blockId?.let { EntityCodec.importedId(EntityType.STAY, "block:$it") },
            )
            add(EntityType.EXPENSE, "expense:${e.id}", EntityCodec.fields(x))
        }

        return ImportSnapshot(out)
    }
}
