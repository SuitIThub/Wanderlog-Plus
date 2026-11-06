package de.wanderlogplus.app.ui.chat

import de.wanderlogplus.app.ui.guides.tripDates
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.sync.ChatRef
import java.text.Normalizer
import java.time.LocalDate

/** Something to pick after "@": the reference, a second line, and extra words it is found by (dates, numbers). */
data class MentionOption(val ref: ChatRef, val subtitle: String, val keys: List<String> = emptyList())

/** "@" mentions in the chat input: what can be mentioned, the search while typing, and keeping text and refs together. */
object Mentions {
    const val LIMIT = 6
    private const val MAX_QUERY = 30

    /** The "@" at [start] and what follows it up to the cursor. */
    data class Query(val start: Int, val query: String)

    /** Saved places, days, stays, trains, flights, guides and regions of the trip. */
    fun options(s: TripSnapshot): List<MentionOption> = buildList {
        fun place(id: String) = s.places[id]?.name?.takeIf { it.isNotBlank() }
        fun region(id: String?) = id?.let { r -> s.regions.firstOrNull { it.id == r }?.name }
        s.savedPlaces.forEach { sp ->
            val name = place(sp.placeId) ?: return@forEach
            add(MentionOption(ChatRef(ChatRef.PLACE, sp.id, name), listOfNotNull("Ort", region(sp.regionId)).joinToString(" · ")))
        }
        s.tripDates().forEachIndexed { i, date ->
            val d = LocalDate.parse(date)
            val short = "%02d.%02d.".format(d.dayOfMonth, d.monthValue)
            val title = s.days.firstOrNull { it.date == date }?.title.orEmpty()
            add(
                MentionOption(
                    ChatRef(ChatRef.DAY, date, "Tag ${i + 1}, $short"),
                    listOf("Tag", title).filter { it.isNotBlank() }.joinToString(" · "),
                    listOf("tag ${i + 1}", short, "${d.dayOfMonth}.${d.monthValue}.", date, title),
                ),
            )
        }
        s.stays.forEach { st ->
            val name = place(st.placeId) ?: "Unterkunft"
            add(MentionOption(ChatRef(ChatRef.STAY, st.id, name), listOf("Unterkunft", region(st.regionId), dates(st.checkIn, st.checkOut)).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")))
        }
        s.trains.forEach { t ->
            val name = listOfNotNull(t.carrier, t.number).joinToString(" ").ifBlank { "${t.depart.name} → ${t.arrive.name}" }
            add(MentionOption(ChatRef(ChatRef.TRAIN, t.id, name), "Zug · ${t.depart.name} → ${t.arrive.name}", listOfNotNull(t.depart.name, t.arrive.name, t.depart.date)))
        }
        s.flights.forEach { f ->
            val name = listOfNotNull(f.airlineCode ?: f.airline, f.number).joinToString(" ").ifBlank { "${f.depart.name} → ${f.arrive.name}" }
            add(
                MentionOption(
                    ChatRef(ChatRef.FLIGHT, f.id, name), "Flug · ${f.depart.code ?: f.depart.name} → ${f.arrive.code ?: f.arrive.name}",
                    listOfNotNull(f.airline, f.depart.name, f.arrive.name, f.depart.code, f.arrive.code, f.depart.date),
                ),
            )
        }
        s.guides.forEach { g -> add(MentionOption(ChatRef(ChatRef.GUIDE, g.id, g.title.ifBlank { "Guide" }), "Guide")) }
        s.regions.forEach { r -> if (r.name.isNotBlank()) add(MentionOption(ChatRef(ChatRef.REGION, r.id, r.name), "Region")) }
    }

    private fun dates(from: String, to: String): String? {
        fun short(d: String) = runCatching { LocalDate.parse(d) }.getOrNull()?.let { "%02d.%02d.".format(it.dayOfMonth, it.monthValue) }
        val a = short(from) ?: return null
        return a + (short(to)?.let { "–$it" } ?: "")
    }

    /** Lower case without accents ("Küstenort" → "kustenort"). */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()

    /** The best [LIMIT] matches: label start, then a key's or a word's start, then anywhere; ties by kind, then label. */
    fun search(options: List<MentionOption>, query: String): List<MentionOption> {
        val q = normalize(query)
        val order = ChatRef.TYPES
        fun score(o: MentionOption): Int? {
            if (q.isEmpty()) return 0
            val label = normalize(o.ref.label)
            val keys = o.keys.map(::normalize)
            return when {
                label.startsWith(q) -> 0
                keys.any { it.startsWith(q) } || label.split(Regex("[\\s\\-·,()]+")).any { it.startsWith(q) } -> 1
                label.contains(q) || keys.any { it.contains(q) } -> 2
                else -> null
            }
        }
        return options.mapNotNull { o -> score(o)?.let { o to it } }
            .sortedWith(compareBy({ it.second }, { order.indexOf(it.first.ref.type) }, { it.first.ref.label.lowercase() }))
            .take(LIMIT).map { it.first }
    }

    /**
     * The mention being typed at [cursor]: an "@" at the start or after a space, no line break after it, at most
     * [MAX_QUERY] characters; null when there is none or the "@" starts an object picked already ([refs]).
     */
    fun queryAt(text: String, cursor: Int, refs: List<ChatRef>): Query? {
        if (cursor < 1 || cursor > text.length) return null
        val at = text.lastIndexOf('@', cursor - 1)
        if (at < 0 || cursor - at - 1 > MAX_QUERY) return null
        if (at > 0 && !text[at - 1].isWhitespace() && text[at - 1] != '(') return null
        val query = text.substring(at + 1, cursor)
        if ('\n' in query || '@' in query) return null
        if (refs.any { text.startsWith("@" + it.label, at) }) return null
        return Query(at, query)
    }

    /** [text] with the typed [q] replaced by "@label " and the cursor after it. */
    fun insert(text: String, cursor: Int, q: Query, label: String): Pair<String, Int> {
        val rest = text.substring(cursor)
        val inserted = "@$label" + if (rest.startsWith(" ")) "" else " "
        val out = text.substring(0, q.start) + inserted + rest
        return out to (q.start + "@$label ".length)
    }

    /** The refs whose "@label" is still in [text]. */
    fun kept(text: String, refs: List<ChatRef>): List<ChatRef> = refs.filter { ("@" + it.label) in text }.distinct()

    /** Where each ref's "@label" stands in [text] (every occurrence), for highlighting. */
    fun spans(text: String, refs: List<ChatRef>): List<Pair<IntRange, ChatRef>> =
        refs.flatMap { r ->
            val needle = "@" + r.label
            generateSequence(text.indexOf(needle).takeIf { it >= 0 }) { i -> text.indexOf(needle, i + 1).takeIf { it >= 0 } }
                .map { (it until it + needle.length) to r }.toList()
        }.sortedBy { it.first.first }
}
