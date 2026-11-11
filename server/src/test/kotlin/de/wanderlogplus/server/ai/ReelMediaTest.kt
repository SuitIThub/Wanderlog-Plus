package de.wanderlogplus.server.ai

import java.nio.file.Files
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReelMediaTest {
    private val dir = Files.createTempDirectory("wlp-gallery")

    @Test
    fun `collectGallery finds images and videos in order`() {
        listOf("03.webp", "01.jpg", "02.mp4", "01.jpg.json", "readme.txt").forEach { Files.writeString(dir.resolve(it), "x") }
        val (videos, images) = collectGallery(dir)
        assertEquals(listOf("02.mp4"), videos.map { it.name })
        assertEquals(listOf("01.jpg", "03.webp"), images.map { it.name })
    }

    @Test
    fun `collectGallery looks into sub folders`() {
        val sub = Files.createDirectories(dir.resolve("instagram").resolve("user"))
        Files.writeString(sub.resolve("1.jpg"), "x")
        assertEquals(1, collectGallery(dir).second.size)
    }

    @Test
    fun `galleryCaption reads description or caption`() {
        Files.writeString(dir.resolve("01.jpg.json"), """{"description":"Tipps für Tokio","likes":3}""")
        assertEquals("Tipps für Tokio", galleryCaption(dir))
        val other = Files.createTempDirectory("wlp-gallery2")
        Files.writeString(other.resolve("x.json"), """{"caption":"Onsen-Regeln"}""")
        assertEquals("Onsen-Regeln", galleryCaption(other))
        assertNull(galleryCaption(Files.createTempDirectory("wlp-gallery3")))
    }

    @Test
    fun `frame budget prefers gallery images`() {
        assertEquals(listOf(1, 1), frameBudget(18, 2))
        assertEquals(listOf(0), frameBudget(20, 1))
        assertEquals(listOf(0), frameBudget(25, 1))
        assertEquals(listOf(12), frameBudget(0, 1))
        assertEquals(listOf(8, 8), frameBudget(4, 2))
        assertEquals(listOf(2, 1), frameBudget(17, 2))
        assertEquals(emptyList(), frameBudget(3, 0))
    }
}
