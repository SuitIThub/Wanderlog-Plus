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

    init {
        viewModelScope.launch { snapshots().collect { _snapshot.value = it } }
    }

    fun consumeError() { _error.value = null }

    private fun write(action: (LocalEditor, TripSnapshot) -> Unit): Job = writeScope.launch {
        try {
            edit(action)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = e.javaClass.simpleName
        }
    }

    fun addSuggestion(date: String, savedPlaceId: String) = write { e, s -> e.addSuggestionToDay(s, date, savedPlaceId) }
    fun move(itemId: String, up: Boolean) = write { e, s -> e.moveDayItem(s, itemId, up) }
    fun remove(itemId: String) = write { e, s -> e.removeDayItem(s, itemId) }
}
