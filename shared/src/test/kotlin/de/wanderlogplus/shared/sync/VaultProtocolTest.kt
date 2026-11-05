package de.wanderlogplus.shared.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class VaultProtocolTest {

    private val json = Json { explicitNulls = false; ignoreUnknownKeys = true }

    /** Entries stored by an old server (or produced by an old app) carry none of the video fields. */
    @Test fun legacyImageInfoDecodesWithDefaults() {
        val info = json.decodeFromString<VaultImageInfo>(
            """{"id":"i","fileName":"f","mimeType":"image/jpeg","size":1,"score":0.0,"uploadedAt":2}""",
        )
        assertEquals(VaultKind.PHOTO, info.kind)
        assertFalse(info.hasVideo)
        assertEquals(null, info.videoRef)
        assertEquals(null, info.durationMs)
    }

    /** An old server answers the trigger poll with only `requested`; the new fields default. */
    @Test fun legacyUploadTriggerDecodesWithDefaults() {
        assertEquals(
            VaultUploadTrigger(requested = true, paused = false, videoRefs = emptyList()),
            json.decodeFromString<VaultUploadTrigger>("""{"requested":true}"""),
        )
    }

    @Test fun videoImageInfoRoundTrips() {
        val info = VaultImageInfo(
            id = "i",
            fileName = "clip.mp4",
            mimeType = "image/jpeg",
            size = 1234,
            score = 0.0,
            uploadedAt = 99,
            hash = "ab",
            kind = VaultKind.VIDEO,
            videoRef = "content://media/external/video/1",
            durationMs = 6500,
            videoBytes = 5_000_000,
            hasVideo = true,
            videoHash = "cd",
            videoMimeType = "video/mp4",
        )
        assertEquals(info, json.decodeFromString<VaultImageInfo>(json.encodeToString(info)))
    }

    @Test fun videoSessionRoundTrips() {
        val session = VaultVideoSession(
            id = "s1",
            videoRef = "content://media/external/video/1",
            fileName = "clip.mp4",
            size = 20_000_000,
            sha256 = "ef",
            partSize = VAULT_PART_SIZE,
            receivedParts = listOf(0, 2),
            complete = false,
        )
        assertEquals(session, json.decodeFromString<VaultVideoSession>(json.encodeToString(session)))
    }

    /** The device stats ride the scan report; old apps omit them. */
    @Test fun scanUpdateAndStatusCarryOptionalDeviceStats() {
        val legacy = json.decodeFromString<VaultScanUpdate>(
            """{"state":"done","totalImages":3,"scannedImages":3,"foundImages":2}""",
        )
        assertEquals(null, legacy.photosOnDevice)
        assertEquals(null, legacy.videosOnDevice)
        assertEquals(null, legacy.pendingUploads)

        val update = VaultScanUpdate(
            state = "done", totalImages = 3, scannedImages = 3, foundImages = 2,
            photosOnDevice = 100, videosOnDevice = 7, pendingUploads = 42,
        )
        assertEquals(update, json.decodeFromString<VaultScanUpdate>(json.encodeToString(update)))

        val status = VaultScanStatus(
            user = "u", state = "done", totalImages = 3, scannedImages = 3, foundImages = 2,
            updatedAt = 5, photosOnDevice = 100, videosOnDevice = 7, pendingUploads = 42,
        )
        assertEquals(status, json.decodeFromString<VaultScanStatus>(json.encodeToString(status)))
    }
}
