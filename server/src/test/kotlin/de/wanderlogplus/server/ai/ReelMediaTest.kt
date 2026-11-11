package de.wanderlogplus.server.ai

import java.nio.file.Files
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    /** A fake yt-dlp/gallery-dl: records calls and writes the files the scenario says. */
    private class Tools(val ytVideo: Boolean, val gallery: List<String>) {
        val calls = mutableListOf<String>()
        fun run(args: List<String>, timeout: Long): Boolean {
            val tool = args.first()
            calls += tool
            when (tool) {
                "yt-dlp" -> {
                    if (!ytVideo) return false
                    val out = java.nio.file.Path.of(args[args.indexOf("-o") + 1]).parent
                    Files.writeString(out.resolve("video.mp4"), "v")
                }
                "gallery-dl" -> {
                    val d = java.nio.file.Path.of(args[args.indexOf("-D") + 1])
                    gallery.forEach { Files.writeString(d.resolve(it), "x") }
                    if (gallery.isEmpty()) return false
                }
            }
            return true
        }
    }

    private fun media(t: Tools) = ToolReelMedia(runner = t::run)

    @Test
    fun `a post link goes to gallery-dl first and keeps images and videos`() {
        val t = Tools(ytVideo = true, gallery = listOf("1.jpg", "2.mp4", "3.jpg"))
        val d = media(t).download("https://www.instagram.com/p/abc/", Files.createTempDirectory("dl"))
        assertEquals(listOf("gallery-dl"), t.calls)
        assertEquals(2, d.images.size)
        assertEquals(1, d.videos.size)
    }

    @Test
    fun `a post without gallery result falls back to yt-dlp`() {
        val t = Tools(ytVideo = true, gallery = emptyList())
        val d = media(t).download("https://www.instagram.com/p/abc/", Files.createTempDirectory("dl"))
        assertEquals(listOf("gallery-dl", "yt-dlp"), t.calls)
        assertEquals(1, d.videos.size)
    }

    @Test
    fun `a reel link uses yt-dlp only`() {
        val t = Tools(ytVideo = true, gallery = listOf("1.jpg"))
        val d = media(t).download("https://www.instagram.com/reel/abc/", Files.createTempDirectory("dl"))
        assertEquals(listOf("yt-dlp"), t.calls)
        assertEquals(1, d.videos.size)
    }

    @Test
    fun `a reel without video falls back to gallery-dl`() {
        val t = Tools(ytVideo = false, gallery = listOf("1.jpg"))
        val d = media(t).download("https://www.instagram.com/reel/abc/", Files.createTempDirectory("dl"))
        assertEquals(listOf("yt-dlp", "gallery-dl"), t.calls)
        assertEquals(1, d.images.size)
    }

    @Test
    fun `nothing at all fails`() {
        val t = Tools(ytVideo = false, gallery = emptyList())
        kotlin.test.assertFailsWith<DownloadFailed> { media(t).download("https://www.instagram.com/p/abc/", Files.createTempDirectory("dl")) }
    }

    @Test
    fun `gallery-dl is limited in count and size`() {
        val args = mutableListOf<List<String>>()
        runCatching { ToolReelMedia(runner = { a, _ -> args += a; false }).download("https://www.instagram.com/p/abc/", Files.createTempDirectory("dl")) }
        val g = args.first { it.first() == "gallery-dl" }
        assertTrue("--range" in g && "--filesize-max" in g)
    }
}
