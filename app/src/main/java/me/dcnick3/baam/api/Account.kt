package me.dcnick3.baam.api

import android.util.Base64
import io.ktor.http.parseClientCookiesHeader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class Account(
    val id: String,
    val name: String,
    /**
     * Raw `Cookie` header value for baam, as captured from the web view.
     */
    val cookie: String,
    /**
     * A stable user identifier extracted from the auth token, if we managed to find one.
     * Used to avoid storing the same user twice.
     */
    val identity: String? = null,
)

private val identityClaims = listOf(
    "email",
    "upn",
    "preferred_username",
    "unique_name",
    "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress",
    "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/upn",
    "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name",
    "name",
    "sub",
)

/**
 * Tries to find a human-readable user identifier (usually the email) in the JWT stored in the baam cookies.
 */
fun extractIdentity(cookie: String): String? {
    for (value in parseClientCookiesHeader(cookie).values) {
        val parts = value.split('.')
        if (parts.size != 3) continue

        val claims = try {
            val payload = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            Json.parseToJsonElement(String(payload)).jsonObject
        } catch (e: Exception) {
            continue
        }

        for (claim in identityClaims) {
            val identity = try {
                claims[claim]?.jsonPrimitive?.contentOrNull
            } catch (e: IllegalArgumentException) {
                null
            }
            if (!identity.isNullOrBlank()) {
                return identity
            }
        }
    }
    return null
}
