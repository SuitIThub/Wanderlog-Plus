package de.wanderlogplus.server.balances

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.YearMonth

/**
 * Counts the server's own Google API calls per month and kind ("Details", "Textsuche", "Umgebungssuche", "Fotos",
 * "Routen"), kept in [file] so a restart does not lose them. Google offers no balance for an API key, so these
 * counts are what the app can show. A new month starts at zero.
 */
class GoogleUsage(private val file: Path?, private val month: () -> YearMonth = { YearMonth.now() }) {
    @Serializable
    private data class Stored(val month: String = "", val counts: Map<String, Int> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true }
    private val log = LoggerFactory.getLogger(GoogleUsage::class.java)
    private var stored: Stored = load()

    @Synchronized
    fun count(kind: String) {
        val m = month().toString()
        val base = if (stored.month == m) stored.counts else emptyMap()
        stored = Stored(m, base + (kind to (base[kind] ?: 0) + 1))
        save()
    }

    /** This month's calls by kind. */
    @Synchronized
    fun thisMonth(): Map<String, Int> = if (stored.month == month().toString()) stored.counts else emptyMap()

    private fun load(): Stored = try {
        file?.takeIf { Files.exists(it) }?.let { json.decodeFromString(Stored.serializer(), Files.readString(it)) } ?: Stored()
    } catch (e: Exception) {
        Stored()
    }

    private fun save() {
        val f = file ?: return
        try {
            f.parent?.let { Files.createDirectories(it) }
            val tmp = f.resolveSibling(f.fileName.toString() + ".tmp")
            Files.writeString(tmp, json.encodeToString(Stored.serializer(), stored))
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            log.warn("Google usage not saved: {}", e.javaClass.simpleName)
        }
    }
}
