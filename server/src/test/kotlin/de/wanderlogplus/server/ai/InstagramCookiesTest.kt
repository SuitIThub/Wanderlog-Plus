package de.wanderlogplus.server.ai

import java.nio.file.Files
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstagramCookiesTest {
    private val dir = Files.createTempDirectory("wlp-cookies")

    @Test
    fun `a session id becomes a netscape cookies file`() {
        val f = writeSessionCookies(dir, "  123%3Aabc%3A27  ")
        val lines = f.readLines()
        assertEquals("# Netscape HTTP Cookie File", lines.first())
        assertEquals(listOf(".instagram.com", "TRUE", "/", "TRUE", "2147483647", "sessionid", "123%3Aabc%3A27"), lines.last().split('\t'))
    }

    @Test
    fun `a session id with tabs or line breaks is refused`() {
        assertFailsWith<IllegalArgumentException> { writeSessionCookies(dir, "a\tb") }
        assertFailsWith<IllegalArgumentException> { writeSessionCookies(dir, "a\nb") }
    }

    @Test
    fun `an existing cookies file wins`() {
        val file = Files.writeString(dir.resolve("c.txt"), "# Netscape HTTP Cookie File\n")
        assertEquals(file, resolveInstagramCookies(file, "123", dir) {})
    }

    @Test
    fun `a cookies setting that is no file is ignored with a warning`() {
        val warnings = mutableListOf<String>()
        assertNull(resolveInstagramCookies(java.nio.file.Path.of("65048713084%3AITXD"), null, dir) { warnings += it })
        assertEquals(1, warnings.size)
        assertTrue("WLP_INSTAGRAM_SESSIONID" in warnings.single() && "65048713084" !in warnings.single())
    }

    @Test
    fun `a session id is used when there is no cookies file`() {
        val f = resolveInstagramCookies(java.nio.file.Path.of("nope"), "123", dir) {}
        assertEquals("123", f!!.readLines().last().split('\t').last())
        assertNull(resolveInstagramCookies(null, null, dir) {})
    }
}
