package de.wanderlogplus.app.ui.places

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.shared.model.Place
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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

    @Test fun wanderlogPhotosRightAwayGooglePhotosOnRequestWithCredit() {
        val calls = mutableListOf<String>()
        val r = repo(calls = calls)
        rule.setContent { WanderlogPlusTheme { CompositionLocalProvider(LocalPlacePhotos provides r) { PlacePhotosRow(place) } } }
        rule.onNodeWithTag("place-photo-1").assertExists()
        rule.onNode(hasTestTag("place-photo-2")).assertDoesNotExist() // blank key skipped
        tap("place-photos-google")
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("place-photo-3")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("Google-Fotos: Aiko, Ken")).assertExists()
        rule.onNode(hasTestTag("place-photos-google")).assertDoesNotExist()
        assertEquals("/api/places/photos", calls.first())
        // Full screen viewer with the author.
        tap("place-photo-2")
        rule.onNodeWithTag("place-photo-viewer").assertExists()
        rule.waitUntil(5_000) { calls.any { it.endsWith("/media") } }
        tap("place-photo-close")
        rule.onNode(hasTestTag("place-photo-viewer")).assertDoesNotExist()
    }

    @Test fun aServerWithoutKeyExplainsIt() {
        val r = repo(HttpStatusCode.ServiceUnavailable)
        rule.setContent { WanderlogPlusTheme { CompositionLocalProvider(LocalPlacePhotos provides r) { PlacePhotosRow(place) } } }
        tap("place-photos-google")
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("place-photos-note")).fetchSemanticsNodes().isNotEmpty() }
        val text = rule.onNodeWithTag("place-photos-note").fetchSemanticsNode().config.toString()
        assertTrue(text, "nicht eingerichtet" in text || "nicht erreichbar" in text)
    }

    @Test fun withoutRepositoryOnlyWanderlogPhotos() {
        rule.setContent { WanderlogPlusTheme { PlacePhotosRow(place) } }
        rule.onNodeWithTag("place-photo-0").assertExists()
        rule.onNode(hasTestTag("place-photos-google")).assertDoesNotExist()
    }
}
