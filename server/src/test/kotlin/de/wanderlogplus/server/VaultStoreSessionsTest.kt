package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.VaultKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Video upload sessions, the per-video request queue and the pause flag of [VaultStore]. */
class VaultStoreSessionsTest {
    @TempDir
    lateinit var dir: Path

    private fun store(partSize: Long = 4) = VaultStore(dir, maxBytes = 25L * 1024 * 1024, partSize = partSize)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Seeds a VIDEO entry (the auto-uploaded thumbnail) and returns its entry id. */
    private fun seedVideoEntry(store: VaultStore, ref: String, user: String = "alice"): String =
        store.addImage(
            user, byteArrayOf(1, 2, 3).inputStream(), "clip.jpg", "image/jpeg", 0.0,
            kind = VaultKind.VIDEO, videoRef = ref, durationMs = 6500, videoBytes = 11,
        ).id

    @Test
    fun sessionLifecycleResumeAndComplete() {
        val store = store()
        val ref = "content://media/external/video/1"
        val entryId = seedVideoEntry(store, ref)
        store.requestVideo("alice", ref)

        // 11 bytes at partSize 4 → parts 0,1,2 (4+4+3).
        val content = byteArrayOf(10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20)
        val hash = sha256Hex(content)
        val session = store.startVideoSession("alice", ref, "clip.mp4", content.size.toLong(), hash, "video/mp4")
        assertFalse(session.complete)
        assertTrue(session.id.isNotBlank())

        store.savePart("alice", session.id, 0, content.inputStream(0, 4))
        store.savePart("alice", session.id, 2, content.inputStream(8, 3))
        assertEquals(listOf(0, 2), store.videoSession("alice", session.id)?.receivedParts)

        // Re-starting with the same key resumes the same session instead of starting over.
        val resumed = store.startVideoSession("alice", ref, "clip.mp4", content.size.toLong(), hash, "video/mp4")
        assertEquals(session.id, resumed.id)
        assertEquals(listOf(0, 2), resumed.receivedParts)

        store.savePart("alice", session.id, 1, content.inputStream(4, 4))
        val done = store.completeVideoSession("alice", session.id)
        assertTrue(done.hasVideo)
        assertEquals(hash, done.videoHash)
        assertEquals("video/mp4", done.videoMimeType)
        assertEquals(entryId, done.id)

        // The request left the queue and the video bytes serve as the three parts concatenated.
        assertTrue(store.videoRequests("alice").isEmpty())
        assertNull(store.videoSession("alice", session.id))
        assertContentEquals(content, Files.readAllBytes(store.videoPath("alice", entryId)!!))
    }

    @Test
    fun saveScanCarriesDeviceStats() {
        val store = store()
        store.saveScan(
            "alice",
            de.wanderlogplus.shared.sync.VaultScanUpdate(
                state = "done", totalImages = 3, scannedImages = 3, foundImages = 2,
                photosOnDevice = 100, videosOnDevice = 7, pendingUploads = 42,
            ),
        )
        val scan = store.scan("alice")
        assertNotNull(scan)
        assertEquals(100, scan.photosOnDevice)
        assertEquals(7, scan.videosOnDevice)
        assertEquals(42, scan.pendingUploads)
    }

    @Test
    fun savePartIsIdempotentAndConflicts() {
        val store = store()
        val ref = "r"
        seedVideoEntry(store, ref)
        val session = store.startVideoSession("alice", ref, "v.mp4", 8, sha256Hex(ByteArray(8)), "video/mp4")

        val part = byteArrayOf(1, 2, 3, 4)
        store.savePart("alice", session.id, 0, part.inputStream())
        // Same content twice at the same index is a harmless retry.
        store.savePart("alice", session.id, 0, part.inputStream())
        assertEquals(listOf(0), store.videoSession("alice", session.id)?.receivedParts)
        // Different content at a taken index means the file changed mid-upload: refuse loudly.
        assertFailsWith<PartConflict> {
            store.savePart("alice", session.id, 0, byteArrayOf(9, 9, 9, 9).inputStream())
        }
        // Unknown session ids are gone sessions (janitor or never existed).
        assertFailsWith<SessionGone> {
            store.savePart("alice", "0".repeat(32), 0, part.inputStream())
        }
    }

    @Test
    fun completeWithMissingPartThrows() {
        val store = store()
        val ref = "r"
        seedVideoEntry(store, ref)
        val content = ByteArray(8) { it.toByte() }
        val session = store.startVideoSession("alice", ref, "v.mp4", content.size.toLong(), sha256Hex(content), "video/mp4")
        store.savePart("alice", session.id, 0, content.inputStream(0, 4))
        assertFailsWith<BadInput> { store.completeVideoSession("alice", session.id) }
        assertFailsWith<SessionGone> { store.completeVideoSession("alice", "0".repeat(32)) }
    }

