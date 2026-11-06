package de.wanderlogplus.server

import de.wanderlogplus.server.ai.PlacesSearchTransport
import de.wanderlogplus.shared.sync.WebPhotoSource
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebPhotosServiceTest {
    private val dir = Files.createTempDirectory("wlp-webphotos")
    private val store = SqliteStore(dir.resolve("t.db"))

    private fun googlePlace(id: String, photos: Int) =
        """{"id":"$id","displayName":{"text":"Ort $id"},"location":{"latitude":35.0,"longitude":135.0},"photos":[""" +
            (1..photos).joinToString(",") { """{"name":"places/$id/photos/p$it","widthPx":800,"heightPx":600}""" } + "]}"

    private val googleAnswer = 200 to """{"places":[${googlePlace("a", 5)},${googlePlace("b", 5)}]}"""
    private val google = GooglePlacesSearch(PlacesSearchTransport { googleAnswer }, store)

    private fun wikiPage(title: String, mime: String = "image/jpeg", artist: String = "Jane", license: String = "CC BY-SA 4.0") =
        """"$title":{"title":"File:$title","imageinfo":[{"mime":"$mime","width":4000,"height":3000,
            "thumburl":"https://upload.wikimedia.org/thumb/$title","url":"https://upload.wikimedia.org/$title",
            "extmetadata":{"Artist":{"value":${kotlinx.serialization.json.JsonPrimitive(artist)}},"LicenseShortName":{"value":"$license"}}}]}"""

    private val wikiSearch = """{"query":{"pages":{${wikiPage("A.jpg")},${wikiPage("B.png", "image/png")},${wikiPage("C.jpg")}}}}"""
    private val asked = mutableListOf<String>()

    private fun wiki(answer: (String) -> Pair<Int, ByteArray>) = WikimediaTransport { url -> asked += url; answer(url) }

    private val normalWiki = wiki { url ->
        when {
            "generator=search" in url -> 200 to wikiSearch.toByteArray()
            "titles=" in url -> 200 to """{"query":{"pages":{${wikiPage("A.jpg")}}}}""".toByteArray()
            url.startsWith("https://upload.wikimedia.org/") -> 200 to byteArrayOf(1, 2, 3)
            else -> 404 to ByteArray(0)
        }
    }

    private fun service(g: GooglePlacesSearch = google, w: WikimediaTransport = normalWiki) =
        WebPhotosService(g, { _, _ -> dir.resolve("g.jpg").also { Files.write(it, byteArrayOf(9)) } }, w)

    @Test
    fun mixesGoogleFirst() {
        val photos = service().search("Kinkaku-ji", null)
        assertEquals(11, photos.size)
        assertTrue(photos.take(8).all { it.source == WebPhotoSource.GOOGLE && it.id.startsWith("g:") })
        assertEquals(4, photos.count { it.id.startsWith("g:places/a/") })
        assertEquals(listOf("w:A.jpg", "w:B.png", "w:C.jpg"), photos.drop(8).map { it.id })
        assertEquals("Ort a", photos.first().title)
    }

    @Test
    fun wikimediaDownStillGoogle() {
        val photos = service(w = wiki { throw IOException("down") }).search("Kinkaku-ji", null)
        assertEquals(8, photos.size)
    }

    @Test
    fun noGoogleKeyOnlyWikimedia() {
        val photos = service(g = GooglePlacesSearch(null, store)).search("Kinkaku-ji", null)
        assertEquals(3, photos.size)
        assertTrue(photos.all { it.source == WebPhotoSource.WIKIMEDIA })
    }

    @Test
    fun creditStripsHtml() {
        val w = wiki { """{"query":{"pages":{${wikiPage("A.jpg", artist = "<a href=\"x\">Jane</a>")}}}}""".toByteArray().let { 200 to it } }
        val p = service(g = GooglePlacesSearch(null, store), w = w).search("x y", null).single()
        assertEquals("Jane · CC BY-SA 4.0", p.credit)
        assertEquals("A", p.title)
    }

    @Test
    fun skipsNonImages() {
        val w = wiki { """{"query":{"pages":{${wikiPage("A.tif", "image/tiff")},${wikiPage("B.pdf", "application/pdf")},${wikiPage("C.webp", "image/webp")}}}}""".toByteArray().let { 200 to it } }
        assertEquals(listOf("w:C.webp"), service(g = GooglePlacesSearch(null, store), w = w).search("x y", null).map { it.id })
    }

    @Test
    fun mediaLoadsFromWikimedia() {
        val m = service().media("w:A.jpg", 400)
        assertContentEquals(byteArrayOf(1, 2, 3), m.bytes)
        assertTrue(asked.any { "iiurlwidth=400" in it })
    }

    @Test
    fun mediaFromGoogle() {
        assertContentEquals(byteArrayOf(9), service().media("g:places/a/photos/p1", 400).bytes)
    }

    @Test
    fun mediaRejectsForeignHost() {
        val w = wiki { url ->
            if ("titles=" in url) 200 to """{"query":{"pages":{"1":{"imageinfo":[{"mime":"image/jpeg","thumburl":"https://evil.example/x.jpg"}]}}}}""".toByteArray()
            else 200 to byteArrayOf(1)
        }
        assertFailsWith<PhotosUnavailable> { service(w = w).media("w:A.jpg", 400) }
        assertTrue(asked.none { "evil.example" in it })
    }

    @Test
    fun mediaRejectsHttp() {
        val w = wiki { url ->
            if ("titles=" in url) 200 to """{"query":{"pages":{"1":{"imageinfo":[{"mime":"image/jpeg","thumburl":"http://upload.wikimedia.org/x.jpg"}]}}}}""".toByteArray()
            else 200 to byteArrayOf(1)
        }
        assertFailsWith<PhotosUnavailable> { service(w = w).media("w:A.jpg", 400) }
    }

    @Test
    fun mediaBadPrefix() {
        assertFailsWith<IllegalArgumentException> { service().media("x:1", 400) }
    }

    @Test
    fun mediaTooLarge() {
        val w = wiki { url ->
            if ("titles=" in url) 200 to """{"query":{"pages":{${wikiPage("A.jpg")}}}}""".toByteArray()
            else 200 to ByteArray(15 * 1024 * 1024 + 1)
        }
        assertFailsWith<PhotosUnavailable> { service(w = w).media("w:A.jpg", 1600) }
    }
}
