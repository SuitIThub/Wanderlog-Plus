package de.wanderlogplus.app.ui.trip

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.client.TripSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * [snapshots] yields the live snapshot flow, [editor] the local editor (both await app initialization).
 * All writes run on Dispatchers.IO because the editor is blocking.
 */
class TripViewModel(
    private val snapshots: suspend () -> StateFlow<TripSnapshot?>,
    private val editor: suspend () -> LocalEditor,
) : ViewModel() {
    private val _snapshot = MutableStateFlow<TripSnapshot?>(null)
    val snapshot: StateFlow<TripSnapshot?> = _snapshot

    /** Exception class name of the last failed action (never data); null when fine. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        viewModelScope.launch { snapshots().collect { _snapshot.value = it } }
    }

    private fun write(action: (LocalEditor, TripSnapshot) -> Unit) {
        val snap = _snapshot.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                action(editor(), snap)
                _error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.javaClass.simpleName
            }
        }
    }

    fun addSuggestion(date: String, savedPlaceId: String) = write { e, s -> e.addSuggestionToDay(s, date, savedPlaceId) }
    fun move(itemId: String, up: Boolean) = write { e, s -> e.moveDayItem(s, itemId, up) }
    fun remove(itemId: String) = write { e, s -> e.removeDayItem(s, itemId) }
}
