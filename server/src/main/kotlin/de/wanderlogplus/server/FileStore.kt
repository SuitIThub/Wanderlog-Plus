package de.wanderlogplus.server

import kotlinx.serialization.Serializable
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** An upload exceeded the configured size limit. Fixed message, no request data. */
class FileTooLarge : RuntimeException("file too large")

@Serializable
data class StoredFile(val hash: String, val size: Long, val duplicate: Boolean)

/** Content-addressed file storage: files live at `<dir>/<sha256 hex>`. */
class FileStore(private val dir: Path, private val maxBytes: Long) {
    init {
        Files.createDirectories(dir)
    }

    fun save(input: InputStream): StoredFile {
        val tmp = Files.createTempFile(dir, "upload-", ".tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            Files.newOutputStream(tmp).use { out ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    size += n
                    if (size > maxBytes) throw FileTooLarge()
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val target = dir.resolve(hash)
            if (Files.exists(target)) {
                Files.deleteIfExists(tmp)
                return StoredFile(hash, size, duplicate = true)
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
            return StoredFile(hash, size, duplicate = false)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** Path of the stored file, or null if the hash is malformed or unknown (never touches the FS for malformed hashes). */
    fun open(hash: String): Path? {
        if (!isValidHash(hash)) return null
        val p = dir.resolve(hash)
        return if (Files.isRegularFile(p)) p else null
    }

    companion object {
        private val HASH = Regex("^[0-9a-f]{64}$")
        fun isValidHash(hash: String) = HASH.matches(hash)
        fun forConfig(config: ServerConfig) = FileStore(config.dataDir.resolve("files"), config.maxFileBytes)
    }
}
