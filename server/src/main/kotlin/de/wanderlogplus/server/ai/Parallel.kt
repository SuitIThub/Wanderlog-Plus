package de.wanderlogplus.server.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** A web source found by the search: page, title and the relevant excerpts. */
data class WebSource(val url: String, val title: String?, val excerpts: List<String>)

/** Researched facts about one place, in German. [sources] are the pages the answer relies on. */
data class PlaceResearch(
    val description: String,
    val tips: List<String>,
    val hints: List<String>,
    val worth: String?,
    val worthReason: String,
    val category: String?,
    val sources: List<String>,
)

/** Web research for a place; null when nothing usable came back. Blocking. */
fun interface PlaceResearcher {
    fun research(query: ResearchQuery): PlaceResearch?
}

/** What is known about a place before research. */
data class ResearchQuery(val name: String, val localName: String?, val city: String?, val country: String?, val kind: String, val evidence: String)

/** Sends one HTTP request to Parallel; returns status and body. Replaced in tests. */
fun interface ParallelTransport {
    fun send(method: String, path: String, body: String?, timeout: Duration): Pair<Int, String>
}

class HttpParallelTransport(private val apiKey: String, private val base: String = "https://api.parallel.ai") : ParallelTransport {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    override fun send(method: String, path: String, body: String?, timeout: Duration): Pair<Int, String> {
        val b = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(timeout)
            .header("x-api-key", apiKey)
            .header("Content-Type", "application/json")
        val request = if (method == "POST") b.POST(HttpRequest.BodyPublishers.ofString(body ?: "{}")).build() else b.GET().build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw AiFailed("research_unreachable")
        }
        return response.statusCode() to response.body()
    }
}

/**
 * Research via Parallel: the Search API ("fast") gathers pages and excerpts about the place first, then a Task run
 * ([processor], default "base") turns them plus its own web research into a structured German result.
 */
class ParallelResearcher(private val transport: ParallelTransport, private val processor: String = "base") : PlaceResearcher {
    private val json = Json { ignoreUnknownKeys = true }

    override fun research(query: ResearchQuery): PlaceResearch? {
        val sources = try {
            search(query)
        } catch (e: AiFailed) {
            emptyList() // the task can still research on its own
        }
        val output = runTask(query, sources) ?: return null
        val taskSources = output.second
        return output.first.copy(sources = (taskSources + sources.map { it.url }).distinct().take(MAX_SOURCES))
    }

    /** Search API: a few pages with excerpts about the place (reviews, tips, opening hours). */
    fun search(q: ResearchQuery): List<WebSource> {
        val body = searchBody(q)
        val (status, text) = transport.send("POST", "/v1/search", body, Duration.ofSeconds(30))
        if (status == 401 || status == 403) throw AiFailed("research_key_rejected")
        if (status == 402) throw AiFailed("research_no_credit")
        if (status !in 200..299) throw AiFailed("research_failed")
        return parseSearch(text)
    }

    /** Task API: create a run, then wait for its result (the result call blocks; a timeout just asks again). */
    private fun runTask(q: ResearchQuery, sources: List<WebSource>): Pair<PlaceResearch, List<String>>? {
        val (status, created) = transport.send("POST", "/v1/tasks/runs", taskBody(q, sources, processor), Duration.ofSeconds(30))
        if (status == 401 || status == 403) throw AiFailed("research_key_rejected")
        if (status == 402) throw AiFailed("research_no_credit")
        if (status !in 200..299) throw AiFailed("research_failed")
        val runId = (json.parseToJsonElement(created).jsonObject["run_id"] as? JsonPrimitive)?.content ?: throw AiFailed("research_failed")
        repeat(RESULT_ATTEMPTS) {
            val (s, body) = transport.send("GET", "/v1/tasks/runs/$runId/result?timeout=$RESULT_WAIT_SECONDS", null, Duration.ofSeconds(RESULT_WAIT_SECONDS + 30))
            when (s) {
                in 200..299 -> return parseResult(body)
                408 -> Unit // still running: ask again
                else -> throw AiFailed("research_failed")
            }
        }
        throw AiFailed("research_timeout")
    }

