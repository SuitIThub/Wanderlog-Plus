package de.wanderlogplus.importer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Converts Wanderlog Quill Delta ops to Markdown. */
object QuillMarkdown {
    fun convert(ops: JsonArray?): String {
        if (ops == null) return ""
        val out = StringBuilder()
        val line = StringBuilder()
        var firstLine = true

        fun endLine(attrs: JsonObject?) {
            val prefix = when ((attrs?.get("list") as? JsonPrimitive)?.contentOrNull) {
                "bullet" -> "- "
                "ordered" -> "1. "
                else -> ""
            }
            if (!firstLine) out.append('\n')
            out.append(prefix).append(line)
            line.clear()
            firstLine = false
        }

        for (el in ops) {
            val op = el as? JsonObject ?: continue
            val insert = op["insert"] as? JsonPrimitive ?: continue
            if (!insert.isString) continue
            val attrs = op["attributes"] as? JsonObject
            val parts = insert.content.split("\n")
            parts.forEachIndexed { i, part ->
                if (part.isNotEmpty()) line.append(inline(part, attrs))
                if (i < parts.lastIndex) endLine(attrs)
            }
        }
        if (line.isNotEmpty()) endLine(null)
        return out.toString().trim()
    }

    private fun inline(text: String, attrs: JsonObject?): String {
        if (attrs == null) return text
        var s = text
        if (flag(attrs, "bold")) s = "**$s**"
        if (flag(attrs, "italic")) s = "*$s*"
        val link = (attrs["link"] as? JsonPrimitive)?.contentOrNull
        if (!link.isNullOrEmpty()) s = if (text == link) link else "[$s]($link)"
        return s
    }

    private fun flag(attrs: JsonObject, key: String): Boolean =
        (attrs[key] as? JsonPrimitive)?.booleanOrNull == true
}
