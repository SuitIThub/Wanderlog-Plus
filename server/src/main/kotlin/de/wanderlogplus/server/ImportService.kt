package de.wanderlogplus.server

import de.wanderlogplus.importer.ImportFormatException
import de.wanderlogplus.importer.ImportResult
import de.wanderlogplus.importer.ImportRunner
import de.wanderlogplus.importer.WanderlogClient
import de.wanderlogplus.shared.sync.HlcClock
import java.nio.file.Files
import java.time.Instant

/** Supplies the raw Wanderlog trip JSON; blocking. */
fun interface TripSource {
    fun fetch(): String
}

/** No import source (neither trip key nor import file) is configured. */
class ImportNotConfigured : RuntimeException("no import source configured")

/** Import failed; [message] is structural text only (no booking values). */
class ImportFailed(message: String) : RuntimeException(message)

fun tripSourceFrom(config: ServerConfig): TripSource? {
    config.importFile?.let { file -> return TripSource { Files.readString(file) } }
    config.tripKey?.let { key -> return TripSource { WanderlogClient().fetch(key) } }
    return null
}

class ImportService(
    private val store: SqliteStore,
    private val sync: SyncService,
    private val source: TripSource?,
    private val now: () -> Instant = Instant::now,
) {
    val configured: Boolean get() = source != null

    /** Fetches outside the write lock, then parses, merges and applies atomically inside it. Always logs the outcome. */
    fun runImport(): ImportLogEntry {
        val src = source ?: throw ImportNotConfigured()
        val started = now()
        val entry = try {
            val json = src.fetch()
            val stats = sync.withWriteLock {
                store.transaction("importer") { runMerge(json) }
            }.stats
            ImportLogEntry(
                started.toString(), now().toString(), true,
                stats.created, stats.updated, stats.conflicts, stats.removed, stats.flaggedRemoved,
            )
        } catch (e: ImportFormatException) {
            failed(started, e.message ?: "ImportFormatException")
        } catch (e: Exception) {
            // Only the class name: other exceptions may carry values.
            failed(started, e.javaClass.simpleName)
        }
        sync.withWriteLock { store.appendImportLog(entry) }
        return entry
    }

    private fun runMerge(json: String): ImportResult = ImportRunner.run(json, store, HlcClock("importer"))

    private fun failed(started: Instant, error: String) =
        ImportLogEntry(started.toString(), now().toString(), false, error = error)
}