    companion object {
        const val MAX_SOURCES = 5
        const val RESULT_WAIT_SECONDS = 240L
        const val RESULT_ATTEMPTS = 3

        fun searchBody(q: ResearchQuery): String = buildJsonObject {
            put("objective", "Bewertungen, Tipps und praktische Hinweise (Öffnungszeiten, Reservierung, Andrang, Preise) zu ${placeLabel(q)} für Reisende.")
            putJsonArray("search_queries") {
                listOfNotNull(
                    listOfNotNull(q.name, q.city).joinToString(" "),
                    q.localName,
                    "${q.name} tips reviews",
                ).distinct().take(3).forEach { add(JsonPrimitive(it)) }
            }
            put("mode", "fast")
            putJsonObject("advanced_settings") {
                put("max_results", 5)
                put("location", "jp")
                putJsonObject("excerpt_settings") { put("max_chars_per_result", 1500) }
            }
        }.toString()

        fun taskBody(q: ResearchQuery, sources: List<WebSource>, processor: String): String = buildJsonObject {
            put(
                "input",
                buildString {
                    append("Recherchiere für eine Reise-App den Ort ").append(placeLabel(q)).append(". ")
                    if (q.evidence.isNotBlank()) append("Bekannt aus einem Reise-Reel: ").append(q.evidence).append(". ")
                    append("Schreibe alle Texte auf Deutsch, sachlich wie ein Reiseführer. ")
                    append("worth: MUST = muss man gesehen haben, WORTH = lohnt sich, OPTIONAL = wenn Zeit ist, SKIP = eher auslassen.")
                    if (sources.isNotEmpty()) {
                        append("\n\nBereits gefundene Quellen:\n")
                        sources.take(MAX_SOURCES).forEach { s ->
                            append("- ").append(s.title ?: s.url).append(" (").append(s.url).append("): ")
                            append(s.excerpts.joinToString(" ").take(800)).append('\n')
                        }
                    }
                },
            )
            put("processor", processor)
            putJsonObject("task_spec") {
                putJsonObject("output_schema") {
                    put("type", "json")
                    putJsonObject("json_schema") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("description") { put("type", "string"); put("description", "2-4 Sätze auf Deutsch: was der Ort ist und was ihn ausmacht") }
                            putJsonObject("tips") { put("type", "array"); putJsonObject("items") { put("type", "string") }; put("description", "höchstens 4 kurze Tipps auf Deutsch") }
                            putJsonObject("hints") { put("type", "array"); putJsonObject("items") { put("type", "string") }; put("description", "höchstens 4 Hinweise oder Warnungen auf Deutsch (Öffnungszeiten, Reservierung, Andrang, Preise)") }
                            putJsonObject("worth") { put("type", "string"); putJsonArray("enum") { listOf("MUST", "WORTH", "OPTIONAL", "SKIP").forEach { add(JsonPrimitive(it)) } } }
                            putJsonObject("worthReason") { put("type", "string"); put("description", "1 Satz Begründung auf Deutsch") }
                            putJsonObject("category") { put("type", "string"); putJsonArray("enum") { listOf("SIGHT", "FOOD", "SHOPPING", "NIGHTLIFE", "OTHER").forEach { add(JsonPrimitive(it)) } } }
                        }
                        putJsonArray("required") { listOf("description", "tips", "hints", "worth", "worthReason", "category").forEach { add(JsonPrimitive(it)) } }
                        put("additionalProperties", false)
                    }
                }
            }
        }.toString()

        private fun placeLabel(q: ResearchQuery) =
            listOfNotNull(q.name + (q.localName?.let { " ($it)" } ?: ""), q.city, q.country ?: "Japan").joinToString(", ")

        private val JSON = Json { ignoreUnknownKeys = true }

        fun parseSearch(body: String): List<WebSource> {
            val root = try { JSON.parseToJsonElement(body).jsonObject } catch (e: Exception) { throw AiFailed("research_failed") }
            return (root["results"] as? JsonArray).orEmpty().mapNotNull { r ->
                val o = r as? JsonObject ?: return@mapNotNull null
                val url = o.str("url") ?: return@mapNotNull null
                WebSource(url, o.str("title"), (o["excerpts"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content })
            }
        }

        /** The task's JSON output and the citation URLs from its basis. */
        fun parseResult(body: String): Pair<PlaceResearch, List<String>>? {
            val root = try { JSON.parseToJsonElement(body).jsonObject } catch (e: Exception) { throw AiFailed("research_failed") }
            val output = root["output"] as? JsonObject ?: return null
            val content = when (val c = output["content"]) {
                is JsonObject -> c
                is JsonPrimitive -> try { OpenRouterModel.jsonObjectIn(c.content) } catch (e: AiFailed) { return null }
                else -> return null
            }
            fun strings(key: String) = (content[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }
            val citations = (output["basis"] as? JsonArray).orEmpty().flatMap { b ->
                ((b as? JsonObject)?.get("citations") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("url") }
            }
            return PlaceResearch(
                description = content.str("description").orEmpty(),
                tips = strings("tips").take(4),
                hints = strings("hints").take(4),
                worth = content.str("worth"),
                worthReason = content.str("worthReason").orEmpty(),
                category = content.str("category"),
                sources = emptyList(),
            ) to citations.distinct()
        }
    }
}
