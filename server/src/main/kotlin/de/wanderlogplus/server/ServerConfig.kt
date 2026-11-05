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
    /** Google key for place photos (Places API New); falls back to [googleRoutesKey]. */
    val googlePlacesKey: String? = null,
    /** AirLabs key for the live status of flights; without it `/api/live/flight` answers `flight_live_not_configured`. */
    val airlabsKey: String? = null,
    /** OpenRouter key for the assistant (reel analysis, worth judgements); without it those answer `ai_not_configured`. */
    val openRouterKey: String? = null,
    val aiModel: String = DEFAULT_AI_MODEL,
    /** Parallel key for the web research of reel places (Search + Task API); without it OpenRouter researches. */
    val parallelKey: String? = null,
    val parallelProcessor: String = "base",
    /** Optional Netscape cookies file for yt-dlp (reels that need a login). */
    val instagramCookies: Path? = null,
    /** Manages the access tokens (the /api/admin endpoints); never a sync token. Null: no administration. */
    val adminToken: String? = null,
    /** Apify token for public-transport connections in Japan; without it `/api/transit` answers `transit_not_configured`. */
    val apifyToken: String? = null,
    /** Apify actor of the timetable search ("Japan Train Route & Fare Finder"). */
    val apifyTransitActor: String = ApifyTransitSource.DEFAULT_ACTOR,
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
                airlabsKey = env["WLP_AIRLABS_KEY"]?.takeIf { it.isNotBlank() },
                googlePlacesKey = env["WLP_GOOGLE_PLACES_KEY"]?.takeIf { it.isNotBlank() } ?: env["WLP_GOOGLE_ROUTES_KEY"]?.takeIf { it.isNotBlank() },
                openRouterKey = env["WLP_OPENROUTER_KEY"]?.takeIf { it.isNotBlank() },
                aiModel = env["WLP_AI_MODEL"]?.takeIf { it.isNotBlank() } ?: DEFAULT_AI_MODEL,
                parallelKey = env["WLP_PARALLEL_KEY"]?.takeIf { it.isNotBlank() },
                parallelProcessor = env["WLP_PARALLEL_PROCESSOR"]?.takeIf { it.isNotBlank() } ?: "base",
                instagramCookies = env["WLP_INSTAGRAM_COOKIES"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) },
                adminToken = env["WLP_ADMIN_TOKEN"]?.trim()?.takeIf { it.isNotBlank() }?.also { admin ->
                    require(admin.length >= MIN_TOKEN_LENGTH) { "WLP_ADMIN_TOKEN must be at least $MIN_TOKEN_LENGTH characters" }
                    require(admin !in tokens.values) { "WLP_ADMIN_TOKEN must differ from every WLP_TOKENS token" }
                },
                apifyToken = env["WLP_APIFY_TOKEN"]?.takeIf { it.isNotBlank() },
                apifyTransitActor = env["WLP_APIFY_TRANSIT_ACTOR"]?.takeIf { it.isNotBlank() } ?: ApifyTransitSource.DEFAULT_ACTOR,
            )
        }
    }
}
