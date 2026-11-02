package de.wanderlogplus.app.ui.places

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import de.wanderlogplus.app.data.assessConfigOf
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.more.AiSettingsScreen
import de.wanderlogplus.app.ui.more.toggleCriterion
import de.wanderlogplus.app.ui.trip.EditFn
import de.wanderlogplus.client.InMemoryOutbox
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.shared.model.Criteria
import de.wanderlogplus.shared.model.CriterionScore
import de.wanderlogplus.shared.model.PlaceDossier
import de.wanderlogplus.shared.query.Suggestion
import de.wanderlogplus.shared.sync.AssessConfig
import de.wanderlogplus.shared.sync.HlcClock
import de.wanderlogplus.shared.sync.InMemoryEntityStore
import de.wanderlogplus.shared.sync.Worth
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
class ResearchTest {
    @get:Rule val rule = createComposeRule()

    private val dossier = PlaceDossier(
        criteria = listOf(CriterionScore("crowds", "Andrang", 2, "Ab 10 Uhr voll"), CriterionScore("booking", "", 5, "Einfach hingehen")),
        verdict = "Lohnt sich früh am Morgen.",
        insiderTips = listOf("Hinterer Pfad ist leer"),
        hiddenSpots = listOf("Kleiner Teich hinter dem Tor"),
        goodToKnow = listOf("Nur Bargeld"),
        sources = listOf("https://www.example.jp/tempel"),
        researchedAt = 1_800_000_000_000,
    )

    @Test fun researchWritesDossierVerdictAndMissingDescription() {
        val store = InMemoryEntityStore()
        val editor = LocalEditor(store, InMemoryOutbox(), HlcClock("dev-a", now = { 1000L }))
        val snap = Fixtures.trip()
        val edit: EditFn = { block -> block(editor, snap) }
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        var sent = ""
        val api = SyncApi(
            HttpClient(MockEngine { req ->
                if (req.url.encodedPath.endsWith("/research")) {
                    sent = String(req.body.toByteArray())
                    respond("""{"id":"r1","kind":"research","createdAt":0,"status":"QUEUED","total":2}""", HttpStatusCode.OK, jsonHeaders)
                } else {
                    respond(
                        """{"id":"r1","kind":"research","createdAt":0,"status":"DONE","progress":2,"total":2,"dossiers":[
                           {"savedPlaceId":"s-nudel","worth":"WORTH","description":"Kleine Nudelküche.","dossier":{"verdict":"Gut und günstig.","insiderTips":["Tsukemen bestellen"],"researchedAt":5}},
                           {"savedPlaceId":"s-tempel","worth":"MUST","description":"Anders.","dossier":{"verdict":"Ikonisch.","researchedAt":5}}]}""",
                        HttpStatusCode.OK, jsonHeaders,
                    )
                }
            }),
            "https://s", "tok",
        )
        val config = AssessConfig(listOf("crowds"), "Ruhig")
        val vm = PlacesViewModel({ MutableStateFlow(snap) }, edit, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), api = { api }, pollMillis = 1, config = { config })
        vm.research(listOf("s-nudel", "s-tempel", "s-nudel"))
        val deadline = System.currentTimeMillis() + 5_000
        while (vm.researching.value.isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("2 Orte recherchiert", vm.researchStatus.value)
        assertTrue(sent.contains("\"crowds\"") && sent.contains("Ruhig"))
        val nudel = store.all().single { it.id == "s-nudel" }
        assertEquals("WORTH", (nudel.value("worth") as JsonPrimitive).content)
        assertEquals("Gut und günstig.", (nudel.value("worthReason") as JsonPrimitive).content)
        assertTrue((nudel.value("dossier") as JsonObject).toString().contains("Tsukemen"))
        // Description only where the place had none: p-nudel gets one, p-tempel keeps its own.
        assertEquals("Kleine Nudelküche.", (store.all().single { it.id == "p-nudel" }.value("description") as JsonPrimitive).content)
        assertFalse(store.all().any { it.id == "p-tempel" })
        vm.consumeResearchStatus()
        assertNull(vm.researchStatus.value)
    }

    @Test fun serverAppliedResearchOnlySyncsAndSurvivesNetworkGaps() {
        val store = InMemoryEntityStore()
        val editor = LocalEditor(store, InMemoryOutbox(), HlcClock("dev-a", now = { 1000L }))
        val snap = Fixtures.trip()
        val edit: EditFn = { block -> block(editor, snap) }
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        var polls = 0
        val api = SyncApi(
            HttpClient(MockEngine { req ->
                if (req.url.encodedPath.endsWith("/research")) {
                    respond("""{"id":"r1","kind":"research","createdAt":0,"status":"QUEUED","total":2,"appliedOnServer":true}""", HttpStatusCode.OK, jsonHeaders)
                } else {
                    polls++
                    when (polls) {
                        1 -> throw java.io.IOException("offline") // a short gap must not end the research
                        2 -> respond("""{"id":"r1","kind":"research","createdAt":0,"status":"RESEARCHING","total":2,"progress":1,"appliedOnServer":true,
                               "dossiers":[{"savedPlaceId":"s-nudel","dossier":{"verdict":"x","researchedAt":5}}]}""", HttpStatusCode.OK, jsonHeaders)
                        else -> respond("""{"id":"r1","kind":"research","createdAt":0,"status":"DONE","total":2,"progress":2,"appliedOnServer":true,
                               "dossiers":[{"savedPlaceId":"s-nudel","dossier":{"verdict":"x","researchedAt":5}},{"savedPlaceId":"s-tempel","dossier":{"verdict":"y","researchedAt":5}}]}""", HttpStatusCode.OK, jsonHeaders)
                    }
                }
            }),
            "https://s", "tok",
        )
        var syncs = 0
        val vm = PlacesViewModel({ MutableStateFlow(snap) }, edit, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), api = { api }, pollMillis = 1, syncNow = { syncs++ })
        vm.research(listOf("s-nudel", "s-tempel"))
        val deadline = System.currentTimeMillis() + 5_000
        while (vm.researching.value.isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("2 Orte recherchiert", vm.researchStatus.value)
        assertTrue(store.all().isEmpty()) // the server wrote the results, the app did not
        assertTrue(syncs >= 2) // once while running (first place done), once at the end
        assertEquals("r1", vm.researchJob.value)
    }

