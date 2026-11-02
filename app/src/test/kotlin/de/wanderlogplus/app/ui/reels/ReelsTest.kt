package de.wanderlogplus.app.ui.reels

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.places.placeGroups
import de.wanderlogplus.app.ui.places.savedPlacesIn
import de.wanderlogplus.app.ui.places.PlacesViewModel
import de.wanderlogplus.app.ui.share.firstLinkIn
import de.wanderlogplus.app.ui.share.isReelLink
import de.wanderlogplus.app.ui.trip.EditFn
import de.wanderlogplus.shared.sync.InMemoryEntityStore
import de.wanderlogplus.client.InMemoryOutbox
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.PlaceStatus
import de.wanderlogplus.shared.query.Suggestion
import de.wanderlogplus.shared.sync.AiJobInfo
import de.wanderlogplus.shared.sync.HlcClock
import de.wanderlogplus.shared.sync.ReelCandidate
import de.wanderlogplus.shared.sync.Worth
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class ReelsTest {
    @get:Rule val rule = createComposeRule()

    /** Near the fixture's mountain temple (r-berg). */
    private val near = ReelCandidate(
        name = "Moostempel", city = "Bergstadt", lat = 35.0, lng = 135.76, category = "SIGHT",
        description = "Ein stiller Tempel.", tips = listOf("Früh kommen"), hints = listOf("Nur bar"),
        worth = Worth.MUST, worthReason = "Einzigartig.", distanceKm = 1.2, nearestAnchor = "Alter Bergtempel", onRoute = true,
    )
    private val far = ReelCandidate(name = "Fernes Café", city = "Sapporo", lat = 43.06, lng = 141.35, category = "FOOD", distanceKm = 870.0, nearestAnchor = "Hotel", onRoute = false)
    private val nowhere = ReelCandidate(name = "Unbekannt", category = "OTHER")

    private val doneJob = AiJobInfo("j1", "reel", 0, "DONE", source = "https://www.instagram.com/reel/abc/", summary = "Ein Tag in Bergstadt.", candidates = listOf(near, far, nowhere))

    @Test fun labelsAndNote() {
        assertEquals("auf der Route · 1,2 km von Alter Bergtempel", routeLabel(near))
        assertEquals("abseits · 870 km von Hotel", routeLabel(far))
        assertEquals("ohne Kartenposition", routeLabel(nowhere))
        val note = reelNote(near, "https://www.instagram.com/reel/abc/")
        assertTrue(note.startsWith("**Tipps**\n- Früh kommen"))
        assertTrue(note.contains("**Hinweise**\n- Nur bar"))
        assertTrue(note.endsWith("Aus dem Reel: https://www.instagram.com/reel/abc/"))
    }

    @Test fun shareLinksAreRecognised() {
        assertEquals("https://www.instagram.com/reel/C1x/?igsh=abc", firstLinkIn("Sieh dir das an: https://www.instagram.com/reel/C1x/?igsh=abc"))
        assertTrue(isReelLink("https://www.instagram.com/reel/C1x/"))
        assertFalse(isReelLink("https://example.com/reel"))
        assertNull(firstLinkIn("kein Link"))
    }

    @Test fun importCreatesIdeasWithNoteTagAndWorth() {
        val store = InMemoryEntityStore()
        val editor = LocalEditor(store, InMemoryOutbox(), HlcClock("dev-a", now = { 1000L }))
        val ids = importPicks(editor, listOf(ReelPick(near, "r-berg"), ReelPick(nowhere, null)), doneJob.source)
        assertEquals(1, ids.size) // the place without coordinates is skipped
        val s = TripSnapshot.from(store.all())
        val saved = s.savedPlaces.single()
        assertEquals("r-berg", saved.regionId)
        assertEquals(PlaceStatus.IDEA, saved.status)
        assertEquals(listOf(REEL_TAG), saved.tags)
        assertEquals(Worth.MUST, saved.worth)
        assertEquals("Einzigartig.", saved.worthReason)
        assertEquals("Ein stiller Tempel.", s.places[saved.placeId]!!.description)
        assertTrue(saved.note.contains("Früh kommen"))
    }

    @Test fun previewPreselectsPlacesOnTheRoute() {
        var picks: List<ReelPick>? = null
        rule.setContent {
            WanderlogPlusTheme { ReelJobScreen(doneJob, Fixtures.trip(), imported = false, onImport = { picks = it }, onOpenPlaces = {}, onBack = {}) }
        }
        rule.onNodeWithText("Ein Tag in Bergstadt.").assertExists()
        rule.onNodeWithText("1 Ort übernehmen").assertExists()
        rule.onNodeWithTag("reel-pick-2").assertIsNotEnabled() // no coordinates
        rule.onNodeWithTag("reel-pick-1").performClick() // add the far one anyway
        rule.onNodeWithTag("reel-import").performClick()
        assertEquals(listOf("Moostempel", "Fernes Café"), picks!!.map { it.candidate.name })
        assertEquals("r-berg", picks!![0].regionId) // nearest region within 50 km
        assertNull(picks!![1].regionId) // Sapporo: no region close by
    }

    @Test fun progressAndFailureAreShown() {
        val job = androidx.compose.runtime.mutableStateOf(AiJobInfo("j2", "reel", 0, "ANALYZING"))
        rule.setContent { WanderlogPlusTheme { ReelJobScreen(job.value, null, false, {}, {}, {}) } }
        rule.onNodeWithTag("reel-status").assertExists()
        rule.onNodeWithText("KI schaut sich Bilder und Ton an …").assertExists()
        job.value = AiJobInfo("j2", "reel", 0, "FAILED", error = "download_failed")
        rule.onNodeWithTag("reel-error").assertExists()
    }

    @Test fun importedJobOffersPlaces() {
        var opened = false
        rule.setContent { WanderlogPlusTheme { ReelJobScreen(doneJob, Fixtures.trip(), imported = true, onImport = {}, onOpenPlaces = { opened = true }, onBack = {}) } }
        rule.onNodeWithTag("reel-imported").performClick()
        assertTrue(opened)
    }

    @Test fun regionListGroupsByWorthOnceJudged() {
        val s = Fixtures.trip()
        val plain = savedPlacesIn(s, "r-berg").map { Suggestion(it, s.places[it.placeId]) }
        assertTrue(placeGroups(plain, null).all { it.key.startsWith("category-") })
        val judged = plain.map { if (it.saved.id == "s-tempel") it.copy(saved = it.saved.copy(worth = Worth.MUST)) else it }
        val groups = placeGroups(judged, null)
        assertEquals(listOf("worth-MUST", "worth-NONE"), groups.map { it.key })
        assertEquals("Muss man sehen", groups.first().label)
        assertEquals(listOf("s-tempel"), groups.first().places.map { it.saved.id })
    }

    @Test fun assessmentWritesJudgements() {
        val store = InMemoryEntityStore()
        val editor = LocalEditor(store, InMemoryOutbox(), HlcClock("dev-a", now = { 1000L }))
        val snap = Fixtures.trip()
        val edit: EditFn = { block -> block(editor, snap) }
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        var polls = 0
        val api = SyncApi(
            HttpClient(MockEngine { req ->
                if (req.url.encodedPath.endsWith("/assess")) {
                    respond("""{"id":"a1","kind":"assess","createdAt":0,"status":"QUEUED"}""", HttpStatusCode.OK, jsonHeaders)
                } else {
                    polls++
                    respond("""{"id":"a1","kind":"assess","createdAt":0,"status":"DONE","assessments":[{"savedPlaceId":"s-tempel","worth":"MUST","reason":"Ikonisch."},{"savedPlaceId":"gone","worth":"SKIP"}]}""", HttpStatusCode.OK, jsonHeaders)
                }
            }),
            "https://s", "tok",
        )
        val vm = PlacesViewModel({ MutableStateFlow(snap) }, edit, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), api = { api }, pollMillis = 1)
        vm.assessAll()
        val deadline = System.currentTimeMillis() + 5_000
        while (vm.assessing.value == "Orte werden eingeschätzt …" && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("1 Ort eingeschätzt", vm.assessing.value)
        // Only the known place was written (the store holds exactly what the editor wrote).
        val written = store.all().single { it.id == "s-tempel" }
        assertEquals("MUST", (written.value("worth") as kotlinx.serialization.json.JsonPrimitive).content)
        assertFalse(store.all().any { it.id == "gone" })
        assertTrue(polls >= 1)
    }
}
