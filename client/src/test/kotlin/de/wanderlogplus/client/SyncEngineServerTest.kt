package de.wanderlogplus.client

import de.wanderlogplus.server.FileStore
import de.wanderlogplus.server.ImportService
import de.wanderlogplus.server.ServerConfig
import de.wanderlogplus.server.Services
import de.wanderlogplus.server.SqliteStore
import de.wanderlogplus.server.SyncService
import de.wanderlogplus.server.TripSource
import de.wanderlogplus.server.module
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.Hlc
import de.wanderlogplus.shared.sync.HlcClock
import de.wanderlogplus.shared.sync.InMemoryEntityStore
import de.wanderlogplus.shared.sync.parse
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SyncEngineServerTest {
    @TempDir
    lateinit var dir: Path

    private val tokenA = "token-alice-0123456789"
    private val tokenB = "token-bob-0123456789ab"
    private val fixture: String =
        Files.readString(Path.of(System.getProperty("repoRoot"), "importer", "src", "test", "resources", "wanderlog-mini.json"))
    private val oldNote = "Note on T1"
    private var sourceJson = fixture

    private fun services(name: String): Services {
        val d = Files.createDirectories(dir.resolve(name))
        val config = ServerConfig(
            tokens = mapOf("alice" to tokenA, "bob" to tokenB),
            dataDir = d,
            port = 0,
            maxFileBytes = 25L * 1024 * 1024,
            tripKey = null,
            importFile = null,
        )
        val store = SqliteStore(d.resolve("wanderlog-plus.db"))
        val sync = SyncService(store)
        return Services(config, store, sync, FileStore.forConfig(config), ImportService(store, sync, TripSource { sourceJson }))
    }

    /** One simulated device; its local state survives across server instances. */
    private class Device(nodeId: String, val token: String) {
        val store = InMemoryEntityStore()
        val outbox = InMemoryOutbox()
        val state = InMemorySyncState()
        val clock = HlcClock(nodeId)
        val editor = LocalEditor(store, outbox, clock)
        fun snapshot() = TripSnapshot.from(store.all())
        fun savedPlace(note: String): SavedPlace = snapshot().savedPlaces.first { it.note == note }
    }

    private fun ApplicationTestBuilder.api(d: Device) = SyncApi(createClient { }, "", d.token)
    private fun ApplicationTestBuilder.engine(d: Device) =
        SyncEngine(api(d), d.store, d.outbox, d.state, d.clock)

    private suspend fun ApplicationTestBuilder.sync(d: Device): SyncResult.Ok =
        engine(d).sync().let { assertIs<SyncResult.Ok>(it, it.toString()) }

    private fun withServer(name: String, block: suspend ApplicationTestBuilder.(Services) -> Unit) {
        val services = services(name)
        runBlocking {
            testApplication {
                application { module(services) }
                block(services)
            }
        }
        services.store.close()
    }

    private fun recordsOf(d: Device) = d.store.all().associateBy { it.type to it.id }

    private fun setNote(d: Device, id: String, note: String) =
        d.editor.set(EntityType.SAVED_PLACE, id, mapOf("note" to JsonPrimitive(note)))

    @Test fun twoDevicesConverge() = withServer("s1") {
        val a = Device("dev-aaaaaaaaaaaa", tokenA)
        val b = Device("dev-bbbbbbbbbbbb", tokenB)
        api(a).triggerImport()
        sync(a)
        sync(b)
        assertTrue(a.store.all().isNotEmpty())
        assertEquals(recordsOf(a), recordsOf(b))
    }

    @Test fun offlineEditSurvivesAndPropagates() = withServer("s1") {
        val a = Device("dev-aaaaaaaaaaaa", tokenA)
        val b = Device("dev-bbbbbbbbbbbb", tokenB)
        api(a).triggerImport()
        sync(a)
        sync(b)
        val id = a.savedPlace(oldNote).id
        setNote(a, id, "A edited")
        sync(a)
        sync(b)
        assertEquals("A edited", b.snapshot().savedPlaces.first { it.id == id }.note)

        api(a).triggerImport()
        sync(a)
        sync(b)
        assertEquals("A edited", a.snapshot().savedPlaces.first { it.id == id }.note)
        assertEquals("A edited", b.snapshot().savedPlaces.first { it.id == id }.note)
    }

    @Test fun resetLocalCursorRepushes() = withServer("s1") {
        val a = Device("dev-aaaaaaaaaaaa", tokenA)
        api(a).triggerImport()
        sync(a)
        setNote(a, a.savedPlace(oldNote).id, "A edited")
        sync(a)
        val epoch = a.state.epoch
        assertTrue(a.state.since > 0)
        assertEquals(0, a.outbox.size())

        engine(a).resetLocalCursor()
        val r = sync(a)
        assertTrue(r.pushed > 0)
        assertEquals(epoch, a.state.epoch)
        assertTrue(a.state.since > 0)
        assertEquals(0, a.outbox.size())
        assertEquals(false, a.state.pendingReset)
        assertEquals("A edited", a.savedPlace("A edited").note)
    }

    @Test fun resetRepushesServerConflictWinnersRelabelledToDevice() {
        val a = Device("dev-aaaaaaaaaaaa", tokenA)
        var id = ""
        withServer("s1") {
            api(a).triggerImport()
            sync(a)
            id = a.savedPlace(oldNote).id
            setNote(a, id, "App version")
            sync(a)
        }
        // A fresh server that imports first turns the older device edit into a server-written conflict winner.
        withServer("s2") {
            api(a).triggerImport()
            sync(a)
            val note = a.store.get(EntityType.SAVED_PLACE, id)!!.fields.getValue("note")
            assertEquals("server", Hlc.parse(note.hlc).nodeId)
        }
        // Another fresh server: the server-node fields must be re-pushed (relabelled), not rejected as reserved.
        withServer("s3") {
            api(a).triggerImport()
            sync(a)
            val c = Device("dev-cccccccccccc", tokenA)
            sync(c)
            for (d in listOf(a, c)) {
                assertEquals("App version", d.snapshot().savedPlaces.first { it.id == id }.note)
                val conflict = d.snapshot().conflicts.single { it.id == id && it.field == "note" }
                assertEquals(JsonPrimitive(oldNote), conflict.wanderlogValue)
            }
        }
    }

    @Test fun newServerDatabaseTriggersFullRepush() {
        val a = Device("dev-aaaaaaaaaaaa", tokenA)
        val b = Device("dev-bbbbbbbbbbbb", tokenB)
        var epoch1: String? = null
        var aId = ""
        var bId = ""
        withServer("s1") {
            api(a).triggerImport()
            sync(a)
            sync(b)
            epoch1 = a.state.epoch
            aId = a.savedPlace(oldNote).id
            bId = b.snapshot().savedPlaces.first { it.id != aId }.id
            setNote(a, aId, "A edited")
            sync(a)
            sync(b)
            // B edits locally and has not synced when the server is replaced.
            setNote(b, bId, "B edited")
        }
        withServer("s2") {
            api(a).triggerImport()
            sync(a)
            assertNotEquals(epoch1, a.state.epoch)
            sync(b)
            assertEquals(a.state.epoch, b.state.epoch)

            val c = Device("dev-cccccccccccc", tokenA)
            sync(c)
            val notes = c.snapshot().savedPlaces.associate { it.id to it.note }
            assertEquals("A edited", notes[aId])
            assertEquals("B edited", notes[bId])
            // The fresh server imported before the devices re-pushed, so both older edits surface as open conflicts
            // (the edit wins, the imported text is offered as the Wanderlog value).
            val conflicts = c.snapshot().conflicts.map { it.id to it.field }.toSet()
            assertEquals(setOf(aId to "note", bId to "note"), conflicts)
            sync(a)
            assertEquals("B edited", a.snapshot().savedPlaces.first { it.id == bId }.note)
            assertEquals("A edited", b.snapshot().savedPlaces.first { it.id == aId }.note)
        }
    }

    @Test fun conflictRoundTrip() = withServer("s1") {
        val a = Device("dev-aaaaaaaaaaaa", tokenA)
        api(a).triggerImport()
        sync(a)
        val id = a.savedPlace(oldNote).id
        setNote(a, id, "App version")
        sync(a)

        sourceJson = fixture.replace(oldNote, "Wanderlog version")
        assertNotEquals(fixture, sourceJson)
        api(a).triggerImport()
        sync(a)

        val conflict = a.snapshot().conflicts.single { it.id == id && it.field == "note" }
        assertEquals(JsonPrimitive("App version"), conflict.appValue)
        assertEquals(JsonPrimitive("Wanderlog version"), conflict.wanderlogValue)

        a.editor.takeWanderlog(conflict)
        sync(a)
        val snap = a.snapshot()
        assertEquals("Wanderlog version", snap.savedPlaces.first { it.id == id }.note)
        assertTrue(snap.conflicts.none { it.id == id })

        // The server agrees: a second device sees the resolved value and no conflict.
        val b = Device("dev-bbbbbbbbbbbb", tokenB)
        sync(b)
        assertEquals("Wanderlog version", b.snapshot().savedPlaces.first { it.id == id }.note)
        assertTrue(b.snapshot().conflicts.none { it.id == id })
    }
}
