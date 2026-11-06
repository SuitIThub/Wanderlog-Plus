package de.wanderlogplus.server.ai

import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.RoomInfo
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.model.StayInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** A booking confirmation attached to a stay (PDF or image). */
class StayDocument(val mime: String, val bytes: ByteArray, val name: String)

/** Reads one web page into facts; null when nothing usable came back; throws [AiFailed] when it cannot read it. */
fun interface PageReader {
    fun read(url: String, say: (String) -> Unit): Facts?
}

/** Pictures of the booked room type on a page: (file name, bytes), at most a few. */
fun interface RoomPhotoSource {
    fun photos(url: String, roomType: String): List<Pair<String, ByteArray>>
}

/**
 * Researches what a stay offers: the booked room (from the confirmations and the given pages) and the property
 * (also from a web search and Google). [media] reads the confirmations (PDF/images); [writer] writes the result.
 * Booking numbers and traveller names never go further than [media].
 */
class StayResearch(
    private val media: ChatModel,
    private val writer: ChatModel,
    private val facts: FactSource?,
    private val pages: PageReader?,
    private val google: (String) -> List<Fact> = { emptyList() },
    val photos: RoomPhotoSource? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    fun research(stay: Stay, place: Place?, documents: List<StayDocument>, links: List<String>, say: (String) -> Unit): StayInfo? {
        val secrets = secretsOf(stay)
        fun clean(text: String) = text.lines().filterNot { line -> secrets.any { line.contains(it, ignoreCase = true) } }.joinToString("\n").trim()
        val name = place?.name?.ifBlank { null } ?: "die Unterkunft"

        val booking = documents.takeIf { it.isNotEmpty() }?.let { docs ->
            say("Buchungsbestätigungen werden gelesen (${docs.size}) …")
            try {
                val system = """
                    Du liest Buchungsbestätigungen einer Unterkunft. Nenne nur: gebuchter Zimmertyp, Betten, Bad, Größe, Aussicht,
                    Verpflegung (mit Zeiten), Check-in/-out-Zeiten und Ausstattung des Zimmers. Eine Angabe pro Zeile, kurz.
                    Nenne keine Namen von Gästen, keine Buchungs- oder Bestätigungsnummern, keine Preise, keine Kontaktdaten.
                """.trimIndent()
                val parts = listOf(AiPart.Text("Unterkunft: $name")) + docs.map { d ->
                    if (d.mime == "application/pdf") AiPart.Pdf(d.bytes, d.name) else AiPart.Image(d.bytes, d.mime.ifBlank { "image/jpeg" })
                }
                clean(media.complete(system, parts, webSearch = false)).ifBlank { null }
            } catch (e: AiFailed) {
                say("Bestätigungen nicht lesbar (${e.code})")
                null
            }
        }
        if (booking != null) say("Angaben aus der Buchung gelesen")

        val fromPages = links.mapNotNull { url ->
            val r = pages ?: return@mapNotNull null
            try {
                r.read(url, say) ?: run { say("Link ohne verwertbare Angaben: ${ParallelResearcher.hostOf(url)}"); null }
            } catch (e: AiFailed) {
                say("Link nicht lesbar: ${ParallelResearcher.hostOf(url)} (${e.code})")
                null
            }
        }

        val fromWeb = facts?.let { f ->
            try {
                f.facts(
                    FactQuery(
                        "Ausstattung, Services, Verpflegung und Hausregeln der Unterkunft $name" + (place?.address?.let { ", $it" } ?: ""),
                        listOfNotNull(name, "$name amenities facilities", place?.address?.let { "$name $it" }),
                        listOf("Onsen/Bad", "WLAN", "Wäscherei", "Gepäckaufbewahrung", "Essenszeiten", "Schließzeiten"),
                    ),
                    say,
                )
            } catch (e: AiFailed) {
                say("Websuche fehlgeschlagen (${e.code})")
                null
            }
        }
        val fromGoogle = place?.googlePlaceId?.takeIf { it.isNotBlank() }?.let { id -> try { google(id) } catch (e: Exception) { emptyList() } }.orEmpty()

        val pageFacts = fromPages.flatMap { it.items }
        val propertyFacts = fromWeb?.items.orEmpty() + fromGoogle
        if (booking == null && pageFacts.isEmpty() && propertyFacts.isEmpty()) {
            say("Keine verwertbaren Angaben gefunden")
            return null
        }
        val roomKnown = booking != null || pageFacts.isNotEmpty()
        say("Zimmer und Ausstattung werden zusammengefasst …")
        val system = """
            Du fasst für eine Reise-App zusammen, was eine Unterkunft bietet, auf Deutsch, knapp und sachlich.
            "room" beschreibt das gebuchte Zimmer: Angaben aus der Buchungsbestätigung haben Vorrang; von Webseiten nur, was zu
            diesem Zimmertyp passt. "property" beschreibt die Unterkunft selbst. Behaupte nichts, was nicht in den Angaben steht.
            Ausstattung in Gruppen: Bad, Schlafen, Technik, Essen & Trinken, Onsen & Spa, Service, Sonstiges (nur genutzte).
            Antworte nur mit JSON: {"room": {"type": "", "description": "", "sizeSqm": null, "beds": "", "bath": "", "view": "",
            "maxGuests": null, "amenities": [{"name": "Bad", "items": [{"name": "…", "note": "kurz oder leer"}]}]},
            "property": {"description": "", "meals": "", "houseRules": "", "amenities": [{"name": "…", "items": [{"name": "…", "note": ""}]}]}}
        """.trimIndent()
        val user = buildString {
            append("Unterkunft: ").append(name).append('\n')
            if (booking != null) append("\nAus der Buchungsbestätigung:\n").append(booking).append('\n')
            if (pageFacts.isNotEmpty()) append("\nVon den angegebenen Seiten:\n").append(factLines(Facts(pageFacts, emptyList()))).append('\n')
            if (propertyFacts.isNotEmpty()) append("\nZur Unterkunft (Websuche, Google):\n").append(factLines(Facts(propertyFacts, emptyList()))).append('\n')
            if (!roomKnown) append("\nKeine Angaben zum gebuchten Zimmer: lass \"room\" leer.\n")
        }
        val o = completeJson(writer, system, clean(user), GERMAN_PLEASE) { it["room"] is JsonObject || it["property"] is JsonObject }
        val decoded = try { json.decodeFromJsonElement(StayInfo.serializer(), o) } catch (e: Exception) { throw AiFailed("ai_bad_answer") }
        // Defence in depth: whatever the writer made of it, no booking data is kept.
        var text = json.encodeToString(StayInfo.serializer(), decoded)
        secrets.forEach { s -> text = text.replace(s, "", ignoreCase = true) }
        val info = json.decodeFromString(StayInfo.serializer(), text)
        val sources = (links + fromPages.flatMap { it.sources } + fromWeb?.sources.orEmpty() + fromGoogle.flatMap { it.sources }).distinct()
        return info.copy(room = if (roomKnown) info.room else RoomInfo(), sources = sources, editedByHand = emptySet())
    }

    companion object {
        /** The booking number and the travellers' names (also each surname) of a stay. */
        fun secretsOf(stay: Stay): List<String> =
            (listOfNotNull(stay.confirmationNumber?.trim()?.takeIf { it.length >= 4 }) +
                stay.travelerNames.flatMap { n -> listOf(n.trim()) + n.trim().split(Regex("\\s+")).filter { it.length >= 4 } })
                .filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }
    }
}

