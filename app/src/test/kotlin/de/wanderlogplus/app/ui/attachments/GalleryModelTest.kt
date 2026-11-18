package de.wanderlogplus.app.ui.attachments

import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Attachment
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.PlacePhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GalleryModelTest {
    private val place = Place("p", googlePlaceId = "gid", name = "Kiyomizu-dera", imageKeys = listOf("k1", " ", "k2"))
    private fun photo(id: String, target: EntityType, targetId: String, label: String? = null, origin: String? = null) =
        Attachment(id, target, targetId, "h-$id", "$id.jpg", "image/jpeg", 1, kind = "photo", label = label, origin = origin)
    private val g1 = PlacePhoto("places/gid/photos/1", author = "Aiko")
    private val g2 = PlacePhoto("places/gid/photos/2", author = "Ken")

    private fun snap(saved: List<SavedPlace> = emptyList(), stays: List<Stay> = emptyList(), attachments: List<Attachment> = emptyList()) =
        TripSnapshot(null, emptyList(), mapOf("p" to place), saved, stays, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
            .copy(attachments = attachments)

    private fun refs(g: Gallery) = g.photos.map { it.ref }

    @Test fun placeGalleryHasHeaderFirstThenOwnThenWanderlogThenGoogle() {
        val s = snap(listOf(SavedPlace("s", "p")), attachments = listOf(photo("a", EntityType.SAVED_PLACE, "s"), photo("b", EntityType.SAVED_PLACE, "s")))
        val g = galleryOf(s, EntityType.SAVED_PLACE, "s", listOf(g1, g2))
        // No own header: the place's first image is the header.
        assertEquals("wl:k1", g.headerRef)
        assertEquals(listOf("wl:k1", "att:h-a", "att:h-b", "wl:k2", "g:places/gid/photos/1", "g:places/gid/photos/2"), refs(g))
        assertEquals("gid", g.googlePlaceId)
    }

    @Test fun anOwnHeaderComesFirst() {
        val s = snap(listOf(SavedPlace("s", "p", headerImage = "att:h-b")), attachments = listOf(photo("a", EntityType.SAVED_PLACE, "s"), photo("b", EntityType.SAVED_PLACE, "s")))
        assertEquals(listOf("att:h-b", "att:h-a", "wl:k1", "wl:k2"), refs(galleryOf(s, EntityType.SAVED_PLACE, "s", null)))
    }

    @Test fun anAdoptedGooglePhotoIsNotShownTwice() {
        val s = snap(listOf(SavedPlace("s", "p")), attachments = listOf(photo("a", EntityType.SAVED_PLACE, "s", origin = "g:places/gid/photos/2")))
        assertEquals(listOf("wl:k1", "att:h-a", "wl:k2", "g:places/gid/photos/1"), refs(galleryOf(s, EntityType.SAVED_PLACE, "s", listOf(g1, g2))))
    }

    @Test fun aPlaceWithoutImagesFallsBackToItsFirstOwnPhoto() {
        val bare = place.copy(imageKeys = emptyList())
        val s = snap(listOf(SavedPlace("s", "p")), attachments = listOf(photo("a", EntityType.SAVED_PLACE, "s"))).let { it.copy(places = mapOf("p" to bare)) }
        assertEquals("att:h-a", galleryOf(s, EntityType.SAVED_PLACE, "s", null).headerRef)
    }

    @Test fun stayGalleryPutsRoomPhotosFirstAndUsesOneAsHeader() {
        val s = snap(stays = listOf(Stay("st", "p")), attachments = listOf(photo("a", EntityType.STAY, "st"), photo("r", EntityType.STAY, "st", label = Attachment.LABEL_ROOM)))
        val g = galleryOf(s, EntityType.STAY, "st", null)
        assertEquals("att:h-r", g.headerRef)
        assertEquals(listOf("att:h-r", "att:h-a", "wl:k1", "wl:k2"), refs(g))
        assertEquals(1, g.indexOfOwn("a"))
    }

    @Test fun dayGalleryHasOnlyOwnPhotos() {
        val s = snap(attachments = listOf(photo("a", EntityType.DAY, "d"), photo("b", EntityType.DAY, "d")))
        val g = galleryOf(s, EntityType.DAY, "d", listOf(g1))
        assertEquals(listOf("att:h-a", "att:h-b"), refs(g))
        assertNull(g.headerRef)
        assertNull(g.googlePlaceId)
    }

    @Test fun orderCanBeFrozenToAnEarlierHeader() {
        val s = snap(listOf(SavedPlace("s", "p", headerImage = "wl:k2")), attachments = listOf(photo("a", EntityType.SAVED_PLACE, "s")))
        val g = galleryOf(s, EntityType.SAVED_PLACE, "s", null, orderBy = "wl:k1")
        assertEquals("wl:k2", g.headerRef)
        assertEquals(listOf("wl:k1", "att:h-a", "wl:k2"), refs(g))
    }

    @Test fun adoptedGooglePhotoFileNameCreditsTheAuthor() {
        assertEquals("Kiyomizu-dera (Foto: Aiko · Google).jpg", googlePhotoFileName("Kiyomizu-dera", g1))
        assertEquals("Foto (Foto: Google).jpg", googlePhotoFileName("", PlacePhoto("x")))
    }
}
