package de.wanderlogplus.server.tabelog

import de.wanderlogplus.server.ServerEdit
import de.wanderlogplus.server.ai.ChatModel
import de.wanderlogplus.server.ai.GERMAN_PLEASE
import de.wanderlogplus.server.ai.completeJson
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.model.TabelogInfo
import de.wanderlogplus.shared.model.TabelogStatus
import de.wanderlogplus.shared.model.isRestaurant
import de.wanderlogplus.shared.model.canonicalTabelogUrl
import de.wanderlogplus.shared.sync.EntityStore
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Two or three German sentences from the newest Tabelog reviews (Japanese); empty when there are none or the model fails. */
class TabelogSummary(private val model: ChatModel) {
    fun summarize(reviews: List<TabelogReview>): String {
        if (reviews.isEmpty()) return ""
        val system = """
            Du fasst Restaurant-Bewertungen von Tabelog (japanisch) für deutsche Japan-Reisende zusammen.
            Zwei bis drei kurze Sätze auf Deutsch: was gelobt wird, was kritisiert wird, ein praktischer Tipp (Bestellung, Wartezeit, Zeitpunkt).
            Nur was in den Bewertungen steht. Antworte nur mit JSON: {"summary": "…"}
        """.trimIndent()
        val user = reviews.joinToString("\n\n") { r -> "Bewertung (${r.date}): ${r.title}\n${r.text.take(1500)}" }
        return try {
            (completeJson(model, system, user, GERMAN_PLEASE) { (it["summary"] as? JsonPrimitive)?.content?.isNotBlank() == true }["summary"] as JsonPrimitive)
                .content.trim()
        } catch (e: Exception) {
            ""
        }
    }
}

/**
 * Fills [Place.tabelog] for saved restaurants: those never looked at, and those the app asked for (REQUESTED, with
 * or without a link set by hand). At most [batch] per sweep, so a big trip's first run spreads its Apify cost. When
 * Apify or Google is unusable (credit, key, outage) the sweep stops, writes nothing for that place and pauses an hour;
 * a failure of a single place marks it not found and the sweep goes on.
 */
