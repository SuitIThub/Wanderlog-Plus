package de.wanderlogplus.server.ai

import de.wanderlogplus.shared.model.Criteria
import de.wanderlogplus.shared.model.PlaceDossier
import de.wanderlogplus.shared.model.PoiKinds
import de.wanderlogplus.shared.sync.AssessConfig
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

/** What research found about one place (German): description, category, worth and the dossier (criteria, verdict, tips, sources). */
data class ResearchOutcome(val description: String, val category: String?, val worth: String?, val dossier: PlaceDossier)

/** Web research for a place, judged by [config]'s criteria; null when nothing usable came back. Blocking. */
fun interface PlaceResearcher {
    fun research(query: ResearchQuery, config: AssessConfig): ResearchOutcome?
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

    override fun research(query: ResearchQuery, config: AssessConfig): ResearchOutcome? {
        val sources = try {
            search(query)
        } catch (e: AiFailed) {
            emptyList() // the task can still research on its own
        }
        val (content, citations) = runTask(query, sources, config) ?: return null
        return parseResearch(content, config, citations + sources.map { it.url })
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
    private fun runTask(q: ResearchQuery, sources: List<WebSource>, config: AssessConfig): Pair<JsonObject, List<String>>? {
        val (status, created) = transport.send("POST", "/v1/tasks/runs", taskBody(q, sources, processor, config), Duration.ofSeconds(30))
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

        fun taskBody(q: ResearchQuery, sources: List<WebSource>, processor: String, config: AssessConfig = AssessConfig()): String = buildJsonObject {
            val keys = config.criteria.mapNotNull { Criteria.byKey(it)?.key }.ifEmpty { Criteria.DEFAULT }
            put(
                "input",
                buildString {
                    append("Recherchiere für eine Reise-App den Ort ").append(placeLabel(q)).append(". ")
                    if (q.evidence.isNotBlank()) append("Bekannt: ").append(q.evidence).append(". ")
                    append("Schreibe alle Texte auf Deutsch, sachlich wie ein guter Reiseführer. ")
                    append("Suche neben Bewertungen und praktischen Hinweisen gezielt Geheimtipps von Einheimischen und Kennern, ")
                    append("versteckte Ecken oder kleine Orte in der Nähe, zugehörige Punkte mit Lage (weitere Eingänge, Aussichtspunkte, nächster Bahnhof) und Dinge, die man vorher wissen sollte.\n")
                    append(criteriaPrompt(config))
                    if (sources.isNotEmpty()) {
                        append("\nBereits gefundene Quellen:\n")
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
                            fun list(name: String, description: String) = putJsonObject(name) {
                                put("type", "array"); putJsonObject("items") { put("type", "string") }; put("description", description)
                            }
                            putJsonObject("description") { put("type", "string"); put("description", "2-4 Sätze auf Deutsch: was der Ort ist und was ihn ausmacht") }
                            putJsonObject("category") { put("type", "string"); putJsonArray("enum") { listOf("SIGHT", "FOOD", "SHOPPING", "NIGHTLIFE", "OTHER").forEach { add(JsonPrimitive(it)) } } }
                            putJsonObject("criteria") {
                                put("type", "array")
                                put("description", "je ein Eintrag pro Kriterium, 1-5 Punkte, 5 = am besten für die Reisenden")
                                putJsonObject("items") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("key") { put("type", "string"); putJsonArray("enum") { keys.forEach { add(JsonPrimitive(it)) } } }
                                        putJsonObject("score") { put("type", "integer") }
                                        putJsonObject("note") { put("type", "string"); put("description", "kurze Begründung auf Deutsch") }
                                    }
                                    putJsonArray("required") { listOf("key", "score", "note").forEach { add(JsonPrimitive(it)) } }
                                    put("additionalProperties", false)
                                }
                            }
                            putJsonObject("worth") { put("type", "string"); putJsonArray("enum") { listOf("MUST", "WORTH", "OPTIONAL", "SKIP").forEach { add(JsonPrimitive(it)) } } }
                            putJsonObject("verdict") { put("type", "string"); put("description", "Gesamtfazit aus den Kriterien, 1-2 Sätze auf Deutsch") }
                            list("insiderTips", "Geheimtipps von Einheimischen/Kennern, höchstens 4, Deutsch")
                            list("hiddenSpots", "versteckte Ecken oder kleine Orte in der Nähe, höchstens 4, Deutsch")
                            list("goodToKnow", "Gut zu wissen (Etikette, Zahlung, Regeln, Besonderheiten), höchstens 4, Deutsch")
                            list("tips", "praktische Tipps, höchstens 4, Deutsch")
                            list("hints", "Warnungen/Hinweise (Öffnungszeiten, Reservierung, Andrang, Preise), höchstens 4, Deutsch")
                            putJsonObject("nearbyPoints") {
                                put("type", "array")
                                put(
                                    "description",
                                    "höchstens 6 konkrete Punkte, die zum Ort gehören oder direkt daneben liegen: weitere Eingänge/Tore, Aussichtspunkte, " +
                                        "die versteckten Ecken, nächster Bahnhof. lat/lng nur wenn sicher bekannt, sonst 0",
                                )
                                putJsonObject("items") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("name") { put("type", "string") }
                                        putJsonObject("kind") { put("type", "string"); putJsonArray("enum") { PoiKinds.LABELS.keys.forEach { add(JsonPrimitive(it)) } } }
                                        putJsonObject("note") { put("type", "string"); put("description", "kurz, Deutsch") }
                                        putJsonObject("lat") { put("type", "number") }
                                        putJsonObject("lng") { put("type", "number") }
                                    }
                                    putJsonArray("required") { listOf("name", "kind", "note", "lat", "lng").forEach { add(JsonPrimitive(it)) } }
                                    put("additionalProperties", false)
                                }
                            }
                        }
                        putJsonArray("required") {
                            listOf("description", "category", "criteria", "worth", "verdict", "insiderTips", "hiddenSpots", "goodToKnow", "tips", "hints", "nearbyPoints").forEach { add(JsonPrimitive(it)) }
                        }
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

        /** The task's JSON output object and the citation URLs from its basis; null without output. */
        fun parseResult(body: String): Pair<JsonObject, List<String>>? {
            val root = try { JSON.parseToJsonElement(body).jsonObject } catch (e: Exception) { throw AiFailed("research_failed") }
            val output = root["output"] as? JsonObject ?: return null
            val content = when (val c = output["content"]) {
                is JsonObject -> c
                is JsonPrimitive -> try { OpenRouterModel.jsonObjectIn(c.content) } catch (e: AiFailed) { return null }
                else -> return null
            }
            val citations = (output["basis"] as? JsonArray).orEmpty().flatMap { b ->
                ((b as? JsonObject)?.get("citations") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("url") }
            }
            return content to citations.distinct()
        }
    }
}
