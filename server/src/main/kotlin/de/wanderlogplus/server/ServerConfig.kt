package de.wanderlogplus.server

import java.nio.file.Path

data class ServerConfig(
    val tokens: Map<String, String>, // name -> token
    val dataDir: Path,
    val port: Int,
    val maxFileBytes: Long,
    val tripKey: String?,
    val importFile: Path?,
    /** Google Routes API key; without it, route computation answers `routes_not_configured`. */
    val googleRoutesKey: String? = null,
    /** OpenRouter key for the assistant (reel analysis, worth judgements); without it those answer `ai_not_configured`. */
    val openRouterKey: String? = null,
    val aiModel: String = DEFAULT_AI_MODEL,
    /** Optional Netscape cookies file for yt-dlp (reels that need a login). */
    val instagramCookies: Path? = null,
) {
    companion object {
        const val MIN_TOKEN_LENGTH = 16
        const val DEFAULT_AI_MODEL = "google/gemini-2.5-flash"

        fun fromEnv(env: Map<String, String>): ServerConfig {
            val raw = env["WLP_TOKENS"]?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("WLP_TOKENS is required (format: name:token,name:token)")
            val tokens = linkedMapOf<String, String>()
            for (entry in raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                val idx = entry.indexOf(':')
                require(idx > 0) { "WLP_TOKENS entry must look like name:token (entry without name or ':')" }
                val name = entry.substring(0, idx).trim()
                val token = entry.substring(idx + 1).trim()
                require(name.isNotEmpty()) { "WLP_TOKENS entry has an empty name" }
                require(token.length >= MIN_TOKEN_LENGTH) {
                    "WLP_TOKENS token for '$name' must be at least $MIN_TOKEN_LENGTH characters"
                }
                require(name !in tokens) { "WLP_TOKENS contains duplicate name '$name'" }
                require(token !in tokens.values) { "WLP_TOKENS assigns the same token to more than one name" }
                tokens[name] = token
            }
            require(tokens.isNotEmpty()) { "WLP_TOKENS must contain at least one entry" }

            val port = env["WLP_PORT"]?.let {
                it.toIntOrNull()?.takeIf { p -> p in 0..65535 }
                    ?: throw IllegalArgumentException("WLP_PORT must be a port number between 0 and 65535")
            } ?: 8080
            val maxMb = env["WLP_MAX_FILE_MB"]?.let {
                it.toLongOrNull()?.takeIf { m -> m > 0 }
                    ?: throw IllegalArgumentException("WLP_MAX_FILE_MB must be a positive integer")
            } ?: 25L

            return ServerConfig(
                tokens = tokens,
                dataDir = Path.of(env["WLP_DATA_DIR"]?.takeIf { it.isNotBlank() } ?: "/data"),
                port = port,
                maxFileBytes = maxMb * 1024 * 1024,
                tripKey = env["WLP_TRIP_KEY"]?.takeIf { it.isNotBlank() },
                importFile = env["WLP_IMPORT_FILE"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) },
                googleRoutesKey = env["WLP_GOOGLE_ROUTES_KEY"]?.takeIf { it.isNotBlank() },
                openRouterKey = env["WLP_OPENROUTER_KEY"]?.takeIf { it.isNotBlank() },
                aiModel = env["WLP_AI_MODEL"]?.takeIf { it.isNotBlank() } ?: DEFAULT_AI_MODEL,
                instagramCookies = env["WLP_INSTAGRAM_COOKIES"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) },
            )
        }
    }
}
