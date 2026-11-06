package de.wanderlogplus.server.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
import java.util.Base64

/** No OpenRouter key is configured. */
class AiNotConfigured : RuntimeException("ai not configured")

/** The model could not be reached or gave no usable answer. Never carries model or user text. */
class AiFailed(val code: String = "ai_failed") : RuntimeException(code)

/** One part of a user message: text, an image (JPEG bytes) or audio (MP3 bytes). */
sealed interface AiPart {
    data class Text(val text: String) : AiPart
    data class Image(val jpeg: ByteArray, val mime: String = "image/jpeg") : AiPart
    data class Pdf(val bytes: ByteArray, val name: String = "dokument.pdf") : AiPart
    data class Audio(val mp3: ByteArray) : AiPart
}

/** Sends a chat completion and returns the answer text. Blocking. */
fun interface ChatModel {
    fun complete(system: String, parts: List<AiPart>, webSearch: Boolean): String
}

/** A function call the model asked for; [arguments] is its JSON text. */
data class ToolCall(val id: String, val name: String, val arguments: String)

/** One model turn with tools: answer text and/or tool calls; [message] goes back into the history as is. */
data class ToolTurn(val text: String?, val calls: List<ToolCall>, val message: JsonObject)

/** Chat completion with OpenAI-style function tools ([tools] null: the model must answer in text). Blocking. */
fun interface ToolChatModel {
    fun turn(messages: List<JsonObject>, tools: JsonArray?): ToolTurn
}

/** OpenRouter's chat completions endpoint; [model] e.g. "google/gemini-2.5-flash". */
class OpenRouterModel(private val apiKey: String, private val model: String) : ChatModel, ToolChatModel {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    override fun complete(system: String, parts: List<AiPart>, webSearch: Boolean): String =
        answerText(post(requestBody(model, system, parts, webSearch)))

    override fun turn(messages: List<JsonObject>, tools: JsonArray?): ToolTurn = toolTurn(post(toolRequestBody(model, messages, tools)))

    private fun post(body: String): String {
        val request = HttpRequest.newBuilder(URI.create(ENDPOINT))
            .timeout(Duration.ofSeconds(180))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .header("X-Title", "Wanderlog Plus")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        // Many places at once can hit OpenRouter's in-flight budget or a provider's rate limit: wait and try again.
        val response = throttled {
            val r = try {
                http.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: IOException) {
                throw AiFailed("ai_unreachable")
            }
            Attempt(r.statusCode(), r.body() ?: "", r.headers().firstValue("Retry-After").orElse(null))
        }
        if (response.status == 401 || response.status == 403) throw AiFailed("ai_key_rejected")
        if (response.status == 402) throw AiFailed("ai_no_credit")
        if (response.status !in 200..299) throw AiFailed()
        return response.body
    }

    companion object {
        private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
        private val JSON = Json { ignoreUnknownKeys = true }

        fun requestBody(model: String, system: String, parts: List<AiPart>, webSearch: Boolean): String = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray { parts.forEach { add(partJson(it)) } })
                    },
                )
            }
            putJsonObject("response_format") { put("type", "json_object") }
            if (webSearch) putJsonArray("plugins") { add(buildJsonObject { put("id", "web"); put("max_results", 5) }) }
        }.toString()

        fun toolRequestBody(model: String, messages: List<JsonObject>, tools: JsonArray?): String = buildJsonObject {
            put("model", model)
            put("messages", JsonArray(messages))
            if (tools != null && tools.isNotEmpty()) put("tools", tools)
        }.toString()

        /** Text and tool calls of the first choice; the history message keeps only role, content and tool_calls. */
        fun toolTurn(body: String): ToolTurn {
            val root = try {
                JSON.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                throw AiFailed()
            }
            val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: throw AiFailed()
            val msg = choice["message"] as? JsonObject ?: throw AiFailed()
            val text = when (val c = msg["content"]) {
                is JsonPrimitive -> c.contentOrNull
                is JsonArray -> c.joinToString("") { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content.orEmpty() }
                else -> null
            }?.takeIf { it.isNotBlank() }
            val rawCalls = (msg["tool_calls"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val calls = rawCalls.mapIndexedNotNull { i, c ->
                val fn = c["function"] as? JsonObject ?: return@mapIndexedNotNull null
                val name = (fn["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapIndexedNotNull null
                val id = (c["id"] as? JsonPrimitive)?.contentOrNull ?: "call_$i"
                val args = when (val a = fn["arguments"]) {
                    is JsonPrimitive -> a.contentOrNull ?: "{}"
                    is JsonObject -> a.toString()
                    else -> "{}"
                }
                ToolCall(id, name, args)
            }
            val history = buildJsonObject {
                put("role", "assistant")
                put("content", text.orEmpty())
                if (calls.isNotEmpty()) {
                    putJsonArray("tool_calls") {
                        calls.forEach { c ->
                            add(
                                buildJsonObject {
                                    put("id", c.id)
                                    put("type", "function")
                                    putJsonObject("function") { put("name", c.name); put("arguments", c.arguments) }
                                },
                            )
                        }
                    }
                }
            }
            return ToolTurn(text, calls, history)
        }

        private fun partJson(p: AiPart): JsonElement = when (p) {
            is AiPart.Text -> buildJsonObject { put("type", "text"); put("text", p.text) }
            is AiPart.Image -> buildJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", "data:${p.mime};base64," + Base64.getEncoder().encodeToString(p.jpeg)) }
            }
            is AiPart.Pdf -> buildJsonObject {
                put("type", "file")
                putJsonObject("file") {
                    put("filename", p.name)
                    put("file_data", "data:application/pdf;base64," + Base64.getEncoder().encodeToString(p.bytes))
                }
            }
            is AiPart.Audio -> buildJsonObject {
                put("type", "input_audio")
                putJsonObject("input_audio") {
                    put("data", Base64.getEncoder().encodeToString(p.mp3))
                    put("format", "mp3")
                }
            }
        }

        /** The first choice's message text. */
        fun answerText(body: String): String {
            val root = try {
                JSON.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                throw AiFailed()
            }
            val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: throw AiFailed()
            val content = (choice["message"] as? JsonObject)?.get("content") ?: throw AiFailed()
            return when (content) {
                is JsonPrimitive -> content.content
                is JsonArray -> content.joinToString("") { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content.orEmpty() }
                else -> throw AiFailed()
            }
        }

        /** The JSON object in a model answer, tolerating ```json fences and text around it. */
        fun jsonObjectIn(answer: String): JsonObject {
            val start = answer.indexOf('{')
            val end = answer.lastIndexOf('}')
            if (start < 0 || end <= start) throw AiFailed("ai_bad_answer")
            return try {
                JSON.parseToJsonElement(answer.substring(start, end + 1)).jsonObject
            } catch (e: Exception) {
                throw AiFailed("ai_bad_answer")
            }
        }
    }
}
