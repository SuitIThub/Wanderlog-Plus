package de.wanderlogplus.shared.guide

import de.wanderlogplus.shared.model.Guide
import de.wanderlogplus.shared.model.GuideLink
import de.wanderlogplus.shared.model.GuideLinkKind
import kotlin.test.Test
import kotlin.test.assertEquals

class GuideLinksTest {
    @Test
    fun `union drops duplicates and keeps the first`() {
        val a = Guide("a", links = listOf(GuideLink("Japan Transit", GuideLinkKind.APP, androidPackage = "jp.co.jorudan.jtp"), GuideLink("japan-guide", GuideLinkKind.WEB, "https://www.japan-guide.com/")))
        val b = Guide("b", links = listOf(GuideLink("Jorudan", GuideLinkKind.APP, androidPackage = "jp.co.jorudan.jtp"), GuideLink("JG", GuideLinkKind.WEB, "https://www.Japan-Guide.com")))
        val u = GuideLinks.union(listOf(a, b))
        assertEquals(listOf("Japan Transit", "japan-guide"), u.map { it.name })
    }

    @Test
    fun `app and web with the same url are different`() {
        val g = Guide("a", links = listOf(GuideLink("Suica", GuideLinkKind.APP, "https://x.jp", androidPackage = "jp.suica"), GuideLink("Suica Web", GuideLinkKind.WEB, "https://x.jp")))
        assertEquals(2, GuideLinks.union(listOf(g)).size)
    }
}
