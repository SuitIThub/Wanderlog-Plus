package de.wanderlogplus.app.ui.guides

import androidx.compose.runtime.compositionLocalOf
import androidx.lifecycle.ViewModel
import de.wanderlogplus.app.ui.trip.EditFn
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Guide
import de.wanderlogplus.shared.model.GuideTarget
import de.wanderlogplus.shared.sync.AiJobInfo
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.GuideAsk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the guide screens and chips can do; the app's [GuidesViewModel], a recorder in tests. */
interface GuideActions {
    fun dismiss(topic: String)
    fun createSelected(topics: List<String>)
    fun save(guide: Guide)
    fun delete(id: String)
    fun pin(guideId: String, target: GuideTarget)
    fun hide(guideId: String, target: GuideTarget)
}

/** The guide actions for detail screens and the today card; null hides hiding/pinning there. */
val LocalGuideActions = compositionLocalOf<GuideActions?> { null }

/** Opens a guide's page; null where no navigation is available (previews, tests). */
val LocalOpenGuide = compositionLocalOf<((String) -> Unit)?> { null }

/** Opens the guide overview (suggestions); null where no navigation is available. */
val LocalOpenGuides = compositionLocalOf<(() -> Unit)?> { null }

class GuidesViewModel(
    private val edit: EditFn,
    private val scope: CoroutineScope,
    /** Starts a guides job on the server; null when no server is set up. */
    private val startGuides: suspend (List<GuideAsk>) -> AiJobInfo?,
    /** The current state of a job; null when unknown or unreachable. */
    private val job: suspend (String) -> AiJobInfo?,
) : ViewModel(), GuideActions {
    private val _running = MutableStateFlow<AiJobInfo?>(null)

    /** The guides job started last, refreshed until it ends. */
    val running: StateFlow<AiJobInfo?> = _running

    private val _message = MutableStateFlow<String?>(null)

    /** A German note for a snackbar; cleared by [messageShown]. */
    val message: StateFlow<String?> = _message

    fun messageShown() {
        _message.value = null
    }

    override fun dismiss(topic: String) = write { ed, s ->
        val prefs = s.guidePrefs
        if (topic in prefs.dismissedTopics) return@write
        val after = prefs.copy(dismissedTopics = prefs.dismissedTopics + topic)
        ed.set(EntityType.GUIDE_PREFS, after.id, mapOf("dismissedTopics" to EntityCodec.fields(after).getValue("dismissedTopics")))
    }

    override fun createSelected(topics: List<String>) {
        if (topics.isEmpty()) return
        scope.launch {
            val started = try {
                startGuides(topics.map { GuideAsk(topic = it) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (started == null) {
                _message.value = "Guides lassen sich gerade nicht erstellen (Server nicht erreichbar?)."
                return@launch
            }
            _running.value = started
            follow(started.id)
        }
    }

    private suspend fun follow(id: String) {
        while (_running.value?.let { it.id == id && it.status != "DONE" && it.status != "FAILED" } == true) {
            delay(POLL_MS)
            val now = try { job(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { null } ?: continue
            _running.value = now
        }
    }

    override fun save(guide: Guide) = change(guide.id) { guide }

    override fun delete(id: String) = write { ed, _ -> ed.delete(EntityType.GUIDE, id) }

    override fun pin(guideId: String, target: GuideTarget) = change(guideId) { g ->
        g.copy(pinnedTo = (g.pinnedTo + target).distinct(), hiddenFrom = g.hiddenFrom - target)
    }

    override fun hide(guideId: String, target: GuideTarget) = change(guideId) { g ->
        g.copy(pinnedTo = g.pinnedTo - target, hiddenFrom = (g.hiddenFrom + target).distinct())
    }

    private fun change(id: String, update: (Guide) -> Guide) = write { ed, s ->
        val before = s.guides.firstOrNull { it.id == id } ?: return@write
        val after = update(before).copy(id = id)
        ed.setChanged(EntityType.GUIDE, id, EntityCodec.fields(before), EntityCodec.fields(after))
    }

    private fun write(action: (LocalEditor, TripSnapshot) -> Unit) {
        scope.launch {
            try {
                edit(action)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "Speichern fehlgeschlagen."
            }
        }
    }

    private companion object {
        const val POLL_MS = 3000L
    }
}
