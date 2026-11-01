package de.wanderlogplus.client

import java.net.URI

object ServerUrl {
    private const val INVALID = "Ungültige Server-Adresse"
    private val IPV4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")

    fun normalize(input: String): Result<String> {
        var s = input.trim()
        if (s.isEmpty()) return fail()
        if (!s.contains("://")) s = "https://$s"
        s = s.trimEnd('/')
        val uri = try {
            URI(s)
        } catch (e: Exception) {
            return fail()
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return fail()
        if (uri.host.isNullOrEmpty()) return fail()
        if (!uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return fail()
        return Result.success(s)
    }

    fun isInsecure(url: String): Boolean {
        val uri = try {
            URI(url.trim())
        } catch (e: Exception) {
            return true
        }
        if (uri.scheme?.lowercase() != "http") return false
        val host = uri.host?.lowercase() ?: return true
        if (host == "localhost" || host.endsWith(".local") || host == "[::1]") return false
        val m = IPV4.matchEntire(host) ?: return true
        val o = m.groupValues.drop(1).map { it.toInt() }
        if (o.any { it > 255 }) return true
        return !(o[0] == 127 || o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168))
    }

    private fun fail(): Result<String> = Result.failure(IllegalArgumentException(INVALID))
}
