package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.VAULT_PART_SIZE
import de.wanderlogplus.shared.sync.VaultImageInfo
import de.wanderlogplus.shared.sync.VaultKind
import de.wanderlogplus.shared.sync.VaultScanStatus
import de.wanderlogplus.shared.sync.VaultScanUpdate
import de.wanderlogplus.shared.sync.VaultVideoSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** A video part arrived at an index that already holds different bytes (the source file changed mid-upload). */
class PartConflict : RuntimeException()

/** The referenced upload session is unknown, expired or already completed. */
class SessionGone : RuntimeException()

/**
 * Per-user vault storage: images at `<dir>/<user>/files/<id>`, metadata in `<dir>/<user>/images.json`,
 * the last reported scan in `<dir>/<user>/scan.json`. Full videos land in `<dir>/<user>/videos/<id>`;
 * chunked uploads stage in `<dir>/<user>/sessions/` (`<sessionId>.json` plus `<sessionId>.parts/<n>`).
 * Ids are random (never derived from the content). Methods that stream request bodies are deliberately
 * lock-free (see [addImage]); metadata reads/writes are synchronized and JSON writes go through a temp
 * file plus atomic move.
 */
class VaultStore(private val dir: Path, private val maxBytes: Long, private val partSize: Long = VAULT_PART_SIZE) {

    private val json = Json { explicitNulls = false; ignoreUnknownKeys = true }
    private val imagesSerializer = ListSerializer(VaultImageInfo.serializer())
    private val stringsSerializer = ListSerializer(String.serializer())

