package de.wanderlogplus.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SortKeyTest {
    @Test fun `between open ends`() { val k = SortKey.between(null, null); assertTrue(k.isNotEmpty()) }
    @Test fun `between is strictly between`() {
        val a = SortKey.between(null, null); val b = SortKey.between(a, null); val m = SortKey.between(a, b)
        assertTrue(a < m && m < b)
    }
    @Test fun `repeated insertion at the front stays ordered`() {
        var first = SortKey.between(null, null); val keys = mutableListOf(first)
        repeat(200) { first = SortKey.between(null, first); keys.add(0, first) }
        assertEquals(keys.sorted(), keys); assertEquals(keys.size, keys.toSet().size)
    }
    @Test fun `repeated insertion between neighbours stays ordered`() {
        val a = SortKey.between(null, null); var b = SortKey.between(a, null)
        repeat(200) { val m = SortKey.between(a, b); assertTrue(a < m && m < b); b = m }
    }
    @Test fun `sequence is ascending and distinct`() {
        val s = SortKey.sequence(50); assertEquals(s.sorted(), s); assertEquals(50, s.toSet().size)
    }
    @Test fun `invalid order throws`() { assertFailsWith<IllegalArgumentException> { SortKey.between("b", "a") } }
    @Test fun `keys never end in zero and edge cases hold`() {
        val cases = listOf(null to "1", "1" to "10", "0V" to "0W", "z" to null, "Vz" to "W", "1" to "11", null to "01")
        for ((a, b) in cases) {
            val m = try { SortKey.between(a, b) } catch (e: IllegalArgumentException) { continue }
            assertTrue(!m.endsWith("0"), "$a $b -> $m")
            assertTrue((a == null || a < m) && (b == null || m < b), "$a $b -> $m")
        }
        assertEquals(emptyList(), SortKey.sequence(0))
        SortKey.sequence(5000).let { assertEquals(it.sorted(), it); assertEquals(5000, it.toSet().size); assertTrue(it.none { k -> k.endsWith("0") }) }
    }
}
