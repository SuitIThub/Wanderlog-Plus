package de.wanderlogplus.client

import de.wanderlogplus.server.FileStore
import de.wanderlogplus.server.ImportService
import de.wanderlogplus.server.ServerConfig
import de.wanderlogplus.server.Services
import de.wanderlogplus.server.SqliteStore
import de.wanderlogplus.server.SyncService
import de.wanderlogplus.server.TripSource
import de.wanderlogplus.server.module
import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.model.PlaceStatus
import de.wanderlogplus.shared.sync.HlcClock
import de.wanderlogplus.shared.sync.InMemoryEntityStore
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Runs only when the private real export exists locally; never prints field values. */
class RealDataClientTest {
    @TempDir
    lateinit var dir: Path

    private val tokenA = "token-alice-0123456789"
    private val tokenB = "token-bob-0123456789ab"

    private class Device(nodeId: String, val token: String) {
        val store = InMemoryEntityStore()
        val outbox = InMemoryOutbox()
        val state = InMemorySyncState()
        val clock = HlcClock(nodeId)
        val editor = LocalEditor(store, outbox, clock)
        fun snapshot() = TripSnapshot.from(store.all())
    }

    private fun ApplicationTestBuilder.sync(d: Device) {
        val engine = SyncEngine(SyncApi(createClient { }, "", d.token), d.store, d.outbox, d.state, d.clock)
        runBlocking { engine.sync() }.let { assertIs<SyncResult.Ok>(it, it::class.simpleName) }
    }

    @Test fun realTripEndToEnd() {
        val file = Path.of(System.getProperty("repoRoot"), "testdata", "private", "wanderlog-real.json")
        assumeTrue(Files.exists(file), "real export not present")
        val json = Files.readString(file)
        val config = ServerConfig(
            tokens = mapOf("alice" to tokenA, "bob" to tokenB),
            dataDir = dir, port = 0, maxFileBytes = 25L * 1024 * 1024, tripKey = null, importFile = null,
        )
        val store = SqliteStore(dir.resolve("wanderlog-plus.db"))
        val sync = SyncService(store)
        val services = Services(config, store, sync, FileStore.forConfig(config), ImportService(store, sync, TripSource { json }))
        runBlocking {
            testApplication {
                application { module(services) }
                val a = Device("dev-aaaaaaaaaaaa", tokenA)
                val b = Device("dev-bbbbbbbbbbbb", tokenB)
                SyncApi(createClient { }, "", tokenA).triggerImport()
                sync(a)
                sync(b)
                assertEquals(533, a.store.all().size)
                assertEquals(533, b.store.all().size)

                val snapA = a.snapshot()
                val days = snapA.queries()!!.days()
                assertEquals(22, days.size)
                assertEquals("2027-05-08", days.first().date)
                assertEquals("2027-05-29", days.last().date)

                val date = "2027-05-20"
                val suggestions = snapA.queries()!!.suggestionsFor(date)
                val regionNames = suggestions.mapNotNull { s -> snapA.regions.firstOrNull { it.id == s.regionId }?.name }.toSet()
                assertTrue("Kyoto" in regionNames, "suggestions include Kyoto region")
                assertTrue("Nara" in regionNames, "suggestions include Nara region")

                val pick = suggestions.first()
                a.editor.addSuggestionToDay(snapA, date, pick.id)
                sync(a)
                sync(b)

                val snapB = b.snapshot()
                val saved = snapB.savedPlaces.first { it.id == pick.id }
                assertEquals(PlaceStatus.PLANNED, saved.status)
                val day = snapB.days.first { it.date == date }
                assertTrue(snapB.dayItemsFor(day.id).any { it.kind == DayItemKind.PLACE && it.placeId == pick.placeId })
                assertTrue(snapB.queries()!!.suggestionsFor(date).none { it.id == pick.id })
            }
        }
        store.close()
    }
}
