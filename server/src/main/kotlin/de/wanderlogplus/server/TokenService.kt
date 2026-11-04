package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.AccessToken
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** A token name is already taken (by `WLP_TOKENS` or an earlier one). */
class TokenNameTaken : RuntimeException("token name taken")

/** A token from `WLP_TOKENS` cannot be removed in the app (it lives in the server's .env). */
class TokenInEnv : RuntimeException("token in env")

/** No such token. */
class TokenNotFound : RuntimeException("token not found")

/** No `WLP_ADMIN_TOKEN` is configured. */
class AdminNotConfigured : RuntimeException("admin not configured")

/**
 * Access tokens: those from `WLP_TOKENS` plus the ones created in the app (stored in the database). The admin token
 * (`WLP_ADMIN_TOKEN`) only manages them; it is never a sync token. The name of a token is the author of its changes.
 */
class TokenService(private val config: ServerConfig, private val store: SqliteStore, private val now: () -> Long = System::currentTimeMillis) {
    private val random = SecureRandom()

    @Volatile private var cache: Map<String, String>? = null

    val adminConfigured: Boolean get() = config.adminToken != null

    /** name -> token of every valid sync token. */
    fun all(): Map<String, String> = cache ?: (config.tokens + store.appTokens().filterKeys { it !in config.tokens }.mapValues { it.value.first }).also { cache = it }

    /** The name of the sync token [presented]; null when it is none. Compares against every token in constant time. */
    fun nameFor(presented: String): String? = find(all(), presented)

    fun isAdmin(presented: String): Boolean {
        val admin = config.adminToken ?: return false
        return MessageDigest.isEqual(presented.toByteArray(Charsets.UTF_8), admin.toByteArray(Charsets.UTF_8))
    }

    fun list(): List<AccessToken> {
        val app = store.appTokens()
        return config.tokens.map { (name, token) -> AccessToken(name, token, "env") } +
            app.filterKeys { it !in config.tokens }.map { (name, v) -> AccessToken(name, v.first, "app", v.second) }.sortedBy { it.createdAt }
    }

    /** A new token for [name] (1–40 letters, digits, space, `.`, `_`, `-`). */
    fun create(name: String): AccessToken {
        val n = name.trim()
        if (!NAME.matches(n)) throw BadInput()
        if (n in all() || n.equals(ADMIN_NAME, ignoreCase = true)) throw TokenNameTaken()
        val bytes = ByteArray(24).also { random.nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val t = AccessToken(n, token, "app", now())
        store.putAppToken(n, token, t.createdAt)
        cache = null
        return t
    }

    fun delete(name: String) {
        if (name in config.tokens) throw TokenInEnv()
        if (!store.deleteAppToken(name)) throw TokenNotFound()
        cache = null
    }

    companion object {
        private val NAME = Regex("""[\p{L}\p{N} ._-]{1,40}""")
        private const val ADMIN_NAME = "admin"

        fun find(tokens: Map<String, String>, presented: String): String? {
            val p = presented.toByteArray(Charsets.UTF_8)
            var found: String? = null
            // No early exit: compare against every token in constant time per comparison.
            for ((name, token) in tokens) {
                if (MessageDigest.isEqual(p, token.toByteArray(Charsets.UTF_8))) found = name
            }
            return found
        }
    }
}
