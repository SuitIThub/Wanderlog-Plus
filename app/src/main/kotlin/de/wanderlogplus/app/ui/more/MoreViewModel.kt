package de.wanderlogplus.app.ui.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.wanderlogplus.app.ui.trip.EditFn
import de.wanderlogplus.client.Conflict
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.client.keepApp
import de.wanderlogplus.client.takeWanderlog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Resolves [conflict] only if [fresh] still contains it with the same values; otherwise (Wanderlog or another
 * device changed it meanwhile) nothing is written and false is returned.
 */
fun resolveConflict(editor: LocalEditor, fresh: TripSnapshot, conflict: Conflict, takeWanderlog: Boolean): Boolean {
    val current = fresh.conflicts.firstOrNull {
        it.type == conflict.type && it.id == conflict.id && it.field == conflict.field
    } ?: return false
    if (current.appValue != conflict.appValue || current.wanderlogValue != conflict.wanderlogValue) return false
    if (takeWanderlog) editor.takeWanderlog(current) else editor.keepApp(current)
    return true
}

/** Notes, logistics and conflicts. Every write goes through [edit] only. */
class MoreViewModel(
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

    fun keepApp(c: Conflict) = write { e, s -> resolveConflict(e, s, c, takeWanderlog = false) }
    fun takeWanderlog(c: Conflict) = write { e, s -> resolveConflict(e, s, c, takeWanderlog = true) }
}
