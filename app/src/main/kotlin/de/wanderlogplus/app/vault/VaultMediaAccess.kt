package de.wanderlogplus.app.vault

import android.Manifest

/**
 * Media permissions the vault scan needs. Images and videos are separate grants on API 33+: an upgrade
 * that already has photo access must still request [Manifest.permission.READ_MEDIA_VIDEO], otherwise
 * [VaultScanner] skips the video query on purpose.
 */
object VaultMediaAccess {
    fun needed(sdk: Int): Array<String> = if (sdk >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun canReadImages(sdk: Int, granted: (String) -> Boolean): Boolean {
        if (sdk >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES)) return true
        if (sdk < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE)) return true
        return sdk >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }

    fun canReadVideos(sdk: Int, granted: (String) -> Boolean): Boolean {
        if (sdk < 33) return canReadImages(sdk, granted)
        if (granted(Manifest.permission.READ_MEDIA_VIDEO)) return true
        return sdk >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }

    /** Of [needed], those not yet granted. A selected-photos grant does not remove the full-access pair. */
    fun missing(sdk: Int, granted: (String) -> Boolean): Array<String> =
        needed(sdk).filterNot(granted).toTypedArray()
}
