package de.wanderlogplus.server.tabelog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TabelogSourceTest {
    private fun fixture(name: String) = javaClass.getResource("/tabelog/$name")!!.readText(Charsets.UTF_8)

    @Test
    fun `search rows become hits`() {
        val (hits, reviews) = parseTabelogRows(fixture("search.json"))
        assertEquals(listOf(3.1, 3.2, 3.34), hits.map { it.score })
        assertEquals("一蘭 渋谷店", hits[1].name)
        assertEquals("https://tabelog.com/tokyo/A1303/A130301/13001762/", hits[1].url)
        assertTrue(hits.all { it.lat != null && it.lng != null })
        assertTrue(reviews.values.all { it.isEmpty() })
    }

    @Test
    fun `review rows are grouped by restaurant`() {
        val (hits, reviews) = parseTabelogRows(fixture("reviews.json"))
        assertEquals(1, hits.size)
        assertEquals(1159, hits.single().reviewCount)
        assertEquals(3, reviews.getValue("26032368").size)
        assertTrue(reviews.getValue("26032368").first().text.isNotBlank())
    }

    @Test
    fun `numbers as text and missing values`() {
        val (hits, _) = parseTabelogRows(
            """[{"restaurantId":"1","restaurantName":"A","restaurantUrl":"https://tabelog.com/a/","restaurantRating":"-","totalReviewCount":"12","latitude":null,"longitude":"139.7"},
               {"restaurantId":"2","restaurantName":"B","restaurantUrl":"https://tabelog.com/b/","restaurantRating":"3.45","latitude":"35.1","longitude":"139.7"}]""",
        )
        assertNull(hits[0].score)
        assertEquals(12, hits[0].reviewCount)
        assertNull(hits[0].lat)
        assertEquals(3.45, hits[1].score)
        assertEquals(35.1, hits[1].lat)
    }

    @Test
    fun `search request is small and keeps Japanese`() {
        val bodies = mutableListOf<String>()
        val s = ApifyTabelogSource("t", transport = { b -> bodies += b; 200 to fixture("search.json") })
        assertEquals(3, s.search("一蘭 渋谷", "tokyo").size)
        val body = bodies.single()
        assertTrue("\"includeReviews\":false" in body && "\"maxRestaurants\":3" in body && "一蘭 渋谷" in body && "\"searchArea\":\"tokyo\"" in body, body)
    }

    @Test
    fun `details ask for three newest reviews of one url`() {
        val bodies = mutableListOf<String>()
        val s = ApifyTabelogSource("t", transport = { b -> bodies += b; 200 to fixture("reviews.json") })
        val (hit, reviews) = s.details("https://tabelog.com/kyoto/A2601/A260201/26032368/")
        assertEquals("麺屋 猪一", hit!!.name)
        assertEquals(3, reviews.size)
        val body = bodies.single()
        assertTrue("\"maxReviewsPerRestaurant\":3" in body && "\"includeReviews\":true" in body && "26032368" in body && "\"sortBy\":\"newest\"" in body, body)
    }

    @Test
    fun `http errors are unavailable`() {
        val s = ApifyTabelogSource("t", transport = { 402 to """{"error":{"type":"not-enough-usage"}}""" })
        assertEquals("apify_402", assertFailsWith<TabelogUnavailable> { s.search("x", "all") }.code)
    }
}
