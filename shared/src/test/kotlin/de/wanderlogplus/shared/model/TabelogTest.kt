package de.wanderlogplus.shared.model

import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldValue
import de.wanderlogplus.shared.sync.Hlc
import de.wanderlogplus.shared.sync.encode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TabelogTest {
    private val info = TabelogInfo(
        TabelogStatus.FOUND, "https://tabelog.com/kyoto/A2601/A260201/26032368/", name = "麺屋 猪一", score = 3.71, reviewCount = 1159,
        genre = "ラーメン", budgetLunch = "￥1,000～￥1,999", summary = "Klare Dashi-Brühe, oft Schlange.", checkedAt = 5,
    )

    @Test
    fun `place with tabelog survives the codec`() {
        val p = Place("p1", name = "Menya Inoichi", lat = 35.0, lng = 135.76, tabelog = info)
        val rec = EntityRecord(EntityType.PLACE, "p1", EntityCodec.fields(p).mapValues { (_, v) -> FieldValue(v, Hlc(1, 0, "a").encode()) })
        assertEquals(p, EntityCodec.decode<Place>(rec))
    }

    @Test
    fun `old place json has no tabelog`() {
        val p = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(Place.serializer(), """{"id":"p1","name":"X"}""")
        assertNull(p.tabelog)
    }

    @Test
    fun `english url`() {
        assertEquals("https://tabelog.com/en/kyoto/A2601/A260201/26032368/", info.englishUrl())
        assertEquals("https://tabelog.com/en/kyoto/A2601/", info.copy(url = "https://tabelog.com/en/kyoto/A2601/").englishUrl())
        assertEquals("", info.copy(url = "").englishUrl())
    }

    @Test
    fun `only tabelog urls`() {
        assertTrue(isTabelogUrl("https://tabelog.com/tokyo/A1303/A130301/13001762/"))
        assertTrue(isTabelogUrl("https://s.tabelog.com/tokyo/A1303/"))
        assertFalse(isTabelogUrl("http://tabelog.com/tokyo/"))
        assertFalse(isTabelogUrl("https://example.com/tabelog.com/"))
        assertFalse(isTabelogUrl("https://tabelog.com.evil.jp/tokyo/"))
        assertFalse(isTabelogUrl(""))
    }

    @Test
    fun `restaurants are food or nightlife with a position`() {
        val p = Place("p1", lat = 35.0, lng = 135.0)
        assertTrue(isRestaurant(SavedPlace("s", "p1", category = PlaceCategory.FOOD), p))
        assertTrue(isRestaurant(SavedPlace("s", "p1", category = PlaceCategory.NIGHTLIFE), p))
        assertFalse(isRestaurant(SavedPlace("s", "p1", category = PlaceCategory.SIGHT), p))
        assertFalse(isRestaurant(SavedPlace("s", "p1", category = PlaceCategory.FOOD, status = PlaceStatus.DROPPED), p))
        assertFalse(isRestaurant(SavedPlace("s", "p1", category = PlaceCategory.FOOD), p.copy(lat = 0.0, lng = 0.0)))
        assertFalse(isRestaurant(SavedPlace("s", "p1", category = PlaceCategory.FOOD), null))
    }

    @Test
    fun `restaurant pages only, in their Japanese form`() {
        val ja = "https://tabelog.com/kyoto/A2601/A260201/26032368/"
        assertEquals(ja, canonicalTabelogUrl(ja))
        assertEquals(ja, canonicalTabelogUrl("  https://tabelog.com/en/kyoto/A2601/A260201/26032368/  "))
        assertEquals(ja, canonicalTabelogUrl("https://tabelog.com/tw/kyoto/A2601/A260201/26032368/dtlrvwlst/"))
        assertEquals(ja, canonicalTabelogUrl("https://s.tabelog.com/kyoto/A2601/A260201/26032368"))
        assertNull(canonicalTabelogUrl("https://tabelog.com/rstLst/?vs=1&sw=ramen"))
        assertNull(canonicalTabelogUrl("https://tabelog.com/kyoto/A2601/A260201/"))
        assertNull(canonicalTabelogUrl("https://example.com/kyoto/A2601/A260201/26032368/"))
        assertNull(canonicalTabelogUrl("http://tabelog.com/kyoto/A2601/A260201/26032368/"))
    }
}
