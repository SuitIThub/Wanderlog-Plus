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

    fun set(type: EntityType, id: String, values: Map<String, JsonElement>) {
        val bad = values.keys.firstOrNull(::isReserved)
        require(bad == null) { "Field '$bad' is reserved and cannot be written by a device" }
        if (values.isEmpty()) return
        tx.run {
            val changes = values.map { (field, value) -> clock.change(type, id, field, value) }
            val accepted = store.applyChanges(changes)
            if (accepted.isNotEmpty()) outbox.add(accepted)
        }
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
        val existing = snapshot.days.firstOrNull { it.date == date }
        val dayId = existing?.id ?: Ids.dayId(trip.id, date)
        val last = existing?.let { snapshot.dayItemsFor(it.id).lastOrNull()?.sortKey }
        val itemId = Ids.newId()
        // One transaction would be nicer, but each set() is atomic and the order (day, item, status) is safe to interrupt.
        if (existing == null) create(EntityType.DAY, mapOf("date" to JsonPrimitive(date)), dayId)
        create(
            EntityType.DAY_ITEM,
            mapOf(
                "dayId" to JsonPrimitive(dayId),
                "kind" to JsonPrimitive(DayItemKind.PLACE.name),
                "placeId" to JsonPrimitive(saved.placeId),
                "sortKey" to JsonPrimitive(SortKey.between(last, null)),
            ),
            itemId,
        )
        set(EntityType.SAVED_PLACE, saved.id, mapOf("status" to JsonPrimitive(PlaceStatus.PLANNED.name)))
        return itemId
    }

    fun moveDayItem(snapshot: TripSnapshot, itemId: String, up: Boolean) {
        val item = snapshot.dayItems.firstOrNull { it.id == itemId } ?: return
        val siblings = snapshot.dayItemsFor(item.dayId)
        val idx = siblings.indexOfFirst { it.id == itemId }
        val key = if (up) {
            if (idx <= 0) return
            SortKey.between(siblings.getOrNull(idx - 2)?.sortKey, siblings[idx - 1].sortKey)
        } else {
            if (idx < 0 || idx >= siblings.lastIndex) return
            SortKey.between(siblings[idx + 1].sortKey, siblings.getOrNull(idx + 2)?.sortKey)
        }
        set(EntityType.DAY_ITEM, itemId, mapOf("sortKey" to JsonPrimitive(key)))
    }

    fun removeDayItem(snapshot: TripSnapshot, itemId: String) {
        val item = snapshot.dayItems.firstOrNull { it.id == itemId }
        delete(EntityType.DAY_ITEM, itemId)
        if (item == null || item.kind != DayItemKind.PLACE || item.placeId == null) return
        val stillPlanned = snapshot.dayItems.any { it.id != itemId && it.kind == DayItemKind.PLACE && it.placeId == item.placeId }
        if (stillPlanned) return
        snapshot.savedPlaces
            .filter { it.placeId == item.placeId && it.status == PlaceStatus.PLANNED }
            .forEach { set(EntityType.SAVED_PLACE, it.id, mapOf("status" to JsonPrimitive(PlaceStatus.IDEA.name))) }
    }
}
