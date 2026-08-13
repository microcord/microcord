package com.discord.activities

import com.discord.api.channel.Channel
import com.discord.api.embeddedactivities.EmbeddedActivityOAuthAuthentication
import com.discord.api.embeddedactivities.EmbeddedActivityOAuthAuthorizeBody
import com.discord.api.embeddedactivities.EmbeddedActivityOAuthLocationContext
import com.discord.restapi.EmbeddedActivityOAuthAPI
import com.google.gson.JsonObject
import java.net.URI
import java.net.URLDecoder
import rx.Observable

data class EmbeddedActivityAuthorizeRequest(
    val clientId: Long,
    val scopes: List<String>,
    val state: String?,
    val codeChallenge: String? = null,
    val codeChallengeMethod: String? = null,
    /** `none` asks for a silent grant; anything else always shows the consent screen. */
    val prompt: String? = null,
) {
    val wantsSilentConsent: Boolean get() = prompt == PROMPT_NONE

    companion object {
        const val PROMPT_NONE = "none"
    }
}

sealed class EmbeddedActivityOAuthException(
    val rpcCode: Int,
    message: String,
) : IllegalArgumentException(message) {
    class InvalidClient : EmbeddedActivityOAuthException(4000, "Invalid OAuth client")
    class InvalidRequest : EmbeddedActivityOAuthException(4002, "Invalid OAuth request")
    class AuthorizationFailed(reason: String = "Authorization failed") :
        EmbeddedActivityOAuthException(4011, reason)
    class AuthenticationFailed : EmbeddedActivityOAuthException(4009, "Authentication failed")
}

class EmbeddedActivityOAuthClient(
    private val authorizeApi: EmbeddedActivityOAuthAPI,
    private val tokenApi: EmbeddedActivityOAuthAPI,
    private val applicationId: Long,
    private val channel: Channel,
    private val currentUserId: Long,
) {
    fun requiresConsent(request: EmbeddedActivityAuthorizeRequest): Observable<Boolean> {
        try {
            validate(request)
        } catch (failure: EmbeddedActivityOAuthException) {
            return Observable.error(failure)
        }
        if (!request.wantsSilentConsent) return Observable.just(true)
        return authorizeApi.preauthorize(
            clientId = request.clientId.toString(),
            responseType = "code",
            scope = request.scopes.joinToString(" "),
            state = request.state,
            codeChallenge = request.codeChallenge,
            codeChallengeMethod = request.codeChallengeMethod,
            integrationType = EmbeddedActivityOAuthAuthorizeBody.USER_INSTALL,
        ).map { !it.authorized }
    }

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
        return authorizeApi.authorize(
            clientId = request.clientId.toString(),
            responseType = "code",
            scope = request.scopes.joinToString(" "),
            state = request.state,
            codeChallenge = request.codeChallenge,
            codeChallengeMethod = request.codeChallengeMethod,
            body = body,
        ).map { response -> extractCode(response.location, request.state) }
    }

    fun authenticate(accessToken: String): Observable<EmbeddedActivityOAuthAuthentication> {
        if (!isValidAccessToken(accessToken)) {
            return Observable.error(EmbeddedActivityOAuthException.AuthenticationFailed())
        }
        return tokenApi.authenticate("Bearer $accessToken").map { authorization ->
            if (authorization.application.id != applicationId || authorization.user?.id != currentUserId) {
                throw EmbeddedActivityOAuthException.AuthenticationFailed()
            }
            EmbeddedActivityOAuthAuthentication(authorization, accessToken)
        }
    }

    /** As [authenticate], but answering with the raw body so it can be forwarded untouched. */
    fun authenticateRaw(accessToken: String): Observable<JsonObject> {
        if (!isValidAccessToken(accessToken)) {
            return Observable.error(EmbeddedActivityOAuthException.AuthenticationFailed())
        }
        return tokenApi.authenticateRaw("Bearer $accessToken").map { body ->
            val application = body.getAsJsonObject("application")?.get("id")?.asString?.toLongOrNull()
            val user = body.getAsJsonObject("user")?.get("id")?.asString?.toLongOrNull()
            if (application != applicationId || user != currentUserId) {
                throw EmbeddedActivityOAuthException.AuthenticationFailed()
            }
            body
        }
    }

    private fun validate(request: EmbeddedActivityAuthorizeRequest) {
        if (request.clientId != applicationId) throw EmbeddedActivityOAuthException.InvalidClient()
        if (request.scopes.isEmpty() || request.scopes.size > MAX_SCOPES ||
            request.scopes.any { !SCOPE.matches(it) } ||
            request.state?.let { it.length > MAX_STATE_LENGTH || it.any(Char::isISOControl) } == true ||
            request.codeChallenge?.let { !CODE_CHALLENGE.matches(it) } == true ||
            request.codeChallengeMethod?.let { it != "S256" || request.codeChallenge == null } == true
        ) throw EmbeddedActivityOAuthException.InvalidRequest()
    }

    companion object {
        private const val MAX_SCOPES = 50
        private const val MAX_STATE_LENGTH = 2048
        private const val MAX_ACCESS_TOKEN_LENGTH = 8192
        private val SCOPE = Regex("[a-z0-9._:-]{1,128}")
        private val CODE_CHALLENGE = Regex("[A-Za-z0-9_-]{43,128}")

        internal fun extractCode(location: String, expectedState: String?): String {
            val uri = runCatching { URI(location) }.getOrNull()
                ?: throw EmbeddedActivityOAuthException.AuthorizationFailed("Unparseable redirect")
            val params = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate { entry ->
                val key = entry.substringBefore('=')
                val value = entry.substringAfter('=', "")
                decode(key) to decode(value)
            }
            val code = params["code"]?.takeIf(String::isNotBlank)
                ?: throw EmbeddedActivityOAuthException.AuthorizationFailed(
                    "No auth code in redirect: ${params.keys.joinToString()}",
                )
            // The SDK sends `state: ""` and the server echoes nothing back, so empty is no state.
            if (!expectedState.isNullOrEmpty() && params["state"] != expectedState) {
                throw EmbeddedActivityOAuthException.AuthorizationFailed("Redirect state mismatch")
            }
            return code
        }

        private fun isValidAccessToken(token: String) =
            token.isNotBlank() && token.length <= MAX_ACCESS_TOKEN_LENGTH && token.none(Char::isWhitespace)

        private fun decode(value: String) = URLDecoder.decode(value, Charsets.UTF_8.name())
    }
}
