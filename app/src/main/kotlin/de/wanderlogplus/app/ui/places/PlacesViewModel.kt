package de.wanderlogplus.app.ui.places

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.wanderlogplus.app.ui.trip.EditFn
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.model.PlaceStatus
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** [snapshots] yields the live snapshot flow for display; every write goes through [edit] only. */
class PlacesViewModel(
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

    private fun write(action: (de.wanderlogplus.client.LocalEditor, TripSnapshot) -> Unit): Job = writeScope.launch {
        try {
            edit(action)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = e.javaClass.simpleName
            _failures.value++
        }
    }

    fun setNote(savedPlaceId: String, note: String) =
        write { e, _ -> e.set(EntityType.SAVED_PLACE, savedPlaceId, mapOf("note" to JsonPrimitive(note))) }

    fun setStatus(savedPlaceId: String, status: PlaceStatus) =
        write { e, _ -> e.set(EntityType.SAVED_PLACE, savedPlaceId, mapOf("status" to JsonPrimitive(status.name))) }

    fun setTags(savedPlaceId: String, tags: List<String>) =
        write { e, _ -> e.set(EntityType.SAVED_PLACE, savedPlaceId, mapOf("tags" to JsonArray(tags.map { JsonPrimitive(it) }))) }

    /** [regionId] null means "Keine Region". */
    fun setRegion(savedPlaceId: String, regionId: String?) =
        write { e, _ -> e.set(EntityType.SAVED_PLACE, savedPlaceId, mapOf("regionId" to (regionId?.let { JsonPrimitive(it) } ?: JsonNull))) }

    /** A place found by search goes into [regionId]'s collection as an idea (null: no region). */
    fun addFoundPlace(place: de.wanderlogplus.client.GeoPlace, regionId: String?) = write { e, _ ->
        e.addPlaceToCollection(place.name, place.lat, place.lng, place.detail.ifBlank { null }, place.category, regionId)
    }

    fun setCategory(savedPlaceId: String, category: PlaceCategory) =
        write { e, _ -> e.set(EntityType.SAVED_PLACE, savedPlaceId, mapOf("category" to JsonPrimitive(category.name))) }
}
