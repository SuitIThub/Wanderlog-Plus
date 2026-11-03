package de.wanderlogplus.app.update

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.more.SettingsInfo
import de.wanderlogplus.app.ui.more.SettingsScreen
import de.wanderlogplus.app.sync.SyncStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class AppUpdatesTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val apkBytes = ByteArray(200_000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apkBytes).joinToString("") { "%02x".format(it) }

    private fun releaseJson(tag: String = "v0.14.0", digest: String? = sha, size: Int = apkBytes.size) = """
        {"tag_name":"$tag","draft":false,"prerelease":false,"body":"- **Updates in der App**\n- Neu: Ablauf",
         "assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":3},
                   {"name":"wanderlog-plus-0.14.0.apk","browser_download_url":"https://github.com/SuitIThub/Wanderlog-Plus/releases/download/$tag/wanderlog-plus-0.14.0.apk",
                    "size":$size${digest?.let { ",\"digest\":\"sha256:$it\"" } ?: ""}}]}"""

    private fun source(release: String = releaseJson(), apk: ByteArray = apkBytes, latestStatus: HttpStatusCode = HttpStatusCode.OK) =
        ReleaseSource(HttpClient(MockEngine { req ->
            if (req.url.encodedPath.endsWith("/releases/latest")) respond(release, latestStatus, json)
            else respond(apk, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/octet-stream"))
        }))

    private class MemPrefs(override var lastCheckAt: Long = 0, override var skippedVersion: String? = null) : UpdatePrefs

    private fun vm(src: ReleaseSource = source(), prefs: UpdatePrefs = MemPrefs(), current: String = "0.13.4", auto: Boolean = true, now: Long = 10_000_000_000) =
        UpdateViewModel(src, current, prefs, tmp.newFolder(), autoCheck = auto, now = { now })

    private fun waitFor(vm: UpdateViewModel, cond: (UpdateState) -> Boolean): UpdateState {
        val end = System.currentTimeMillis() + 5_000
        while (!cond(vm.state.value) && System.currentTimeMillis() < end) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
        return vm.state.value
    }

    @Test fun versionsCompareNumerically() {
        assertTrue(isNewer("0.14.0", "0.13.4"))
        assertTrue(isNewer("v0.13.10", "0.13.9"))
        assertTrue(isNewer("1.0", "0.99.99"))
        assertFalse(isNewer("0.13.4", "0.13.4"))
        assertFalse(isNewer("0.13.3", "0.13.4"))
        assertFalse(isNewer("0.13.4", "0.13.4-debug"))
        assertFalse(isNewer("nightly", "0.1"))
    }

    @Test fun parsesTheApkAssetAndDigest() {
        val r = source().parse(releaseJson())!!
        assertEquals("0.14.0", r.version)
        assertTrue(r.apkUrl.endsWith(".apk"))
        assertEquals(sha, r.sha256)
        assertTrue(r.notes.startsWith("- **Updates"))
        assertNull(source().parse("""{"tag_name":"v1","prerelease":true,"assets":[]}"""))
        assertNull(source().parse("""{"tag_name":"v1","assets":[{"name":"a.zip","browser_download_url":"https://x"}]}"""))
    }

    @Test fun autoCheckOffersANewerVersionAndRespectsSkipAndInterval() {
        val v = vm()
        v.checkAuto()
        assertEquals("0.14.0", (waitFor(v) { it is UpdateState.Available } as UpdateState.Available).release.version)

        val skipped = vm(prefs = MemPrefs(skippedVersion = "0.14.0"))
        skipped.checkAuto()
        assertEquals(UpdateState.Idle, waitFor(skipped) { it !is UpdateState.Checking })

        val recent = vm(prefs = MemPrefs(lastCheckAt = 10_000_000_000 - 60_000))
        recent.checkAuto()
        assertEquals(UpdateState.Idle, recent.state.value)

        val debug = vm(auto = false)
        debug.checkAuto()
        assertEquals(UpdateState.Idle, debug.state.value)
    }

    @Test fun manualCheckSaysUpToDateOrFailed() {
        val current = vm(current = "0.14.0")
        current.checkNow()
        assertEquals(UpdateState.UpToDate, waitFor(current) { it !is UpdateState.Checking })
        val broken = vm(src = source(latestStatus = HttpStatusCode.InternalServerError))
        broken.checkNow()
        assertTrue(waitFor(broken) { it !is UpdateState.Checking } is UpdateState.Failed)
        // A skipped version still shows when asked for explicitly.
        val asked = vm(prefs = MemPrefs(skippedVersion = "0.14.0"))
        asked.checkNow()
        assertTrue(waitFor(asked) { it !is UpdateState.Checking } is UpdateState.Available)
    }

    @Test fun downloadChecksSizeAndDigest() = runBlocking {
        val target = tmp.root.resolve("u/app.apk")
        val progress = mutableListOf<Long>()
        val r = source().parse(releaseJson())!!
        source().download(r, target) { done, _ -> progress += done }
        assertEquals(apkBytes.size.toLong(), target.length())
        assertEquals(apkBytes.size.toLong(), progress.last())

        val tampered = source(apk = apkBytes.copyOf().also { it[0] = 99 })
        val failed = runCatching { tampered.download(r, tmp.root.resolve("u/bad.apk")) { _, _ -> } }
        assertTrue(failed.isFailure)
        assertFalse(tmp.root.resolve("u/bad.apk").exists())
    }

    @Test fun dialogShowsChangelogAndStartsTheUpdate() {
        val r = source().parse(releaseJson())!!
        var updated: AppRelease? = null
        var skipped: AppRelease? = null
        rule.setContent {
            WanderlogPlusTheme {
                UpdateDialog(UpdateState.Available(r), "0.13.4", debugBuild = false, onUpdate = { updated = it }, onLater = {}, onSkip = { skipped = it }, onCancelDownload = {}, onInstall = {}, onDismiss = {})
            }
        }
        rule.onNodeWithText("Update auf 0.14.0").assertExists()
        rule.onNodeWithText("Installiert: 0.13.4 · Download 0,2 MB").assertExists()
        rule.onNodeWithText("Neu: Ablauf", substring = true).assertExists()
        rule.onNodeWithTag("update-skip").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("update-install").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(r, updated)
        assertEquals(r, skipped)
    }

    @Test fun settingsShowVersionAndCheck() {
        var checks = 0
        rule.setContent {
            WanderlogPlusTheme {
                SettingsScreen(SettingsInfo(null, null, "dev"), SyncStatus(), onChangeServer = {}, onSyncNow = {}, onResetSync = {}, appVersion = "0.14.0", onCheckUpdates = { checks++ })
            }
        }
        rule.onNodeWithText("Version: 0.14.0").assertExists()
        rule.onNodeWithTag("settings-check-updates").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, checks)
    }
}
