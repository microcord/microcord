package com.discord.gateway

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

interface GatewaySocketLogger {
    val logLevel: LogLevel

    fun logInboundMessage(rawMessage: String)
    fun logMessageInflateFailed(throwable: Throwable)
    fun logOutboundMessage(rawMessage: String)

    enum class LogLevel { NONE, VERBOSE }
}

internal object NoopGatewaySocketLogger : GatewaySocketLogger {
    override val logLevel = GatewaySocketLogger.LogLevel.NONE
    override fun logInboundMessage(rawMessage: String) = Unit
    override fun logMessageInflateFailed(throwable: Throwable) = Unit
    override fun logOutboundMessage(rawMessage: String) = Unit
}

internal object GatewayLogRedactor {
    private val sensitiveKeys = setOf(
        "analytics_token",
        "auth_session_id_hash",
        "auth_token",
        "authorization",
        "captcha_key",
        "captcha_rqtoken",
        "client_secret",
        "code",
        "content",
        "email",
        "mfa_code",
        "nonce",
        "password",
        "phone",
        "session_id",
        "static_client_session_id",
        "token",
        "user_settings_proto",
    )

    fun redact(rawMessage: String): String = runCatching {
        JsonParser.parseString(rawMessage).also(::redact).toString()
    }.getOrDefault("<redacted malformed gateway payload>")

    private fun redact(element: JsonElement) {
        when {
            element.isJsonArray -> element.asJsonArray.forEach(::redact)
            element.isJsonObject -> element.asJsonObject.entrySet().forEach { (key, value) ->
                if (key.lowercase() in sensitiveKeys) {
                    element.asJsonObject.add(key, JsonPrimitive("<redacted>"))
                } else {
                    redact(value)
                }
            }
        }
    }
}
