package de.wanderlogplus.server.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** True when [s] has any Japanese characters (kana or kanji). */
fun hasJapanese(s: String): Boolean = s.any { c ->
    val b = Character.UnicodeBlock.of(c)
    b == Character.UnicodeBlock.HIRAGANA || b == Character.UnicodeBlock.KATAKANA || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
}

/** Translates short travel texts into German with the text model; whatever fails stays as it was. */
class Translator(private val model: ChatModel) {
    /** [texts] in German, same order and count; a text that is German already comes back unchanged. */
    fun toGerman(texts: List<String>): List<String> {
        if (texts.isEmpty()) return emptyList()
        val system = """
            Du übersetzt Texte für eine Reise-App ins Deutsche: natürlich, knapp, sachlich. Eigennamen (Orte, Restaurants) bleiben,
            japanische Namen in lateinischer Umschrift mit dem Original in Klammern, wenn hilfreich. Preise und Zeiten unverändert.
            Ist ein Text schon Deutsch, gib ihn unverändert zurück.
            Antworte nur mit JSON: {"texts": ["…"]} – gleiche Anzahl und Reihenfolge wie die Eingabe.
        """.trimIndent()
        val input = buildJsonObject { putJsonArray("texts") { texts.forEach { add(JsonPrimitive(it)) } } }
        return try {
            val o = completeJson(model, system, "Übersetze diese Texte:\n$input") { (it["texts"] as? JsonArray)?.size == texts.size }
            (o["texts"] as JsonArray).mapIndexed { i, e -> (e as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() } ?: texts[i] }
        } catch (e: Exception) {
            texts
        }
    }
}
