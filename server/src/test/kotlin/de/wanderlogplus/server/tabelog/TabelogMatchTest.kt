package de.wanderlogplus.server.tabelog

import de.wanderlogplus.shared.model.Place
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TabelogMatchTest {
    private fun hit(name: String, lat: Double?, lng: Double?, id: String = name) =
        TabelogHit(id, name, "https://tabelog.com/$id/", 3.2, 10, "ラーメン", lat, lng, "", "", "", "", "")

    // From the real search: the two Shibuya branches are about 200 m apart.
    private val spain = hit("一蘭 渋谷スペイン坂店", 35.66099485870731, 139.69872232422748)
    private val shibuya = hit("一蘭 渋谷店", 35.66112621870795, 139.70099237422724)
    private val ueno = hit("一蘭 アトレ上野山下口店", 35.71241450869903, 139.77524065469322)

    @Test
    fun `the nearer branch with a matching name wins`() {
        val atShibuya = Place("p", name = "Ichiran Shibuya", lat = 35.66110, lng = 139.70095)
        assertEquals(shibuya, TabelogMatch.pick(atShibuya, "一蘭 渋谷店", listOf(spain, shibuya, ueno)))
    }

    @Test
    fun `an unrelated name nearby is no match`() {
        val p = Place("p", lat = 35.66110, lng = 139.70095)
        assertNull(TabelogMatch.pick(p, "すし ざんまい", listOf(spain, shibuya)))
    }

    @Test
    fun `a matching name too far away is no match`() {
        val p = Place("p", lat = 35.66110 + 0.0040, lng = 139.70095) // about 445 m north
        assertNull(TabelogMatch.pick(p, "一蘭 渋谷店", listOf(shibuya)))
    }

    @Test
    fun `hits without coordinates are never picked`() {
        val p = Place("p", lat = 35.66110, lng = 139.70095)
        assertNull(TabelogMatch.pick(p, "一蘭 渋谷店", listOf(hit("一蘭 渋谷店", null, null))))
    }

    @Test
    fun `similar names`() {
        assertTrue(TabelogMatch.similar("麺屋 猪一", "麺屋猪一"))
        assertTrue(TabelogMatch.similar("麺屋 猪一 離れ", "麺屋 猪一"))
        assertTrue(TabelogMatch.similar("一蘭 渋谷店", "一蘭 渋谷スペイン坂店"))
        assertTrue(TabelogMatch.similar("ＡＢＣ Cafe", "abc cafe"))
        assertFalse(TabelogMatch.similar("すし ざんまい", "一蘭 渋谷店"))
        assertFalse(TabelogMatch.similar("", "一蘭"))
    }

    @Test
    fun `one-character names do not match by containment`() {
        assertFalse(TabelogMatch.similar("鮨", "鮨 さいとう"))
        assertTrue(TabelogMatch.similar("鮨 さいとう", "鮨さいとう"))
    }
}
