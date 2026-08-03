package com.discord.activities

import com.discord.api.channel.Channel
import com.discord.api.embeddedactivities.EmbeddedActivityOAuthAuthentication
import com.discord.api.embeddedactivities.EmbeddedActivityOAuthAuthorizeBody
import com.discord.api.embeddedactivities.EmbeddedActivityOAuthLocationContext
import com.discord.restapi.EmbeddedActivityOAuthAPI
import java.net.URI
import java.net.URLDecoder
import rx.Observable

data class EmbeddedActivityAuthorizeRequest(
    val clientId: Long,
    val scopes: List<String>,
    val state: String?,
)

sealed class EmbeddedActivityOAuthException(
    val rpcCode: Int,
    message: String,
) : IllegalArgumentException(message) {
    class InvalidClient : EmbeddedActivityOAuthException(4000, "Invalid OAuth client")
    class InvalidRequest : EmbeddedActivityOAuthException(4002, "Invalid OAuth request")
    class AuthorizationFailed : EmbeddedActivityOAuthException(4011, "Authorization failed")
    class AuthenticationFailed : EmbeddedActivityOAuthException(4009, "Authentication failed")
}

class EmbeddedActivityOAuthClient(
    private val api: EmbeddedActivityOAuthAPI,
    private val applicationId: Long,
    private val channel: Channel,
    private val currentUserId: Long,
) {
    fun authorize(request: EmbeddedActivityAuthorizeRequest): Observable<String> {
        try {
            validate(request)
        } catch (failure: EmbeddedActivityOAuthException) {
            return Observable.error(failure)
        }
        val body = EmbeddedActivityOAuthAuthorizeBody(
            locationContext = EmbeddedActivityOAuthLocationContext(
                channelId = channel.id.toString(),
                channelType = channel.type,
                guildId = channel.guildId.takeIf { it > 0L }?.toString(),
            ),
        )
        return api.authorize(
            clientId = request.clientId.toString(),
            responseType = "code",
            scope = request.scopes.joinToString(" "),
            state = request.state,
            body = body,
        ).map { response -> extractCode(response.location, request.state) }
    }

    fun authenticate(accessToken: String): Observable<EmbeddedActivityOAuthAuthentication> {
        if (!isValidAccessToken(accessToken)) {
            return Observable.error(EmbeddedActivityOAuthException.AuthenticationFailed())
        }
        return api.authenticate("Bearer $accessToken").map { authorization ->
            if (authorization.application.id != applicationId || authorization.user?.id != currentUserId) {
                throw EmbeddedActivityOAuthException.AuthenticationFailed()
            }
            EmbeddedActivityOAuthAuthentication(authorization, accessToken)
        }
    }

    private fun validate(request: EmbeddedActivityAuthorizeRequest) {
        if (request.clientId != applicationId) throw EmbeddedActivityOAuthException.InvalidClient()
        if (request.scopes.isEmpty() || request.scopes.size > MAX_SCOPES ||
            request.scopes.any { !SCOPE.matches(it) } ||
            request.state?.let { it.length > MAX_STATE_LENGTH || it.any(Char::isISOControl) } == true
        ) throw EmbeddedActivityOAuthException.InvalidRequest()
    }

    companion object {
        private const val MAX_SCOPES = 50
        private const val MAX_STATE_LENGTH = 2048
        private const val MAX_ACCESS_TOKEN_LENGTH = 8192
        private val SCOPE = Regex("[a-z0-9._:-]{1,128}")

        internal fun extractCode(location: String, expectedState: String?): String {
            val uri = runCatching { URI(location) }.getOrNull()
                ?: throw EmbeddedActivityOAuthException.AuthorizationFailed()
            val params = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate { entry ->
                val key = entry.substringBefore('=')
                val value = entry.substringAfter('=', "")
                decode(key) to decode(value)
            }
            val code = params["code"]?.takeIf(String::isNotBlank)
                ?: throw EmbeddedActivityOAuthException.AuthorizationFailed()
            if (expectedState != null && params["state"] != expectedState) {
                throw EmbeddedActivityOAuthException.AuthorizationFailed()
            }
            return code
        }

        private fun isValidAccessToken(token: String) =
            token.isNotBlank() && token.length <= MAX_ACCESS_TOKEN_LENGTH && token.none(Char::isWhitespace)

        private fun decode(value: String) = URLDecoder.decode(value, Charsets.UTF_8.name())
    }
}
