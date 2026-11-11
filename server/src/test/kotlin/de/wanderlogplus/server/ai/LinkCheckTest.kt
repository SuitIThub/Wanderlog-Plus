package de.wanderlogplus.server.ai

import de.wanderlogplus.shared.model.GuideLink
import de.wanderlogplus.shared.model.GuideLinkKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinkCheckTest {
    private fun play(pkg: String) = "https://play.google.com/store/apps/details?id=$pkg"

    private fun check(reachable: Set<String>, links: List<GuideLink>) = LinkCheck(UrlProbe { it in reachable }).check(links)

    @Test
    fun `keeps reachable web and drops the rest`() {
        val r = check(
            setOf("https://www.japan-guide.com/"),
            listOf(
                GuideLink("JG", GuideLinkKind.WEB, "https://www.japan-guide.com/"),
                GuideLink("Tot", GuideLinkKind.WEB, "https://tot.example/"),
                GuideLink("FTP", GuideLinkKind.WEB, "ftp://x.jp/"),
                GuideLink("JS", GuideLinkKind.WEB, "javascript:alert(1)"),
            ),
        )
        assertEquals(listOf("JG"), r.map { it.name })
    }

    @Test
    fun `app with a valid package stays`() {
        val r = check(setOf(play("jp.co.jorudan.jtp")), listOf(GuideLink("Japan Transit", GuideLinkKind.APP, androidPackage = "jp.co.jorudan.jtp")))
        assertEquals("jp.co.jorudan.jtp", r.single().androidPackage)
    }

    @Test
    fun `app with unknown package keeps its website only`() {
        val r = check(setOf("https://www.suica.jp/"), listOf(GuideLink("Suica", GuideLinkKind.APP, "https://www.suica.jp/", androidPackage = "jp.erfunden.suica")))
        assertEquals("", r.single().androidPackage)
        assertEquals("https://www.suica.jp/", r.single().url)
    }

    @Test
    fun `app with invalid package and no url is dropped`() {
        val probe = UrlProbe { error("must not be asked") }
        assertTrue(LinkCheck(probe).check(listOf(GuideLink("X", GuideLinkKind.APP, androidPackage = "not a package"))).isEmpty())
    }

    @Test
    fun `duplicates go and at most eight stay`() {
        val links = (1..10).map { GuideLink("W$it", GuideLinkKind.WEB, "https://w$it.jp/") } + GuideLink("W1 again", GuideLinkKind.WEB, "https://W1.jp")
        val r = check(links.map { it.url }.toSet(), links)
        assertEquals(8, r.size)
        assertEquals(1, r.count { it.name.startsWith("W1") && it.name != "W10" })
    }

    @Test
    fun `probe failure drops the link`() {
        val r = LinkCheck(UrlProbe { throw RuntimeException("boom") }).check(listOf(GuideLink("JG", GuideLinkKind.WEB, "https://www.japan-guide.com/")))
        assertTrue(r.isEmpty())
    }

    @Test
    fun `parseLinks reads kinds and ignores unknown ones`() {
        val arr = Json.parseToJsonElement(
            """[{"name":"Japan Transit","kind":"app","androidPackage":"jp.co.jorudan.jtp","note":"Fahrplan"},
               {"name":"JG","kind":"WEB","url":"https://www.japan-guide.com/"},{"name":"?","kind":"podcast","url":"https://p.jp"},{"kind":"web","url":"https://n.jp"}]""",
        ) as JsonArray
        val r = parseLinks(arr)
        assertEquals(listOf(GuideLinkKind.APP, GuideLinkKind.WEB), r.map { it.kind })
        assertEquals("Fahrplan", r.first().note)
    }
}
