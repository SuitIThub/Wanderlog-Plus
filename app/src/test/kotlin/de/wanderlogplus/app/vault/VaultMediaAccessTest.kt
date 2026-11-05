package de.wanderlogplus.app.vault

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultMediaAccessTest {
    @Test fun upgradeFromImagesOnlyStillRequestsVideo() {
        val granted = setOf(Manifest.permission.READ_MEDIA_IMAGES)
        assertTrue(VaultMediaAccess.canReadImages(33, granted::contains))
        assertFalse(VaultMediaAccess.canReadVideos(33, granted::contains))
        assertEquals(listOf(Manifest.permission.READ_MEDIA_VIDEO), VaultMediaAccess.missing(33, granted::contains).toList())
    }

    @Test fun bothGrantsMeanNothingToAsk() {
        val granted = setOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        assertTrue(VaultMediaAccess.canReadVideos(33, granted::contains))
        assertTrue(VaultMediaAccess.missing(33, granted::contains).isEmpty())
    }

    @Test fun storageGrantCoversVideoBelowApi33() {
        val granted = setOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        assertTrue(VaultMediaAccess.canReadVideos(32, granted::contains))
        assertTrue(VaultMediaAccess.missing(32, granted::contains).isEmpty())
    }

    @Test fun selectedPhotosCountAsPartialVideoAccessButStillAskForFull() {
        val granted = setOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertTrue(VaultMediaAccess.canReadImages(34, granted::contains))
        assertTrue(VaultMediaAccess.canReadVideos(34, granted::contains))
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO),
            VaultMediaAccess.missing(34, granted::contains).toList(),
        )
    }
}
