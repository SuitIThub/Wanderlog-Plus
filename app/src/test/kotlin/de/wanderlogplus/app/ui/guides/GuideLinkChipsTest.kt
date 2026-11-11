package de.wanderlogplus.app.ui.guides

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.today.TodayGuidesWidget
import de.wanderlogplus.shared.guide.DayGuide
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Guide
import de.wanderlogplus.shared.model.GuideLink
import de.wanderlogplus.shared.model.GuideLinkKind
import de.wanderlogplus.shared.model.GuideTarget
import de.wanderlogplus.shared.model.GuideTargetType
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldValue
import de.wanderlogplus.shared.sync.Hlc
import de.wanderlogplus.shared.sync.encode
import kotlinx.serialization.json.JsonElement
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GuideLinkChipsTest {
    @get:Rule val rule = createComposeRule()

    private val transit = GuideLink("Japan Transit", GuideLinkKind.APP, androidPackage = "jp.co.jorudan.jtp")
    private val jg = GuideLink("japan-guide", GuideLinkKind.WEB, "https://www.japan-guide.com/")

    private fun rec(type: EntityType, id: String, fields: Map<String, JsonElement>) =
        EntityRecord(type, id, fields.mapValues { (_, v) -> FieldValue(v, Hlc(1, 0, "a").encode()) })

    @Test
    fun guidesSectionShowsTheLinksOfItsGuidesOnce() {
        val snapshot = TripSnapshot.from(
            listOf(
                rec(EntityType.PLACE, "p-ryo", EntityCodec.fields(Place("p-ryo", name = "Tsutaya Ryokan"))),
                rec(EntityType.STAY, "st", EntityCodec.fields(Stay("st", "p-ryo", checkIn = "2027-05-19", checkOut = "2027-05-21"))),
                rec(EntityType.GUIDE, "g-ryo", EntityCodec.fields(Guide("g-ryo", topic = "ryokan", title = "Im Ryokan", links = listOf(transit, jg)))),
                rec(EntityType.GUIDE, "g-pin", EntityCodec.fields(Guide("g-pin", title = "Extra", links = listOf(transit), pinnedTo = listOf(GuideTarget(GuideTargetType.STAY, "st"))))),
            ),
        )
        rule.setContent { WanderlogPlusTheme { GuidesSection(snapshot, GuideTarget(GuideTargetType.STAY, "st"), onOpenGuide = {}) } }
        rule.onNodeWithTag("guide-links").assertExists()
        rule.onAllNodesWithTag("guide-link", useUnmergedTree = true).assertCountEquals(2)
        rule.onNodeWithText("Japan Transit").assertExists()
    }

    @Test
    fun nothingWithoutLinks() {
        rule.setContent { WanderlogPlusTheme { GuideLinkChips(emptyList()) } }
        rule.onNodeWithTag("guide-links").assertDoesNotExist()
    }

    @Test
    fun todayWidgetShowsLinksOfOpenGuides() {
        val open = Guide("a", title = "Shinkansen", links = listOf(transit))
        val done = Guide("b", title = "Vorbei", links = listOf(jg))
        rule.setContent {
            WanderlogPlusTheme {
                TodayGuidesWidget(listOf(DayGuide(open, 600, false), DayGuide(done, 300, true)), suggested = 0, accent = Color.Blue, onOpenGuide = {}, onSuggestions = null)
            }
        }
        rule.onNodeWithText("Japan Transit").assertExists()
        rule.onNodeWithText("japan-guide").assertDoesNotExist()
    }

    @Test
    fun notesShowWhenAsked() {
        rule.setContent { WanderlogPlusTheme { GuideLinkChips(listOf(transit.copy(note = "Fahrplan")), showNotes = true) } }
        rule.onNodeWithText("Fahrplan", substring = true).assertExists()
    }
}
