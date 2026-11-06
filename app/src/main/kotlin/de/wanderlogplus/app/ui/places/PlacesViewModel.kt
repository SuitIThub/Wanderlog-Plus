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
import de.wanderlogplus.shared.model.PlaceDossier
import de.wanderlogplus.shared.sync.AssessConfig
import de.wanderlogplus.shared.sync.DossierResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
    /** The criteria chosen in "KI-Kriterien" (read at the start of every job). */
    private val config: suspend () -> AssessConfig = { AssessConfig() },
    /** Starts a sync now (results the server wrote into the trip). */
    private val syncNow: () -> Unit = {},
    /** The own access name (cached; asked from the server once); null when unknown. */
    private val myName: (suspend () -> String?)? = null,
) : ViewModel() {
    val canAssess: Boolean get() = api != null

    private val _me = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Who votes from this phone; null until known (then no vote buttons). */
    val me: StateFlow<String?> = _me

    init {
        myName?.let { load -> viewModelScope.launch { _me.value = try { load() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null } } }
    }

    /** Casts (or with 0 withdraws) the own vote; may move the place between „Idee“ and „Vorgemerkt“. */
    fun vote(savedPlaceId: String, value: Int) {
        val me = _me.value ?: return
        write { e, s -> voteEdits(s, savedPlaceId, me, value)(e) }
    }

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
                var job = a.startAssessment(config = config())
                while (job.status != "DONE" && job.status != "FAILED") {
                    kotlinx.coroutines.delay(pollMillis)
                    job = a.aiJob(job.id)
                }
                if (job.status == "FAILED") {
                    de.wanderlogplus.app.ui.reels.errorText(job.error)
                } else if (job.appliedOnServer) {
                    // The server wrote the judgements into the trip; a sync brings them here.
                    syncNow()
                    when (val n = job.assessments.size) {
                        0 -> "Alle Orte sind schon eingeschätzt"
                        1 -> "1 Ort eingeschätzt"
                        else -> "$n Orte eingeschätzt"
                    }
                } else {
                    var applied = 0
                    edit { e, s ->
                        val known = s.savedPlaces.associateBy { it.id }
                        // Places deleted meanwhile are skipped.
                        job.assessments.forEach { r ->
                            val saved = known[r.savedPlaceId] ?: return@forEach
                            val fields = mutableMapOf<String, JsonElement>("worth" to JsonPrimitive(r.worth.name), "worthReason" to JsonPrimitive(r.reason))
                            if (r.criteria.isNotEmpty()) {
                                // Keeps earlier research (tips, hidden spots); only the judgement is replaced.
                                val d = (saved.dossier ?: PlaceDossier()).copy(criteria = r.criteria, verdict = r.reason)
                                fields["dossier"] = DOSSIER_JSON.encodeToJsonElement(PlaceDossier.serializer(), d)
                            }
                            e.set(EntityType.SAVED_PLACE, r.savedPlaceId, fields)
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

    /** Saved places whose research job is running. */
    private val _researching = MutableStateFlow<Set<String>>(emptySet())
    val researching: StateFlow<Set<String>> = _researching

    /** Progress or outcome of "Recherchieren (KI)" (fixed German text); null when idle. */
    private val _researchStatus = MutableStateFlow<String?>(null)
    val researchStatus: StateFlow<String?> = _researchStatus

    /** Id of the research job started last (for its "Ablauf"); null before the first. */
    private val _researchJob = MutableStateFlow<String?>(null)
    val researchJob: StateFlow<String?> = _researchJob

    fun consumeResearchStatus() { if (_researching.value.isEmpty()) _researchStatus.value = null }

    /**
     * Web research of [savedPlaceIds] (criteria, verdict, insider tips, hidden spots, good to know). Waits for the
     * server job on the process-wide scope and writes the dossiers; places already being researched are skipped.
     */
    fun research(savedPlaceIds: List<String>) {
        val provider = api ?: return
        val todo = savedPlaceIds.distinct().filterNot { it in _researching.value }.take(MAX_RESEARCH)
        if (todo.isEmpty()) return
        _researching.value = _researching.value + todo
        _researchStatus.value = researchProgress(0, todo.size)
        writeScope.launch {
            val outcome = try {
                val a = provider() ?: throw IllegalStateException("no server")
                var job = a.startResearch(todo, config())
                _researchJob.value = job.id
                var seen = 0
                var offlineSince = 0L
                while (job.status != "DONE" && job.status != "FAILED") {
                    kotlinx.coroutines.delay(pollMillis)
                    job = try {
                        a.aiJob(job.id).also { offlineSince = 0L }
                    } catch (e: de.wanderlogplus.client.ApiException) {
                        // A long research must survive short network gaps (tunnel, app in the background).
                        if (e.kind != de.wanderlogplus.client.ApiErrorKind.NETWORK) throw e
                        if (offlineSince == 0L) offlineSince = System.currentTimeMillis()
                        if (System.currentTimeMillis() - offlineSince > MAX_OFFLINE_MILLIS) throw e
                        continue
                    }
                    if (job.status != "DONE" && job.status != "FAILED") _researchStatus.value = researchProgress(job.progress, todo.size)
                    // The server writes each finished place into the trip: fetch them while the rest still runs.
                    if (job.appliedOnServer && job.dossiers.size > seen) { seen = job.dossiers.size; syncNow() }
                }
                if (job.status == "FAILED") {
                    if (job.appliedOnServer && job.dossiers.isNotEmpty()) syncNow()
                    de.wanderlogplus.app.ui.reels.errorText(job.error)
                } else {
                    val applied = if (job.appliedOnServer) job.dossiers.size.also { syncNow() } else applyDossiers(job.dossiers)
                    when (val n = applied) {
                        0 -> "Recherche ohne Ergebnis"
                        1 -> "1 Ort recherchiert"
                        else -> "$n Orte recherchiert"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: de.wanderlogplus.client.ApiException) {
                if (e.kind == de.wanderlogplus.client.ApiErrorKind.NOT_CONFIGURED) de.wanderlogplus.app.ui.reels.errorText("ai_not_configured") else "Recherche fehlgeschlagen"
            } catch (e: Exception) {
                "Recherche fehlgeschlagen"
            } finally {
                _researching.value = _researching.value - todo.toSet()
            }
            _researchStatus.value = outcome
        }
    }

    /** Writes worth, verdict and dossier; fills the place description only when it has none. Returns how many were applied. */
    private suspend fun applyDossiers(results: List<DossierResult>): Int {
        var applied = 0
        edit { e, s ->
            val known = s.savedPlaces.associateBy { it.id }
            results.forEach { r ->
                val saved = known[r.savedPlaceId] ?: return@forEach // deleted meanwhile
                val fields = mutableMapOf<String, JsonElement>("dossier" to DOSSIER_JSON.encodeToJsonElement(PlaceDossier.serializer(), r.dossier))
                r.worth?.let { fields["worth"] = JsonPrimitive(it.name) }
                if (r.dossier.verdict.isNotBlank()) fields["worthReason"] = JsonPrimitive(r.dossier.verdict)
                e.set(EntityType.SAVED_PLACE, saved.id, fields)
                val place = s.places[saved.placeId]
                if (place != null && place.description.isNullOrBlank() && r.description.isNotBlank()) {
                    e.set(EntityType.PLACE, place.id, mapOf("description" to JsonPrimitive(r.description)))
                }
                applied++
            }
        }
        return applied
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

    /** Deletes the place from the collection and from every day's program. */
    fun delete(savedPlaceId: String) = write { e, s -> e.deleteSavedPlace(s, savedPlaceId) }

    fun setStatus(savedPlaceId: String, status: PlaceStatus) =
        write { e, _ -> e.set(EntityType.SAVED_PLACE, savedPlaceId, mapOf("status" to JsonPrimitive(status.name))) }

    fun setTags(savedPlaceId: String, tags: List<String>) =
        write { e, _ -> e.set(EntityType.SAVED_PLACE, savedPlaceId, mapOf("tags" to JsonArray(tags.map { JsonPrimitive(it) }))) }

    /** [regionId] null means "Keine Region". */
    fun setRegion(savedPlaceId: String, regionId: String?) =
        write { e, _ -> e.set(EntityType.SAVED_PLACE, savedPlaceId, mapOf("regionId" to (regionId?.let { JsonPrimitive(it) } ?: JsonNull))) }

    /** A place found by search goes into [regionId]'s collection as an idea (null: no region). */
    fun addFoundPlace(place: de.wanderlogplus.client.GeoPlace, regionId: String?) = write { e, _ ->
        e.addPlaceToCollection(place.name, place.lat, place.lng, place.detail.ifBlank { null }, place.category, regionId, googlePlaceId = place.googlePlaceId)
    }

    fun setCategory(savedPlaceId: String, category: PlaceCategory) =
        write { e, _ -> e.set(EntityType.SAVED_PLACE, savedPlaceId, mapOf("category" to JsonPrimitive(category.name))) }

    /** [ref] null: back to the default image. */
    fun setHeaderImage(savedPlaceId: String, ref: String?) =
        write { e, _ -> e.set(EntityType.SAVED_PLACE, savedPlaceId, mapOf("headerImage" to (ref?.let { JsonPrimitive(it) } ?: JsonNull))) }

    fun setLinks(savedPlaceId: String, links: List<de.wanderlogplus.shared.model.CustomLink>) =
        write { e, _ -> e.set(EntityType.SAVED_PLACE, savedPlaceId, mapOf("links" to kotlinx.serialization.json.Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(de.wanderlogplus.shared.model.CustomLink.serializer()), links))) }

    fun setReserved(savedPlaceId: String, reserved: Boolean) =
        write { e, _ -> e.set(EntityType.SAVED_PLACE, savedPlaceId, mapOf("reserved" to JsonPrimitive(reserved))) }

    /** Reservation dates set by hand (null: back to the research's). */
    fun setReservationWindow(savedPlaceId: String, window: de.wanderlogplus.shared.model.ReservationWindow?) =
        write { e, _ ->
            val v = window?.let { kotlinx.serialization.json.Json.encodeToJsonElement(de.wanderlogplus.shared.model.ReservationWindow.serializer(), it) } ?: kotlinx.serialization.json.JsonNull
            e.set(EntityType.SAVED_PLACE, savedPlaceId, mapOf("reservationWindow" to v))
        }
}

/** At most this many places per research job (server limit). */
const val MAX_RESEARCH = 300

/** Polling a research gives up after this long without any connection to the server. */
const val MAX_OFFLINE_MILLIS = 30 * 60_000L

internal val DOSSIER_JSON = Json { encodeDefaults = false }

/** "Ort wird recherchiert …" or "Recherche: 3 von 12 Orten …". */
fun researchProgress(done: Int, total: Int): String =
    if (total <= 1) "Ort wird recherchiert …" else "Recherche: $done von $total Orten …"
