package de.wanderlogplus.app.files

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024

class AttachmentTooLargeException : Exception("Datei ist zu groß")

/** Content-addressed attachment files: each file lives at `<dir>/<sha256 hex>`. */
class AttachmentFiles(private val dir: File) {
    data class Imported(val hash: String, val size: Long)

    private val _version = MutableStateFlow(0)

    /** Counts up whenever a file is added, so observers can re-check availability. */
    val version: StateFlow<Int> = _version

    fun fileFor(hash: String): File? {
        if (!HASH.matches(hash)) return null
        return File(dir, hash).takeIf { it.isFile }
    }

    /** Destination path, also when the file does not exist yet. */
    fun target(hash: String): File {
        require(HASH.matches(hash)) { "invalid hash" }
        return File(dir, hash)
    }

    /** Called after a file was placed at [target] by someone else (e.g. a download). */
    fun notifyAdded() {
        _version.update { it + 1 }
    }

    fun importStream(input: InputStream, maxBytes: Long = MAX_ATTACHMENT_BYTES): Imported {
        dir.mkdirs()
        val tmp = File(dir, "tmp-${UUID.randomUUID()}")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            tmp.outputStream().use { out ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    size += n
                    if (size > maxBytes) throw AttachmentTooLargeException()
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val dest = File(dir, hash)
            if (dest.isFile) {
                tmp.delete()
            } else if (!tmp.renameTo(dest)) {
                throw java.io.IOException("rename failed")
            }
            notifyAdded()
            return Imported(hash, size)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private companion object {
        val HASH = Regex("[0-9a-f]{64}")
    }
}
