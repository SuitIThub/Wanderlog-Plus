package de.wanderlogplus.server

import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.PlaceStatus
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.model.Votes
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldChange
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncServiceVotesTest {
    @TempDir
    lateinit var dir: Path

    private val nowMillis = 1_000_000_000L
    private lateinit var store: SqliteStore
    private lateinit var sync: SyncService

    @BeforeEach
    fun setUp() {
        store = SqliteStore(dir.resolve("wanderlog-plus.db"))
        sync = SyncService(store) { nowMillis }
        sync.writeAsServer("t", listOf(ServerEdit(EntityType.SAVED_PLACE, "s1", EntityCodec.fields(SavedPlace("s1", "p1")), create = true)))
    }

    @AfterEach
    fun tearDown() = store.close()

    private var wall = nowMillis

    private fun vote(voter: String, value: Int, node: String = "dev-$voter", idVoter: String = voter): List<FieldChange> {
        wall += 10
        val id = Votes.id("s1", idVoter)
        return listOf(
            FieldChange(EntityType.VOTE, id, "savedPlaceId", JsonPrimitive("s1"), hlc(wall, node, 0)),
            FieldChange(EntityType.VOTE, id, "voter", JsonPrimitive(voter), hlc(wall, node, 1)),
            FieldChange(EntityType.VOTE, id, "value", JsonPrimitive(value), hlc(wall, node, 2)),
        )
    }

    private fun status() = store.get(EntityType.SAVED_PLACE, "s1")!!.let { EntityCodec.decode<SavedPlace>(it) }.status

    @Test
    fun `foreign votes are dropped, the rest of the push is kept`() {
        wall += 10
        val note = FieldChange(EntityType.SAVED_PLACE, "s1", "note", JsonPrimitive("Hallo"), hlc(wall, "dev-daniel"))
        sync.push("daniel", vote("selina", Votes.LIKE, node = "dev-daniel") + vote("daniel", Votes.LIKE, idVoter = "selina") + note)
        assertNull(store.get(EntityType.VOTE, Votes.id("s1", "selina")))
        assertEquals("\"Hallo\"", store.get(EntityType.SAVED_PLACE, "s1")!!.fields["note"]!!.value.toString())
    }

    @Test
    fun `server sets the status from the votes`() {
        sync.push("daniel", vote("daniel", Votes.LIKE))
        assertEquals(PlaceStatus.IDEA, status())
        sync.push("selina", vote("selina", Votes.LIKE))
        assertEquals(PlaceStatus.SHORTLIST, status())
        sync.push("selina", vote("selina", Votes.DISLIKE))
        assertEquals(PlaceStatus.IDEA, status())
    }

    @Test
    fun `one vote per voter and place, however many records are pushed`() {
        // Records under other ids, each claiming the place: they must not add up.
        val stuffed = listOf("a", "b").flatMap { prefix ->
            wall += 10
            val id = "$prefix|daniel"
            listOf(
                FieldChange(EntityType.VOTE, id, "savedPlaceId", JsonPrimitive("s1"), hlc(wall, "dev-daniel", 0)),
                FieldChange(EntityType.VOTE, id, "value", JsonPrimitive(Votes.DOUBLE), hlc(wall, "dev-daniel", 1)),
            )
        }
        sync.push("daniel", stuffed)
        assertEquals(PlaceStatus.IDEA, status())
        assertNull(store.get(EntityType.VOTE, "a|daniel")?.fields?.get("savedPlaceId"))
    }

    @Test
    fun `names with a bar still settle the place`() {
        sync.push("a|b", vote("a|b", Votes.DOUBLE))
        assertEquals(PlaceStatus.SHORTLIST, status())
    }
}