open class TabelogSweeper(
    private val store: EntityStore,
    private val source: TabelogSource,
    private val names: JaNameSource,
    private val summary: TabelogSummary?,
    private val write: (List<ServerEdit>) -> Int,
    private val now: () -> Long = System::currentTimeMillis,
    private val batch: Int = 20,
) {
    private val running = AtomicBoolean(false)
    private val json = Json { encodeDefaults = false }
    private val log = LoggerFactory.getLogger(TabelogSweeper::class.java)
    private var scheduler: ScheduledExecutorService? = null

    /** After an outage (no credit, Google down …) sweeps pause until then (epoch millis). */
    @Volatile private var pausedUntil = 0L

    /**
     * One sweep now (blocking); the number of places written. A sweep already running makes this one a no-op (0), as
     * does a pause after an outage. Each place is read again right before and after its lookup: what the app changed
     * in the meantime (a link set by hand) is never overwritten.
     */
    fun sweep(): Int {
        if (now() < pausedUntil) return 0
        if (!running.compareAndSet(false, true)) return 0
        try {
            var written = 0
            for (id in due().map { it.id }) {
                val place = current(id)?.takeIf(::isDue) ?: continue
                val info = try {
                    lookup(place)
                } catch (e: TabelogUnavailable) {
                    if (e.global) {
                        pausedUntil = now() + PAUSE_MS
                        log.warn("Tabelog sweep stopped: {}, paused for an hour", e.code)
                        break
                    }
                    // A problem of this place only: not found, so it does not block the others (the app can search again).
                    log.warn("Tabelog lookup failed: {}", e.code)
                    notFound()
                }
                if (current(id)?.tabelog != place.tabelog) continue
                val field = json.encodeToJsonElement(TabelogInfo.serializer(), info)
                written += write(listOf(ServerEdit(EntityType.PLACE, id, mapOf("tabelog" to field))))
            }
            if (written > 0) log.info("Tabelog: {} place(s) written", written)
            return written
        } finally {
            running.set(false)
        }
    }

    private fun current(id: String): Place? =
        store.get(EntityType.PLACE, id)?.takeIf { !it.isDeleted }?.let { runCatching { EntityCodec.decode<Place>(it) }.getOrNull() }

    private fun isDue(p: Place) = p.tabelog == null || p.tabelog?.status == TabelogStatus.REQUESTED

    /** Restaurants to look at: asked for first, then never looked at; each place once, at most [batch]. */
    internal fun due(): List<Place> {
        val places = store.all(EntityType.PLACE).filter { !it.isDeleted }
            .mapNotNull { r -> runCatching { EntityCodec.decode<Place>(r) }.getOrNull() }.associateBy { it.id }
        val restaurants = store.all(EntityType.SAVED_PLACE).filter { !it.isDeleted }
            .mapNotNull { r -> runCatching { EntityCodec.decode<SavedPlace>(r) }.getOrNull() }
            .filter { isRestaurant(it, places[it.placeId]) }
            .mapNotNull { places[it.placeId] }.distinctBy { it.id }
        val asked = restaurants.filter { it.tabelog?.status == TabelogStatus.REQUESTED }
        val fresh = restaurants.filter { it.tabelog == null }
        return (asked + fresh).take(batch)
    }

    private fun lookup(place: Place): TabelogInfo {
        val byHand = place.tabelog?.takeIf { it.status == TabelogStatus.REQUESTED }?.url?.let(::canonicalTabelogUrl)
        if (byHand != null) {
            val (hit, reviews) = source.details(byHand)
            return hit?.let { found(it, reviews, byHand) } ?: notFound()
        }
        val ja = names.find(place) ?: return notFound()
        val match = TabelogMatch.pick(place, ja.name, source.search(ja.name, ja.area)) ?: return notFound()
        val (hit, reviews) = source.details(canonicalTabelogUrl(match.url) ?: match.url)
        // No detail rows (e.g. no reviews yet): the search already said what we need.
        return found(hit ?: match, reviews, match.url)
    }

    private fun found(hit: TabelogHit, reviews: List<TabelogReview>, fallbackUrl: String) = TabelogInfo(
        status = TabelogStatus.FOUND,
        url = canonicalTabelogUrl(hit.url) ?: canonicalTabelogUrl(fallbackUrl) ?: fallbackUrl,
        name = hit.name,
        score = hit.score,
        reviewCount = hit.reviewCount,
        genre = hit.genre,
        budgetLunch = hit.budgetLunch,
        budgetDinner = hit.budgetDinner,
        hours = hit.hours,
        reservation = hit.reservation,
        station = hit.station,
        summary = summary?.summarize(reviews).orEmpty(),
        checkedAt = now(),
    )

    private fun notFound() = TabelogInfo(TabelogStatus.NOT_FOUND, checkedAt = now())

    /** Sweeps one minute after start and every 15 minutes on [executor]. */
    fun start(executor: ScheduledExecutorService) {
        scheduler = executor
        executor.scheduleWithFixedDelay({ runSafely() }, 1, 15, TimeUnit.MINUTES)
    }

    /** A sweep right away (after the app asked for a place), in the background. */
    open fun kick() {
        scheduler?.execute { runSafely() } ?: Thread({ runSafely() }, "tabelog-kick").apply { isDaemon = true }.start()
    }

    private fun runSafely() {
        try {
            sweep()
        } catch (e: Throwable) {
            // Never let anything escape: the scheduled task would silently stop for good.
            log.error("Tabelog sweep failed: {}", e.javaClass.simpleName)
        }
    }

    private companion object {
        const val PAUSE_MS = 60 * 60_000L
    }
}
