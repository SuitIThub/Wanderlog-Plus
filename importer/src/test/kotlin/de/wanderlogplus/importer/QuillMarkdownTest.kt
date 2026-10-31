package de.wanderlogplus.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals

class QuillMarkdownTest {
    private fun ops(json: String): JsonArray = Json.parseToJsonElement(json).jsonArray

    @Test fun `bullets bold and emoji`() {
        val o = ops("""[
          {"insert":"⚠️ "},{"insert":"Overrated","attributes":{"bold":true}},{"insert":"\n","attributes":{"list":"bullet"}},
          {"insert":"Tsumago 妻籠 – schön für Füße"},{"insert":"\n","attributes":{"list":"bullet"}}]""")
        assertEquals("- ⚠️ **Overrated**\n- Tsumago 妻籠 – schön für Füße", QuillMarkdown.convert(o))
    }

    @Test fun `plain multi-line text with blank lines`() {
        assertEquals("WANDERTAG\n\nZeile", QuillMarkdown.convert(ops("""[{"insert":"WANDERTAG\n\nZeile"}]""")))
    }

    @Test fun `link with same text collapses to url`() {
        val o = ops("""[{"insert":"https://a.b","attributes":{"link":"https://a.b"}},{"insert":"\n"}]""")
        assertEquals("https://a.b", QuillMarkdown.convert(o))
    }

    @Test fun `link with other text`() {
        val o = ops("""[{"insert":"Seite","attributes":{"link":"https://a.b"}}]""")
        assertEquals("[Seite](https://a.b)", QuillMarkdown.convert(o))
    }

    @Test fun `null and embeds`() {
        assertEquals("", QuillMarkdown.convert(null))
        assertEquals("a", QuillMarkdown.convert(ops("""[{"insert":{"image":"x"}},{"insert":"a\n"}]""")))
    }

    @Test fun `multi-line text then bullet newline only bullets last line`() {
        val o = ops("""[{"insert":"Emoji Legende:\n\n👍 - Ist besser"},{"insert":"\n","attributes":{"list":"bullet"}}]""")
        assertEquals("Emoji Legende:\n\n- 👍 - Ist besser", QuillMarkdown.convert(o))
    }

    @Test fun `attributes before insert and ordered list`() {
        val o = ops("""[{"attributes":{"bold":true},"insert":"x"},{"attributes":{"list":"ordered"},"insert":"\n"}]""")
        assertEquals("1. **x**", QuillMarkdown.convert(o))
    }

    @Test fun `bold text ending in newline does not wrap the newline`() {
        val o = ops("""[{"insert":"Titel\n","attributes":{"bold":true}},{"insert":"Rest"}]""")
        assertEquals("**Titel**\nRest", QuillMarkdown.convert(o))
    }
}
