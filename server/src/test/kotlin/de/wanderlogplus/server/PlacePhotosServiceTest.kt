package de.wanderlogplus.server

import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlacePhotosServiceTest {
    private val tmp = Files.createTempDirectory("wlp-photos")
    private val store = SqliteStore(tmp.resolve("wanderlog-plus.db"))
    private var clock = Instant.parse("2027-05-10T09:00:00Z")
    private val gid = "ChIJ8cM8zdaoAWARPR27azYdlsA"
    private val photoName = "places/$gid/photos/AelY_CsPhotoReference123"

    private inner class Fake : PlacesPhotoTransport {
        var details = 0
        var media = 0
        var status = 200
        override fun details(googlePlaceId: String): Pair<Int, String> {
            details++
            val photos = (1..12).joinToString(",") { i ->
                """{"name":"$photoName$i","widthPx":4032,"heightPx":3024,"authorAttributions":[{"displayName":"Aiko $i","uri":"https://maps.google.com/maps/contrib/$i"}]}"""
            }
            return status to """{"photos":[$photos]}"""
        }
        override fun media(name: String, maxWidth: Int): Pair<Int, ByteArray> {
            media++
            return status to "jpeg-$name-$maxWidth".toByteArray()
        }
    }

    @Test
    fun `lists at most ten photos with authors, caches the list and stores every photo once`() {
        val fake = Fake()
        val s = PlacePhotosService(store, tmp.resolve("place-photos"), fake) { clock }
        val list = s.list(gid)
        assertEquals(10, list.size)
        assertEquals("Aiko 1", list.first().author)
        assertEquals(4032, list.first().width)
        s.list(gid)
        assertEquals(1, fake.details)
        clock = clock.plus(Duration.ofDays(31))
        fake.status = 500
        assertEquals(10, s.list(gid).size) // stale list when Google fails
        assertEquals(2, fake.details)

        fake.status = 200
        val f = s.media(list.first().name, 800)
        assertContentEquals("jpeg-${list.first().name}-800".toByteArray(), Files.readAllBytes(f))
        s.media(list.first().name, 800)
        assertEquals(1, fake.media)

        assertFailsWith<BadInput> { s.media("../../etc/passwd", 800) }
        assertFailsWith<BadInput> { s.media(list.first().name, 5000) }
        assertFailsWith<PhotosNotConfigured> { PlacePhotosService(store, tmp, null).list(gid) }
    }
}
