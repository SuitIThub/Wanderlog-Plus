package de.wanderlogplus.app.ui.trip

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.client.TripSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import de.wanderlogplus.client.GeoPlace
import de.wanderlogplus.client.ItemPosition
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.TravelMode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** Runs a write with the editor and a fresh snapshot, serialized app-wide (see AppContainer.edit). */
typealias EditFn = suspend (block: (LocalEditor, TripSnapshot) -> Unit) -> Unit

/** [snapshots] yields the live snapshot flow for display; every write goes through [edit] only. */
class TripViewModel(
    private val snapshots: suspend () -> StateFlow<TripSnapshot?>,
    private val edit: EditFn,
    /** Process-wide scope for writes: leaving a screen must not cancel a pending write. */
    private val writeScope: CoroutineScope,
) : ViewModel() {
    private val _snapshot = MutableStateFlow<TripSnapshot?>(null)
    val snapshot: StateFlow<TripSnapshot?> = _snapshot

    /** Exception class name of the last failed action (never data); null when fine. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** Counts failed writes so screens can drop optimistic state. */
    private val _failures = MutableStateFlow(0)
    val failures: StateFlow<Int> = _failures

    init {
        viewModelScope.launch { snapshots().collect { _snapshot.value = it } }
    }

    fun consumeError() { _error.value = null }

    /**
     * The saved place of a program place, created (status "geplant", nearest region) when the place is in no
     * collection yet, so its detail can open. Null when that failed.
     */
    suspend fun savedPlaceFor(placeId: String): String? {
        _snapshot.value?.savedPlaces?.firstOrNull { it.placeId == placeId }?.id?.let { return it }
        var id: String? = null
        return try {
            edit { e, s ->
                id = s.savedPlaces.firstOrNull { it.placeId == placeId }?.id ?: run {
                    val p = s.places[placeId]
                    val region = p?.let { de.wanderlogplus.app.ui.places.nearestRegion(s, it.lat, it.lng) }
                    e.addSavedPlace(placeId, region)
                }
            }
            id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = e.javaClass.simpleName
            null
        }
    }

    private fun write(action: (LocalEditor, TripSnapshot) -> Unit): Job = writeScope.launch {
        try {
            edit(action)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = e.javaClass.simpleName
            _failures.value++
        }
    }

    fun addSuggestion(date: String, savedPlaceId: String) = write { e, s -> e.addSuggestionToDay(s, date, savedPlaceId) }
    fun move(itemId: String, up: Boolean) = write { e, s -> e.moveDayItem(s, itemId, up) }
    fun remove(itemId: String) = write { e, s -> e.removeDayItem(s, itemId) }

    fun setDayTitle(date: String, title: String) = write { e, s -> e.setDayFields(s, date, mapOf("title" to JsonPrimitive(title))) }
    fun setDayNote(date: String, note: String) = write { e, s -> e.setDayFields(s, date, mapOf("note" to JsonPrimitive(note))) }

    /** [regionId] null means "Automatisch": JsonNull is written, so the stay's region applies again. */
    fun setRegionOverride(date: String, regionId: String?) =
        write { e, s -> e.setDayFields(s, date, mapOf("regionOverrideId" to (regionId?.let { JsonPrimitive(it) } ?: JsonNull))) }

    fun addNote(date: String, text: String) = write { e, s -> e.addNoteToDay(s, date, text) }

    fun addSuggestionAt(date: String, savedPlaceId: String, position: ItemPosition) =
        write { e, s -> e.addSuggestionToDay(s, date, savedPlaceId, position) }

    fun addNoteAt(date: String, text: String, position: ItemPosition) = write { e, s -> e.addNoteToDay(s, date, text, position) }

    fun addStayAt(date: String, stayId: String, position: ItemPosition) = write { e, s -> e.addStayToDay(s, date, stayId, position) }

    /** A place found by name or on the map becomes a planned place of the day's region and a program item at [position]. */
    fun addSearchedPlace(date: String, position: ItemPosition, place: GeoPlace) = write { e, s ->
        e.addPlaceToDay(s, date, place.name, place.lat, place.lng, place.detail.ifBlank { null }, place.category, position)
    }

    /** Applies a chat's day plan; the program before is saved as a version at [at] (epoch ms). */
    fun applyPlan(date: String, ops: List<de.wanderlogplus.client.PlanOp>, remove: Set<String>, at: Long) =
        write { e, s -> e.applyDayPlan(s, date, ops, remove, "Vor Chat-Plan", at) }

    /** Restores the day's version saved at [at]; the current program becomes a version too (at [now]). */
    fun restoreVersion(date: String, at: Long, now: Long = System.currentTimeMillis()) =
        write { e, s -> e.restoreDayVersion(s, date, at, now) }

    /** A chat's web find becomes a planned place of the day's region and a program item at [position]. */
    fun addFoundToDay(date: String, position: ItemPosition, name: String, lat: Double, lng: Double, address: String?, category: PlaceCategory) =
        write { e, s -> e.addPlaceToDay(s, date, name, lat, lng, address, category, position) }

    /** A chat's web find goes into [regionId]'s collection as an idea. */
    fun addFoundToPlaces(name: String, lat: Double, lng: Double, address: String?, category: PlaceCategory, regionId: String?, note: String = "") =
        write { e, _ -> e.addPlaceToCollection(name, lat, lng, address, category, regionId, note = note) }

    fun setShowLegs(date: String, show: Boolean) = write { e, s -> e.setDayFields(s, date, mapOf("showLegs" to JsonPrimitive(show))) }

    fun setLegMode(itemId: String, mode: TravelMode) =
        write { e, _ -> e.set(EntityType.DAY_ITEM, itemId, mapOf("legMode" to JsonPrimitive(mode.name))) }

    /** Takes a public-transport connection over for the way to [itemId] (mode becomes ÖPNV); null: found automatically again. */
    fun setLegTransit(itemId: String, connection: de.wanderlogplus.shared.sync.TransitResult?) = write { e, _ ->
        val value = connection?.let { TRANSIT_JSON.encodeToJsonElement(de.wanderlogplus.shared.sync.TransitResult.serializer(), it) } ?: kotlinx.serialization.json.JsonNull
        e.set(EntityType.DAY_ITEM, itemId, mapOf("legTransit" to value, "legMode" to JsonPrimitive(TravelMode.TRANSIT.name)))
    }
}

private val TRANSIT_JSON = kotlinx.serialization.json.Json { encodeDefaults = false }
