package de.wanderlogplus.server.tabelog

import de.wanderlogplus.shared.model.Place
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TabelogNamesTest {
    @Test
    fun `prefecture slugs from Japanese addresses`() {
        assertEquals("kyoto", prefectureSlug("日本、〒600-8033 京都府京都市下京区恵美須之町542"))
        assertEquals("tokyo", prefectureSlug("日本、〒150-0041 東京都渋谷区神南１丁目２２−７"))
        assertEquals("hokkaido", prefectureSlug("日本、〒060-0001 北海道札幌市中央区"))
        assertEquals("osaka", prefectureSlug("大阪府大阪市中央区"))
        assertEquals("okinawa", prefectureSlug("沖縄県那覇市"))
        assertEquals("nagano", prefectureSlug("長野県木曽郡南木曽町"))
        assertEquals("kanagawa", prefectureSlug("神奈川県横浜市"))
        assertEquals("all", prefectureSlug("Shibuya, Tokyo"))
        assertEquals(47, PREFECTURES.size)
    }

    private class Fake(val details: Pair<Int, String> = 200 to "{}", val search: Pair<Int, String> = 200 to """{"places":[]}""") : GoogleJaTransport {
        val asked = mutableListOf<String>()
        override fun details(id: String): Pair<Int, String> { asked += "details:$id"; return details }
        override fun search(body: String): Pair<Int, String> { asked += "search:$body"; return search }
    }

    private val inoichi = Place("p", googlePlaceId = "ChIJ1", name = "Menya Inoichi", lat = 35.00132, lng = 135.76695)

    @Test
    fun `with a google id the details give the Japanese name`() {
        val t = Fake(details = 200 to """{"displayName":{"text":"麺屋 猪一","languageCode":"ja"},"formattedAddress":"日本、〒600-8033 京都府京都市下京区"}""")
        assertEquals(JaName("麺屋 猪一", "kyoto"), GoogleJaNames(t).find(inoichi))
        assertEquals(listOf("details:ChIJ1"), t.asked)
    }

    @Test
    fun `without a google id a nearby search is used`() {
        val t = Fake(search = 200 to """{"places":[{"displayName":{"text":"麺屋 猪一"},"formattedAddress":"京都府京都市","location":{"latitude":35.0014,"longitude":135.7668}}]}""")
        assertEquals(JaName("麺屋 猪一", "kyoto"), GoogleJaNames(t).find(inoichi.copy(googlePlaceId = "")))
        val body = t.asked.single()
        assertTrue("\"languageCode\":\"ja\"" in body && "Menya Inoichi" in body && "\"radius\":300.0" in body, body)
    }

    @Test
    fun `a far search hit or an error gives nothing`() {
        val far = Fake(search = 200 to """{"places":[{"displayName":{"text":"麺屋 猪一"},"formattedAddress":"京都府","location":{"latitude":35.02,"longitude":135.7668}}]}""")
        assertNull(GoogleJaNames(far).find(inoichi.copy(googlePlaceId = "")))
        assertNull(GoogleJaNames(Fake(details = 403 to "{}")).find(inoichi))
    }
}
