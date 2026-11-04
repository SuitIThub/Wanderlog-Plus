package de.wanderlogplus.shared.sync

import kotlinx.serialization.Serializable

/**
 * An access token of the server (`GET /api/admin/tokens`, admin only). [source] "env": from WLP_TOKENS (cannot be
 * removed in the app); "app": created via `POST /api/admin/tokens`. [createdAt] epoch ms (0 for env tokens).
 */
@Serializable
data class AccessToken(val name: String, val token: String, val source: String = "app", val createdAt: Long = 0)

@Serializable
data class AccessTokens(val tokens: List<AccessToken> = emptyList())

@Serializable
data class CreateTokenRequest(val name: String)
