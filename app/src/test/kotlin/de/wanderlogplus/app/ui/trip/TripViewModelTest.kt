package de.wanderlogplus.app.ui.trip

import de.wanderlogplus.client.InMemoryOutbox
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.*
import de.wanderlogplus.shared.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TripViewModelTest {
    private val store = InMemoryEntityStore()
    private val editor = LocalEditor(store, InMemoryOutbox(), HlcClock("dev-a", now = { 1000L }))
    private val lock = Mutex()
    private val edit: EditFn = { block ->
        lock.withLock { withContext(Dispatchers.IO) { block(editor, TripSnapshot.from(store.all())) } }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Unconfined)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private inline fun <reified T> seed(type: EntityType, id: String, entity: T) {
        val fields: Map<String, JsonElement> = EntityCodec.fields(entity) + ("deleted" to JsonPrimitive(false))
        var rec = EntityRecord(type, id)
        fields.forEach { (k, v) -> rec = rec.apply(FieldChange(type, id, k, v, Hlc(1, 0, "importer").encode()))!! }
        store.put(rec)
    }

    private fun vm() = TripViewModel({ MutableStateFlow(null) }, edit, scope)

    @Test fun concurrentAddOfSameSuggestionCreatesOneItem() {
        seed(EntityType.TRIP, "t1", Trip("t1", "T", "2027-05-08", "2027-05-12"))
        seed(EntityType.PLACE, "pa", Place("pa", name = "A"))
        seed(EntityType.SAVED_PLACE, "sv", SavedPlace("sv", "pa", "r1"))
        val vm = vm()
        runBlocking { List(8) { vm.addSuggestion("2027-05-09", "sv") }.joinAll() }
        val items = TripSnapshot.from(store.all()).dayItems
        assertEquals(1, items.size)
        assertEquals(1, TripSnapshot.from(store.all()).days.size)
    }

    @Test fun quickMovesUpStayConsistent() {
        seed(EntityType.TRIP, "t1", Trip("t1", "T", "2027-05-08", "2027-05-12"))
        seed(EntityType.DAY, "d1", Day("d1", "2027-05-09"))
        listOf("i1" to "a0", "i2" to "a1", "i3" to "a2").forEach { (id, k) ->
            seed(EntityType.DAY_ITEM, id, DayItem(id, "d1", k, DayItemKind.NOTE, text = id))
        }
        val vm = vm()
        runBlocking { listOf(vm.move("i3", true), vm.move("i3", true)).joinAll() }
        assertEquals(listOf("i3", "i1", "i2"), TripSnapshot.from(store.all()).dayItemsFor("d1").map { it.id })
    }

    @Test fun failedActionSetsClassNameOnly() {
        val vm = TripViewModel({ MutableStateFlow(null) }, { _ -> throw IllegalStateException("secret") }, scope)
        runBlocking { vm.remove("x").join() }
        assertEquals("IllegalStateException", vm.error.value)
        vm.consumeError()
        assertEquals(null, vm.error.value)
    }
}
