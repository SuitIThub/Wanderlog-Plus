package de.wanderlogplus.app.widgets

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.wanderlogplus.app.WanderlogPlusApp
import de.wanderlogplus.app.ui.chat.AndroidChatLocator
import de.wanderlogplus.app.ui.chat.ChatBackend
import de.wanderlogplus.app.ui.chat.SyncApiChatBackend
import de.wanderlogplus.app.ui.chat.hasLocationPermission
import de.wanderlogplus.shared.sync.ChatContext
import de.wanderlogplus.shared.sync.ChatLocation
import de.wanderlogplus.shared.sync.ChatScreens
import de.wanderlogplus.shared.sync.ChatSendRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Where a quick result goes (the [QuickStore] in the app). */
fun interface QuickSink {
    fun write(r: QuickResult)
}

/** What a quick ask needs; replaced in tests. */
class QuickDeps(
    val backend: ChatBackend,
    val load: suspend () -> WidgetInput,
    /** The device's position, or null (no permission, no fix in time). */
    val locate: suspend () -> ChatLocation?,
    val store: QuickSink,
    /** Redraws the widgets. */
    val update: suspend () -> Unit,
    val pollMillis: Long = 2_000,
    val timeoutMillis: Long = 180_000,
    val clock: () -> Long = System::currentTimeMillis,
)

/**
 * A widget's "Was jetzt?": marks the ask as running, opens a new chat with the fixed question and the free time,
 * waits for the answer (at most [QuickDeps.timeoutMillis]) and keeps the suggested places for the widget.
 */
class QuickAskRunner(private val d: QuickDeps) {
    suspend fun run(kind: String) {
        val input = d.load()
        val req = QuickAsks.request(kind, input.snapshot, input.now)
        val date = input.now.toLocalDate().toString()
        val at = d.clock()
        var chatId: String? = null
        fun failed() = QuickResult(kind, chatId, QuickResult.FAILED, at, date)
        d.store.write(QuickResult(kind, null, QuickResult.RUNNING, at, date))
        d.update()
        val result = try {
            val from = d.locate() ?: req.anchor
            val chat = d.backend.create()
            chatId = chat.id
            var c = d.backend.send(chat.id, ChatSendRequest(req.question, ChatContext(ChatScreens.TODAY, date = date, label = "Heute"), from, quick = req.quick))
            val end = d.clock() + d.timeoutMillis
            while (c.pending && d.clock() < end) {
                delay(d.pollMillis)
                c = d.backend.get(chat.id)
            }
            if (c.pending) failed() else QuickAsks.result(kind, c, from, input.snapshot, at, date)
        } catch (e: CancellationException) {
            d.store.write(failed())
            throw e
        } catch (e: Exception) {
            failed()
        }
        d.store.write(result)
        d.update()
    }
}

/** Runs [QuickAskRunner] for the kind in its input data. */
class QuickAskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val kind = inputData.getString(KEY_KIND) ?: return Result.success()
        val container = (applicationContext as WanderlogPlusApp).container
        val ctx = applicationContext
        val store = quickStore(ctx)
        QuickAskRunner(
            QuickDeps(
                backend = SyncApiChatBackend { container.syncApi() },
                load = { loadWidgetInput(ctx, container) },
                locate = {
                    if (!hasLocationPermission(ctx)) null
                    else withTimeoutOrNull(LOCATE_MILLIS) { runCatching { AndroidChatLocator(ctx).locate() }.getOrNull() }
                },
                store = { r -> runCatching { store.write(r) } },
                update = { container.widgets.updateAll() },
            ),
        ).run(kind)
        return Result.success()
    }

    companion object {
        const val KEY_KIND = "kind"
        const val LOCATE_MILLIS = 6_000L
    }
}

/** Starts a quick ask; a second tap while one runs is ignored. */
fun enqueueQuickAsk(context: Context, kind: String) {
    val req = OneTimeWorkRequestBuilder<QuickAskWorker>().setInputData(workDataOf(QuickAskWorker.KEY_KIND to kind)).build()
    WorkManager.getInstance(context.applicationContext ?: context).enqueueUniqueWork(QUICK_WORK, ExistingWorkPolicy.KEEP, req)
}

const val QUICK_WORK = "quick-ask"

/**
 * A tap on „Sehen“/„Essen“: the widget shows „Suche passende Ziele …“ right away ([sink] + [update]), then the
 * ask itself starts as work ([enqueue]), which may take a few seconds to begin.
 */
suspend fun startQuickAsk(
    kind: String,
    input: WidgetInput,
    sink: QuickSink,
    update: suspend () -> Unit,
    enqueue: (String) -> Unit,
    clock: () -> Long = System::currentTimeMillis,
) {
    sink.write(QuickResult(kind, null, QuickResult.RUNNING, clock(), input.now.toLocalDate().toString()))
    update()
    enqueue(kind)
}

/** The chat widget's „Sehenswürdigkeit“/„Essen“ button (widget large enough for the result). */
class AskCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val kind = parameters[KIND] ?: return
        val container = (context.applicationContext as WanderlogPlusApp).container
        val store = quickStore(context)
        startQuickAsk(
            kind, container.widgets.input(),
            sink = { r -> runCatching { store.write(r) } },
            update = { container.widgets.updateAll() },
            enqueue = { k -> enqueueQuickAsk(context, k) },
        )
    }

    companion object {
        val KIND = ActionParameters.Key<String>("kind")
    }
}
