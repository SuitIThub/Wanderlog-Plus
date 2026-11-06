package de.wanderlogplus.client

import de.wanderlogplus.shared.model.*
import de.wanderlogplus.shared.query.TripQueries
import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

data class Conflict(
    val type: EntityType,
    val id: String,
    val field: String,
    val appValue: JsonElement,
    val wanderlogValue: JsonElement,
    val label: String,
)

data class TripSnapshot(
    val trip: Trip?,
    val regions: List<Region>,
    val places: Map<String, Place>,
    val savedPlaces: List<SavedPlace>,
    val stays: List<Stay>,
    val flights: List<Flight>,
    val trains: List<Train>,
    val days: List<Day>,
    val dayItems: List<DayItem>,
    val notes: List<Note>,
    val conflicts: List<Conflict>,
    val attachments: List<Attachment> = emptyList(),
    /** Costs and settlement payments of the budget. */
    val expenses: List<Expense> = emptyList(),
    /** Travel guides (etiquette, language …) and their settings. */
    val guides: List<Guide> = emptyList(),
    val guidePrefs: GuidePrefs = GuidePrefs(),
    /** Everyone's votes on saved places ([de.wanderlogplus.shared.model.Votes]). */
    val votes: List<de.wanderlogplus.shared.model.Vote> = emptyList(),
) {
    /** Files of an entity (tickets, PDFs …), without the gallery photos. */
    fun attachmentsFor(type: EntityType, id: String): List<Attachment> =
        attachments.filter { it.targetType == type && it.targetId == id && it.kind != PHOTO }.sortedWith(compareBy({ it.fileName }, { it.id }))

    /** The gallery of an entity (place or day), oldest first (ids sort by creation time). */
    fun photosFor(type: EntityType, id: String): List<Attachment> =
        attachments.filter { it.targetType == type && it.targetId == id && it.kind == PHOTO }.sortedBy { it.id }

    val isEmpty: Boolean get() = trip == null

    fun queries(): TripQueries? = trip?.let { TripQueries(it, stays, days, regions, savedPlaces, dayItems) }

    fun dayItemsFor(dayId: String): List<DayItem> =
        dayItems.filter { it.dayId == dayId }.sortedWith(compareBy({ it.sortKey }, { it.id }))

    companion object {
        fun from(records: List<EntityRecord>): TripSnapshot {
            val live = records.filter { !it.isDeleted }

            fun <T> decoded(type: EntityType, decode: (EntityRecord) -> T): List<T> =
                live.filter { it.type == type }.sortedBy { it.id }.mapNotNull { r ->
                    try { decode(r) } catch (_: Exception) { null }
                }

            val places = decoded(EntityType.PLACE) { EntityCodec.decode<Place>(it) }.associateBy { it.id }
            val trip = decoded(EntityType.TRIP) { EntityCodec.decode<Trip>(it) }.firstOrNull()
            return TripSnapshot(
                trip = trip,
                regions = decoded(EntityType.REGION) { EntityCodec.decode<Region>(it) },
                places = places,
                savedPlaces = decoded(EntityType.SAVED_PLACE) { EntityCodec.decode<SavedPlace>(it) },
                stays = decoded(EntityType.STAY) { EntityCodec.decode<Stay>(it) },
                flights = decoded(EntityType.FLIGHT) { EntityCodec.decode<Flight>(it) },
                trains = decoded(EntityType.TRAIN) { EntityCodec.decode<Train>(it) },
                days = decoded(EntityType.DAY) { EntityCodec.decode<Day>(it) },
                dayItems = decoded(EntityType.DAY_ITEM) { EntityCodec.decode<DayItem>(it) },
                notes = decoded(EntityType.NOTE) { EntityCodec.decode<Note>(it) },
                conflicts = conflictsOf(live, places),
                attachments = decoded(EntityType.ATTACHMENT) { EntityCodec.decode<Attachment>(it) },
                expenses = decoded(EntityType.EXPENSE) { EntityCodec.decode<Expense>(it) },
                guides = decoded(EntityType.GUIDE) { EntityCodec.decode<Guide>(it) },
                guidePrefs = decoded(EntityType.GUIDE_PREFS) { EntityCodec.decode<GuidePrefs>(it) }.firstOrNull() ?: GuidePrefs(),
                votes = decoded(EntityType.VOTE) { EntityCodec.decode<de.wanderlogplus.shared.model.Vote>(it) },
            )
        }

        private fun conflictsOf(live: List<EntityRecord>, places: Map<String, Place>): List<Conflict> =
            live.sortedWith(compareBy({ it.type }, { it.id })).flatMap { r ->
                r.fields.entries
                    .filter { (k, v) -> k.startsWith("_conflict.") && v.value !is JsonNull }
                    .sortedBy { it.key }
                    .map { (k, v) ->
                        val field = k.removePrefix("_conflict.")
                        Conflict(r.type, r.id, field, r.fields[field]?.value ?: JsonNull, v.value, labelOf(r, places))
                    }
            }

        private fun text(r: EntityRecord, field: String): String? =
            ((r.value(field) as? JsonPrimitive)?.takeIf { it.isString })?.content?.takeIf { it.isNotBlank() }

        private fun labelOf(r: EntityRecord, places: Map<String, Place>): String {
            fun placeName(): String? = text(r, "placeId")?.let { places[it]?.name }?.takeIf { it.isNotBlank() }
            return when (r.type) {
                EntityType.SAVED_PLACE, EntityType.STAY -> placeName() ?: text(r, "name")
                EntityType.DAY_ITEM -> placeName() ?: text(r, "text")
                else -> text(r, "title") ?: text(r, "name")
            } ?: r.type.name
        }
    }
}

/** [Attachment.kind] of gallery photos. */
const val PHOTO = "photo"
