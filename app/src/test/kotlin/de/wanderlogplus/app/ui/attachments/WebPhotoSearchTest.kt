package de.wanderlogplus.app.ui.attachments

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.design.HeaderImages
import de.wanderlogplus.client.InMemoryOutbox
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Attachment
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.HlcClock
import de.wanderlogplus.shared.sync.InMemoryEntityStore
import de.wanderlogplus.shared.sync.WebPhoto
import de.wanderlogplus.shared.sync.WebPhotoSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class WebPhotoSearchTest {
    @get:Rule val rule = createComposeRule()

    private val photos = listOf(
        WebPhoto("g:places/a/photos/1", WebPhotoSource.GOOGLE, "Tempel"),
        WebPhoto("w:B.jpg", WebPhotoSource.WIKIMEDIA, "B", "Jane · CC BY-SA 4.0"),
        WebPhoto("w:C.jpg", WebPhotoSource.WIKIMEDIA, "C", "Max · CC0"),
    )

    @Test
    fun fileNameCarriesCredit() {
        assertEquals("Kinkaku-ji (Foto: Wikimedia · Jane · CC BY-SA 4.0).jpg", photoFileName(WebPhoto("w:a.jpg", WebPhotoSource.WIKIMEDIA, "Kinkaku-ji", "Jane · CC BY-SA 4.0")))
        assertEquals("X (Foto: Google).jpg", photoFileName(WebPhoto("g:x", WebPhotoSource.GOOGLE, "X", "Google")))
        assertEquals("a b (Foto: Google).jpg", photoFileName(WebPhoto("g:x", WebPhotoSource.GOOGLE, "a/b")))
        assertEquals("Foto (Foto: Google).jpg", photoFileName(WebPhoto("g:x", WebPhotoSource.GOOGLE, "")))
        assertEquals(124, photoFileName(WebPhoto("w:x", WebPhotoSource.WIKIMEDIA, "T".repeat(300), "c")).length)
    }

    private fun sheet(multiple: Boolean, onPick: (List<WebPhoto>) -> Unit) {
        rule.setContent {
            WanderlogPlusTheme {
                WebPhotoSearchSheet("Tempel Bergstadt", null, multiple, search = { _, _ -> photos }, thumb = { null }, onPick = onPick, onDismiss = {})
            }
        }
        rule.waitUntil(3_000) { rule.onAllNodesWithTag("web-photo-2").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun sheetPicksSeveral() {
        var picked: List<WebPhoto>? = null
        sheet(multiple = true) { picked = it }
        rule.onNodeWithTag("web-photo-0").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("web-photo-2").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Übernehmen (2)").assertExists()
        rule.onNodeWithText("Max · CC0", substring = true).assertExists()
        rule.onNodeWithTag("web-photos-take").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(photos[0], photos[2]), picked)
    }

    @Test
    fun singleModeReplacesSelection() {
        var picked: List<WebPhoto>? = null
        sheet(multiple = false) { picked = it }
        rule.onNodeWithTag("web-photo-0").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("web-photo-1").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("web-photos-take").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(photos[1]), picked)
    }

    @Test
    fun queryForPlaceStayAndDay() {
        val s = Fixtures.trip()
        val place = s.places.getValue("p-nudel").name
        assertEquals("$place Bergstadt", photoQuery(s, AttachmentTarget.Entity(EntityType.SAVED_PLACE, "s-nudel")).text)
        assertEquals("Bergstadt", photoQuery(s, AttachmentTarget.Day("2027-04-02")).text)
        val stay = s.stays.first { it.id == "st-berg" }
        assertEquals(s.places.getValue(stay.placeId).name + " Bergstadt", photoQuery(s, AttachmentTarget.Entity(EntityType.STAY, "st-berg")).text)
    }

    @Test
    fun chooserLeadsToOnlineSearch() {
        var gallery = 0
        var picked: Pair<PhotoRequest, List<WebPhoto>>? = null
        val request = PhotoRequest(AttachmentTarget.Entity(EntityType.SAVED_PLACE, "s-nudel"), PhotoQuery("Tempel", null), single = false)
        rule.setContent {
            WanderlogPlusTheme {
                WebPhotoFlow(request, search = { _, _ -> photos }, thumb = { null }, onGallery = { gallery++ }, onPicked = { r, p -> picked = r to p }, onDone = {})
            }
        }
        rule.onNodeWithTag("photo-source-online").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(3_000) { rule.onAllNodesWithTag("web-photo-0").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("web-photo-0").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("web-photos-take").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(request to listOf(photos[0]), picked)
        assertEquals(0, gallery)
    }

    @Test
    fun chooserGallery() {
        var gallery = 0
        val request = PhotoRequest(AttachmentTarget.Day("2027-04-02"), PhotoQuery("Bergstadt", null), single = false)
        rule.setContent {
            WanderlogPlusTheme { WebPhotoFlow(request, search = { _, _ -> photos }, thumb = { null }, onGallery = { gallery++ }, onPicked = { _, _ -> }, onDone = {}) }
        }
        rule.onNodeWithTag("photo-source-gallery").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, gallery)
    }

    @Test
    fun addPhotosReportsIds() = runBlocking {
        var ids = listOf("a1", "a2").iterator()
        var reported: List<String>? = null
        val vm = AttachmentsViewModel(
            edit = { _ -> error("unused") }, addAttachment = { _, _, _, _, _ -> "x" }, ensureDay = { "d" },
            writeScope = CoroutineScope(Dispatchers.Unconfined), addPhoto = { _, _, _, _ -> ids.next() },
        )
        val src = object : AttachmentSource {
            override fun fileName() = "a.jpg"
            override fun mimeType() = "image/jpeg"
            override fun open() = java.io.ByteArrayInputStream(byteArrayOf(1))
        }
        vm.addPhotos(AttachmentTarget.Entity(EntityType.SAVED_PLACE, "s1"), listOf(src, src)) { reported = it }.join()
        assertEquals(listOf("a1", "a2"), reported)
    }

    @Test
    fun headerFromAttachment() {
        val store = InMemoryEntityStore()
        val e = LocalEditor(store, InMemoryOutbox(), HlcClock("dev", now = { 1000L }))
        e.create(EntityType.SAVED_PLACE, EntityCodec.fields(SavedPlace("s1", "p1")), id = "s1")
        e.create(EntityType.ATTACHMENT, EntityCodec.fields(Attachment("a1", EntityType.SAVED_PLACE, "s1", fileHash = "h1", kind = "photo")), id = "a1")
        headerFromAttachmentEdits(EntityType.SAVED_PLACE, "s1", "a1")(e, TripSnapshot.from(store.all()))
        assertEquals(HeaderImages.OWN + "h1", TripSnapshot.from(store.all()).savedPlaces.single().headerImage)
    }
}