    @Test
    fun completeWithHashMismatchThrows() {
        val store = store()
        val ref = "r"
        seedVideoEntry(store, ref)
        val content = ByteArray(8) { it.toByte() }
        // The declared hash belongs to different content: assembly must fail verification.
        val session = store.startVideoSession("alice", ref, "v.mp4", content.size.toLong(), sha256Hex(ByteArray(8) { 99 }), "video/mp4")
        store.savePart("alice", session.id, 0, content.inputStream(0, 4))
        store.savePart("alice", session.id, 1, content.inputStream(4, 4))
        assertFailsWith<BadInput> { store.completeVideoSession("alice", session.id) }
        // A bricked session (its parts can never satisfy the hash) is removed, not kept forever.
        assertNull(store.videoSession("alice", session.id))
        assertTrue(store.openVideoSessions("alice").isEmpty())
    }

    @Test
    fun startSessionDeduplicatesByVideoHash() {
        val store = store()
        val ref = "r"
        seedVideoEntry(store, ref)
        val content = ByteArray(4) { 5 }
        val hash = sha256Hex(content)
        val first = store.startVideoSession("alice", ref, "v.mp4", content.size.toLong(), hash, "video/mp4")
        store.savePart("alice", first.id, 0, content.inputStream())
        store.completeVideoSession("alice", first.id)

        // The full video with this hash is already stored: no new session, just "complete".
        val again = store.startVideoSession("alice", ref, "v.mp4", content.size.toLong(), hash, "video/mp4")
        assertTrue(again.complete)
        assertEquals("", again.id)
        assertTrue(store.openVideoSessions("alice").isEmpty())
    }

    @Test
    fun janitorDropsStaleSessions() {
        val store = store()
        val sessionId = "a".repeat(32)
        val sessionsDir = dir.resolve("alice").resolve("sessions")
        Files.createDirectories(sessionsDir)
        val stale = System.currentTimeMillis() - 8L * 24 * 3600 * 1000
        Files.writeString(
            sessionsDir.resolve("$sessionId.json"),
            """{"id":"$sessionId","videoRef":"r","fileName":"v.mp4","size":8,"sha256":"${"0".repeat(64)}","videoMimeType":"video/mp4","partSize":4,"receivedParts":[0],"createdAt":$stale}""",
        )
        val partDir = sessionsDir.resolve("$sessionId.parts")
        Files.createDirectories(partDir)
        Files.write(partDir.resolve("0"), byteArrayOf(1, 2, 3, 4))

        assertNull(store.videoSession("alice", sessionId))
        assertTrue(store.openVideoSessions("alice").isEmpty())
        assertFalse(Files.exists(sessionsDir.resolve("$sessionId.json")))
        assertFalse(Files.exists(partDir))
    }

    @Test
    fun pauseFlagAndVideoRequestsRoundTrip() {
        val store = store()
        assertFalse(store.uploadPaused("alice"))
        store.setUploadPaused("alice", true)
        assertTrue(store.uploadPaused("alice"))
        assertFalse(store.uploadPaused("bob")) // per-user isolation
        store.setUploadPaused("alice", false)
        assertFalse(store.uploadPaused("alice"))

        assertTrue(store.videoRequests("alice").isEmpty())
        store.requestVideo("alice", "r1")
        store.requestVideo("alice", "r2")
        store.requestVideo("alice", "r1") // duplicate is a no-op
        assertEquals(setOf("r1", "r2"), store.videoRequests("alice"))
        assertTrue(store.videoRequests("bob").isEmpty()) // per-user isolation
        store.unrequestVideo("alice", "r1")
        assertEquals(setOf("r2"), store.videoRequests("alice"))
    }

    @Test
    fun deleteImageCleansVideoArtifacts() {
        val store = store()
        val ref = "r"
        val entryId = seedVideoEntry(store, ref)
        val content = ByteArray(4) { 7 }
        val session = store.startVideoSession("alice", ref, "v.mp4", content.size.toLong(), sha256Hex(content), "video/mp4")
        store.savePart("alice", session.id, 0, content.inputStream())
        store.completeVideoSession("alice", session.id)
        assertNotNull(store.videoPath("alice", entryId))

        // A fresh request and a second open session for the same ref must not survive the delete either.
        store.requestVideo("alice", ref)
        store.startVideoSession("alice", ref, "v2.mp4", content.size.toLong(), sha256Hex(ByteArray(4) { 8 }), "video/mp4")

        assertTrue(store.deleteImage("alice", entryId))
        assertNull(store.videoPath("alice", entryId))
        assertTrue(store.videoRequests("alice").isEmpty())
        assertTrue(store.openVideoSessions("alice").isEmpty())
    }

    private fun ByteArray.inputStream(offset: Int, length: Int) =
        java.io.ByteArrayInputStream(this, offset, length)
}
