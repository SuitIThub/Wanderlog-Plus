package de.wanderlogplus.app.ui.logistics

import androidx.lifecycle.ViewModel
import de.wanderlogplus.shared.sync.AiJobInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Starts the research of a stay's room and amenities on the server, follows it and syncs the result. */
class StayInfoViewModel(
    private val scope: CoroutineScope,
    private val start: suspend (stayId: String, links: List<String>, fresh: Boolean) -> AiJobInfo?,
    private val job: suspend (id: String) -> AiJobInfo?,
    private val syncNow: () -> Unit,
    private val pollMillis: Long = 3_000,
) : ViewModel() {
    private val _running = MutableStateFlow<AiJobInfo?>(null)

    /** The stay research started last ([AiJobInfo.stayId] says for which stay), refreshed until it ends. */
    val running: StateFlow<AiJobInfo?> = _running

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun messageShown() {
        _message.value = null
    }

    fun research(stayId: String, links: List<String>, fresh: Boolean) {
        scope.launch {
            val first = try { start(stayId, links, fresh) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            if (first == null) {
                _message.value = "Recherche lässt sich gerade nicht starten (Server nicht erreichbar?)."
                return@launch
            }
            _running.value = first
            var current: AiJobInfo = first
            while (current.status != "DONE" && current.status != "FAILED") {
                delay(pollMillis)
                current = (try { job(first.id) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }) ?: continue
                _running.value = current
            }
            if (current.status == "DONE") syncNow() else _message.value = de.wanderlogplus.app.ui.reels.errorText(current.error)
        }
    }
}
