package de.wanderlogplus.app.widgets

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime

/** Plans the next redraw at [time] (in the trip's local time; [now] is the same clock's present). */
fun interface BoundaryScheduler {
    fun at(time: LocalDateTime, now: LocalDateTime)
}

/**
 * Keeps the widgets current: [updateAll] redraws them and plans the next redraw at the next boundary
 * ([WidgetData.nextBoundary]); [watch] redraws after data changes. [input] is shared by all widgets of one redraw.
 */
class WidgetUpdater(
    private val load: suspend () -> WidgetInput,
    private val scheduler: BoundaryScheduler,
    private val redraw: suspend () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var cached: WidgetInput? = null
    private var cachedAt = 0L

    /** The input of the current redraw (loaded at most once per [CACHE_MILLIS]); an empty one when loading fails. */
    suspend fun input(): WidgetInput = lock.withLock {
        val t = clock()
        cached?.takeIf { t - cachedAt < CACHE_MILLIS }?.let { return@withLock it }
        val fresh = try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            WidgetInput(null, LocalDateTime.now(), null, null, null, null)
        }
        cached = fresh
        cachedAt = t
        fresh
    }

    /** Reloads the data, redraws every widget and plans the next redraw. */
    suspend fun updateAll() {
        lock.withLock { cached = null }
        val i = input()
        try {
            redraw()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { android.util.Log.w("Widgets", "Redraw failed: ${e.javaClass.simpleName}") }
        }
        runCatching { scheduler.at(WidgetData.nextBoundary(i), i.now) }
    }

    /** Redraws [DEBOUNCE_MILLIS] after the last of a burst of [changes]. */
    @OptIn(FlowPreview::class)
    fun watch(scope: CoroutineScope, changes: Flow<Any?>) {
        scope.launch { changes.debounce(DEBOUNCE_MILLIS).collect { updateAll() } }
    }

    companion object {
        const val CACHE_MILLIS = 1_000L
        const val DEBOUNCE_MILLIS = 2_000L
    }
}
