package de.wanderlogplus.server.ai

import de.wanderlogplus.shared.guide.GuideLinks
import de.wanderlogplus.shared.model.GuideLink
import de.wanderlogplus.shared.model.GuideLinkKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Links per guide at most. */
const val MAX_GUIDE_LINKS = 8

/** An Android package id: dot-separated parts that start with a letter. */
internal val PACKAGE_ID = Regex("""[a-zA-Z][\w]*(\.[a-zA-Z][\w]*)+""")

/** Whether a page answers; replaced by fakes in tests. */
fun interface UrlProbe {
    fun ok(url: String): Boolean
}

/** GET through the [guard] (https, public hosts only), redirects followed by hand; true for a final 2xx. */
class HttpUrlProbe(private val guard: UrlGuard = UrlGuard.DNS) : UrlProbe {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()

    override fun ok(url: String): Boolean = try {
        var current = url
        var result = false
        for (hop in 0..MAX_REDIRECTS) {
            if (!guard.allowed(current)) break
            val r = http.send(
                HttpRequest.newBuilder(URI.create(current)).timeout(Duration.ofSeconds(10)).header("User-Agent", "Mozilla/5.0 (Wanderlog Plus)").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            r.body().close()
            val location = r.headers().firstValue("location").orElse(null)
            if (r.statusCode() in 300..399 && location != null) {
                current = URI(current).resolve(location).toString()
                continue
            }
            result = r.statusCode() in 200..299
            break
        }
        result
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val MAX_REDIRECTS = 3
    }
}

/**
 * Keeps only links that work: a web page must answer, an app's package must have a Play Store page (else only its
 * website stays). In doubt a link goes: none is better than a made-up one.
 */
class LinkCheck(private val probe: UrlProbe = HttpUrlProbe()) {
    fun check(links: List<GuideLink>): List<GuideLink> {
        val unique = links.map { it.copy(name = it.name.trim(), url = it.url.trim(), androidPackage = it.androidPackage.trim()) }
            .filter { it.name.isNotEmpty() }
            .distinctBy(GuideLinks::key)
        if (unique.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(4, unique.size)) { r -> Thread(r, "link-check").apply { isDaemon = true } }
        try {
            return pool.invokeAll(unique.map { l -> Callable { checked(l) } }).mapNotNull { it.get() }
                .distinctBy(GuideLinks::key).take(MAX_GUIDE_LINKS)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun checked(l: GuideLink): GuideLink? {
        val url = l.url.takeIf { isWeb(it) && answers(it) }.orEmpty()
        return when (l.kind) {
            GuideLinkKind.WEB -> l.takeIf { url.isNotEmpty() }
            GuideLinkKind.APP -> {
                val pkg = l.androidPackage.takeIf { PACKAGE_ID.matches(it) && answers("https://play.google.com/store/apps/details?id=$it") }.orEmpty()
                if (pkg.isEmpty() && url.isEmpty()) null else l.copy(url = url, androidPackage = pkg)
            }
        }
    }

    private fun isWeb(u: String) = u.startsWith("https://") || u.startsWith("http://")

    private fun answers(u: String) = try { probe.ok(u) } catch (e: Exception) { false }
}

/** Links from the model's JSON (`[{"name","kind":"app|web","url","androidPackage","note"}]`); unknown kinds go. */
internal fun parseLinks(arr: JsonArray?): List<GuideLink> = arr.orEmpty().mapNotNull { e ->
    val o = e as? JsonObject ?: return@mapNotNull null
    fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
    val kind = when (s("kind").lowercase()) {
        "app" -> GuideLinkKind.APP
        "web" -> GuideLinkKind.WEB
        else -> return@mapNotNull null
    }
    GuideLink(s("name").ifEmpty { return@mapNotNull null }, kind, s("url"), s("androidPackage"), s("note"))
}
