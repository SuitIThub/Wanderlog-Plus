package de.wanderlogplus.app.ui

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import de.wanderlogplus.app.testing.FakeChatBackend
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.testing.MemLastChat
import de.wanderlogplus.app.ui.chat.ChatViewModel
import de.wanderlogplus.app.ui.more.MoreViewModel
import de.wanderlogplus.app.ui.places.PlacesViewModel
import de.wanderlogplus.app.ui.setup.SetupViewModel
import de.wanderlogplus.app.ui.trip.EditFn
import de.wanderlogplus.app.ui.trip.TripViewModel
import de.wanderlogplus.app.widgets.WidgetTarget
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.QuickAsk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/** A widget tap opens the app at the right screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetTargetNavigationTest {
    @get:Rule val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val edit: EditFn = { _ -> }
    private val backend = FakeChatBackend()
    private val chatVm by lazy { ChatViewModel(backend, MemLastChat(), pollMillis = 10) }
    private var consumed = 0

    @After fun tearDown() = scope.cancel()

    private fun factory(create: () -> ViewModel) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }

    private fun launch(target: WidgetTarget?) {
        val snapshot: TripSnapshot = Fixtures.trip().let { t ->
            t.copy(places = t.places + ("p-hotel-berg" to t.places.getValue("p-hotel-berg").copy(address = "1-2-3 Higashiyama")))
        }
        val flow = MutableStateFlow<TripSnapshot?>(snapshot)
        rule.setContent {
            WanderlogPlusTheme {
                AppNavigation(
                    hasServer = true,
                    snapshot = snapshot,
                    setupViewModelFactory = factory { SetupViewModel({ _, _ -> Result.success("a") }) { _, _ -> } },
                    tripViewModelFactory = factory { TripViewModel({ flow }, edit, scope) },
                    placesViewModelFactory = factory { PlacesViewModel({ flow }, edit, scope) },
                    moreViewModelFactory = factory { MoreViewModel({ flow }, edit, scope) },
                    budgetViewModelFactory = factory { de.wanderlogplus.app.ui.budget.BudgetViewModel(edit, scope, { null }, { "2027-04-02" }) },
                    clock = { LocalDateTime.parse("2027-04-02T10:00") },
                    chatViewModelFactory = factory { chatVm },
                    pendingWidgetTarget = target,
                    onPendingWidgetConsumed = { consumed++ },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun shows(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).assertExists()

    @Test fun day() {
        launch(WidgetTarget.Day("2027-04-03"))
        rule.onNodeWithText("Ausflug ans Wasser", substring = true).assertExists()
        assertEquals(1, consumed)
    }

    @Test fun logistics() {
        launch(WidgetTarget.Logistics(EntityType.TRAIN, "tr-1"))
        shows("logistics-detail")
    }

    @Test fun newYenExpense() {
        launch(WidgetTarget.NewExpense)
        shows("expense-amount")
        rule.onNodeWithTag("expense-currency").assertTextContains("JPY")
    }

    @Test fun stayAddress() {
        launch(WidgetTarget.StayAddress("st-berg"))
        shows("stay-address-screen")
        rule.onNodeWithText("1-2-3 Higashiyama").assertExists()
    }

    @Test fun place() {
        launch(WidgetTarget.Place("s-tempel"))
        shows("place-detail")
    }

    @Test fun chat() {
        launch(WidgetTarget.Chat(null))
        shows("chat-screen")
    }

    @Test fun askSendsTheQuestionRightAway() {
        launch(WidgetTarget.Ask(QuickAsk.KIND_FOOD))
        shows("chat-screen")
        rule.waitUntil(5_000) { backend.sent.isNotEmpty() }
        val req = backend.sent.single()
        assertEquals("Wo kann ich jetzt gut essen?", req.text)
        assertEquals(QuickAsk.KIND_FOOD, req.quick?.kind)
        assertEquals("10:00", req.quick?.now)
        assertTrue(req.location != null) // the program's place or the stay when the position is not shared
    }

    @Test fun noTargetDoesNothing() {
        launch(null)
        assertEquals(0, consumed)
        assertEquals(0, rule.onAllNodesWithTag("chat-screen").fetchSemanticsNodes().size)
    }
}
