package de.wanderlogplus.app.ui.places

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.trip.EditFn
import de.wanderlogplus.client.InMemoryOutbox
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.TabelogInfo
import de.wanderlogplus.shared.model.TabelogStatus
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.HlcClock
import de.wanderlogplus.shared.sync.InMemoryEntityStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class TabelogCardTest {
    @get:Rule val rule = createComposeRule()

    private val found = TabelogInfo(
        TabelogStatus.FOUND, "https://tabelog.com/kyoto/A2601/A260201/26032368/", name = "麺屋 猪一", score = 3.71, reviewCount = 1159,
        genre = "ラーメン", budgetLunch = "￥1,000～￥1,999", budgetDinner = "￥1,000～￥1,999", hours = "11:00 - 15:00", reservation = "予約不可",
        summary = "Klare Dashi-Brühe, oft eine Schlange.",
    )

    @Test fun foundShowsScoreSummaryAndOpen() {
        var opened = 0
        rule.setContent { WanderlogPlusTheme { TabelogCard(found, onOpen = { opened++ }, onRefresh = {}, onSetLink = {}, onSearch = {}) } }
        rule.onNodeWithText("3,71 · 1.159 Bewertungen").assertExists()
        rule.onNodeWithText("Klare Dashi-Brühe, oft eine Schlange.").assertExists()
        rule.onNodeWithTag("tabelog-open").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, opened)
    }

    @Test fun notFoundOffersLinkAndSearch() {
        var searched = 0
        var retried = 0
        rule.setContent { WanderlogPlusTheme { TabelogCard(TabelogInfo(TabelogStatus.NOT_FOUND), onOpen = {}, onRefresh = { retried++ }, onSetLink = {}, onSearch = { searched++ }) } }
        rule.onNodeWithTag("tabelog-retry").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, retried)
        rule.onNodeWithText("Bei Tabelog nicht gefunden").assertExists()
        rule.onNodeWithTag("tabelog-set-link").assertExists()
        rule.onNodeWithTag("tabelog-search").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, searched)
    }

    @Test fun loadingWhileUnknownOrRequested() {
        rule.setContent { WanderlogPlusTheme { TabelogCard(null, onOpen = {}, onRefresh = null, onSetLink = null, onSearch = {}) } }
        rule.onNodeWithText("Tabelog wird geladen …").assertExists()
    }

    @Test fun linkDialogOnlyTakesTabelog() {
        var set: String? = null
        rule.setContent { WanderlogPlusTheme { TabelogCard(TabelogInfo(TabelogStatus.NOT_FOUND), onOpen = {}, onRefresh = {}, onSetLink = { set = it }, onSearch = {}) } }
        rule.onNodeWithTag("tabelog-set-link").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("tabelog-link-input").performTextReplacement("https://example.com/x")
        rule.onNodeWithTag("tabelog-link-save").assertIsNotEnabled()
        // A search or list page is no restaurant.
        rule.onNodeWithTag("tabelog-link-input").performTextReplacement("https://tabelog.com/rstLst/?vs=1&sw=ramen")
        rule.onNodeWithTag("tabelog-link-save").assertIsNotEnabled()
        rule.onNodeWithTag("tabelog-link-input").performTextReplacement("https://tabelog.com/tokyo/A1303/A130301/13001762/")
        rule.onNodeWithTag("tabelog-link-save").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("https://tabelog.com/tokyo/A1303/A130301/13001762/", set)
    }

    @Test fun chipOnlyForFound() {
        assertEquals("★ 3,7", tabelogChip(Place("p", tabelog = found))?.text)
        assertNull(tabelogChip(Place("p", tabelog = TabelogInfo(TabelogStatus.NOT_FOUND))))
        assertNull(tabelogChip(Place("p")))
        assertNull(tabelogChip(Place("p", tabelog = found.copy(score = null))))
    }

    @Test fun requestingWritesRequestedAndAsksTheServer() {
        val store = InMemoryEntityStore()
        val editor = LocalEditor(store, InMemoryOutbox(), HlcClock("dev-a", now = { 1000L }))
        editor.create(EntityType.PLACE, EntityCodec.fields(Place("p1", name = "Inoichi", tabelog = found)), id = "p1")
        val edit: EditFn = { block -> block(editor, TripSnapshot.from(store.all())) }
        val paths = mutableListOf<String>()
        val api = SyncApi(HttpClient(MockEngine { req -> paths += req.url.encodedPath; respond("""{"status":"queued"}""", HttpStatusCode.Accepted) }), "https://s", "tok")
        val vm = PlacesViewModel({ MutableStateFlow(TripSnapshot.from(store.all())) }, edit, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), api = { api })
        vm.tabelogRequest("p1", "https://tabelog.com/kyoto/A2601/A260201/26029810/")
        val deadline = System.currentTimeMillis() + 5_000
        while (paths.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val t = TripSnapshot.from(store.all()).places.getValue("p1").tabelog!!
        assertEquals(TabelogStatus.REQUESTED, t.status)
        assertEquals("https://tabelog.com/kyoto/A2601/A260201/26029810/", t.url)
        assertTrue(paths.single().endsWith("/api/tabelog/refresh"))
        vm.tabelogRequest("p1", null)
        assertEquals("https://tabelog.com/kyoto/A2601/A260201/26029810/", TripSnapshot.from(store.all()).places.getValue("p1").tabelog!!.url)
    }
}
