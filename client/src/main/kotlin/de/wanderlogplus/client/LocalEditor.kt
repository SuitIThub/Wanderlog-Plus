package de.wanderlogplus.client

import de.wanderlogplus.shared.model.DayItem
import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.model.DayVersion
import de.wanderlogplus.shared.model.DayVersionItem
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Flight
import de.wanderlogplus.shared.model.Train
import de.wanderlogplus.shared.model.Ids
import de.wanderlogplus.shared.model.PlaceStatus
import de.wanderlogplus.shared.sync.*
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import de.wanderlogplus.shared.model.DayLeg
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import de.wanderlogplus.shared.query.PlaceMatch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
        val DAY_FIELDS = setOf("title", "note", "regionOverrideId", "showLegs", "legs")
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
        /** Google's place id when known: the server then adds hours, rating and photos. */
        googlePlaceId: String? = null,
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
                    googlePlaceId?.takeIf { it.isNotBlank() }?.let { put("googlePlaceId", JsonPrimitive(it)) }
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
        /** "file" or "photo" (gallery). */
        kind: String = "file",
    ): String = create(
        EntityType.ATTACHMENT,
        mapOf(
            "targetType" to JsonPrimitive(targetType.name),
            "targetId" to JsonPrimitive(targetId),
            "fileHash" to JsonPrimitive(fileHash),
            "fileName" to JsonPrimitive(fileName),
            "mimeType" to JsonPrimitive(mimeType),
            "size" to JsonPrimitive(size),
        ) + (if (kind != "file") mapOf("kind" to JsonPrimitive(kind)) else emptyMap()),
    )

    /** Mode and connection of a way of [date] that ends at no program item ([DayFlow] key); null [leg] clears it. */
    fun setDayLeg(snapshot: TripSnapshot, date: String, key: String, leg: DayLeg?) {
        val current = snapshot.queries()?.dayFor(date)?.legs.orEmpty()
        val next = if (leg == null) current - key else current + (key to leg)
        setDayFields(snapshot, date, mapOf("legs" to LEG_JSON.encodeToJsonElement(MapSerializer(String.serializer(), DayLeg.serializer()), next)))
    }

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

    /**
     * Groups the trains/flights [legs] into one journey: an existing journey among them is extended, else a new one is
     * made. Returns the journey id. Nothing is deleted; every leg keeps its data.
     */
    fun joinJourney(snapshot: TripSnapshot, legs: List<Pair<EntityType, String>>): String {
        require(legs.distinct().size >= 2) { "A journey needs two legs" }
        fun journeyOf(type: EntityType, id: String): String? = when (type) {
            EntityType.TRAIN -> snapshot.trains.firstOrNull { it.id == id }?.journeyId
            EntityType.FLIGHT -> snapshot.flights.firstOrNull { it.id == id }?.journeyId
            else -> throw IllegalArgumentException("Only trains and flights form journeys")
        }
        val existing = legs.mapNotNull { (t, i) -> journeyOf(t, i)?.takeIf { it.isNotBlank() } }.firstOrNull()
        val jid = existing ?: Ids.newId()
        val before = existing?.let { j -> journeys(snapshot).firstOrNull { it.id == j } }
        tx.run {
            legs.distinct().forEach { (t, i) -> if (journeyOf(t, i) != jid) write(t, i, mapOf("journeyId" to JsonPrimitive(jid))) }
            // A journey sorted by hand keeps its order; new legs go where their time fits.
            if (before != null && hasManualOrder(before)) {
                val order = before.legs.map { it.type to it.id }.toMutableList()
                val added = legs.distinct().filter { it !in order }.map { r -> r to legOf(movementOf(snapshot, r)) }.sortedBy { it.second.departs }
                added.forEach { (r, leg) ->
                    val at = before.legs.indexOfFirst { e -> leg.departs != null && e.departs != null && e.departs > leg.departs }
                    val pos = if (at < 0) order.size else order.indexOf(before.legs[at].type to before.legs[at].id)
                    order.add(pos, r)
                }
                writeOrder(order)
            }
        }
        onChange()
        return jid
    }

    private fun movementOf(snapshot: TripSnapshot, r: Pair<EntityType, String>): de.wanderlogplus.shared.query.Movement = when (r.first) {
        EntityType.FLIGHT -> movementOf(snapshot.flights.first { it.id == r.second })
        else -> movementOf(snapshot.trains.first { it.id == r.second })
    }

    private fun writeOrder(order: List<Pair<EntityType, String>>) =
        order.forEachIndexed { i, (t, id) -> write(t, id, mapOf("journeyOrder" to JsonPrimitive(i))) }

    /** Moves the leg at [index] of [journeyId] one up or down; the journey is sorted by hand from then on. */
    fun moveJourneyLeg(snapshot: TripSnapshot, journeyId: String, index: Int, up: Boolean) {
        val j = journeys(snapshot).firstOrNull { it.id == journeyId } ?: return
        val to = if (up) index - 1 else index + 1
        if (index !in j.legs.indices || to !in j.legs.indices) return
        val order = j.legs.map { it.type to it.id }.toMutableList()
        order.add(to, order.removeAt(index))
        tx.run { writeOrder(order) }
        onChange()
    }

    /**
     * Puts a just-created leg [leg] of [journeyId] right after [prev] (or before [next]): the chosen place in the
     * journey, written as its order. [snapshot] may be from before the leg was created.
     */
    fun placeInJourney(snapshot: TripSnapshot, journeyId: String, leg: Pair<EntityType, String>, prev: Pair<EntityType, String>?, next: Pair<EntityType, String>?) {
        val j = journeys(snapshot).firstOrNull { it.id == journeyId } ?: return
        val order = j.legs.map { it.type to it.id }.filter { it != leg }.toMutableList()
        val pos = when {
            prev != null && prev in order -> order.indexOf(prev) + 1
            next != null && next in order -> order.indexOf(next)
            else -> order.size
        }
        order.add(pos, leg)
        tx.run { writeOrder(order) }
        onChange()
    }

    private fun changedOnly(before: Map<String, JsonElement>, after: Map<String, JsonElement>) = after.filter { (k, v) -> before[k] != v }

    /** Takes a leg out of its journey; a journey left with one leg is dissolved. */
    fun leaveJourney(snapshot: TripSnapshot, type: EntityType, id: String) {
        val j = journeyOf(snapshot, type, id) ?: return
        tx.run {
            val rest = j.legs.filterNot { it.type == type && it.id == id }
            write(type, id, mapOf("journeyId" to JsonNull, "journeyOrder" to JsonNull))
            if (rest.size < 2) rest.forEach { write(it.type, it.id, mapOf("journeyId" to JsonNull, "journeyOrder" to JsonNull)) }
        }
        onChange()
    }

    /** Dissolves a whole journey (its legs stay). */
    fun dissolveJourney(snapshot: TripSnapshot, journeyId: String) {
        val j = journeys(snapshot).firstOrNull { it.id == journeyId } ?: return
        tx.run { j.legs.forEach { write(it.type, it.id, mapOf("journeyId" to JsonNull, "journeyOrder" to JsonNull)) } }
        onChange()
    }

    /**
     * Splits an old merged train/flight (one entry with stopovers) into legs start→stop 1→…→destination, all in one new
     * journey. A stop's time becomes the arrival of the leg before it; the next leg's departure time stays open. The
     * first leg keeps number, booking, note and attachments. Returns the journey id.
     */
    fun splitLegacy(snapshot: TripSnapshot, type: EntityType, id: String): String {
        val jid = Ids.newId()
        fun stop(e: Endpoint, asArrival: Boolean) = if (asArrival) e else e.copy(time = null)
        tx.run {
            when (type) {
                EntityType.TRAIN -> {
                    val t = requireNotNull(snapshot.trains.firstOrNull { it.id == id }) { "Unknown leg" }
                    require(t.via.isNotEmpty()) { "Nothing to split" }
                    val points = listOf(t.depart) + t.via + t.arrive
                    val legs = points.zipWithNext()
                    val first = t.copy(arrive = legs[0].second, via = emptyList(), journeyId = jid, transit = null)
                    changedOnly(EntityCodec.fields(t), EntityCodec.fields(first)).let { write(EntityType.TRAIN, id, it) }
                    legs.drop(1).forEach { (a, b) ->
                        writeNew(EntityType.TRAIN, Ids.newId(), EntityCodec.fields(Train("", carrier = t.carrier, depart = stop(a, false), arrive = b, journeyId = jid)))
                    }
                }
                EntityType.FLIGHT -> {
                    val f = requireNotNull(snapshot.flights.firstOrNull { it.id == id }) { "Unknown leg" }
                    require(f.via.isNotEmpty()) { "Nothing to split" }
                    val points = listOf(f.depart) + f.via + f.arrive
                    val legs = points.zipWithNext()
                    val first = f.copy(arrive = legs[0].second, via = emptyList(), journeyId = jid)
                    changedOnly(EntityCodec.fields(f), EntityCodec.fields(first)).let { write(EntityType.FLIGHT, id, it) }
                    legs.drop(1).forEach { (a, b) ->
                        writeNew(EntityType.FLIGHT, Ids.newId(), EntityCodec.fields(Flight("", airline = f.airline, airlineCode = f.airlineCode, depart = stop(a, false), arrive = b, journeyId = jid)))
                    }
                }
                else -> throw IllegalArgumentException("Only trains and flights can be split")
            }
        }
        onChange()
        return jid
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

    /**
     * Deletes a saved place from the collection, with its program items on every day (unless another saved place of
     * the same place still exists, e.g. a duplicate). Returns how many program items went with it.
     */
    fun deleteSavedPlace(snapshot: TripSnapshot, savedPlaceId: String): Int {
        val saved = requireNotNull(snapshot.savedPlaces.firstOrNull { it.id == savedPlaceId }) { "Unknown saved place $savedPlaceId" }
        val twin = snapshot.savedPlaces.any { it.id != saved.id && it.placeId == saved.placeId }
        val items = if (twin) emptyList() else snapshot.dayItems.filter { it.kind == DayItemKind.PLACE && it.placeId == saved.placeId }
        tx.run {
            write(EntityType.SAVED_PLACE, saved.id, mapOf("deleted" to JsonPrimitive(true)))
            items.forEach { write(EntityType.DAY_ITEM, it.id, mapOf("deleted" to JsonPrimitive(true))) }
        }
        onChange()
        return items.size
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

    /**
     * Applies a plan to the program of [date] in one transaction: [ops] in order (existing items kept, maybe at a new
     * time; saved places or found places added), [remove] deleted; items in neither stay, after the plan. The program
     * before is saved first as a version labelled [label] (at [now], epoch ms). Returns that version's time.
     */
    fun applyDayPlan(snapshot: TripSnapshot, date: String, ops: List<PlanOp>, remove: Set<String>, label: String, now: Long): Long {
        val trip = requireNotNull(snapshot.trip) { "No trip" }
        val queries = snapshot.queries()
        val existing = queries?.dayFor(date)
        val dayId = existing?.id ?: Ids.dayId(trip.id, date)
        val current = existing?.let { snapshot.dayItemsFor(it.id) }.orEmpty()
        val currentIds = current.map { it.id }.toSet()
        tx.run {
            if (existing == null) writeNew(EntityType.DAY, dayId, mapOf("date" to JsonPrimitive(date)))
            saveVersion(dayId, existing?.history.orEmpty(), current, label, now)
            val order = mutableListOf<Pair<String, String?>>() // item id, time to set (null: unchanged)
            val inDay = current.filter { it.kind == DayItemKind.PLACE && it.id !in remove }.mapNotNull { it.placeId }.toMutableSet()
            ops.forEach { op ->
                when (op) {
                    is PlanOp.Keep -> if (op.itemId in currentIds && op.itemId !in remove && order.none { it.first == op.itemId }) order += op.itemId to op.time
                    is PlanOp.AddSaved -> {
                        val saved = snapshot.savedPlaces.firstOrNull { it.id == op.savedPlaceId } ?: return@forEach
                        if (!inDay.add(saved.placeId)) return@forEach
                        val itemId = Ids.newId()
                        writeNew(EntityType.DAY_ITEM, itemId, mapOf("dayId" to JsonPrimitive(dayId), "kind" to JsonPrimitive(DayItemKind.PLACE.name), "placeId" to JsonPrimitive(saved.placeId)))
                        write(EntityType.SAVED_PLACE, saved.id, mapOf("status" to JsonPrimitive(PlaceStatus.PLANNED.name)))
                        order += itemId to op.time
                    }
                    is PlanOp.AddFound -> {
                        require(op.name.isNotBlank()) { "A place needs a name" }
                        fun same(placeId: String?) = placeId?.let { snapshot.places[it] }
                            ?.let { p -> PlaceMatch.same(op.name, op.lat, op.lng, p.name, p.lat, p.lng) } == true
                        // A place the trip already knows is used, never created a second time.
                        current.firstOrNull { it.kind == DayItemKind.PLACE && it.id !in remove && same(it.placeId) }?.let { item ->
                            if (order.none { it.first == item.id }) order += item.id to op.time
                            return@forEach
                        }
                        snapshot.savedPlaces.firstOrNull { it.status != PlaceStatus.DROPPED && same(it.placeId) }?.let { saved ->
                            if (!inDay.add(saved.placeId)) return@forEach
                            val itemId = Ids.newId()
                            writeNew(EntityType.DAY_ITEM, itemId, mapOf("dayId" to JsonPrimitive(dayId), "kind" to JsonPrimitive(DayItemKind.PLACE.name), "placeId" to JsonPrimitive(saved.placeId)))
                            write(EntityType.SAVED_PLACE, saved.id, mapOf("status" to JsonPrimitive(PlaceStatus.PLANNED.name)))
                            order += itemId to op.time
                            return@forEach
                        }
                        val placeId = Ids.newId()
                        writeNew(
                            EntityType.PLACE, placeId,
                            buildMap {
                                put("name", JsonPrimitive(op.name.trim()))
                                put("lat", JsonPrimitive(op.lat))
                                put("lng", JsonPrimitive(op.lng))
                                op.address?.takeIf { it.isNotBlank() }?.let { put("address", JsonPrimitive(it)) }
                            },
                        )
                        writeNew(
                            EntityType.SAVED_PLACE, Ids.newId(),
                            buildMap {
                                put("placeId", JsonPrimitive(placeId))
                                queries?.regionFor(date)?.let { put("regionId", JsonPrimitive(it)) }
                                put("category", JsonPrimitive(op.category.name))
                                put("status", JsonPrimitive(PlaceStatus.PLANNED.name))
                                if (op.note.isNotBlank()) put("note", JsonPrimitive(op.note))
                            },
                        )
                        val itemId = Ids.newId()
                        writeNew(EntityType.DAY_ITEM, itemId, mapOf("dayId" to JsonPrimitive(dayId), "kind" to JsonPrimitive(DayItemKind.PLACE.name), "placeId" to JsonPrimitive(placeId)))
                        order += itemId to op.time
                    }
                }
            }
            current.filter { it.id !in remove && order.none { o -> o.first == it.id } }.forEach { order += it.id to null }
            val keys = SortKey.sequence(order.size)
            order.forEachIndexed { i, (id, time) ->
                write(EntityType.DAY_ITEM, id, buildMap {
                    put("sortKey", JsonPrimitive(keys[i]))
                    if (time != null) put("time", if (time.isBlank()) JsonNull else JsonPrimitive(time))
                })
            }
            val removed = current.filter { it.id in remove }
            removed.forEach { write(EntityType.DAY_ITEM, it.id, mapOf("deleted" to JsonPrimitive(true))) }
            resetUnplanned(snapshot, removed, stillThere = order.map { it.first }.toSet())
        }
        onChange()
        return now
    }

    /**
     * Restores the program of [date] to its version saved at [at]: items added since are removed, removed ones come
     * back with their order and times. The program before is saved as a version too, so this can be undone.
     */
    fun restoreDayVersion(snapshot: TripSnapshot, date: String, at: Long, now: Long) {
        val day = requireNotNull(snapshot.queries()?.dayFor(date)) { "No day" }
        val version = requireNotNull(day.history.firstOrNull { it.at == at }) { "Unknown version" }
        val current = snapshot.dayItemsFor(day.id)
        val keep = version.items.map { it.id }.toSet()
        tx.run {
            saveVersion(day.id, day.history, current, "Vor dem Wiederherstellen", now)
            val removed = current.filter { it.id !in keep }
            removed.forEach { write(EntityType.DAY_ITEM, it.id, mapOf("deleted" to JsonPrimitive(true))) }
            version.items.forEach { v ->
                write(
                    EntityType.DAY_ITEM, v.id,
                    mapOf(
                        "deleted" to JsonPrimitive(false),
                        "sortKey" to JsonPrimitive(v.sortKey),
                        "time" to (v.time?.let { JsonPrimitive(it) } ?: JsonNull),
                    ),
                )
                if (v.kind == DayItemKind.PLACE && v.placeId != null) {
                    snapshot.savedPlaces.filter { it.placeId == v.placeId && it.status == PlaceStatus.IDEA }
                        .forEach { write(EntityType.SAVED_PLACE, it.id, mapOf("status" to JsonPrimitive(PlaceStatus.PLANNED.name))) }
                }
            }
            resetUnplanned(snapshot, removed, stillThere = keep)
        }
        onChange()
    }

    private fun saveVersion(dayId: String, history: List<DayVersion>, items: List<DayItem>, label: String, now: Long) {
        val version = DayVersion(now, label, items.map { DayVersionItem(it.id, it.sortKey, it.time, it.kind, it.placeId) })
        val list = (history.filter { it.at != now } + version).takeLast(MAX_DAY_VERSIONS)
        write(EntityType.DAY, dayId, mapOf("history" to Json.encodeToJsonElement(ListSerializer(DayVersion.serializer()), list)))
    }

    /** Saved places of [removed] items that are in no other program item any more go back to "Idee". */
    private fun resetUnplanned(snapshot: TripSnapshot, removed: List<DayItem>, stillThere: Set<String>) {
        val removedIds = removed.map { it.id }.toSet()
        removed.filter { it.kind == DayItemKind.PLACE && it.placeId != null }.forEach { item ->
            val elsewhere = snapshot.dayItems.any { it.id !in removedIds && it.kind == DayItemKind.PLACE && it.placeId == item.placeId }
            if (elsewhere) return@forEach
            snapshot.savedPlaces.filter { it.placeId == item.placeId && it.status == PlaceStatus.PLANNED }
                .forEach { write(EntityType.SAVED_PLACE, it.id, mapOf("status" to JsonPrimitive(PlaceStatus.IDEA.name))) }
        }
    }
}

/** One step of [LocalEditor.applyDayPlan]; [time] "HH:mm" to set, "" to clear, null to leave as is. */
sealed interface PlanOp {
    data class Keep(val itemId: String, val time: String? = null) : PlanOp
    data class AddSaved(val savedPlaceId: String, val time: String? = null) : PlanOp
    data class AddFound(
        val name: String,
        val lat: Double,
        val lng: Double,
        val address: String?,
        val category: PlaceCategory,
        val time: String? = null,
        val note: String = "",
    ) : PlanOp
}

private val LEG_JSON = Json { encodeDefaults = false }

/** Versions kept per day. */
const val MAX_DAY_VERSIONS = 10