/** Reads a page with a Parallel task that is told to use only that page. */
class ParallelPageReader(private val transport: ParallelTransport, private val processor: String = "base") : PageReader {
    override fun read(url: String, say: (String) -> Unit): Facts? {
        say("Seite wird gelesen: ${ParallelResearcher.hostOf(url)} …")
        val q = FactQuery(
            "Lies die Seite $url und sammle die Angaben zu Zimmern (Typ, Größe, Betten, Bad, Aussicht, Ausstattung), zur Ausstattung der Unterkunft, Verpflegung und Hausregeln. Nutze nur diese Seite.",
            listOf(url),
        )
        val (content, citations) = runParallelTask(transport, ParallelFacts(transport, processor).taskBody(q, emptyList()), say) ?: return null
        return factsFrom(content, listOf(url) + citations)
    }
}

/** Pictures from a hotel page: its preview image and pictures named like the room type or "room", at most [MAX]. */
class HttpRoomPhotoSource : RoomPhotoSource {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()

    override fun photos(url: String, roomType: String): List<Pair<String, ByteArray>> {
        val page = get(url)?.let { String(it, Charsets.UTF_8) } ?: return emptyList()
        val words = (listOf("room", "zimmer", "客室", "suite", "guestroom") + roomType.lowercase().split(Regex("\\W+")).filter { it.length >= 5 }).distinct()
        val og = Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)""", RegexOption.IGNORE_CASE).findAll(page).map { it.groupValues[1] }
        val imgs = Regex("""<img[^>]+>""", RegexOption.IGNORE_CASE).findAll(page).map { it.value }.filter { tag ->
            val t = tag.lowercase()
            words.any { it in t }
        }.mapNotNull { Regex("""src=["']([^"']+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
        return (og + imgs).map { runCatching { URI(url).resolve(it).toString() }.getOrNull() }.filterNotNull()
            .filter { it.startsWith("https://") }.distinct().take(MAX * 2)
            .mapNotNull { src -> get(src, image = true)?.let { bytes -> src.substringAfterLast('/').substringBefore('?').ifBlank { "zimmer.jpg" } to bytes } }
            .take(MAX).toList()
    }

    private fun get(url: String, image: Boolean = false): ByteArray? = try {
        val r = http.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", "Mozilla/5.0 (Wanderlog Plus)").GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        val type = r.headers().firstValue("content-type").orElse("")
        r.body().takeIf { r.statusCode() in 200..299 && it.size <= MAX_BYTES && (!image || type.startsWith("image/")) }
    } catch (e: Exception) {
        null
    }

    companion object {
        const val MAX = 8
        const val MAX_BYTES = 5_000_000
    }
}
