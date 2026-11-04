package de.wanderlogplus.app.ui.more

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.shared.sync.AccessToken
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class AccessTest {
    @get:Rule val rule = createComposeRule()

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** A tiny admin API: tokens in a list; only "admin-secret-0123456789" is accepted. */
    private fun fakeApi(): Pair<(String) -> SyncApi, MutableList<AccessToken>> {
        val tokens = mutableListOf(AccessToken("daniel", "tok-daniel-1234567890", "env"))
        val api = { admin: String ->
            SyncApi(HttpClient(MockEngine { req ->
                if (req.headers[HttpHeaders.Authorization] != "Bearer admin-secret-0123456789") {
                    respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized, json)
                } else when (req.method) {
                    HttpMethod.Get -> respond("""{"tokens":[${tokens.joinToString(",") { """{"name":"${it.name}","token":"${it.token}","source":"${it.source}","createdAt":${it.createdAt}}""" }}]}""", HttpStatusCode.OK, json)
                    HttpMethod.Post -> {
                        val name = Regex("\"name\":\"([^\"]*)\"").find(String(req.body.toByteArray()))!!.groupValues[1]
                        val t = AccessToken(name, "new-token-for-$name-123456", "app", 1_800_000_000_000)
                        tokens += t
                        respond("""{"name":"${t.name}","token":"${t.token}","source":"app","createdAt":${t.createdAt}}""", HttpStatusCode.Created, json)
                    }
                    else -> {
                        tokens.removeAll { req.url.encodedPath.endsWith("/" + it.name) }
                        respond("", HttpStatusCode.NoContent)
                    }
                }
            }), "https://s", admin)
        }
        return api to tokens
    }

    private fun waitUntil(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!cond() && System.currentTimeMillis() < end) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
    }

    @Test fun viewModelStoresTheAdminTokenListsCreatesAndRevokes() {
        val (api, tokens) = fakeApi()
        var stored: String? = null
        val vm = AccessViewModel({ t -> api(t) }, { stored }, { stored = it })
        waitUntil { true }
        assertFalse(vm.state.value.hasAdminToken)

        vm.setAdminToken("wrong-token-000000000")
        waitUntil { vm.state.value.message != null }
        assertEquals("Der Admin-Token stimmt nicht.", vm.state.value.message)

        vm.setAdminToken(" admin-secret-0123456789 ")
        waitUntil { vm.state.value.tokens.isNotEmpty() }
        assertEquals("admin-secret-0123456789", stored)
        assertEquals(listOf("daniel"), vm.state.value.tokens.map { it.name })

        vm.create("Mama ")
        waitUntil { vm.state.value.created != null }
        assertEquals("Mama", vm.state.value.created!!.name)
        assertEquals(2, vm.state.value.tokens.size)

        vm.delete("Mama")
        waitUntil { vm.state.value.tokens.size == 1 }
        assertEquals(1, tokens.size)

        vm.forgetAdminToken()
        waitUntil { !vm.state.value.hasAdminToken }
        assertNull(stored)
    }

    @Test fun screenShowsQrCodesAndOnlyAppTokensCanBeRevoked() {
        val state = AccessState(
            hasAdminToken = true,
            tokens = listOf(AccessToken("daniel", "tok-daniel-1234567890", "env"), AccessToken("Mama", "tok-mama-1234567890", "app", 1_800_000_000_000)),
        )
        var deleted: String? = null
        rule.setContent {
            WanderlogPlusTheme {
                AccessScreen(state, "https://home.example", {}, {}, {}, onDelete = { deleted = it }, onDismissCreated = {}, onBack = {})
            }
        }
        rule.onNodeWithTag("access-delete-daniel").assertDoesNotExist()
        rule.onNodeWithText("aus WLP_TOKENS (.env)").assertExists()
        rule.onNodeWithTag("access-qr-Mama").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Zugang für Mama").assertExists()
        rule.onNodeWithTag("setup-qr").assertExists()
        rule.onNodeWithText("Schließen").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("access-delete-Mama").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("access-delete-confirm").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("Mama", deleted)
    }

    @Test fun withoutAdminTokenTheScreenAsksForIt() {
        var saved: String? = null
        rule.setContent {
            WanderlogPlusTheme { AccessScreen(AccessState(), "https://home.example", { saved = it }, {}, {}, {}, {}, {}) }
        }
        rule.onNodeWithTag("access-admin-token").performTextInput("admin-secret-0123456789")
        rule.onNodeWithTag("access-admin-save").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("admin-secret-0123456789", saved)
        assertTrue(accessError(de.wanderlogplus.client.ApiException(de.wanderlogplus.client.ApiErrorKind.NOT_CONFIGURED)).contains("WLP_ADMIN_TOKEN"))
    }
}
