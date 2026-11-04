package de.wanderlogplus.client

import de.wanderlogplus.shared.model.DayItem
import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Ids
import de.wanderlogplus.shared.model.PlaceStatus
import de.wanderlogplus.shared.sync.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

interface Transactor {
    fun <T> run(block: () -> T): T
}

object NoTransaction : Transactor {
    override fun <T> run(block: () -> T): T = block()
}

class LocalEditor(
    private val store: EntityStore,
    private val outbox: Outbox,
    private val clock: HlcClock,
    private val tx: Transactor = NoTransaction,
    private val onChange: () -> Unit = {},
) {
    private companion object {
        val DAY_FIELDS = setOf("title", "note", "regionOverrideId", "showLegs")
    }

    private fun isReserved(field: String) = field == "_source" || field == "_sourceId" || field.startsWith("_base.")

    /** Validates and writes without notifying; callers must be inside [tx]. */
    private fun write(type: EntityType, id: String, values: Map<String, JsonElement>) {
        val bad = values.keys.firstOrNull(::isReserved)
        require(bad == null) { "Field '$bad' is reserved and cannot be written by a device" }
        if (values.isEmpty()) return
        val accepted = store.applyChanges(values.map { (field, value) -> clock.change(type, id, field, value) })
        if (accepted.isNotEmpty()) outbox.add(accepted)
    }

    private fun writeNew(type: EntityType, id: String, values: Map<String, JsonElement>) =
        write(type, id, values + ("deleted" to JsonPrimitive(false)))

    /** A saved place (status "geplant") for an existing [placeId], e.g. a place of a day that is in no collection yet. Returns its id. */
    fun addSavedPlace(placeId: String, regionId: String?, category: de.wanderlogplus.shared.model.PlaceCategory = de.wanderlogplus.shared.model.PlaceCategory.SIGHT): String {
        val id = de.wanderlogplus.shared.model.Ids.newId()
        writeNew(
            EntityType.SAVED_PLACE, id,
            mapOf(
                "placeId" to JsonPrimitive(placeId),
                "regionId" to (regionId?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull),
                "category" to JsonPrimitive(category.name),
                "status" to JsonPrimitive(de.wanderlogplus.shared.model.PlaceStatus.PLANNED.name),
            ),
        )
        return id
    }

    fun set(type: EntityType, id: String, values: Map<String, JsonElement>) {
        val bad = values.keys.firstOrNull(::isReserved)
        require(bad == null) { "Field '$bad' is reserved and cannot be written by a device" }
        if (values.isEmpty()) return
        tx.run { write(type, id, values) }
        onChange()
    }

    fun create(type: EntityType, values: Map<String, JsonElement>, id: String = Ids.newId()): String {
        set(type, id, values + ("deleted" to JsonPrimitive(false)))
        return id
    }

    fun delete(type: EntityType, id: String) =
        set(type, id, mapOf("deleted" to JsonPrimitive(true)))

    fun addSuggestionToDay(snapshot: TripSnapshot, date: String, savedPlaceId: String, position: ItemPosition = ItemPosition.End): String {
        val trip = requireNotNull(snapshot.trip) { "No trip" }
        val saved = requireNotNull(snapshot.savedPlaces.firstOrNull { it.id == savedPlaceId }) { "Unknown saved place $savedPlaceId" }
        val existing = snapshot.queries()?.dayFor(date)
        val siblings = existing?.let { snapshot.dayItemsFor(it.id) }.orEmpty()
        siblings.firstOrNull { it.kind == DayItemKind.PLACE && it.placeId == saved.placeId }?.let { return it.id }
        val dayId = existing?.id ?: Ids.dayId(trip.id, date)
        val itemId = Ids.newId()
        tx.run {
            if (existing == null) writeNew(EntityType.DAY, dayId, mapOf("date" to JsonPrimitive(date)))
            writeNew(
                EntityType.DAY_ITEM,
                itemId,
                mapOf(
                    "dayId" to JsonPrimitive(dayId),
                    "kind" to JsonPrimitive(DayItemKind.PLACE.name),
                    "placeId" to JsonPrimitive(saved.placeId),
                    "sortKey" to JsonPrimitive(insertKey(siblings, position)),
                ),
            )
            write(EntityType.SAVED_PLACE, saved.id, mapOf("status" to JsonPrimitive(PlaceStatus.PLANNED.name)))
        }
        onChange()
        return itemId
    }

    /**
     * Puts a stay's hotel into the day's program at [position] (e.g. as the day's start or the evening's destination).
     * The same stay may appear several times a day; nothing else changes.
     */
    fun addStayToDay(snapshot: TripSnapshot, date: String, stayId: String, position: ItemPosition = ItemPosition.End): String {
        val trip = requireNotNull(snapshot.trip) { "No trip" }
        val stay = requireNotNull(snapshot.stays.firstOrNull { it.id == stayId }) { "Unknown stay" }
        require(stay.placeId.isNotBlank()) { "Stay has no place" }
        val existing = snapshot.queries()?.dayFor(date)
        val siblings = existing?.let { snapshot.dayItemsFor(it.id) }.orEmpty()
        val dayId = existing?.id ?: Ids.dayId(trip.id, date)
        val itemId = Ids.newId()
        tx.run {
            if (existing == null) writeNew(EntityType.DAY, dayId, mapOf("date" to JsonPrimitive(date)))
            writeNew(
                EntityType.DAY_ITEM,
                itemId,
                mapOf(
                    "dayId" to JsonPrimitive(dayId),
                    "kind" to JsonPrimitive(DayItemKind.PLACE.name),
                    "placeId" to JsonPrimitive(stay.placeId),
                    "sortKey" to JsonPrimitive(insertKey(siblings, position)),
                ),
            )
        }
        onChange()
        return itemId
    }

    /**
     * Adds a place found by search, a reel or similar (not from Wanderlog) to the collection of [regionId] (null: no
     * region): a new Place with optional [description] and a SavedPlace with status "Idee", [note], [tags] and any
     * further SavedPlace fields in [savedExtra]. One transaction; returns the SavedPlace id.
     */
    fun addPlaceToCollection(
        name: String,
        lat: Double,
        lng: Double,
        address: String?,
        category: PlaceCategory,
        regionId: String?,
        description: String? = null,
        note: String = "",
        tags: List<String> = emptyList(),
        savedExtra: Map<String, JsonElement> = emptyMap(),
    ): String {
        require(name.isNotBlank()) { "A place needs a name" }
        val placeId = Ids.newId()
        val savedId = Ids.newId()
        tx.run {
            writeNew(
                EntityType.PLACE,
                placeId,
                buildMap {
                    put("name", JsonPrimitive(name.trim()))
                    put("lat", JsonPrimitive(lat))
                    put("lng", JsonPrimitive(lng))
                    address?.takeIf { it.isNotBlank() }?.let { put("address", JsonPrimitive(it)) }
                    description?.takeIf { it.isNotBlank() }?.let { put("description", JsonPrimitive(it)) }
                },
            )
            writeNew(
                EntityType.SAVED_PLACE,
                savedId,
                buildMap {
                    put("placeId", JsonPrimitive(placeId))
                    regionId?.let { put("regionId", JsonPrimitive(it)) }
                    put("category", JsonPrimitive(category.name))
                    put("status", JsonPrimitive(PlaceStatus.IDEA.name))
                    if (note.isNotBlank()) put("note", JsonPrimitive(note))
                    if (tags.isNotEmpty()) put("tags", kotlinx.serialization.json.JsonArray(tags.map { JsonPrimitive(it) }))
                    putAll(savedExtra)
                },
            )
        }
        onChange()
        return savedId
    }

    /**
     * Adds a place found by search (not from Wanderlog) to the day at [position]: a new Place, a SavedPlace in the day's
     * region with status "geplant" (so it also shows under Orte) and the program item, in one transaction.
     */
    fun addPlaceToDay(
        snapshot: TripSnapshot,
        date: String,
        name: String,
        lat: Double,
        lng: Double,
        address: String?,
        category: PlaceCategory,
        position: ItemPosition = ItemPosition.End,
    ): String {
        val trip = requireNotNull(snapshot.trip) { "No trip" }
        require(name.isNotBlank()) { "A place needs a name" }
        val queries = snapshot.queries()
        val existing = queries?.dayFor(date)
        val siblings = existing?.let { snapshot.dayItemsFor(it.id) }.orEmpty()
        val dayId = existing?.id ?: Ids.dayId(trip.id, date)
        val placeId = Ids.newId()
        val savedId = Ids.newId()
        val itemId = Ids.newId()
        tx.run {
            if (existing == null) writeNew(EntityType.DAY, dayId, mapOf("date" to JsonPrimitive(date)))
            writeNew(
                EntityType.PLACE,
                placeId,
                buildMap {
                    put("name", JsonPrimitive(name.trim()))
                    put("lat", JsonPrimitive(lat))
                    put("lng", JsonPrimitive(lng))
                    address?.takeIf { it.isNotBlank() }?.let { put("address", JsonPrimitive(it)) }
                },
            )
            writeNew(
                EntityType.SAVED_PLACE,
                savedId,
                buildMap {
                    put("placeId", JsonPrimitive(placeId))
                    queries?.regionFor(date)?.let { put("regionId", JsonPrimitive(it)) }
                    put("category", JsonPrimitive(category.name))
                    put("status", JsonPrimitive(PlaceStatus.PLANNED.name))
                },
            )
            writeNew(
                EntityType.DAY_ITEM,
                itemId,
                mapOf(
                    "dayId" to JsonPrimitive(dayId),
                    "kind" to JsonPrimitive(DayItemKind.PLACE.name),
                    "placeId" to JsonPrimitive(placeId),
                    "sortKey" to JsonPrimitive(insertKey(siblings, position)),
                ),
            )
        }
        onChange()
        return itemId
    }

    /**
     * Sort key for a new item at [position] among [siblings] (sorted). Equal or inverted neighbour keys (concurrent
     * edits) get the whole day re-keyed first, inside the caller's transaction, so there is always room in between.
     */
    private fun insertKey(siblings: List<DayItem>, position: ItemPosition): String {
        val index = when (position) {
            ItemPosition.Start -> 0
            ItemPosition.End -> siblings.size
            is ItemPosition.After -> siblings.indexOfFirst { it.id == position.itemId }.let { if (it < 0) siblings.size else it + 1 }
        }
        val before = siblings.getOrNull(index - 1)?.sortKey
        val after = siblings.getOrNull(index)?.sortKey
        if (before == null || after == null || before < after) {
            try {
                return SortKey.between(before, after)
            } catch (_: IllegalArgumentException) {
                // fall through to re-keying
            }
        }
        val keys = SortKey.sequence(siblings.size + 1)
        siblings.forEachIndexed { i, d ->
            val k = keys[if (i < index) i else i + 1]
            write(EntityType.DAY_ITEM, d.id, mapOf("sortKey" to JsonPrimitive(k)))
        }
        return keys[index]
    }

    fun addAttachment(
        targetType: EntityType,
        targetId: String,
        fileHash: String,
        fileName: String,
        mimeType: String,
        size: Long,
    ): String = create(
        EntityType.ATTACHMENT,
        mapOf(
            "targetType" to JsonPrimitive(targetType.name),
            "targetId" to JsonPrimitive(targetId),
            "fileHash" to JsonPrimitive(fileHash),
            "fileName" to JsonPrimitive(fileName),
            "mimeType" to JsonPrimitive(mimeType),
            "size" to JsonPrimitive(size),
        ),
    )

    fun setDayFields(snapshot: TripSnapshot, date: String, values: Map<String, JsonElement>) {
        val bad = values.keys.firstOrNull { it !in DAY_FIELDS }
        require(bad == null) { "Field not editable on a day" }
        val trip = requireNotNull(snapshot.trip) { "No trip" }
        if (values.isEmpty()) return
        val existing = snapshot.queries()?.dayFor(date)
        tx.run {
            if (existing == null) {
                writeNew(EntityType.DAY, Ids.dayId(trip.id, date), values + ("date" to JsonPrimitive(date)))
            } else {
                write(EntityType.DAY, existing.id, values)
            }
        }
        onChange()
    }

    /** Writes only the fields whose value differs between [before] and [after] (e.g. `EntityCodec.fields` of an edit). */
    fun setChanged(type: EntityType, id: String, before: Map<String, JsonElement>, after: Map<String, JsonElement>) {
        val changed = after.filter { (k, v) -> before[k] != v }
        if (changed.isEmpty()) return
        set(type, id, changed)
    }

    /**
     * Joins two legs of one journey (both trains or both flights): the first keeps its start and gets the second's
     * destination, the first's destination becomes a stopover, the second is deleted and its attachments move to the
     * first. Booking number, travelers and notes are combined.
     */
    fun mergeMoves(snapshot: TripSnapshot, type: EntityType, firstId: String, secondId: String) {
        require(firstId != secondId) { "Cannot merge a leg with itself" }
        val (before, after) = when (type) {
            EntityType.TRAIN -> {
                val a = requireNotNull(snapshot.trains.firstOrNull { it.id == firstId }) { "Unknown leg" }
                val b = requireNotNull(snapshot.trains.firstOrNull { it.id == secondId }) { "Unknown leg" }
                EntityCodec.fields(a) to EntityCodec.fields(
                    a.copy(
                        carrier = a.carrier?.takeIf { it.isNotBlank() } ?: b.carrier,
                        arrive = b.arrive,
                        via = a.via + a.arrive + b.via,
                        confirmationNumber = mergedText(a.confirmationNumber, b.confirmationNumber, ", "),
                        travelerNames = (a.travelerNames + b.travelerNames).distinct(),
                        note = mergedText(a.note, b.note, "\n\n").orEmpty(),
                    ),
                )
            }
            EntityType.FLIGHT -> {
                val a = requireNotNull(snapshot.flights.firstOrNull { it.id == firstId }) { "Unknown leg" }
                val b = requireNotNull(snapshot.flights.firstOrNull { it.id == secondId }) { "Unknown leg" }
                EntityCodec.fields(a) to EntityCodec.fields(
                    a.copy(
                        arrive = b.arrive,
                        via = a.via + a.arrive + b.via,
                        confirmationNumber = mergedText(a.confirmationNumber, b.confirmationNumber, ", "),
                        travelerNames = (a.travelerNames + b.travelerNames).distinct(),
                        note = mergedText(a.note, b.note, "\n\n").orEmpty(),
                    ),
                )
            }
            else -> throw IllegalArgumentException("Only trains and flights can be merged")
        }
        tx.run {
            val changed = after.filter { (k, v) -> before[k] != v }
            if (changed.isNotEmpty()) write(type, firstId, changed)
            snapshot.attachmentsFor(type, secondId).forEach {
                write(EntityType.ATTACHMENT, it.id, mapOf("targetId" to JsonPrimitive(firstId)))
            }
            write(type, secondId, mapOf("deleted" to JsonPrimitive(true)))
        }
        onChange()
    }

    private fun mergedText(a: String?, b: String?, sep: String): String? {
        val parts = listOfNotNull(a?.takeIf { it.isNotBlank() }, b?.takeIf { it.isNotBlank() }).distinct()
        return if (parts.isEmpty()) a else parts.joinToString(sep)
    }

    fun addNoteToDay(snapshot: TripSnapshot, date: String, text: String, position: ItemPosition = ItemPosition.End): String {
        val trip = requireNotNull(snapshot.trip) { "No trip" }
        val existing = snapshot.queries()?.dayFor(date)
        val siblings = existing?.let { snapshot.dayItemsFor(it.id) }.orEmpty()
        val dayId = existing?.id ?: Ids.dayId(trip.id, date)
        val itemId = Ids.newId()
        tx.run {
            if (existing == null) writeNew(EntityType.DAY, dayId, mapOf("date" to JsonPrimitive(date)))
            writeNew(
                EntityType.DAY_ITEM,
                itemId,
                mapOf(
                    "dayId" to JsonPrimitive(dayId),
                    "kind" to JsonPrimitive(DayItemKind.NOTE.name),
                    "text" to JsonPrimitive(text),
                    "sortKey" to JsonPrimitive(insertKey(siblings, position)),
                ),
            )
        }
        onChange()
        return itemId
    }

    fun moveDayItem(snapshot: TripSnapshot, itemId: String, up: Boolean) {
        val item = snapshot.dayItems.firstOrNull { it.id == itemId } ?: return
        val siblings = snapshot.dayItemsFor(item.dayId)
        val idx = siblings.indexOfFirst { it.id == itemId }
        val target = if (up) idx - 1 else idx + 1
        if (idx < 0 || target < 0 || target > siblings.lastIndex) return
        val before = if (up) siblings.getOrNull(target - 1)?.sortKey else siblings[target].sortKey
        val after = if (up) siblings[target].sortKey else siblings.getOrNull(target + 1)?.sortKey
        tx.run {
            val key = if (before == null || after == null || before < after) {
                try { SortKey.between(before, after) } catch (_: IllegalArgumentException) { null }
            } else null
            if (key != null) {
                write(EntityType.DAY_ITEM, itemId, mapOf("sortKey" to JsonPrimitive(key)))
            } else {
                // Equal/inverted bounds (concurrent adds): re-key the whole day in the desired order.
                val order = siblings.toMutableList().apply { add(target, removeAt(idx)) }
                val keys = SortKey.sequence(order.size)
                order.forEachIndexed { i, d -> write(EntityType.DAY_ITEM, d.id, mapOf("sortKey" to JsonPrimitive(keys[i]))) }
            }
        }
        onChange()
    }

    fun removeDayItem(snapshot: TripSnapshot, itemId: String) {
        val item = snapshot.dayItems.firstOrNull { it.id == itemId }
        tx.run {
            write(EntityType.DAY_ITEM, itemId, mapOf("deleted" to JsonPrimitive(true)))
            if (item == null || item.kind != DayItemKind.PLACE || item.placeId == null) return@run
            val stillPlanned = snapshot.dayItems.any { it.id != itemId && it.kind == DayItemKind.PLACE && it.placeId == item.placeId }
            if (stillPlanned) return@run
            snapshot.savedPlaces
                .filter { it.placeId == item.placeId && it.status == PlaceStatus.PLANNED }
                .forEach { write(EntityType.SAVED_PLACE, it.id, mapOf("status" to JsonPrimitive(PlaceStatus.IDEA.name))) }
        }
        onChange()
    }
}
