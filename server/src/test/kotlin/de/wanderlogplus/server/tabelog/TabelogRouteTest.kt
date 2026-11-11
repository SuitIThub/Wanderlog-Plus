package de.wanderlogplus.server.tabelog

import de.wanderlogplus.server.Services
import de.wanderlogplus.server.authed
import de.wanderlogplus.server.module
import de.wanderlogplus.server.testServices
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TabelogRouteTest {
    private val tmp = Files.createTempDirectory("wlp-tabelog")

    @Test
    fun `refresh without apify is not configured`() = testApplication {
        val base = testServices(tmp)
        application { module(base) }
        val r = client.post("/api/tabelog/refresh") { authed() }
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
        assertTrue("tabelog_not_configured" in r.bodyAsText())
    }

    @Test
    fun `refresh kicks the sweeper`() = testApplication {
        val base = testServices(tmp)
        var swept = 0
        val source = object : TabelogSource {
            override fun search(keyword: String, area: String) = emptyList<TabelogHit>()
            override fun details(url: String) = null to emptyList<TabelogReview>()
        }
        val sweeper = object : TabelogSweeper(base.store, source, { null }, null, { 0 }) {
            override fun kick() { swept++ }
        }
        application { module(Services(base.config, base.store, base.sync, base.files, base.imports, tabelog = sweeper)) }
        assertEquals(HttpStatusCode.Accepted, client.post("/api/tabelog/refresh") { authed() }.status)
        assertEquals(1, swept)
    }
}
