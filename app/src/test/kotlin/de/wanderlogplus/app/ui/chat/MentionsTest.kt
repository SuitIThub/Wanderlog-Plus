package de.wanderlogplus.app.ui.chat

import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.shared.sync.ChatRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionsTest {
    private val options = Mentions.options(Fixtures.trip())

    private fun labels(query: String) = Mentions.search(options, query).map { it.ref.label }

    @Test
    fun everyKindOfObjectCanBeMentioned() {
        val byType = options.groupBy { it.ref.type }
        assertEquals(setOf("s-tempel", "s-nudel", "s-garten", "s-markt", "s-ufer"), byType[ChatRef.PLACE]!!.map { it.ref.id }.toSet())
        assertEquals(6, byType[ChatRef.DAY]!!.size)
        assertEquals(ChatRef(ChatRef.DAY, "2027-04-02", "Tag 2, 02.04."), byType[ChatRef.DAY]!![1].ref)
        assertEquals(ChatRef(ChatRef.STAY, "st-berg", "Gasthaus am Hang"), byType[ChatRef.STAY]!!.first().ref)
        assertEquals("Schnellzug 101", byType[ChatRef.TRAIN]!!.single().ref.label)
        assertEquals(listOf("TF 123", "TF 456"), byType[ChatRef.FLIGHT]!!.map { it.ref.label })
        assertEquals(3, byType[ChatRef.REGION]!!.size)
    }

    @Test
    fun searchIgnoresCaseAndAccentsAndPrefersTheStart() {
        assertEquals("Alter Bergtempel", labels("alter").first())
        // "Küstenort" by "kusten", the region before the stay of the same town by its start.
        assertTrue("Küstenort" in labels("kusten"))
        // A word start beats a match inside a word.
        val berg = labels("berg")
        assertEquals("Bergstadt", berg.first())
        assertTrue(berg.indexOf("Alter Bergtempel") < berg.indexOf("Gasthaus am Hang").let { if (it < 0) Int.MAX_VALUE else it })
        // Days by number or date.
        assertEquals("Tag 3, 03.04.", labels("tag 3").first())
        assertEquals("Tag 3, 03.04.", labels("3.4").first())
        assertTrue(labels("xyz").isEmpty())
        assertTrue(Mentions.search(options, "").size in 1..Mentions.LIMIT)
    }

    @Test
    fun queryStartsAtAnAtSignAfterASpace() {
        assertEquals(Mentions.Query(4, "kiyo"), Mentions.queryAt("Wie @kiyo", 9, emptyList()))
        assertEquals(Mentions.Query(0, ""), Mentions.queryAt("@", 1, emptyList()))
        assertEquals(Mentions.Query(0, "tag 3"), Mentions.queryAt("@tag 3", 6, emptyList()))
        assertNull(Mentions.queryAt("mail@kiyo", 9, emptyList()))
        assertNull(Mentions.queryAt("Wie geht's", 10, emptyList()))
        assertNull(Mentions.queryAt("@a\nb", 4, emptyList()))
        // A picked object does not reopen the list.
        val ref = ChatRef(ChatRef.PLACE, "s1", "Kiyomizu-dera")
        assertNull(Mentions.queryAt("@Kiyomizu-dera und", 18, listOf(ref)))
    }

    @Test
    fun pickingReplacesTheQueryAndRefsFollowTheText() {
        val ref = ChatRef(ChatRef.PLACE, "s1", "Kiyomizu-dera")
        val (text, cursor) = Mentions.insert("Wie @kiy ist es", 8, Mentions.Query(4, "kiy"), ref.label)
        assertEquals("Wie @Kiyomizu-dera  ist es".replace("  ", " "), text)
        assertEquals("Wie @Kiyomizu-dera ".length, cursor)
        assertEquals(listOf(ref), Mentions.kept(text, listOf(ref)))
        assertEquals(emptyList<ChatRef>(), Mentions.kept("Wie @Kiyomizu ist es", listOf(ref)))
        assertEquals(listOf(4 until 18), Mentions.spans(text, listOf(ref)).map { it.first })
    }
}
