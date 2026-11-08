package de.wanderlogplus.app.ui.logistics

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.TransitLeg
import de.wanderlogplus.shared.sync.TransitResult
import de.wanderlogplus.shared.sync.TransitRoute
import de.wanderlogplus.shared.sync.TransitStation
import de.wanderlogplus.shared.sync.TransitStop
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class TrainStopsTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun detailListsTheStopsWithTimes() {
        val shin = TransitStation("新大阪", "Shin-Ōsaka")
        val hiro = TransitStation("広島", "Hiroshima")
        val route = TransitRoute(
            "09:30", "10:55", 85, date = "2027-04-04",
            legs = listOf(TransitLeg("のぞみ", "JR Shinkansen Nozomi 9", from = shin, to = hiro, departure = "09:30", arrival = "10:55",
                stops = listOf(TransitStop("新神戸", "Shin-Kōbe", "09:42"), TransitStop("岡山", "", "10:15")))),
        )
        val s = Fixtures.trip().let { t -> t.copy(trains = t.trains.map { it.copy(transit = TransitResult(shin, hiro, "2027-04-04T09:30", routes = listOf(route))) }) }
        rule.setContent { WanderlogPlusTheme { LogisticsDetailScreen(s, EntityType.TRAIN, "tr-1", onBack = {}) } }
        rule.onNodeWithTag("train-stops").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("train-stop-0", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("09:42 Shin-Kōbe", substring = true).assertExists()
        rule.onNodeWithText("10:15 岡山", substring = true).assertExists()
        rule.onNodeWithText("10:55 Hiroshima", substring = true).assertExists()
    }
}
