package de.wanderlogplus.shared.sync

import kotlinx.serialization.Serializable

/** Scan states reported by the app; stored and shown as-is (validated on the server). */
object VaultScanStates {
    const val RUNNING = "running"
    const val DONE = "done"
    const val FAILED = "failed"
    val ALL = setOf(RUNNING, DONE, FAILED)
}

/** Body of `POST /api/vault/scan`: a device reports its media-scan progress (best effort, also skipped when offline). */
@Serializable
data class VaultScanUpdate(
    val state: String,
    val totalImages: Int,
    val scannedImages: Int,
    val foundImages: Int,
    /** Free-form device label (e.g. "Google Pixel 8"), shown in the web overview. */
    val device: String? = null,
    /** Photos MediaStore exposes (null when an old app did not report it). */
    val photosOnDevice: Int? = null,
    /** Videos MediaStore exposes (null when an old app did not report it). */
    val videosOnDevice: Int? = null,
    /** Queue entries still waiting for upload when the report was sent. */
    val pendingUploads: Int? = null,
)

/** The server's view of a device's last reported scan; [updatedAt] is server time (epoch millis). */
@Serializable
data class VaultScanStatus(
    val user: String,
    val state: String,
    val totalImages: Int,
    val scannedImages: Int,
    val foundImages: Int,
    val device: String? = null,
    val updatedAt: Long,
    /** See [VaultScanUpdate.photosOnDevice]. */
    val photosOnDevice: Int? = null,
    /** See [VaultScanUpdate.videosOnDevice]. */
    val videosOnDevice: Int? = null,
    /** See [VaultScanUpdate.pendingUploads]. */
    val pendingUploads: Int? = null,
)

/**
 * Answer of `GET /api/vault/uploads/request`: the web overview asked this device to resume its vault upload
 * queue ([requested]), uploads are paused until manually resumed ([paused]), and these vault entries
 * ([videoRefs], the entries' `videoRef` values) should have their full video uploaded.
 */
@Serializable
data class VaultUploadTrigger(
    val requested: Boolean,
    val paused: Boolean = false,
    val videoRefs: List<String> = emptyList(),
)

/** What a vault entry represents: a plain photo backup, or the thumbnail of a device video. */
enum class VaultKind { PHOTO, VIDEO }

/** Size of one video part in bytes (8 MB); shared by the server store, the routes and the app's part loop. */
const val VAULT_PART_SIZE = 8L * 1024 * 1024

/**
 * State of one chunked video upload. [id] is blank when the answer only signals `complete = true`
 * (the content was already stored, no session was created). [receivedParts] lists the part indices the
 * server holds, so a resuming client skips them.
 */
@Serializable
data class VaultVideoSession(
    val id: String,
    val videoRef: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
    val partSize: Long,
    val receivedParts: List<Int>,
    val complete: Boolean,
)

/**
 * One media item in the vault; [score] is a legacy classifier value (0 for plain backups). A [VaultKind.VIDEO]
 * entry's stored file is the auto-uploaded thumbnail; the full video arrives chunked via a [VaultVideoSession]
 * once requested, then [hasVideo] flips and `GET /vault/vid/{id}` serves it.
 */
@Serializable
data class VaultImageInfo(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val score: Double,
    val uploadedAt: Long,
    /** SHA-256 of the content for idempotent re-uploads; null on entries stored before hashes existed. */
    val hash: String? = null,
    val kind: VaultKind = VaultKind.PHOTO,
    /** The device's MediaStore uri for videos; links the entry to its upload request and session. */
    val videoRef: String? = null,
    val durationMs: Long? = null,
    /** Size of the full video as last seen on the device (display only; upload sessions declare their own size). */
    val videoBytes: Long? = null,
    /** True once the full video was uploaded and is streamable. */
    val hasVideo: Boolean = false,
    /** SHA-256 of the full video (set with [hasVideo]). */
    val videoHash: String? = null,
    val videoMimeType: String? = null,
)
