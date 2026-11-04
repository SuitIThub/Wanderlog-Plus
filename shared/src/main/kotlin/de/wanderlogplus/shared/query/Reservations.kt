package de.wanderlogplus.shared.query

import de.wanderlogplus.shared.model.Day
import de.wanderlogplus.shared.model.DayItem
import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.model.PlaceStatus
import de.wanderlogplus.shared.model.SavedPlace

/** A planned place that needs a reservation or tickets and is not marked as reserved yet. */
data class OpenReservation(val saved: SavedPlace, val date: String, val reason: String)

/** Which places need a reservation (or tickets in advance), from the research, the tags and the notes. */
object Reservations {
    /** Days before the planned day on which a reminder is due. */
    val REMINDER_OFFSETS = listOf(30, 14, 7, 2)

    private val NEED = Regex(
        "reservier|vorab (buchen|kaufen|sichern)|im voraus (buchen|kaufen|reservieren)|vorher (buchen|kaufen|reservieren)|" +
            "tickets? .{0,40}(vorher|vorab|im voraus|online|wochen|monate)|ausgebucht|lotterie|voranmeldung|zeitfenster|" +
            "reservation|advance (booking|ticket)|book (ahead|in advance)|予約",
        RegexOption.IGNORE_CASE,
    )
    private val NOT_NEEDED = Regex(
        "keine reservierung|ohne reservierung|reservierung (nicht|nur selten|kaum) (nötig|notwendig|erforderlich)|" +
            "nicht reservieren|no reservation|reservation not",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Why [s] needs a reservation – the research's "Buchbarkeit" (1–2 of 5), a tag or a research note –, or null when
     * nothing says so. A research hint that says "keine Reservierung nötig" does not count.
     */
    fun reason(s: SavedPlace): String? {
        s.tags.firstOrNull { NEED.containsMatchIn(it) && !NOT_NEEDED.containsMatchIn(it) }?.let { return "Tag „$it“" }
        val d = s.dossier
        d?.criteria?.firstOrNull { it.key == "booking" && it.score in 1..2 }?.let { c ->
            return c.note.takeIf { it.isNotBlank() } ?: "Buchbarkeit ${c.score}/5"
        }
        val texts = d?.let { it.hints + it.goodToKnow + it.tips + it.insiderTips }.orEmpty() + listOf(s.note)
        return texts.firstOrNull { t -> NEED.containsMatchIn(t) && !NOT_NEEDED.containsMatchIn(t) }?.trim()?.take(160)
    }

    fun needsReservation(s: SavedPlace): Boolean = reason(s) != null

    /**
     * Places planned on a day from [today] on that need a reservation and are not reserved (nor dropped), each with
     * the first such day; sorted by that day.
     */
    fun open(saved: List<SavedPlace>, days: List<Day>, dayItems: List<DayItem>, today: String): List<OpenReservation> {
        val dateOf = days.associate { it.id to it.date }
        val firstDate = dayItems.filter { it.kind == DayItemKind.PLACE && it.placeId != null }
            .mapNotNull { i -> dateOf[i.dayId]?.takeIf { it >= today }?.let { i.placeId!! to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, v) -> v.min() }
        return saved.filter { !it.reserved && it.status != PlaceStatus.DROPPED }
            .mapNotNull { s -> firstDate[s.placeId]?.let { date -> reason(s)?.let { OpenReservation(s, date, it) } } }
            .distinctBy { it.saved.placeId }
            .sortedWith(compareBy({ it.date }, { it.saved.id }))
    }
}
