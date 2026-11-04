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
)

/** Answer of `GET /api/vault/uploads/request`: the web overview asked this device to resume its vault upload queue. */
@Serializable
data class VaultUploadTrigger(val requested: Boolean)

/** One image uploaded to the vault; [score] is the classifier's value (0..1, higher = more skin). */
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
)
