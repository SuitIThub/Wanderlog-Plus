package de.wanderlogplus.app.ui.attachments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import de.wanderlogplus.app.files.shrinkPhoto
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.places.PlaceDetailScreen
import de.wanderlogplus.app.ui.trip.DayDetailScreen
import de.wanderlogplus.client.InMemoryOutbox
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Attachment
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.HlcClock
import de.wanderlogplus.shared.sync.InMemoryEntityStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoGalleryTest {
    @get:Rule val rule = createComposeRule()

    private val photo = Attachment("a-1", EntityType.SAVED_PLACE, "s-tempel", "h1", "tempel.jpg", "image/jpeg", 100, kind = "photo")
    private val ticket = Attachment("a-2", EntityType.SAVED_PLACE, "s-tempel", "h2", "ticket.pdf", "application/pdf", 100)

    private fun snap() = Fixtures.trip().let { it.copy(attachments = it.attachments + photo + ticket + photo.copy(id = "a-3", fileName = "zwei.jpg")) }

    @Test fun photosAndFilesAreSeparate() {
        val s = snap()
        assertEquals(listOf("a-1", "a-3"), s.photosFor(EntityType.SAVED_PLACE, "s-tempel").map { it.id })
        assertEquals(listOf("a-2"), s.attachmentsFor(EntityType.SAVED_PLACE, "s-tempel").map { it.id })
    }

    @Test fun editorMarksGalleryPhotos() {
        val store = InMemoryEntityStore()
        val editor = LocalEditor(store, InMemoryOutbox(), HlcClock("dev-a", now = { 1000L }))
        editor.addAttachment(EntityType.DAY, "d-1", "h", "a.jpg", "image/jpeg", 10, kind = "photo")
        editor.addAttachment(EntityType.DAY, "d-1", "h2", "b.pdf", "application/pdf", 10)
        val s = TripSnapshot.from(store.all())
        assertEquals(listOf("a.jpg"), s.photosFor(EntityType.DAY, "d-1").map { it.fileName })
        assertEquals(listOf("b.pdf"), s.attachmentsFor(EntityType.DAY, "d-1").map { it.fileName })
    }

    @Test fun largePhotosAreShrunkToJpeg() {
        val big = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val png = ByteArrayOutputStream().also { big.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val out = shrinkPhoto(png)!!
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(out, 0, out.size, bounds)
        assertEquals(2560, maxOf(bounds.outWidth, bounds.outHeight))
        assertEquals("image/jpeg", bounds.outMimeType)
        assertTrue(out.size < png.size)
        assertNull(shrinkPhoto("kein Bild".toByteArray()))
    }

    @Test fun viewModelAddsPhotosAndSaysHowMany() = runBlocking {
        val added = mutableListOf<Pair<EntityType, String>>()
        val vm = AttachmentsViewModel(
            edit = {}, addAttachment = { _, _, _, _, _ -> "x" }, ensureDay = { "day-$it" },
            writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            addPhoto = { type, id, _, name -> if (name == "kaputt.jpg") error("x"); added += type to id(); "p" },
        )
        fun src(name: String) = object : AttachmentSource {
            override fun fileName() = name
            override fun mimeType() = "image/jpeg"
            override fun open(): InputStream = ByteArrayInputStream(ByteArray(3))
        }
        vm.addPhotos(AttachmentTarget.Day("2027-05-20"), listOf(src("a.jpg"), src("b.jpg"))).join()
        assertEquals(listOf(EntityType.DAY to "day-2027-05-20", EntityType.DAY to "day-2027-05-20"), added)
        assertEquals("2 Fotos hinzugefügt", vm.message.value)
        vm.addPhotos(AttachmentTarget.Entity(EntityType.SAVED_PLACE, "s1"), listOf(src("c.jpg"), src("kaputt.jpg"))).join()
        assertEquals("1 von 2 Fotos hinzugefügt", vm.message.value)
    }

    @Test fun placeHasAFotosTabThatAddsAndOpens() {
        var add: Pair<EntityType, String>? = null
        var opened: Triple<EntityType, String, Int>? = null
        val actions = AttachmentActions({ true }, { _, _ -> }, {}, {}, {}, onAddPhotos = { t, id -> add = t to id })
        rule.setContent {
            WanderlogPlusTheme {
                PlaceDetailScreen(snap(), "s-tempel", onBack = {}, onSaveNote = { _, _ -> }, onSetStatus = { _, _ -> }, attachments = actions, onOpenPhoto = { t, id, i -> opened = Triple(t, id, i) })
            }
        }
        rule.onNodeWithText("Fotos (2)").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("photo-add").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(EntityType.SAVED_PLACE to "s-tempel", add)
        rule.onNodeWithTag("photo-1").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(Triple(EntityType.SAVED_PLACE, "s-tempel", 1), opened)
        rule.onNodeWithText("Anhänge (1)").assertExists() // the PDF only
    }

    @Test fun dayHasAFotosTabToo() {
        var addedFor: String? = null
        val actions = AttachmentActions({ true }, { _, _ -> }, {}, {}, {}, onAddPhotosToDay = { addedFor = it })
        rule.setContent {
            WanderlogPlusTheme {
                DayDetailScreen(Fixtures.trip(), "2027-04-02", onBack = {}, onMove = { _, _ -> }, onRemove = {}, onAddSuggestion = { _, _ -> }, attachments = actions, onOpenPhoto = { _, _, _ -> })
            }
        }
        rule.onNodeWithTag("tab-photos").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Noch keine Fotos.").assertExists()
        rule.onNodeWithTag("photo-add").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("2027-04-02", addedFor)
    }

    @Test fun viewerSwipesAndDeletesWithConfirmation() {
        var deleted: Attachment? = null
        rule.setContent {
            WanderlogPlusTheme {
                PhotoViewerScreen(listOf(photo, photo.copy(id = "a-3")), start = 1, files = null, onDelete = { deleted = it }, onBack = {})
            }
        }
        rule.onNodeWithText("2 / 2").assertExists()
        rule.onNodeWithTag("photo-delete").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("photo-delete-confirm").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("a-3", deleted!!.id)
    }
}
