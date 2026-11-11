package de.wanderlogplus.app.ui.logistics

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.more.MoreViewModel
import de.wanderlogplus.app.ui.trip.EditFn
import de.wanderlogplus.client.InMemoryOutbox
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Train
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.HlcClock
import de.wanderlogplus.shared.sync.InMemoryEntityStore
import de.wanderlogplus.shared.sync.TransitLeg
import de.wanderlogplus.shared.sync.TransitResult
import de.wanderlogplus.shared.sync.TransitRoute
import de.wanderlogplus.shared.sync.TransitStation
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
class TransitJourneyUiTest {
    @get:Rule val rule = createComposeRule()

    private val kyoto = TransitStation("京都", "Kyoto", 34.98, 135.75)
    private val nagoya = TransitStation("名古屋", "Nagoya", 35.17, 136.88)
    private val naka = TransitStation("中津川", "Nakatsugawa", 35.49, 137.5)

    private val twoRides = TransitResult(
        kyoto, naka, "2027-05-22T08:00",
        routes = listOf(
            TransitRoute(
                "08:12", "10:05", 113, transfers = 1, fareYen = 7000, date = "2027-05-22",
                legs = listOf(
                    TransitLeg("ＪＲ新幹線のぞみ21号", "JR Shinkansen Nozomi 21", from = kyoto, to = nagoya, departure = "08:12", arrival = "08:47", departurePlatform = "14"),
                    TransitLeg("徒歩", walk = true, from = nagoya, to = nagoya),
                    TransitLeg("ＪＲ特急しなの7号", "JR Ltd. Exp. Shinano 7", from = nagoya, to = naka, departure = "09:00", arrival = "10:05"),
                ),
            ),
        ),
    )
    private val oneRide = twoRides.copy(routes = listOf(twoRides.routes.single().copy(legs = twoRides.routes.single().legs.take(1))))

    private val empty = MoveDraft("", "", Endpoint(), emptyList(), Endpoint())

    @Test
    fun draftTakesFirstRide() {
        val d = draftFromTransit(empty, twoRides)
        assertEquals("JR Shinkansen", d.operator)
        assertEquals("Nozomi 21", d.number)
        assertEquals("Kyoto", d.depart.name)
        assertEquals("08:47", d.arrive.time)
        assertEquals("Nagoya", d.arrive.name)
        assertTrue(d.via.isEmpty())
        assertEquals(1, d.extraRides.size)
        assertEquals("09:00", d.extraRides.single().depart.time)
        assertEquals("Shinano 7", d.extraRides.single().number)
        assertEquals(1, d.transit!!.routes.single().legs.size)
        val single = draftFromTransit(empty, oneRide)
        assertTrue(single.extraRides.isEmpty())
        assertEquals(oneRide, single.transit)
    }

    @Test
    fun hintCountsRides() {
        rule.setContent { WanderlogPlusTheme { TransitJourneyHint(draftFromTransit(empty, twoRides)) } }
        rule.onNodeWithText("Wird als Reise mit 2 Teilstrecken gespeichert").assertExists()
    }

    @Test
    fun editedTrainKeepsBookingAndBecomesJourney() {
        val store = InMemoryEntityStore()
        val editor = LocalEditor(store, InMemoryOutbox(), HlcClock("dev", now = { 1000L }))
        val train = Train("tr", "JR", Endpoint("Kyoto", date = "2027-05-22", time = "08:00"), Endpoint("Nakatsugawa", date = "2027-05-22"), confirmationNumber = "ABC")
        editor.create(EntityType.TRAIN, EntityCodec.fields(train), id = "tr")
        val snap = { TripSnapshot.from(store.all()) }
        val edit: EditFn = { block -> block(editor, snap()) }
        val vm = MoreViewModel({ MutableStateFlow(snap()) }, edit, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val d = draftFromTransit(draftOf(snap(), EntityType.TRAIN, "tr")!!, twoRides)
        val (before, after) = draftChanges(snap(), EntityType.TRAIN, "tr", d)
        vm.saveFields(EntityType.TRAIN, "tr", before, after, d.extraRides).let { job -> kotlinx.coroutines.runBlocking { job.join() } }
        val trains = snap().trains.sortedBy { it.journeyOrder }
        assertEquals(2, trains.size)
        assertEquals("ABC", trains[0].confirmationNumber)
        assertNull(trains[1].confirmationNumber)
        assertEquals(trains[0].journeyId, trains[1].journeyId)
        assertEquals("tr", trains[0].id)
        assertEquals("Nagoya", trains[1].depart.name)
    }

    @Test
    fun menuOffersTransitSearch() {
        var opened = 0
        rule.setContent { WanderlogPlusTheme { LogisticsScreen(Fixtures.trip(), onOpen = {}, onAdd = {}, onAddTransit = { opened++ }) } }
        rule.onNodeWithTag("logistics-edit").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("logistics-add").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("logistics-add-transit").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, opened)
    }
}
