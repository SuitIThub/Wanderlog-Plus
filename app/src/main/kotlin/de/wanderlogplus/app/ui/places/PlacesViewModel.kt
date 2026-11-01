package de.wanderlogplus.app.ui.places

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.wanderlogplus.app.ui.trip.EditFn
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.PlaceStatus
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** [snapshots] yields the live snapshot flow for display; every write goes through [edit] only. */
class PlacesViewModel(
    private val snapshots: suspend () -> StateFlow<TripSnapshot?>,
    private val edit: EditFn,
) : ViewModel() {
    private val _snapshot = MutableStateFlow<TripSnapshot?>(null)
    val snapshot: StateFlow<TripSnapshot?> = _snapshot

    /** Exception class name of the last failed action (never data); null when fine. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        viewModelScope.launch { snapshots().collect { _snapshot.value = it } }
    }

    fun consumeError() { _error.value = null }

    private fun write(action: (de.wanderlogplus.client.LocalEditor, TripSnapshot) -> Unit): Job = viewModelScope.launch {
        try {
            edit(action)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = e.javaClass.simpleName
        }
    }

    fun setNote(savedPlaceId: String, note: String) =
        write { e, _ -> e.set(EntityType.SAVED_PLACE, savedPlaceId, mapOf("note" to JsonPrimitive(note))) }

    fun setStatus(savedPlaceId: String, status: PlaceStatus) =
        write { e, _ -> e.set(EntityType.SAVED_PLACE, savedPlaceId, mapOf("status" to JsonPrimitive(status.name))) }
}