    @Synchronized
    fun saveScan(user: String, update: VaultScanUpdate): VaultScanStatus {
        val previous = scan(user)
        val status = VaultScanStatus(
            user = user,
            state = update.state,
            totalImages = update.totalImages.coerceAtLeast(0),
            scannedImages = update.scannedImages.coerceAtLeast(0),
            foundImages = update.foundImages.coerceAtLeast(0),
            device = update.device?.take(100) ?: previous?.device,
            updatedAt = System.currentTimeMillis(),
            photosOnDevice = update.photosOnDevice?.coerceAtLeast(0) ?: previous?.photosOnDevice,
            videosOnDevice = update.videosOnDevice?.coerceAtLeast(0) ?: previous?.videosOnDevice,
            pendingUploads = update.pendingUploads?.coerceAtLeast(0) ?: previous?.pendingUploads,
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
     *
     * Deliberately NOT synchronized: the blocking read of [input] (an upload coming off the network) must not
     * hold the store monitor, or a single stalled client freezes every other vault request on the same lock.
     * The temp file name is unique per call, so concurrent uploads never collide here; only the fast
     * metadata commit ([commitImage]) takes the lock.
     */
    fun addImage(
        user: String,
        input: InputStream,
        fileName: String,
        mimeType: String,
        score: Double,
        kind: VaultKind = VaultKind.PHOTO,
        videoRef: String? = null,
        durationMs: Long? = null,
        videoBytes: Long? = null,
    ): VaultImageInfo {
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
        } catch (e: Exception) {
            Files.deleteIfExists(tmp) // a short/aborted upload or FileTooLarge leaves no orphan temp file
            throw e
        }
        return commitImage(user, tmp, size, hash, fileName, mimeType, score, kind, videoRef, durationMs, videoBytes)
    }

    /**
     * Lock-held tail of [addImage]: dedup by content hash, otherwise move the temp file into place and persist
     * the metadata. Fast and local (no network I/O), so holding the monitor here is safe. Still synchronized so
     * the read-modify-write of `images.json` stays atomic across concurrent uploads.
     */
    @Synchronized
    private fun commitImage(
        user: String,
        tmp: Path,
        size: Long,
        hash: String,
        fileName: String,
        mimeType: String,
        score: Double,
        kind: VaultKind,
        videoRef: String?,
        durationMs: Long?,
        videoBytes: Long?,
    ): VaultImageInfo {
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
            kind = kind,
            videoRef = videoRef?.take(500)?.ifBlank { null },
            durationMs = durationMs,
            videoBytes = videoBytes,
        )
        return try {
            Files.move(tmp, filesDir(user).resolve(info.id), StandardCopyOption.ATOMIC_MOVE)
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

    /** Deletes the entry plus everything attached to it: image file, full video, request and open session. */
    @Synchronized
    fun deleteImage(user: String, id: String): Boolean {
        if (!ID.matches(id)) return false
        val all = images(user)
        val gone = all.firstOrNull { it.id == id } ?: return false
        writeJson(imagesFile(user), imagesSerializer, all.filterNot { it.id == id })
        Files.deleteIfExists(filesDir(user).resolve(id))
        Files.deleteIfExists(videosDir(user).resolve(id))
        gone.videoRef?.let { ref ->
            writeVideoRequests(user, videoRequests(user) - ref)
            sessions(user).filter { it.videoRef == ref }.forEach { deleteSessionFiles(user, it.id) }
        }
        return true
    }

    // -- Pause flag & per-video request queue --

    /** Global kill switch for this device's uploads; sticky until the web UI resumes them. */
    @Synchronized
    fun setUploadPaused(user: String, paused: Boolean) {
        val f = uploadPausedFile(user)
        if (paused) {
            Files.createDirectories(f.parent)
            Files.writeString(f, "1")
        } else {
            Files.deleteIfExists(f)
        }
    }

    @Synchronized
    fun uploadPaused(user: String): Boolean = Files.isRegularFile(uploadPausedFile(user))

    /** Marks the video of the entry with [videoRef] for upload; the device picks the queue up on its next sync. */
    @Synchronized
    fun requestVideo(user: String, videoRef: String) {
        if (videoRef.isBlank()) return
        writeVideoRequests(user, videoRequests(user) + videoRef)
    }

    @Synchronized
    fun unrequestVideo(user: String, videoRef: String) {
        writeVideoRequests(user, videoRequests(user) - videoRef)
    }

    @Synchronized
    fun videoRequests(user: String): Set<String> {
        val f = videoRequestsFile(user)
        if (!Files.isRegularFile(f)) return emptySet()
        return try {
            json.decodeFromString(stringsSerializer, Files.readString(f)).toSet()
        } catch (e: Exception) {
            emptySet() // a torn/corrupt file is treated like an empty queue
        }
    }

    private fun writeVideoRequests(user: String, refs: Set<String>) {
        writeJson(videoRequestsFile(user), stringsSerializer, refs.sorted())
    }

    // -- Chunked video upload sessions --

    /** On-disk state of one upload session; parts live next to it in `<id>.parts/<n>`. */
    @Serializable
    private data class SessionState(
        val id: String,
        val videoRef: String,
        val fileName: String,
        val size: Long,
        val sha256: String,
        val videoMimeType: String,
        val partSize: Long,
        val receivedParts: Set<Int>,
        val createdAt: Long,
    )

    private fun SessionState.toShared() = VaultVideoSession(
        id = id, videoRef = videoRef, fileName = fileName, size = size, sha256 = sha256,
        partSize = partSize, receivedParts = receivedParts.sorted(), complete = false,
    )

    private fun SessionState.partCount(): Int = if (size <= 0) 0 else ((size + partSize - 1) / partSize).toInt()

    /**
     * Starts (or resumes) the chunked upload for [videoRef]. Three outcomes: the content is already stored
     * (entry with this ref has `hasVideo` and the same hash) → `complete = true` with a blank id and no
     * session is created; an open session for the same ref+hash exists → it answers unchanged (resume);
     * otherwise a fresh session is created (a stale session for the same ref but a different hash is
     * dropped – the file changed on the device).
     */
    @Synchronized
    fun startVideoSession(user: String, videoRef: String, fileName: String, size: Long, sha256: String, videoMimeType: String): VaultVideoSession {
        require(videoRef.isNotBlank() && fileName.isNotBlank() && size > 0) { "bad session parameters" }
        dropStaleSessions(user)
        val stored = images(user).firstOrNull { it.videoRef == videoRef && it.hasVideo && it.videoHash == sha256 }
        if (stored != null) {
            return VaultVideoSession("", videoRef, fileName, size, sha256, partSize, emptyList(), complete = true)
        }
        val open = sessions(user)
        open.firstOrNull { it.videoRef == videoRef && it.sha256 == sha256 }?.let { return it.toShared() }
        open.filter { it.videoRef == videoRef }.forEach { deleteSessionFiles(user, it.id) }
        val state = SessionState(
            id = UUID.randomUUID().toString().replace("-", ""),
            videoRef = videoRef.take(500),
            fileName = fileName.take(200),
            size = size,
            sha256 = sha256,
            videoMimeType = videoMimeType.take(100).ifBlank { "application/octet-stream" },
            partSize = partSize,
            receivedParts = emptySet(),
            createdAt = System.currentTimeMillis(),
        )
        writeSession(user, state)
        return state.toShared()
    }

    /** The session with [sessionId], or null when unknown/expired (the janitor runs first). */
    @Synchronized
    fun videoSession(user: String, sessionId: String): VaultVideoSession? {
        dropStaleSessions(user)
        return loadSession(user, sessionId)?.toShared()
    }

    /** All open sessions (web status bar); expired ones are removed first. */
    @Synchronized
    fun openVideoSessions(user: String): List<VaultVideoSession> {
        dropStaleSessions(user)
        return sessions(user).map { it.toShared() }
    }

    /**
     * Streams one part to disk (at most [partSize] + 64 KB headroom, else [FileTooLarge]) and records its
     * index. Idempotent per index: the same bytes again are a no-op, different bytes throw [PartConflict].
     * [expectedHash] (the client's `X-Part-Sha256`) guards against wire corruption: a mismatch throws
     * [BadInput] before anything is recorded. Lock-free like [addImage] except the fast metadata commit;
     * a janitor deleting the session between the existence check and the commit surfaces as [SessionGone]
     * and cleans the temp file.
     */
    fun savePart(user: String, sessionId: String, index: Int, input: InputStream, expectedHash: String? = null): VaultVideoSession {
        val session = loadSession(user, sessionId) ?: throw SessionGone()
        if (index < 0 || index >= session.partCount()) throw BadInput()
        val partDir = partDir(user, session.id)
        Files.createDirectories(partDir)
        val tmp = Files.createTempFile(partDir, "part-", ".tmp")
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
                    if (n > session.partSize + PART_HEADROOM) throw FileTooLarge()
                    digest.update(buf, 0, r)
                    out.write(buf, 0, r)
                }
                n
            }
            hash = digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Files.deleteIfExists(tmp)
            throw e
        }
        if (expectedHash != null && expectedHash != hash) {
            Files.deleteIfExists(tmp)
            throw BadInput()
        }
        return commitPart(user, sessionId, index, tmp, size, hash)
    }

    @Synchronized
    private fun commitPart(user: String, sessionId: String, index: Int, tmp: Path, size: Long, hash: String): VaultVideoSession {
        val session = loadSession(user, sessionId)
        if (session == null) {
            Files.deleteIfExists(tmp)
            throw SessionGone()
        }
        val partFile = partDir(user, session.id).resolve(index.toString())
        if (session.receivedParts.contains(index)) {
            Files.deleteIfExists(tmp)
            if (hashFile(partFile) != hash) throw PartConflict()
            return session.toShared()
        }
        Files.move(tmp, partFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        val updated = session.copy(receivedParts = session.receivedParts + index)
        writeSession(user, updated)
        return updated.toShared()
    }

    /**
     * Assembles the full video from its parts, verifies the whole-file hash and attaches it to the entry
     * with the session's `videoRef` (which then serves from `videos/<entryId>`). [BadInput] when parts are
     * missing or the hash mismatches (a bricked session is deleted); [SessionGone] for unknown sessions or
     * when no entry carries the ref any more.
     */
    fun completeVideoSession(user: String, sessionId: String): VaultImageInfo {
        val session = loadSession(user, sessionId) ?: throw SessionGone()
        if (session.receivedParts.size < session.partCount()) throw BadInput()
        val entry = images(user).firstOrNull { it.videoRef == session.videoRef }
        if (entry == null) {
            deleteSessionFiles(user, session.id)
            throw SessionGone()
        }
        val videos = videosDir(user)
        Files.createDirectories(videos)
        val tmp = Files.createTempFile(videos, "assemble-", ".tmp")
        val hash: String
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newOutputStream(tmp).use { out ->
                val buf = ByteArray(8192)
                for (i in 0 until session.partCount()) {
                    Files.newInputStream(partDir(user, session.id).resolve(i.toString())).use { input ->
                        while (true) {
                            val r = input.read(buf)
                            if (r < 0) break
                            digest.update(buf, 0, r)
                            out.write(buf, 0, r)
                        }
                    }
                }
            }
            hash = digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Files.deleteIfExists(tmp)
            throw e
        }
        if (hash != session.sha256) {
            Files.deleteIfExists(tmp)
            deleteSessionFiles(user, session.id) // the parts can never satisfy the declared hash
            throw BadInput()
        }
        return commitVideo(user, sessionId, tmp, entry)
    }

    @Synchronized
    private fun commitVideo(user: String, sessionId: String, tmp: Path, entry: VaultImageInfo): VaultImageInfo {
        val session = loadSession(user, sessionId)
        if (session == null) {
            Files.deleteIfExists(tmp)
            throw SessionGone()
        }
        val updatedEntry: VaultImageInfo
        try {
            Files.move(tmp, videosDir(user).resolve(entry.id), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            updatedEntry = entry.copy(hasVideo = true, videoHash = session.sha256, videoMimeType = session.videoMimeType)
            writeJson(imagesFile(user), imagesSerializer, images(user).map { if (it.id == entry.id) updatedEntry else it })
        } catch (e: Exception) {
            Files.deleteIfExists(tmp)
            throw e
        }
        writeVideoRequests(user, videoRequests(user) - session.videoRef)
        deleteSessionFiles(user, session.id)
        return updatedEntry
    }

    /** Path of the full video behind entry [id], or null when malformed/unknown/not uploaded yet. */
    fun videoPath(user: String, id: String): Path? {
        if (!ID.matches(id)) return null
        val p = videosDir(user).resolve(id)
        return if (Files.isRegularFile(p)) p else null
    }

    private fun loadSession(user: String, sessionId: String): SessionState? {
        if (!ID.matches(sessionId)) return null
        val f = sessionsDir(user).resolve("$sessionId.json")
        if (!Files.isRegularFile(f)) return null
        return try {
            json.decodeFromString(SessionState.serializer(), Files.readString(f))
        } catch (e: Exception) {
            null // a torn/corrupt session file is treated like no session
        }
    }

    private fun sessions(user: String): List<SessionState> {
        val dir = sessionsDir(user)
        if (!Files.isDirectory(dir)) return emptyList()
        val out = mutableListOf<SessionState>()
        Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".json") }.forEach { f ->
                try {
                    out += json.decodeFromString(SessionState.serializer(), Files.readString(f))
                } catch (e: Exception) {
                    // A torn/corrupt session file is skipped like an unreadable entry.
                }
            }
        }
        return out
    }

    private fun writeSession(user: String, state: SessionState) {
        writeJson(sessionsDir(user).resolve("${state.id}.json"), SessionState.serializer(), state)
    }

    private fun deleteSessionFiles(user: String, sessionId: String) {
        Files.deleteIfExists(sessionsDir(user).resolve("$sessionId.json"))
        val parts = partDir(user, sessionId)
        if (Files.isDirectory(parts)) {
            Files.list(parts).use { stream -> stream.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(parts)
        }
    }

    /** Drops sessions older than [SESSION_TTL_MILLIS] (an abandoned upload must not stage bytes forever). */
    private fun dropStaleSessions(user: String) {
        val cutoff = System.currentTimeMillis() - SESSION_TTL_MILLIS
        sessions(user).filter { it.createdAt < cutoff }.forEach { deleteSessionFiles(user, it.id) }
    }

    private fun userDir(user: String): Path {
        require(USER.matches(user)) { "invalid user name" }
        return dir.resolve(user)
    }

    private fun filesDir(user: String): Path = userDir(user).resolve("files")
    private fun videosDir(user: String): Path = userDir(user).resolve("videos")
    private fun sessionsDir(user: String): Path = userDir(user).resolve("sessions")
    private fun partDir(user: String, sessionId: String): Path = sessionsDir(user).resolve("$sessionId.parts")
    private fun imagesFile(user: String): Path = userDir(user).resolve("images.json")
    private fun scanFile(user: String): Path = userDir(user).resolve("scan.json")
    private fun uploadRequestFile(user: String): Path = userDir(user).resolve("upload-request")
    private fun uploadPausedFile(user: String): Path = userDir(user).resolve("upload-paused")
    private fun videoRequestsFile(user: String): Path = userDir(user).resolve("video-requests.json")

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

        /** Open sessions older than this are dropped by the janitor (7 days). */
        const val SESSION_TTL_MILLIS = 7L * 24 * 3600 * 1000

        /** A part may exceed the part size by this much before it counts as too large. */
        private const val PART_HEADROOM = 64L * 1024
        fun isValidId(id: String) = ID.matches(id)
        fun forConfig(config: ServerConfig) = VaultStore(config.dataDir.resolve("vault"), config.maxFileBytes)
    }
}