    @Test fun assessmentTabShowsVerdictAndCriteria() {
        val snap = Fixtures.trip().let { s ->
            s.copy(savedPlaces = s.savedPlaces.map { if (it.id == "s-tempel") it.copy(worth = Worth.MUST, worthReason = "alt", dossier = dossier) else it })
        }
        var researched = 0
        rule.setContent {
            WanderlogPlusTheme {
                PlaceDetailScreen(snap, "s-tempel", onBack = {}, onSaveNote = { _, _ -> }, onSetStatus = { _, _ -> }, research = ResearchAction(false, { researched++ }))
            }
        }
        rule.onNodeWithTag("tab-assessment").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("place-verdict").assertExists()
        rule.onNodeWithText("Lohnt sich früh am Morgen.").assertExists()
        rule.onNodeWithText("Ø 3,5").assertExists()
        rule.onNodeWithText("Buchbarkeit").assertExists() // label from the catalogue
        rule.onNodeWithText("Ab 10 Uhr voll").assertExists()
        rule.onNodeWithTag("place-research-button").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, researched)

        rule.onNodeWithTag("tab-research").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("GEHEIMTIPPS").assertExists()
        rule.onNodeWithText("Hinterer Pfad ist leer").assertExists()
        rule.onNodeWithText("VERSTECKTE ECKEN").assertExists()
        rule.onNodeWithText("example.jp").assertExists()
    }

    @Test fun unresearchedPlaceOffersResearchWhileRunningDisabled() {
        val snap = Fixtures.trip()
        rule.setContent {
            WanderlogPlusTheme {
                PlaceDetailScreen(snap, "s-nudel", onBack = {}, onSaveNote = { _, _ -> }, onSetStatus = { _, _ -> }, research = ResearchAction(true, {}))
            }
        }
        rule.onNodeWithTag("tab-research").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Wird recherchiert …").assertExists()
        rule.onNodeWithTag("place-research-button").assertIsNotEnabled()
    }

    @Test fun aiSettingsToggleCriteriaAndStoreExtraPrompt() {
        var config by mutableStateOf(AssessConfig(listOf("crowds")))
        rule.setContent { WanderlogPlusTheme { AiSettingsScreen(config, onChange = { config = it }) } }
        rule.onNodeWithTag("criterion-booking").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("crowds", "booking"), config.criteria)
        rule.onNodeWithTag("criterion-crowds").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("booking"), config.criteria)
        rule.onNodeWithTag("criteria-default").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(Criteria.DEFAULT, config.criteria)
        rule.onNodeWithTag("ai-extra-prompt").performTextInput("Keine Freizeitparks")
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals("Keine Freizeitparks", config.extraPrompt)
    }

    @Test fun criteriaHelpers() {
        assertEquals(listOf("highlight", "crowds"), toggleCriterion(listOf("crowds"), "highlight")) // catalogue order
        assertEquals(Criteria.DEFAULT, assessConfigOf(null, null).criteria)
        assertEquals(emptyList<String>(), assessConfigOf("", "x").criteria)
        assertEquals(listOf("crowds", "booking"), assessConfigOf("crowds,bogus,booking,crowds", null).criteria)
        assertEquals("Ø 3,5", averageScore(dossier.criteria))
        assertNull(averageScore(emptyList()))
        assertEquals("example.jp", sourceHost("https://www.example.jp/x"))
        assertNull(researchedLabel(PlaceDossier(verdict = "nur Einschätzung")))
        assertEquals("Ort wird recherchiert …", researchProgress(0, 1))
        assertEquals("Recherche: 3 von 12 Orten …", researchProgress(3, 12))
    }

    @Test fun regionResearchDialogOffersOnlyNewPlaces() {
        val snap = Fixtures.trip().let { s ->
            s.copy(savedPlaces = s.savedPlaces.map { if (it.id == "s-tempel") it.copy(dossier = dossier) else it })
        }
        val all = savedPlacesIn(snap, "r-berg").map { Suggestion(it, snap.places[it.placeId]) }
        assertFalse("s-tempel" in unresearched(all))
        var started: List<String>? = null
        rule.setContent {
            WanderlogPlusTheme { RegionPlacesScreen(snap, "r-berg", onBack = {}, onOpenPlace = {}, onResearch = { started = it }) }
        }
        rule.onNodeWithTag("region-research").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("research-new").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(unresearched(all), started)
        assertTrue(started!!.isNotEmpty())
    }
}
