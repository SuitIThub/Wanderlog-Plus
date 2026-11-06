package de.wanderlogplus.app.ui

import android.content.ComponentName
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The screens make room for the keyboard themselves (imePadding); the window must therefore only pass the keyboard
 * on (adjustResize) and not also pan up by the keyboard's height, which left a keyboard-high gap under text fields.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardModeTest {
    @Test
    fun activitiesLetComposeHandleTheKeyboard() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        listOf(MainActivity::class.java, de.wanderlogplus.app.ui.share.ShareActivity::class.java).forEach { a ->
            val info = ctx.packageManager.getActivityInfo(ComponentName(ctx, a), PackageManager.GET_META_DATA)
            assertEquals(a.simpleName, WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE, info.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
        }
    }
}
