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
    /** API of the current server, for the assistant's judgements; null hides "Einschätzen". */
    private val api: (suspend () -> de.wanderlogplus.client.SyncApi?)? = null,
    private val pollMillis: Long = 2_500,
) : ViewModel() {
    val canAssess: Boolean get() = api != null

    /** Progress or outcome of "Einschätzen (KI)" (fixed German text); null when idle. */
    private val _assessing = MutableStateFlow<String?>(null)
    val assessing: StateFlow<String?> = _assessing

    /**
     * Lets the assistant judge all places without a judgement yet, waits for the server job and writes the results.
     * Runs on the process-wide scope, so leaving the screen does not stop it.
     */
    fun assessAll() {
        val provider = api ?: return
        if (_assessing.value == "Orte werden eingeschätzt …") return
        _assessing.value = "Orte werden eingeschätzt …"
        writeScope.launch {
            _assessing.value = try {
                val a = provider() ?: throw IllegalStateException("no server")
                var job = a.startAssessment()
                while (job.status != "DONE" && job.status != "FAILED") {
                    kotlinx.coroutines.delay(pollMillis)
                    job = a.aiJob(job.id)
                }
                if (job.status == "FAILED") {
                    de.wanderlogplus.app.ui.reels.errorText(job.error)
                } else {
                    var applied = 0
                    edit { e, s ->
                        val known = s.savedPlaces.map { it.id }.toSet()
                        // Places deleted meanwhile are skipped.
                        job.assessments.filter { it.savedPlaceId in known }.forEach { r ->
                            e.set(EntityType.SAVED_PLACE, r.savedPlaceId, mapOf("worth" to JsonPrimitive(r.worth.name), "worthReason" to JsonPrimitive(r.reason)))
                            applied++
                        }
                    }
                    when (applied) {
                        0 -> "Alle Orte sind schon eingeschätzt"
                        1 -> "1 Ort eingeschätzt"
                        else -> "$applied Orte eingeschätzt"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: de.wanderlogplus.client.ApiException) {
                if (e.kind == de.wanderlogplus.client.ApiErrorKind.NOT_CONFIGURED) de.wanderlogplus.app.ui.reels.errorText("ai_not_configured") else "Einschätzen fehlgeschlagen"
            } catch (e: Exception) {
                "Einschätzen fehlgeschlagen"
            }
        }
    }

    fun consumeAssessing() { if (_assessing.value != "Orte werden eingeschätzt …") _assessing.value = null }
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
