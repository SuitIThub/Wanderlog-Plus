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

    init {
        viewModelScope.launch { snapshots().collect { _snapshot.value = it } }
    }

    fun consumeMessage() { _message.value = null }

    fun isImported(id: String) = imported.contains(id)

    fun refreshList() {
        viewModelScope.launch {
            call { a -> _jobs.value = a.aiJobs("reel") }
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

    /** Takes the picks over (once per job) as saved places. */
    fun import(jobId: String, picks: List<ReelPick>, source: String?) {
        if (imported.contains(jobId) || picks.isEmpty()) return
        writeScope.launch {
            try {
                edit { e, _ -> importPicks(e, picks, source); Unit }
                imported.add(jobId)
                _importedNow.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "Übernehmen fehlgeschlagen"
            }
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
                ApiErrorKind.BAD_REQUEST -> "Das ist kein unterstützter Reel-Link"
                else -> "Anfrage fehlgeschlagen"
            }
        }
    }
}
