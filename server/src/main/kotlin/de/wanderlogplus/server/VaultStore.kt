package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.VaultImageInfo
import de.wanderlogplus.shared.sync.VaultScanStatus
import de.wanderlogplus.shared.sync.VaultScanUpdate
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Per-user vault storage: images at `<dir>/<user>/files/<id>`, metadata in `<dir>/<user>/images.json`,
 * the last reported scan in `<dir>/<user>/scan.json`. Ids are random (never derived from the content).
 * All mutating methods are synchronized; JSON writes go through a temp file plus atomic move.
 */
class VaultStore(private val dir: Path, private val maxBytes: Long) {

    private val json = Json { explicitNulls = false; ignoreUnknownKeys = true }
    private val imagesSerializer = ListSerializer(VaultImageInfo.serializer())

    @Synchronized
    fun saveScan(user: String, update: VaultScanUpdate): VaultScanStatus {
        val status = VaultScanStatus(
            user = user,
            state = update.state,
            totalImages = update.totalImages.coerceAtLeast(0),
            scannedImages = update.scannedImages.coerceAtLeast(0),
            foundImages = update.foundImages.coerceAtLeast(0),
            device = update.device?.take(100),
            updatedAt = System.currentTimeMillis(),
        )
        writeJson(scanFile(user), VaultScanStatus.serializer(), status)
        return status
    }

    @Synchronized
    fun scan(user: String): VaultScanStatus? {
        val f = scanFile(user)
        if (!Files.isRegularFile(f)) return null
        return try {
            json.decodeFromString(VaultScanStatus.serializer(), Files.readString(f))
        } catch (e: Exception) {
            null // a torn/corrupt file is treated like no report
        }
    }

    /**
     * Streams the image to disk (throwing [FileTooLarge] past [maxBytes]), then appends its metadata.
     * Idempotent per user: content whose SHA-256 is already stored returns the existing entry instead of a
     * duplicate, so a retried upload (e.g. the client timed out after the file was stored) stays harmless.
     */
    @Synchronized
    fun addImage(user: String, input: InputStream, fileName: String, mimeType: String, score: Double): VaultImageInfo {
        val files = filesDir(user)
        Files.createDirectories(files)
        val tmp = Files.createTempFile(files, "upload-", ".tmp")
        val size: Long
        val hash: String
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            size = Files.newOutputStream(tmp).use { out ->
                var n = 0L
                val buf = ByteArray(8192)
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    n += r
                    if (n > maxBytes) throw FileTooLarge()
                    digest.update(buf, 0, r)
                    out.write(buf, 0, r)
                }
                n
            }
            hash = digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            // Kept when the move below succeeds? No: tmp is either moved or deleted here on failure paths.
        }
        val existing = ensureHashes(user).firstOrNull { it.hash == hash }
        if (existing != null) {
            Files.deleteIfExists(tmp)
            return existing
        }
        val info = VaultImageInfo(
            id = UUID.randomUUID().toString().replace("-", ""),
            fileName = fileName.take(200).ifBlank { "image" },
            mimeType = mimeType.take(100),
            size = size,
            score = score.coerceIn(0.0, 1.0),
            uploadedAt = System.currentTimeMillis(),
            hash = hash,
        )
        return try {
            Files.move(tmp, files.resolve(info.id), StandardCopyOption.ATOMIC_MOVE)
            val all = images(user) + info
            writeJson(imagesFile(user), imagesSerializer, all)
            info
        } catch (e: Exception) {
            Files.deleteIfExists(tmp)
            throw e
        }
    }

    /**
     * The image with this content hash (lowercase SHA-256 hex), when the user's vault has it. Lets clients
     * skip re-sending bytes the server already stored (e.g. an upload whose response never arrived).
     */
    fun imageByHash(user: String, hash: String): VaultImageInfo? = ensureHashes(user).firstOrNull { it.hash == hash }

    /**
     * The image list with every entry carrying its content hash; entries stored before hashes existed are
     * backfilled once and persisted (entries whose file vanished keep a null hash and never dedup-match).
     */
    private fun ensureHashes(user: String): List<VaultImageInfo> {
        val all = images(user)
        if (all.all { it.hash != null }) return all
        val enriched = all.map { info ->
            if (info.hash != null) info else info.copy(hash = hashFile(filesDir(user).resolve(info.id)))
        }
        writeJson(imagesFile(user), imagesSerializer, enriched)
        return enriched
    }

    private fun hashFile(p: Path): String? {
        if (!Files.isRegularFile(p)) return null
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(p).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                digest.update(buf, 0, r)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun images(user: String): List<VaultImageInfo> {
        val f = imagesFile(user)
        if (!Files.isRegularFile(f)) return emptyList()
        return try {
            json.decodeFromString(imagesSerializer, Files.readString(f))
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** The web overview's "Upload fortsetzen" flag: the device picks it up on its next sync (there is no push channel). */
    @Synchronized
    fun requestUpload(user: String) {
        val f = uploadRequestFile(user)
        Files.createDirectories(f.parent)
        Files.writeString(f, "1")
    }

    /** Peek without consuming; the device clears explicitly after scheduling the upload pass (at-least-once). */
    @Synchronized
    fun uploadRequested(user: String): Boolean = Files.isRegularFile(uploadRequestFile(user))

    @Synchronized
    fun clearUploadRequest(user: String) {
        Files.deleteIfExists(uploadRequestFile(user))
    }

    /** Path of the image, or null when [id] is malformed or unknown (never touches the FS for malformed ids). */
    fun imagePath(user: String, id: String): Path? {
        if (!ID.matches(id)) return null
        val p = filesDir(user).resolve(id)
        return if (Files.isRegularFile(p)) p else null
    }

    fun mimeTypeOf(user: String, id: String): String? = images(user).firstOrNull { it.id == id }?.mimeType

    @Synchronized
    fun deleteImage(user: String, id: String): Boolean {
        if (!ID.matches(id)) return false
        val all = images(user)
        val remaining = all.filterNot { it.id == id }
        if (remaining.size == all.size) return false
        writeJson(imagesFile(user), imagesSerializer, remaining)
        Files.deleteIfExists(filesDir(user).resolve(id))
        return true
    }

    private fun userDir(user: String): Path {
        require(USER.matches(user)) { "invalid user name" }
        return dir.resolve(user)
    }

    private fun filesDir(user: String): Path = userDir(user).resolve("files")
    private fun imagesFile(user: String): Path = userDir(user).resolve("images.json")
    private fun scanFile(user: String): Path = userDir(user).resolve("scan.json")
    private fun uploadRequestFile(user: String): Path = userDir(user).resolve("upload-request")

    private fun <T> writeJson(file: Path, serializer: kotlinx.serialization.SerializationStrategy<T>, value: T) {
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, json.encodeToString(serializer, value))
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private val ID = Regex("^[0-9a-f]{32}$")
        private val USER = Regex("^[A-Za-z0-9._-]{1,64}$")
        fun isValidId(id: String) = ID.matches(id)
        fun forConfig(config: ServerConfig) = VaultStore(config.dataDir.resolve("vault"), config.maxFileBytes)
    }
}
