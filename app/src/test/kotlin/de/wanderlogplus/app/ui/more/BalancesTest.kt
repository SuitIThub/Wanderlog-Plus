package de.wanderlogplus.app.ui.more

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.shared.sync.BalanceLine
import de.wanderlogplus.shared.sync.BalanceReport
import de.wanderlogplus.shared.sync.BalanceStatus
import de.wanderlogplus.shared.sync.ServiceBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class BalancesTest {
    @get:Rule val rule = createComposeRule()

    private val today = LocalDate.of(2026, 11, 11)
    private val openRouter = ServiceBalance("openrouter", "OpenRouter (KI)", BalanceStatus.OK, "USD", used = 34.99, limit = 50.0, remaining = 15.01,
        details = listOf(BalanceLine("Diesen Monat", "14,63 $")))
    private val apify = ServiceBalance("apify", "Apify (Tabelog, ÖPNV)", BalanceStatus.OK, "USD", used = 4.2, limit = 5.0, remaining = 0.8, periodEnd = "2026-12-03T23:59:59.999Z")
    private val airlabs = ServiceBalance("airlabs", "AirLabs (Flüge live)", BalanceStatus.OK, "CALLS", used = 1.0, limit = 1000.0, remaining = 999.0, expiresAt = "2026-12-05T00:00:00.000Z")
    private val google = ServiceBalance("google", "Google (Orte, Fotos, Routen)", BalanceStatus.LINK_ONLY, "CALLS", used = 42.0,
        details = listOf(BalanceLine("Details", "30"), BalanceLine("Textsuche", "12")), link = "https://console.cloud.google.com/billing")

    @Test fun warnings() {
        assertNull(balanceWarning(openRouter, today))
        assertEquals("84 % verbraucht", balanceWarning(apify, today))
        assertNull(balanceWarning(airlabs, today)) // 24 days left: only from 14 days on
        assertEquals("Key läuft in 10 Tagen ab", balanceWarning(airlabs, LocalDate.of(2026, 11, 25)))
        assertEquals("Key ist abgelaufen", balanceWarning(airlabs, LocalDate.of(2026, 12, 6)))
        assertNull(balanceWarning(google, today))
    }

    @Test fun amounts() {
        assertEquals("15,01 $ übrig von 50,00 $", amountText(openRouter))
        assertEquals("999 von 1.000 Abfragen übrig", amountText(airlabs))
        assertEquals("42 Aufrufe diesen Monat", amountText(google))
        assertNull(amountText(ServiceBalance("parallel", "Parallel", BalanceStatus.LINK_ONLY)))
    }

    @Test fun screenShowsCardsAndRefreshes() {
        val loads = mutableListOf<Boolean>()
        val report = BalanceReport(
            listOf(openRouter, apify, airlabs, ServiceBalance("parallel", "Parallel (Recherche)", BalanceStatus.LINK_ONLY, link = "https://platform.parallel.ai/", note = "Das Guthaben zeigt nur das Parallel-Dashboard."), google,
                ServiceBalance("x", "Ohne Key", BalanceStatus.NOT_CONFIGURED)),
            fetchedAt = 0,
        )
        rule.setContent { WanderlogPlusTheme { BalancesScreen(load = { fresh -> loads.add(fresh); report }, onBack = {}, today = today) } }
        rule.waitUntil(5_000) { loads.isNotEmpty() }
        rule.waitForIdle()
        rule.onNodeWithText("15,01 $ übrig von 50,00 $").assertExists()
        rule.onNodeWithText("84 % verbraucht").assertExists()
        rule.onNodeWithText("Das Guthaben zeigt nur das Parallel-Dashboard.").assertExists()
        rule.onNodeWithText("nicht eingerichtet").assertExists()
        rule.onNodeWithText("Textsuche").assertExists()
        rule.onNodeWithTag("balances-refresh").assertIsEnabled()
        rule.onNodeWithTag("balances-refresh").performSemanticsAction(SemanticsActions.OnClick)
        // The cards' progress bars keep Compose busy; let the clock run so the new load starts.
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        rule.waitUntil(5_000) { loads.size == 2 }
        assertEquals(listOf(false, true), loads)
    }

    @Test fun screenWithoutServer() {
        rule.setContent { WanderlogPlusTheme { BalancesScreen(load = { null }, onBack = {}, today = today) } }
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("Server nicht erreichbar", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun moreMenuHasTheEntry() {
        var opened: String? = null
        rule.setContent { WanderlogPlusTheme { MoreScreen(conflictCount = 0, onOpen = { opened = it }) } }
        rule.onNodeWithText("Guthaben & Limits").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(BALANCES_ROUTE, opened)
    }

    @Test fun openRouterWarnsOnWhatIsLeftNotOnTheLifetimeShare() {
        val longLived = openRouter.copy(used = 400.0, limit = 500.0, remaining = 100.0)
        assertNull(balanceWarning(longLived, today))
        assertEquals("Nur noch 4,00 $ übrig", balanceWarning(openRouter.copy(used = 46.0, limit = 50.0, remaining = 4.0), today))
    }

    @Test fun datesAreTheProvidersDays() {
        assertEquals(LocalDate.of(2026, 12, 3), balanceDate("2026-12-03T23:59:59.999Z"))
        assertEquals("Key läuft heute ab", balanceWarning(airlabs, LocalDate.of(2026, 12, 5)))
        assertEquals("Key läuft in 1 Tag ab", balanceWarning(airlabs, LocalDate.of(2026, 12, 4)))
    }

    @Test fun aFailedRefreshIsShown() {
        var n = 0
        val report = BalanceReport(listOf(openRouter), fetchedAt = 0)
        rule.setContent { WanderlogPlusTheme { BalancesScreen(load = { n++; if (n == 1) report else null }, onBack = {}, today = today) } }
        rule.waitUntil(5_000) { n == 1 }
        rule.waitForIdle()
        rule.onNodeWithTag("balances-refresh").performSemanticsAction(SemanticsActions.OnClick)
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        rule.onNodeWithText("Aktualisieren fehlgeschlagen", substring = true).assertExists()
        rule.onNodeWithText("15,01 $ übrig von 50,00 $").assertExists()
    }
}
