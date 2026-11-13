package de.wanderlogplus.app.ui.reels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.wanderlogplus.app.ui.trip.EditFn
import de.wanderlogplus.client.ApiErrorKind
import de.wanderlogplus.client.ApiException
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.sync.AiJobInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Remembers which reel jobs were already taken over, so a second tap cannot import twice. */
interface ImportedJobs {
    fun contains(id: String): Boolean
    fun add(id: String)
}

/**
 * Reel jobs: list, start from a link, watch one until it is finished (polling the server), take its places over.
 * Every write goes through [edit]; network calls use the current server's API ([api], null without server).
 */
class ReelsViewModel(
    private val snapshots: suspend () -> StateFlow<TripSnapshot?>,
    private val edit: EditFn,
    private val api: suspend () -> SyncApi?,
    private val imported: ImportedJobs,
    private val writeScope: CoroutineScope,
    private val pollMillis: Long = 2_500,
    private val config: suspend () -> de.wanderlogplus.shared.sync.AssessConfig = { de.wanderlogplus.shared.sync.AssessConfig() },
    private val syncNow: () -> Unit = {},
) : ViewModel() {
    private val _snapshot = MutableStateFlow<TripSnapshot?>(null)
    val snapshot: StateFlow<TripSnapshot?> = _snapshot

    private val _jobs = MutableStateFlow<List<AiJobInfo>>(emptyList())
    val jobs: StateFlow<List<AiJobInfo>> = _jobs

    private val _job = MutableStateFlow<AiJobInfo?>(null)
    val job: StateFlow<AiJobInfo?> = _job

    /** A message for the user (fixed German text, never server data); null when fine. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _importedNow = MutableStateFlow(false)
    val importedNow: StateFlow<Boolean> = _importedNow

    private var watching: Job? = null

    /** Positions set by hand for candidates of the watched job (index -> place). */
    private val _positions = MutableStateFlow<Map<Int, de.wanderlogplus.client.GeoPlace>>(emptyMap())
    val positions: StateFlow<Map<Int, de.wanderlogplus.client.GeoPlace>> = _positions

    fun setPosition(index: Int, place: de.wanderlogplus.client.GeoPlace) {
        _positions.value = _positions.value + (index to place)
    }

    init {
        viewModelScope.launch { snapshots().collect { _snapshot.value = it } }
    }

    fun consumeMessage() { _message.value = null }

    fun isImported(id: String) = imported.contains(id)

    /** Loads the recent jobs of [kind] (null: all kinds) into [jobs]. */
    fun refreshList(kind: String? = "reel") {
        viewModelScope.launch {
            call { a -> _jobs.value = a.aiJobs(kind, limit = 50) }
        }
    }

    /** "Texte auf Deutsch übersetzen": starts the server's translate job and puts it on top of the list. */
    fun startTranslate() {
        viewModelScope.launch {
            call { a -> a.startTranslate().let { _jobs.value = listOf(it) + _jobs.value } }
        }
    }

    /** Starts a reel from a link; [onStarted] gets the job id. */
    fun start(url: String, onStarted: (String) -> Unit) {
        viewModelScope.launch {
            call { a -> a.startReel(url, config()).let { _jobs.value = listOf(it) + _jobs.value; onStarted(it.id) } }
        }
    }

    /** Follows job [id] until it is done or failed. */
    fun watch(id: String) {
        if (_job.value?.id == id && watching?.isActive == true) return
        if (_job.value?.id != id) _positions.value = emptyMap()
        watching?.cancel()
        _importedNow.value = imported.contains(id)
        watching = viewModelScope.launch {
            while (true) {
                val a = api() ?: run { _message.value = "Kein Server eingerichtet"; return@launch }
                val j = try {
                    a.aiJob(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    if (e.kind != ApiErrorKind.NETWORK) { _message.value = "Auswertung nicht gefunden"; return@launch }
                    null // offline for a moment: keep trying
                }
                if (j != null) {
                    _job.value = j
                    if (isFinished(j)) return@launch
                }
                delay(pollMillis)
            }
        }
    }

    /**
     * Takes the picks over (once per job) as saved places, and the picked [tips] into guides: one guide job on the
     * server that merges them into existing guides or writes new ones.
     */
    fun import(
        jobId: String,
        picks: List<ReelPick>,
        tips: List<de.wanderlogplus.shared.sync.ReelTip>,
        source: String?,
        /** Already saved places whose reel facts go into their research. */
        savedFacts: List<de.wanderlogplus.shared.sync.ReelCandidate> = emptyList(),
    ) {
        if (imported.contains(jobId) || (picks.isEmpty() && tips.isEmpty() && savedFacts.isEmpty())) return
        val guideTips = tips.filter { it.placeIds.isEmpty() }
        val factItems = placeFactItems(tips, savedFacts)
        writeScope.launch {
            try {
                // Tips first: when the guide job cannot start, nothing is taken over and the user can try again.
                if (guideTips.isNotEmpty() && !startTips(guideTips)) return@launch
                if (factItems.isNotEmpty() && !startPlaceFacts(factItems)) return@launch
                var ids: List<String> = emptyList()
                if (picks.isNotEmpty()) edit { e, _ -> ids = importPicks(e, picks, source); Unit }
                imported.add(jobId)
                _importedNow.value = true
                importMessage(guideTips.size, factItems.size)?.let { _message.value = it }
                // "Trotzdem importieren": the place was skipped as a duplicate, so it is researched now (the server
                // waits until the sync has brought it over).
                val kept = picks.filter { importable(it.candidate) }
                val toResearch = ids.filterIndexed { i, _ -> kept.getOrNull(i)?.research == true }
                if (toResearch.isNotEmpty()) {
                    syncNow()
                    call { a -> a.startResearch(toResearch, config()) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "Übernehmen fehlgeschlagen"
            }
        }
    }

    /** Starts the job that works reel facts into places' research; false (with a message) when that did not work. */
    private suspend fun startPlaceFacts(items: List<de.wanderlogplus.shared.sync.PlaceFactsItem>): Boolean {
        val a = api() ?: run { _message.value = "Die Infos zu Orten konnten nicht übernommen werden: kein Server eingerichtet"; return false }
        return try {
            a.startPlaceFacts(items)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _message.value = "Die Infos zu Orten konnten nicht übernommen werden: " + when ((e as? ApiException)?.kind) {
                ApiErrorKind.NOT_CONFIGURED -> "auf dem Server nicht eingerichtet"
                ApiErrorKind.NETWORK -> "Server nicht erreichbar"
                else -> "Anfrage fehlgeschlagen"
            } + ". Bitte später noch einmal."
            false
        }
    }

    /** Starts the guide job for [tips]; false (with a message) when that did not work. */
    private suspend fun startTips(tips: List<de.wanderlogplus.shared.sync.ReelTip>): Boolean {
        val a = api() ?: run { _message.value = "Die Tipps konnten nicht übernommen werden: kein Server eingerichtet"; return false }
        return try {
            a.startGuides(tipAsks(tips))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _message.value = "Die Tipps konnten nicht übernommen werden: " + when ((e as? ApiException)?.kind) {
                ApiErrorKind.NOT_CONFIGURED -> "Guides sind auf dem Server nicht eingerichtet"
                ApiErrorKind.NETWORK -> "Server nicht erreichbar"
                else -> "Anfrage fehlgeschlagen"
            } + ". Bitte später noch einmal."
            false
        }
    }

    private suspend fun call(block: suspend (SyncApi) -> Unit) {
        val a = api() ?: run { _message.value = "Kein Server eingerichtet"; return }
        try {
            block(a)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            _message.value = when (e.kind) {
                ApiErrorKind.NOT_CONFIGURED -> errorText("ai_not_configured")
                ApiErrorKind.NETWORK -> "Server nicht erreichbar"
                ApiErrorKind.BAD_REQUEST -> "Das ist kein unterstützter Reel- oder Post-Link"
                else -> "Anfrage fehlgeschlagen"
            }
        }
    }
}
