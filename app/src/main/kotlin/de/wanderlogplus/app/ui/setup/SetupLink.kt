package de.wanderlogplus.app.ui.setup

import de.wanderlogplus.app.data.ServerSettings
import de.wanderlogplus.client.ServerUrl
import java.net.URLDecoder
import java.net.URLEncoder

/** Setup link `wlp://setup?url=<url-encoded>&token=<url-encoded>`, used by the QR code and the deep link. The token is a secret: never log it. */
object SetupLink {
    private const val PREFIX = "wlp://setup"

    fun parse(raw: String): ServerSettings? {
        val text = raw.trim().substringBefore('#')
        if (!text.startsWith(PREFIX, ignoreCase = true)) return null
        val rest = text.substring(PREFIX.length)
        val query = when {
            rest.startsWith("?") -> rest.substring(1)
            rest.startsWith("/?") -> rest.substring(2)
            else -> return null
        }
        val params = HashMap<String, String>()
        for (pair in query.split('&')) {
            val key = pair.substringBefore('=', "")
            val value = if (pair.contains('=')) pair.substringAfter('=') else ""
            try {
                params.putIfAbsent(URLDecoder.decode(key, "UTF-8"), URLDecoder.decode(value, "UTF-8"))
            } catch (e: IllegalArgumentException) {
                return null
            }
        }
        val url = params["url"]?.trim().orEmpty()
        val token = params["token"]?.trim().orEmpty()
        if (url.isEmpty() || token.isEmpty()) return null
        val normalized = ServerUrl.normalize(url).getOrNull() ?: return null
        return ServerSettings(normalized, token)
    }

    fun build(s: ServerSettings): String =
        "$PREFIX?url=${URLEncoder.encode(s.url, "UTF-8")}&token=${URLEncoder.encode(s.token, "UTF-8")}"
}
