package de.wanderlogplus.client

import de.wanderlogplus.shared.model.DayItemKind
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

    fun addSuggestionToDay(snapshot: TripSnapshot, date: String, savedPlaceId: String): String {
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
                    "sortKey" to JsonPrimitive(SortKey.between(siblings.lastOrNull()?.sortKey, null)),
                ),
            )
            write(EntityType.SAVED_PLACE, saved.id, mapOf("status" to JsonPrimitive(PlaceStatus.PLANNED.name)))
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
