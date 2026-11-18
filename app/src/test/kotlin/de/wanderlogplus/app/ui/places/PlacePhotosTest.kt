package de.wanderlogplus.app.ui.places

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.attachments.AttachmentActions
import de.wanderlogplus.app.ui.attachments.AttachmentsViewModel
import de.wanderlogplus.app.ui.attachments.AttachmentSource
import de.wanderlogplus.app.ui.attachments.GalleryGrid
import de.wanderlogplus.app.ui.attachments.GalleryViewerRoute
import de.wanderlogplus.app.ui.attachments.rememberGallery
import de.wanderlogplus.client.InMemoryOutbox
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.HlcClock
import de.wanderlogplus.shared.sync.InMemoryEntityStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class PlacePhotosTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val place = Place("p", googlePlaceId = "ChIJ8cM8zdaoAWARPR27azYdlsA", name = "Kiyomizu-dera", imageKeys = listOf("k1", "k2", " "))
    private val snapshot = TripSnapshot(null, emptyList(), mapOf("p" to place), listOf(SavedPlace("s", "p")), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    private val list = """{"photos":[{"name":"places/ChIJ8cM8zdaoAWARPR27azYdlsA/photos/AelY_ref000001","width":4032,"height":3024,"author":"Aiko"},
        {"name":"places/ChIJ8cM8zdaoAWARPR27azYdlsA/photos/AelY_ref000002","author":"Ken"}]}"""

    private fun repo(status: HttpStatusCode = HttpStatusCode.OK, calls: MutableList<String> = mutableListOf()): PlacePhotoRepository {
        val engine = MockEngine { req ->
            calls += req.url.encodedPath
            if (req.url.encodedPath.endsWith("/media")) respond(byteArrayOf(1, 2, 3), status, headersOf(HttpHeaders.ContentType, "image/jpeg"))
            else respond(if (status == HttpStatusCode.OK) list else """{"error":"photos_not_configured"}""", status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val dir = tmp.newFolder()
        return PlacePhotoRepository({ SyncApi(HttpClient(engine), "https://s", "tok") }, dir)
    }

    private fun tap(tag: String) = rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)

    private fun grid(r: PlacePhotoRepository?, opened: MutableList<Int> = mutableListOf()) = rule.setContent {
        WanderlogPlusTheme {
            CompositionLocalProvider(LocalPlacePhotos provides r) {
                val ui = rememberGallery(snapshot, EntityType.SAVED_PLACE, "s")
                GalleryGrid(ui.gallery, ui.google, files = null, onAdd = null, onOpen = { opened += it })
            }
        }
    }

    @Test fun wanderlogPhotosRightAwayGooglePhotosOnRequestInTheSameGrid() {
        val calls = mutableListOf<String>()
        val opened = mutableListOf<Int>()
        grid(repo(calls = calls), opened)
        rule.onNodeWithTag("photo-1").assertExists()
        rule.onNode(hasTestTag("photo-2")).assertDoesNotExist() // blank key skipped
        assertEquals(2, rule.onAllNodes(hasText("Wanderlog")).fetchSemanticsNodes().size)
        tap("photo-google")
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("photo-3")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("Google-Fotos: Aiko, Ken")).assertExists()
        rule.onNode(hasTestTag("photo-google")).assertDoesNotExist()
        assertEquals("/api/places/photos", calls.first())
        tap("photo-3")
        assertEquals(listOf(3), opened)
    }

    @Test fun aServerWithoutKeyExplainsIt() {
        grid(repo(HttpStatusCode.ServiceUnavailable))
        tap("photo-google")
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("photo-google-note")).fetchSemanticsNodes().isNotEmpty() }
        val text = rule.onNodeWithTag("photo-google-note").fetchSemanticsNode().config.toString()
        assertTrue(text, "nicht eingerichtet" in text || "nicht erreichbar" in text)
    }

    @Test fun withoutRepositoryOnlyWanderlogPhotos() {
        grid(null)
        rule.onNodeWithTag("photo-0").assertExists()
        rule.onNode(hasTestTag("photo-google")).assertDoesNotExist()
    }

    @Test fun theViewerAdoptsAGooglePhotoAsHeader() {
        val r = repo()
        runBlocking { r.load(place.googlePlaceId!!) }
        val adopted = mutableListOf<Triple<String, String, Boolean>>()
        val headers = mutableListOf<String?>()
        val actions = AttachmentActions(
            { true }, { _, _ -> }, {}, {}, {},
            onSetHeader = { _, _, ref -> headers += ref },
            onAdopt = { _, id, source, origin, asHeader -> adopted += Triple(id, origin + "|" + source.fileName(), asHeader) },
        )
        rule.setContent {
            WanderlogPlusTheme {
                CompositionLocalProvider(LocalPlacePhotos provides r) {
                    // Collection: k1 (header), k2, Google 1, Google 2 – start at Google 1.
                    GalleryViewerRoute(snapshot, EntityType.SAVED_PLACE, "s", 2, files = null, attachments = actions, onBack = {})
                }
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("photo-keep")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("Foto: Aiko · Google")).assertExists()
        tap("photo-set-header")
        assertEquals(
            listOf(Triple("s", "g:places/ChIJ8cM8zdaoAWARPR27azYdlsA/photos/AelY_ref000001|Kiyomizu-dera (Foto: Aiko · Google).jpg", true)),
            adopted,
        )
        assertEquals(emptyList<String?>(), headers)
        tap("photo-keep")
        assertEquals(false, adopted.last().third)
    }

    @Test fun adoptingStoresThePhotoWithItsOriginAndHeader() = runBlocking {
        val store = InMemoryEntityStore()
        val editor = LocalEditor(store, InMemoryOutbox(), HlcClock("dev-a", now = { 1000L }))
        val savedId = editor.addSavedPlace("p", null)
        val vm = AttachmentsViewModel(
            edit = { block -> block(editor, TripSnapshot.from(store.all())) },
            addAttachment = { _, _, _, _, _ -> "x" }, ensureDay = { it },
            writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            addPhoto = { type, id, _, name -> editor.addAttachment(type, id(), "hash-1", name, "image/jpeg", 3, kind = "photo") },
        )
        val source = object : AttachmentSource {
            override fun fileName() = "Kiyomizu-dera (Foto: Aiko · Google).jpg"
            override fun mimeType() = "image/jpeg"
            override fun open() = java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3))
        }
        vm.adopt(EntityType.SAVED_PLACE, savedId, source, "g:places/x/photos/1", asHeader = true).join()
        val s = TripSnapshot.from(store.all())
        val photo = s.photosFor(EntityType.SAVED_PLACE, savedId).single()
        assertEquals("g:places/x/photos/1", photo.origin)
        assertEquals("att:hash-1", s.savedPlaces.single { it.id == savedId }.headerImage)
    }
}
