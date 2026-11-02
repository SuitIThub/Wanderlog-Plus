package de.wanderlogplus.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import de.wanderlogplus.app.files.AttachmentFiles
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.attachments.AttachmentsViewModel
import de.wanderlogplus.app.ui.more.ImportViewModel
import de.wanderlogplus.app.ui.more.MoreViewModel
import de.wanderlogplus.app.ui.more.SettingsInfo
import de.wanderlogplus.app.ui.places.PlacesViewModel
import de.wanderlogplus.app.ui.setup.SetupViewModel
import de.wanderlogplus.app.ui.trip.EditFn
import de.wanderlogplus.app.ui.trip.TripViewModel
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Attachment
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/**
 * The unfolded layout (Galaxy Z Fold inner display, ~840 dp wide): navigation rail instead of the bottom bar, list and
 * detail side by side, and every route still composes without crashing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w840dp-h740dp")
class WideLayoutTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val edit: EditFn = { _ -> }

    @After fun tearDown() = scope.cancel()

    private fun factory(create: () -> ViewModel) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }

    private var mapReloads = 0

    private fun launch(hasServer: Boolean = true) {
        val files = AttachmentFiles(tmp.newFolder("att"))
        val imported = files.importStream("not really a jpeg".byteInputStream())
        val ticket = Attachment("a-ticket", EntityType.TRAIN, "tr-1", imported.hash, "Buchung.jpg", "image/jpeg", imported.size)
        val snapshot: TripSnapshot = Fixtures.trip().copy(attachments = listOf(ticket))
        val flow = MutableStateFlow<TripSnapshot?>(snapshot)
        rule.setContent {
            WanderlogPlusTheme {
                AppNavigation(
                    hasServer = hasServer,
                    snapshot = snapshot,
                    setupViewModelFactory = factory { SetupViewModel({ _, _ -> Result.success("a") }) { _, _ -> } },
                    tripViewModelFactory = factory { TripViewModel({ flow }, edit, scope) },
                    placesViewModelFactory = factory { PlacesViewModel({ flow }, edit, scope) },
                    moreViewModelFactory = factory { MoreViewModel({ flow }, edit, scope) },
                    importViewModelFactory = factory { ImportViewModel({ null }, onImported = {}) },
                    settingsInfo = SettingsInfo("https://s.example", "token1234", "dev-1"),
                    clock = { LocalDateTime.parse("2027-04-02T10:00") },
                    attachmentsViewModelFactory = factory {
                        AttachmentsViewModel(edit, { _, _, _, _, _ -> "x" }, { "d" }, scope)
                    },
                    attachmentFiles = files,
                    onReloadMapImages = { mapReloads++ },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)
    private fun tag(t: String) = rule.onNodeWithTag(t)
    private fun shows(t: String) = tag(t).assertExists()
    private fun tab(route: String) { tag("nav-$route").tap(); rule.waitForIdle() }

    private fun tapRail(route: String) { tag("rail-$route").tap(); rule.waitForIdle() }

    @Test fun railReplacesBottomBar() {
        launch()
        shows("nav-rail")
        assertEquals(0, rule.onAllNodesWithTag("nav-heute").fetchSemanticsNodes().size)
        shows("today-header")
        shows("today-program")
    }

    @Test fun tripShowsListAndDayDetailSideBySide() {
        launch()
        tapRail("reise")
        shows("two-pane")
        shows("trip-list")
        shows("day-add") // detail of today's day (clock: 2027-04-02) right next to the list
        tag("trip-list").performScrollToNode(hasTestTag("day-2027-04-03"))
        tag("day-2027-04-03").tap()
        rule.waitForIdle()
        // In the list card and as the detail's title beside it.
        assertEquals(2, rule.onAllNodesWithText("Ausflug ans Wasser", substring = true).fetchSemanticsNodes().size)
        shows("trip-list") // still beside it, nothing navigated away
    }

    @Test fun logisticsListAndDetail() {
        launch()
        tapRail("logistik")
        shows("two-pane")
        rule.onNodeWithText("Fahrt oder Unterkunft in der Liste antippen").assertExists()
        tag("logistics-tr-1").tap()
        rule.waitForIdle()
        shows("logistics-detail")
        shows("logistics-list")
    }

    @Test fun regionListAndPlaceDetail() {
        launch()
        tapRail("orte")
        tag("region-r-berg").tap()
        rule.waitForIdle()
        shows("two-pane")
        rule.onNodeWithText("Ort in der Liste antippen").assertExists()
        tag("place-s-tempel").tap()
        rule.waitForIdle()
        assertEquals(2, rule.onAllNodesWithText("Alter Bergtempel", substring = true).fetchSemanticsNodes().size) // list + detail
    }

    @Test fun dayMapAndSettingsStillCompose() {
        launch()
        tapRail("reise")
        tag("day-map").tap()
        rule.waitForIdle()
        shows("day-map-screen")
        shows("day-map-list")
        tag("day-map-toggle-list").tap()
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTag("day-map-list").fetchSemanticsNodes().size)
        tapRail("mehr")
        rule.onNodeWithText("Einstellungen").tap()
        rule.waitForIdle()
    }
}
