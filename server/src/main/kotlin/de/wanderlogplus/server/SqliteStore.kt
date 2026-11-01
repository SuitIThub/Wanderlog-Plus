package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityStore
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldValue
import de.wanderlogplus.shared.sync.PullResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** One run of the Wanderlog importer; times are ISO-8601 UTC. */
@Serializable
data class ImportLogEntry(
    val startedAt: String,
    val finishedAt: String,
    val ok: Boolean,
    val created: Int = 0,
    val updated: Int = 0,
    val conflicts: Int = 0,
    val removed: Int = 0,
    val flaggedRemoved: Int = 0,
    val error: String? = null,
)

/**
 * SQLite-backed [EntityStore]. One row per (type, id, field); every written row gets a
 * monotonically increasing `seq` used for paged pulls. All methods share one connection and are synchronized.
 */
class SqliteStore(dbFile: Path) : EntityStore, AutoCloseable {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}")
    private var maxSeq: Long = 0

    /** Random id of this database file, created on first open; changes only if the DB is recreated. */
    val epoch: String

    /** Author recorded for subsequent puts; set via [transaction]. */
    @Volatile
    var currentAuthor: String = "server"
        private set

    init {
        try {
            conn.createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL")
                st.execute(
                    """CREATE TABLE IF NOT EXISTS fields(
                        type TEXT NOT NULL, id TEXT NOT NULL, field TEXT NOT NULL,
                        value TEXT NOT NULL, hlc TEXT NOT NULL, seq INTEGER NOT NULL, author TEXT NOT NULL,
                        PRIMARY KEY(type, id, field))""",
                )
                st.execute("CREATE INDEX IF NOT EXISTS idx_fields_seq ON fields(seq)")
                st.execute(
                    """CREATE TABLE IF NOT EXISTS import_log(
                        id INTEGER PRIMARY KEY AUTOINCREMENT, started_at TEXT, finished_at TEXT, ok INTEGER,
                        created INTEGER, updated INTEGER, conflicts INTEGER, removed INTEGER,
                        flagged_removed INTEGER, error TEXT)""",
                )
            }
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            }
            conn.prepareStatement("INSERT OR IGNORE INTO meta(key, value) VALUES ('db_epoch', ?)").use { ps ->
                ps.setString(1, UUID.randomUUID().toString()); ps.executeUpdate()
            }
            epoch = conn.createStatement().use { st ->
                st.executeQuery("SELECT value FROM meta WHERE key = 'db_epoch'").use { it.next(); it.getString(1) }
            }
            maxSeq = loadMaxSeq()
        } catch (e: Throwable) {
            conn.close()
            throw e
        }
    }

    private fun loadMaxSeq(): Long =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COALESCE(MAX(seq), 0) FROM fields").use { it.next(); it.getLong(1) }
        }

    @Synchronized
    fun maxSeq(): Long = maxSeq

    @Synchronized
    override fun close() {
        conn.close()
    }

    /** Runs [block] in one JDBC transaction attributed to [author]; rolls back and rethrows on exception. */
    @Synchronized
    fun <T> transaction(author: String, block: () -> T): T {
        val previousAuthor = currentAuthor
        conn.autoCommit = false
        currentAuthor = author
        try {
            val result = block()
            conn.commit()
            return result
        } catch (e: Throwable) {
            try { conn.rollback() } catch (r: Throwable) { e.addSuppressed(r) }
            maxSeq = loadMaxSeq()
            throw e
        } finally {
            currentAuthor = previousAuthor
            conn.autoCommit = true
        }
    }

    @Synchronized
    override fun put(record: EntityRecord) {
        val type = record.type.name
        val stored = HashMap<String, Pair<String, String>>()
        conn.prepareStatement("SELECT field, value, hlc FROM fields WHERE type = ? AND id = ?").use { ps ->
            ps.setString(1, type); ps.setString(2, record.id)
            ps.executeQuery().use { rs -> while (rs.next()) stored[rs.getString(1)] = rs.getString(2) to rs.getString(3) }
        }
        val standalone = conn.autoCommit
        if (standalone) conn.autoCommit = false
        var seq = maxSeq
        try {
            conn.prepareStatement(
                "INSERT OR REPLACE INTO fields(type, id, field, value, hlc, seq, author) VALUES (?,?,?,?,?,?,?)",
            ).use { ps ->
                for ((field, fv) in record.fields) {
                    val json = Json.encodeToString(JsonElement.serializer(), fv.value)
                    if (stored[field] == json to fv.hlc) continue
                    seq++
                    ps.setString(1, type); ps.setString(2, record.id); ps.setString(3, field)
                    ps.setString(4, json); ps.setString(5, fv.hlc); ps.setLong(6, seq); ps.setString(7, currentAuthor)
                    ps.executeUpdate()
                }
            }
            if (standalone) conn.commit()
            maxSeq = seq
        } catch (e: Throwable) {
            if (standalone) { try { conn.rollback() } catch (r: Throwable) { e.addSuppressed(r) } }
            throw e
        } finally {
            if (standalone) conn.autoCommit = true
        }
    }

    @Synchronized
    override fun get(type: EntityType, id: String): EntityRecord? {
        val fields = readFields(type, id)
        return if (fields.isEmpty()) null else EntityRecord(type, id, fields)
    }

    private fun readFields(type: EntityType, id: String): Map<String, FieldValue> {
        val out = LinkedHashMap<String, FieldValue>()
        conn.prepareStatement("SELECT field, value, hlc FROM fields WHERE type = ? AND id = ? ORDER BY field").use { ps ->
            ps.setString(1, type.name); ps.setString(2, id)
            ps.executeQuery().use { rs ->
                while (rs.next()) out[rs.getString(1)] = FieldValue(Json.parseToJsonElement(rs.getString(2)), rs.getString(3))
            }
        }
        return out
    }

    @Synchronized
    override fun all(type: EntityType): List<EntityRecord> = readAll("WHERE type = ?", type.name)

    @Synchronized
    override fun all(): List<EntityRecord> = readAll("")

    private fun readAll(where: String, arg: String? = null): List<EntityRecord> {
        val result = ArrayList<EntityRecord>()
        conn.prepareStatement("SELECT type, id, field, value, hlc FROM fields $where ORDER BY type, id, field").use { ps ->
            if (arg != null) ps.setString(1, arg)
            ps.executeQuery().use { rs ->
                var curType: String? = null
                var curId: String? = null
                var fields = LinkedHashMap<String, FieldValue>()
                while (rs.next()) {
                    val t = rs.getString(1)
                    val i = rs.getString(2)
                    if (t != curType || i != curId) {
                        if (curType != null) result += EntityRecord(EntityType.valueOf(curType), curId!!, fields)
                        curType = t; curId = i; fields = LinkedHashMap()
                    }
                    fields[rs.getString(3)] = FieldValue(Json.parseToJsonElement(rs.getString(4)), rs.getString(5))
                }
                if (curType != null) result += EntityRecord(EntityType.valueOf(curType), curId!!, fields)
            }
        }
        return result
    }

    /** Objects whose highest field seq is > [since], ordered by that seq, at most [limit], each with all fields. */
    @Synchronized
    fun pull(since: Long, limit: Int): PullResponse {
        class Head(val type: String, val id: String, val seq: Long)
        val heads = ArrayList<Head>()
        conn.prepareStatement(
            """SELECT type, id, MAX(seq) AS m FROM fields GROUP BY type, id
               HAVING m > ? ORDER BY m LIMIT ?""",
        ).use { ps ->
            ps.setLong(1, since); ps.setInt(2, limit + 1)
            ps.executeQuery().use { rs -> while (rs.next()) heads += Head(rs.getString(1), rs.getString(2), rs.getLong(3)) }
        }
        val hasMore = heads.size > limit
        val page = heads.take(limit)
        val records = page.map { h ->
            val type = EntityType.valueOf(h.type)
            EntityRecord(type, h.id, readFields(type, h.id))
        }
        return PullResponse(records, page.lastOrNull()?.seq ?: since, hasMore, epoch)
    }

    @Synchronized
    fun authorOf(type: EntityType, id: String, field: String): String? =
        conn.prepareStatement("SELECT author FROM fields WHERE type = ? AND id = ? AND field = ?").use { ps ->
            ps.setString(1, type.name); ps.setString(2, id); ps.setString(3, field)
            ps.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    @Synchronized
    fun appendImportLog(entry: ImportLogEntry) {
        conn.prepareStatement(
            """INSERT INTO import_log(started_at, finished_at, ok, created, updated, conflicts, removed, flagged_removed, error)
               VALUES (?,?,?,?,?,?,?,?,?)""",
        ).use { ps ->
            ps.setString(1, entry.startedAt); ps.setString(2, entry.finishedAt); ps.setInt(3, if (entry.ok) 1 else 0)
            ps.setInt(4, entry.created); ps.setInt(5, entry.updated); ps.setInt(6, entry.conflicts)
            ps.setInt(7, entry.removed); ps.setInt(8, entry.flaggedRemoved); ps.setString(9, entry.error)
            ps.executeUpdate()
        }
    }

    @Synchronized
    fun importLog(limit: Int): List<ImportLogEntry> {
        val out = ArrayList<ImportLogEntry>()
        conn.prepareStatement(
            """SELECT started_at, finished_at, ok, created, updated, conflicts, removed, flagged_removed, error
               FROM import_log ORDER BY id DESC LIMIT ?""",
        ).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out += ImportLogEntry(
                        rs.getString(1), rs.getString(2), rs.getInt(3) != 0, rs.getInt(4), rs.getInt(5),
                        rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getString(9),
                    )
                }
            }
        }
        return out
    }
}
