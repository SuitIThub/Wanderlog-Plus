package de.wanderlogplus.app.ui.places

import de.wanderlogplus.app.ui.trip.EditFn
import de.wanderlogplus.client.InMemoryOutbox
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.PlaceStatus
import de.wanderlogplus.shared.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlacesViewModelTest {
    private val store = InMemoryEntityStore()
    private val outbox = InMemoryOutbox()
    private val editor = LocalEditor(store, outbox, HlcClock("dev-a", now = { 1000L }))
    private var editCalls = 0
    private val edit: EditFn = { block -> editCalls++; block(editor, TripSnapshot.from(store.all())) }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Unconfined)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun changes() = outbox.peek(100).map { it.change }

    @Test fun setNoteAndStatusWriteSavedPlaceFieldsThroughEdit() {
        val vm = PlacesViewModel({ MutableStateFlow(null) }, edit, scope)
        vm.setNote("sv1", "Früh hin")
        vm.setStatus("sv1", PlaceStatus.VISITED)
        assertEquals(2, editCalls)
        val note = changes().single { it.field == "note" }
        val status = changes().single { it.field == "status" }
        assertEquals(EntityType.SAVED_PLACE, note.type)
        assertEquals("sv1", note.id)
        assertEquals(JsonPrimitive("Früh hin"), note.value)
        assertEquals(EntityType.SAVED_PLACE, status.type)
        assertEquals(JsonPrimitive("VISITED"), status.value)
    }

    @Test fun failedEditSetsClassNameOnlyAndConsumeClears() {
        val vm = PlacesViewModel({ MutableStateFlow(null) }, { _ -> throw IllegalStateException("secret") }, scope)
        vm.setNote("sv1", "x")
        assertEquals("IllegalStateException", vm.error.value)
        vm.consumeError()
        assertNull(vm.error.value)
    }

    @Test fun writesRunInInjectedScopeNotViewModelScope() {
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
        val injected = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher)
        val vm = PlacesViewModel({ MutableStateFlow(null) }, edit, injected)
        vm.setNote("sv1", "x")
        assertEquals(0, editCalls) // queued on the injected scope, not run on viewModelScope (Main is unconfined)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, editCalls)
    }
}
