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

    /** Saved places in a research job that is waiting or running. */
    private val _researching = MutableStateFlow<Set<String>>(emptySet())
    val researching: StateFlow<Set<String>> = _researching

    /** Progress of the running research (with the waiting ones after it) or the last outcome; null when idle. */
    private val _researchStatus = MutableStateFlow<String?>(null)
    val researchStatus: StateFlow<String?> = _researchStatus

    /** Id of the running research job (for its "Ablauf"), else the one finished last; null before the first. */
    private val _researchJob = MutableStateFlow<String?>(null)
    val researchJob: StateFlow<String?> = _researchJob

    /** Research jobs in the order they were started: one runs, the others wait (the server runs them one by one). */
    private val _queue = MutableStateFlow<List<QueuedResearch>>(emptyList())
    val queue: StateFlow<List<QueuedResearch>> = _queue

    private val queueLock = Any()
    private var nextKey = 1L
    private val started = mutableMapOf<Long, kotlinx.coroutines.CompletableDeferred<de.wanderlogplus.shared.sync.AiJobInfo?>>()
    private var worker: Job? = null

    fun consumeResearchStatus() {
        synchronized(queueLock) {
            if (_queue.value.any { !it.finished }) return
            _queue.value = emptyList()
            _researchStatus.value = null
        }
    }

    private fun updateQueue(change: (List<QueuedResearch>) -> List<QueuedResearch>) {
        synchronized(queueLock) {
            val q = change(_queue.value)
            _queue.value = q
            _researching.value = q.filterNot { it.finished }.flatMap { it.ids }.toSet()
            _researchStatus.value = queueStatus(q)
            (q.firstOrNull { !it.finished }?.jobId ?: q.lastOrNull { it.finished && it.jobId != null }?.jobId)?.let { _researchJob.value = it }
        }
    }

    private fun setEntry(key: Long, change: (QueuedResearch) -> QueuedResearch) =
        updateQueue { q -> q.map { if (it.key == key) change(it) else it } }

    /**
     * Web research of [savedPlaceIds] (criteria, verdict, insider tips, hidden spots, good to know) as one job labelled
     * [label] (a region's name; blank: no prefix in the status). The job goes to the server at once, which runs jobs
     * one after another; here only the running one is followed, then the next. Places already waiting or running
     * are skipped.
     */
    fun research(savedPlaceIds: List<String>, label: String = "") {
        val provider = api ?: return
        val entry: QueuedResearch
        val gate = kotlinx.coroutines.CompletableDeferred<de.wanderlogplus.shared.sync.AiJobInfo?>()
        synchronized(queueLock) {
            val todo = savedPlaceIds.distinct().filterNot { it in _researching.value }.take(MAX_RESEARCH)
            if (todo.isEmpty()) return
            // A fresh start drops what is left over from earlier runs.
            val keep = _queue.value.filterNot { it.finished }
            entry = QueuedResearch(nextKey++, label, todo, total = todo.size, state = QueuedResearch.State.WAITING)
            started[entry.key] = gate
            updateQueue { keep + entry }
            if (starter?.isActive != true) starter = writeScope.launch { startInOrder() }
        }
        // One starter sends the jobs in queue order: parallel starts would reach the server (which runs them in
        // arrival order) shuffled, and the app would follow a job that is only waiting there.
        starts.trySend(Triple(entry, gate, provider))
        synchronized(queueLock) {
            if (worker?.isActive != true) worker = writeScope.launch { work() }
        }
    }

    private val starts = kotlinx.coroutines.channels.Channel<Triple<QueuedResearch, kotlinx.coroutines.CompletableDeferred<de.wanderlogplus.shared.sync.AiJobInfo?>, suspend () -> de.wanderlogplus.client.SyncApi?>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private var starter: Job? = null

    /** Sends queued jobs to the server one after another, in the order they were queued. */
    private suspend fun startInOrder() {
        for ((entry, gate, provider) in starts) {
            val job = try {
                val a = provider() ?: throw IllegalStateException("no server")
                a.startResearch(entry.ids, config())
            } catch (e: CancellationException) {
                gate.complete(null)
                throw e
            } catch (e: de.wanderlogplus.client.ApiException) {
                val text = if (e.kind == de.wanderlogplus.client.ApiErrorKind.NOT_CONFIGURED) de.wanderlogplus.app.ui.reels.errorText("ai_not_configured") else "Recherche fehlgeschlagen"
                setEntry(entry.key) { it.copy(state = QueuedResearch.State.FAILED, outcome = text) }
                null
            } catch (e: Exception) {
                setEntry(entry.key) { it.copy(state = QueuedResearch.State.FAILED, outcome = "Recherche fehlgeschlagen") }
                null
            }
            if (job != null) setEntry(entry.key) { it.copy(jobId = job.id) }
            gate.complete(job)
        }
    }

    /** One research job per region batch (name, place ids), queued in this order. */
    fun researchRegions(batches: List<Pair<String, List<String>>>) = batches.forEach { (name, ids) -> research(ids, name) }

    /** Follows the first unfinished job until none is left. */
    private suspend fun work() {
        while (true) {
            val (entry, gate) = synchronized(queueLock) {
                val e = _queue.value.firstOrNull { !it.finished } ?: run { worker = null; return }
                e to started[e.key]
            }
            val job = gate?.await()
            if (job == null) {
                // Start failed (already marked) or was cancelled.
                if (_queue.value.firstOrNull { it.key == entry.key }?.finished != true) setEntry(entry.key) { it.copy(state = QueuedResearch.State.FAILED, outcome = "Recherche fehlgeschlagen") }
                continue
            }
            setEntry(entry.key) { it.copy(state = QueuedResearch.State.RUNNING) }
            val outcome = follow(entry, job)
            synchronized(queueLock) { started.remove(entry.key) }
            setEntry(entry.key) { it.copy(state = if (outcome.second) QueuedResearch.State.DONE else QueuedResearch.State.FAILED, outcome = outcome.first) }
        }
    }

    /** Polls [first] until it ends and applies its results; returns the outcome text and whether it succeeded. */
    private suspend fun follow(entry: QueuedResearch, first: de.wanderlogplus.shared.sync.AiJobInfo): Pair<String, Boolean> = try {
        val a = api?.invoke() ?: throw IllegalStateException("no server")
        var job = first
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
            if (job.status != "DONE" && job.status != "FAILED") {
                val p = job.progress
                setEntry(entry.key) { it.copy(progress = p) }
            }
            // The server writes each finished place into the trip: fetch them while the rest still runs.
            if (job.appliedOnServer && job.dossiers.size > seen) { seen = job.dossiers.size; syncNow() }
        }
        if (job.status == "FAILED") {
            if (job.appliedOnServer && job.dossiers.isNotEmpty()) syncNow()
            de.wanderlogplus.app.ui.reels.errorText(job.error) to false
        } else {
            val applied = if (job.appliedOnServer) job.dossiers.size.also { syncNow() } else applyDossiers(job.dossiers)
            when (applied) {
                0 -> "Recherche ohne Ergebnis"
                1 -> "1 Ort recherchiert"
                else -> "$applied Orte recherchiert"
            } to true
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: de.wanderlogplus.client.ApiException) {
        (if (e.kind == de.wanderlogplus.client.ApiErrorKind.NOT_CONFIGURED) de.wanderlogplus.app.ui.reels.errorText("ai_not_configured") else "Recherche fehlgeschlagen") to false
    } catch (e: Exception) {
        "Recherche fehlgeschlagen" to false
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

    /**
     * Asks the server to (re)load Tabelog for [placeId]: marks it REQUESTED, with [url] as the entry set by hand (null
     * keeps the known one), then syncs and nudges the server so it does not wait for its next sweep.
     */
    fun tabelogRequest(placeId: String, url: String?) {
        val written = write { e, s ->
            val before = s.places[placeId] ?: return@write
            val old = before.tabelog
            val next = (old ?: de.wanderlogplus.shared.model.TabelogInfo(de.wanderlogplus.shared.model.TabelogStatus.REQUESTED))
                .copy(status = de.wanderlogplus.shared.model.TabelogStatus.REQUESTED, url = url ?: old?.url.orEmpty())
            e.set(EntityType.PLACE, placeId, mapOf("tabelog" to de.wanderlogplus.shared.model.EntityCodec.fields(before.copy(tabelog = next)).getValue("tabelog")))
        }
        writeScope.launch {
            written.join()
            syncNow()
            try {
                api?.invoke()?.tabelogRefresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The next sweep takes it anyway.
            }
        }
    }

    /** Links the place to a Google entry ([hit] null: unlinks); see [googleLinkEdits]. */
    fun linkGoogle(placeId: String, hit: de.wanderlogplus.client.GeoPlace?, takePosition: Boolean) =
        write { e, s -> googleLinkEdits(placeId, hit, takePosition)(e, s) }

    /** The photo attachment [attachmentId] becomes the header of [savedPlaceId]. */
    fun setHeaderFromAttachment(savedPlaceId: String, attachmentId: String) =
        write { e, s -> de.wanderlogplus.app.ui.attachments.headerFromAttachmentEdits(EntityType.SAVED_PLACE, savedPlaceId, attachmentId)(e, s) }

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

/** One research job in the app's queue. [outcome] is the fixed German result text once it ended. */
data class QueuedResearch(
    val key: Long,
    val label: String,
    val ids: List<String>,
    val jobId: String? = null,
    val progress: Int = 0,
    val total: Int,
    val state: State,
    val outcome: String? = null,
) {
    enum class State { WAITING, RUNNING, DONE, FAILED }

    val finished: Boolean get() = state == State.DONE || state == State.FAILED
}

/**
 * The status line of the queue: "<label>: Recherche: 3 von 12 Orten … · danach: Osaka, Nara" while one runs, else
 * the outcome of the last one; null when empty.
 */
fun queueStatus(q: List<QueuedResearch>): String? {
    val open = q.filterNot { it.finished }
    val head = open.firstOrNull() ?: return q.lastOrNull()?.outcome
    val progress = researchProgress(head.progress, head.total)
    val waiting = open.drop(1).map { it.label.ifBlank { if (it.total == 1) "1 Ort" else "${it.total} Orte" } }
    return (if (head.label.isBlank()) progress else "${head.label}: $progress") + (if (waiting.isEmpty()) "" else " · danach: " + waiting.joinToString(", "))
}
