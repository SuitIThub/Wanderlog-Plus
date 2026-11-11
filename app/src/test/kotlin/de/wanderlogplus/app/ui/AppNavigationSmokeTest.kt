package de.wanderlogplus.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
 * Composes the real [AppNavigation] (with the real `SyncedScreen` wrappers) and visits every route by tapping through
 * the UI. Screens tested in isolation miss wrapper problems such as a nested vertical scroll, which crashes with
 * "measured with infinity maximum height" on a device and in Robolectric alike.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppNavigationSmokeTest {
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

    private fun launch(hasServer: Boolean = true, hasAdminToken: Boolean = true) {
        val files = AttachmentFiles(tmp.newFolder("att"))
        val imported = files.importStream("not really a jpeg".byteInputStream())
        val ticket = Attachment("a-ticket", EntityType.TRAIN, "tr-1", imported.hash, "Buchung.jpg", "image/jpeg", imported.size)
        val onsen = de.wanderlogplus.shared.model.Guide(
            "g-onsen", topic = "onsen", title = "Onsen-Knigge", summary = "Erst waschen, dann baden.",
            sections = listOf(de.wanderlogplus.shared.model.GuideSection("Ablauf", "So geht's.", dos = listOf("Waschen"), donts = listOf("Handtuch ins Wasser"))),
            phrases = listOf(de.wanderlogplus.shared.model.GuidePhrase("Wo ist das Bad?", "お風呂はどこですか？", "ofuro wa doko desu ka?")),
        )
        val snapshot: TripSnapshot = Fixtures.trip().copy(attachments = listOf(ticket), guides = listOf(onsen))
        val flow = MutableStateFlow<TripSnapshot?>(snapshot)
        rule.setContent {
            WanderlogPlusTheme {
                AppNavigation(
                    hasServer = hasServer,
                    hasAdminToken = hasAdminToken,
                    snapshot = snapshot,
                    setupViewModelFactory = factory { SetupViewModel({ _, _ -> Result.success("a") }) { _, _ -> } },
                    tripViewModelFactory = factory { TripViewModel({ flow }, edit, scope) },
                    placesViewModelFactory = factory { PlacesViewModel({ flow }, edit, scope) },
                    moreViewModelFactory = factory { MoreViewModel({ flow }, edit, scope) },
                    budgetViewModelFactory = factory { de.wanderlogplus.app.ui.budget.BudgetViewModel(edit, scope, { null }, { "2027-04-02" }) },
                    guidesViewModelFactory = factory { de.wanderlogplus.app.ui.guides.GuidesViewModel(edit, scope, { null }, { null }) },
                    importViewModelFactory = factory { ImportViewModel({ null }, onImported = {}) },
                    vaultScanViewModelFactory = factory {
                        de.wanderlogplus.app.ui.vault.VaultScanViewModel({ MutableStateFlow(null) }, { MutableStateFlow(0) }, { MutableStateFlow(0) }, {}, {})
                    },
                    settingsInfo = SettingsInfo("https://s.example", "token1234", "dev-1"),
                    clock = { LocalDateTime.parse("2027-04-02T10:00") },
                    attachmentsViewModelFactory = factory {
                        AttachmentsViewModel(edit, { _, _, _, _, _ -> "x" }, { "d" }, scope)
                    },
                    attachmentFiles = files,
                    onReloadMapImages = { mapReloads++ },
                    reelsViewModelFactory = factory {
                        de.wanderlogplus.app.ui.reels.ReelsViewModel({ flow }, edit, { null }, object : de.wanderlogplus.app.ui.reels.ImportedJobs {
                            override fun contains(id: String) = false
                            override fun add(id: String) {}
                        }, scope)
                    },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)
    private fun tag(t: String) = rule.onNodeWithTag(t)
    private fun shows(t: String) = tag(t).assertExists()
    private fun tab(route: String) { tag("nav-$route").tap(); rule.waitForIdle() }

    @Test fun dayOpenedFromHeuteLeavesHeuteAndTheTabGoesBack() {
        launch()
        shows("today-header")
        tag("today-header").tap()
        rule.waitForIdle()
        shows("program-list")
        tab("heute")
        shows("today-header")
        // Reise still remembers the day opened from Heute.
        tab("reise")
        shows("program-list")
    }

    @Test fun placeOfTheDayProgramOpensItsDetail() {
        launch()
        tab("reise")
        tag("trip-list").performScrollToNode(hasTestTag("day-2027-04-02"))
        tag("day-2027-04-02").tap()
        shows("program-list")
        tag("program-row-i-2").tap()
        rule.waitForIdle()
        shows("place-detail")
        rule.onNodeWithText("Alter Bergtempel").assertExists()
    }

    @Test fun allTabs() {
        launch()
        shows("today-header")
        tab("reise"); shows("trip-list")
        tab("orte"); shows("regions-list")
        tab("logistik"); shows("logistics-list")
        // The real budget screen, not the tab placeholder.
        tab("budget"); shows("budget-total"); shows("budget-add")
        tab("mehr"); shows("more-list")
        tab("heute"); shows("today-header")
    }

    @Test fun dayDetailWithAllThreeTabs() {
        launch()
        tab("reise")
        tag("trip-list").performScrollToNode(hasTestTag("day-2027-04-02"))
        tag("day-2027-04-02").tap()
        shows("program-edit")
        shows("program-list")
        tag("tab-suggestions").tap(); shows("suggestion-list")
        tag("tab-notes").tap(); rule.waitForIdle()
        tag("tab-program").tap(); shows("program-edit")
    }

    @Test fun regionAndPlaceDetail() {
        launch()
        tab("orte")
        tag("regions-list").performScrollToNode(hasTestTag("region-r-berg"))
        tag("region-r-berg").tap()
        shows("view-map")
        shows("place-s-tempel")
        tag("place-s-tempel").tap()
        shows("place-detail")
        shows("status-chip")
    }

    @Test fun logisticsDetailAndAttachmentViewer() {
        launch()
        tab("logistik")
        tag("logistics-list").performScrollToNode(hasTestTag("logistics-tr-1"))
        tag("logistics-tr-1").tap()
        shows("logistics-detail")
        tag("attachment-a-ticket").performScrollTo().tap()
        rule.waitForIdle()
        shows("viewer-image")
        rule.onNodeWithText("Buchung.jpg").assertIsDisplayed()
    }

    @Test fun staysTabAndStayDetail() {
        launch()
        tab("logistik")
        tag("tab-stays").tap(); rule.waitForIdle()
        tag("logistics-list").performScrollToNode(hasTestTag("logistics-st-berg"))
        tag("logistics-st-berg").tap()
        shows("logistics-detail")
    }

    @Test fun notesAndNoteDetail() {
        launch()
        tab("mehr")
        rule.onNodeWithText("Notizen").tap(); rule.waitForIdle()
        shows("notes-list")
        tag("note-n-1").tap(); rule.waitForIdle()
        shows("note-detail")
    }

    @Test fun guidesAndGuideDetail() {
        launch()
        tab("mehr")
        rule.onNodeWithText("Guides").tap(); rule.waitForIdle()
        shows("guides")
        shows("guide-suggestions")
        rule.onNodeWithText("Japanisch für unterwegs").assertExists()
        rule.onNodeWithText("Onsen-Knigge").tap(); rule.waitForIdle()
        shows("guide-detail")
        rule.onNodeWithText("お風呂はどこですか？").assertExists()
    }

    @Test fun importAndConflicts() {
        launch()
        tab("mehr")
        rule.onNodeWithText("Import").tap(); rule.waitForIdle()
        shows("import-log")
        tab("mehr")
        rule.onNodeWithText("Konflikte").tap(); rule.waitForIdle()
        shows("conflicts-empty")
    }

    @Test fun settingsAndSetup() {
        launch()
        tab("mehr")
        rule.onNodeWithText("Einstellungen").tap(); rule.waitForIdle()
        shows("settings-screen")
        rule.onNodeWithText("Server ändern").performScrollTo().tap(); rule.waitForIdle()
        shows("setup-scan")
    }

    @Test fun reloadMapImagesClearsAndConfirms() {
        launch()
        tab("mehr")
        rule.onNodeWithText("Einstellungen").tap(); rule.waitForIdle()
        tag("settings-reload-maps").performScrollTo().tap(); rule.waitForIdle()
        assertEquals(1, mapReloads)
        rule.onNodeWithText(MSG_RELOAD_MAPS).assertIsDisplayed()
    }

    @Test fun setupAsStartDestination() {
        launch(hasServer = false)
        shows("setup-scan")
    }

    @Test fun reelScreensOpenFromMehr() {
        launch()
        tab("mehr")
        rule.onNodeWithText("Reels auswerten").tap()
        rule.waitForIdle()
        shows("reels-screen")
        shows("reel-link")
    }

    @Test fun vaultScanScreenOpensFromMehr() {
        launch()
        tab("mehr")
        rule.onNodeWithText("Bilder-Upload").tap()
        rule.waitForIdle()
        shows("vault-scan-screen")
        shows("vault-scan-start")
    }

    @Test fun vaultScanHiddenWithoutAdminToken() {
        launch(hasAdminToken = false)
        tab("mehr")
        rule.onNodeWithText("Bilder-Upload").assertDoesNotExist()
    }
}
