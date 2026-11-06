package de.wanderlogplus.server.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Duration

/** One researched statement (German) and the pages that back it. */
data class Fact(val text: String, val sources: List<String> = emptyList())

/** What research found: the facts and every source URL (without duplicates). */
data class Facts(val items: List<Fact>, val sources: List<String>)

/** What to research: [objective] in German, [queries] for the web search, [hints] what to look for (one line each). */
data class FactQuery(val objective: String, val queries: List<String>, val hints: List<String> = emptyList(), val location: String = "jp")

/** Gathers facts with sources; no judgement, no prose. Null when nothing usable came back. Blocking; throws [AiFailed]. */
fun interface FactSource {
    fun facts(q: FactQuery, say: (String) -> Unit): Facts?

    fun facts(q: FactQuery): Facts? = facts(q) {}
}

internal fun factInput(q: FactQuery): String = buildString {
    append("Recherchiere für eine Reise-App: ").append(q.objective).append('\n')
    if (q.hints.isNotEmpty()) append("Achte besonders auf: ").append(q.hints.joinToString("; ")).append('\n')
    append("Sammle nur belegte Fakten, je einen kurzen Satz auf Deutsch, höchstens $MAX_FACTS, jeweils mit den Quellen-URLs. ")
    append("Keine Wertung, kein Fließtext.\n")
}

private const val MAX_FACTS = 25

/** Facts and sources from a parsed `{"facts": [{"text", "sources"}]}` object plus [extraSources]; null without facts. */
internal fun factsFrom(o: JsonObject, extraSources: List<String>): Facts? {
    val items = (o["facts"] as? JsonArray).orEmpty().mapNotNull { e ->
        val f = e as? JsonObject ?: return@mapNotNull null
        val text = (f["text"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Fact(text, (f["sources"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }.distinct())
    }.take(MAX_FACTS)
    if (items.isEmpty()) return null
    return Facts(items, (items.flatMap { it.sources } + extraSources).distinct())
}

/** Facts via Parallel: the Search API gathers pages first, then a Task run whose output has only facts. */
class ParallelFacts(private val transport: ParallelTransport, private val processor: String = "base") : FactSource {
    override fun facts(q: FactQuery, say: (String) -> Unit): Facts? {
        say("Websuche (Parallel Search) …")
        val sources = try {
            search(q).also { say(if (it.isEmpty()) "Websuche: keine Quellen" else "Websuche: ${it.size} Quellen") }
        } catch (e: AiFailed) {
            say("Websuche fehlgeschlagen (${e.code}), Recherche ohne Vorab-Quellen")
            emptyList()
        }
        say("Recherche-Auftrag wird gestartet (Parallel Task, $processor) …")
        val (content, citations) = runParallelTask(transport, taskBody(q, sources), say) ?: return null
        return factsFrom(content, citations + sources.map { it.url })
    }

    private fun search(q: FactQuery): List<WebSource> {
        val body = buildJsonObject {
            put("objective", q.objective)
            putJsonArray("search_queries") { q.queries.distinct().take(3).forEach { add(JsonPrimitive(it)) } }
            put("mode", "fast")
            putJsonObject("advanced_settings") {
                put("max_results", 5)
                put("location", q.location)
                putJsonObject("excerpt_settings") { put("max_chars_per_result", 1500) }
            }
        }.toString()
        val (status, text) = transport.send("POST", "/v1/search", body, Duration.ofSeconds(30))
        if (status == 401 || status == 403) throw AiFailed("research_key_rejected")
        if (status == 402) throw AiFailed("research_no_credit")
        if (status !in 200..299) throw AiFailed("research_failed")
        return ParallelResearcher.parseSearch(text)
    }

    private fun taskBody(q: FactQuery, sources: List<WebSource>): String = buildJsonObject {
        put(
            "input",
            buildString {
                append(factInput(q))
                if (sources.isNotEmpty()) {
                    append("\nBereits gefundene Quellen:\n")
                    sources.take(ParallelResearcher.MAX_SOURCES).forEach { s ->
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
                        putJsonObject("facts") {
                            put("type", "array")
                            put("description", "höchstens $MAX_FACTS Fakten, je ein Satz auf Deutsch, nur Belegtes")
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("text") { put("type", "string") }
                                    putJsonObject("sources") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                                }
                                putJsonArray("required") { add(JsonPrimitive("text")); add(JsonPrimitive("sources")) }
                                put("additionalProperties", false)
                            }
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("facts")) }
                    put("additionalProperties", false)
                }
            }
        }
    }.toString()
}

/** Facts via the model's own web search (when no Parallel key is configured). */
class ModelFacts(private val model: ChatModel) : FactSource {
    override fun facts(q: FactQuery, say: (String) -> Unit): Facts? {
        say("Websuche über das KI-Modell …")
        val system = """Du recherchierst im Web für eine Reise-App. Antworte nur mit JSON: {"facts": [{"text": "ein Satz auf Deutsch", "sources": ["https://…"]}]}"""
        val answer = model.complete(system, listOf(AiPart.Text(factInput(q))), webSearch = true)
        return factsFrom(OpenRouterModel.jsonObjectIn(answer), emptyList())
    }
}
