package com.discord.utilities.error

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.IOException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException
import retrofit2.HttpException

class RestApiError private constructor(
    val type: Type,
    val statusCode: Int?,
    val code: Int,
    val message: String?,
    val retryAfterMs: Long?,
    val global: Boolean,
    val messages: Map<String, List<String>>,
    val requestUrl: String?,
    val captchaSitekey: String?,
    val captchaRqtoken: String?,
    val cause: Throwable,
) {
    enum class Type {
        INTERNAL_SERVER_ERROR,
        INTERMITTENT_CLOUD_FLARE,
        UNAUTHORIZED,
        FORBIDDEN_CLOUD_FLARE,
        REQUEST_TOO_LARGE,
        DISCORD_BAD_REQUEST,
        FORBIDDEN_DISCORD,
        DISCORD_REQUEST_ERROR,
        DISCORD_REQUEST_RESOURCE_NOT_FOUND,
        RATE_LIMITED,
        DISCORD_REQUEST_ERROR_UNKNOWN,
        NETWORK,
        SSL,
        TIMEOUT,
        OTHER,
    }

    override fun toString(): String = "RestApiError(type=$type, statusCode=$statusCode, " +
        "code=$code, message=${redact(message)}, retryAfterMs=$retryAfterMs, global=$global, " +
        "messages=${messages.mapValues { (_, values) -> values.map(::redact) }}, requestUrl=${safeUrl(requestUrl)})"

    companion object {
        fun from(throwable: Throwable, gson: Gson = Gson()): RestApiError {
            if (throwable !is HttpException) return nonHttp(throwable)
            val response = throwable.response()
            val status = throwable.code()
            val errorBody = response?.errorBody()
            val contentType = errorBody?.contentType()?.subtype.orEmpty()
            val body = runCatching { errorBody?.string().orEmpty() }.getOrDefault("")
            val json = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull()
            val code = json?.get("code")?.takeUnless(JsonElement::isJsonNull)?.asInt ?: 0
            val message = json?.get("message")?.takeUnless(JsonElement::isJsonNull)?.asString
            val messages = linkedMapOf<String, List<String>>()
            json?.getAsJsonObject("errors")?.let { flattenErrors(it, "", messages) }
            json?.entrySet()?.forEach { (key, value) ->
                if (key !in setOf("code", "message", "retry_after", "global", "errors") && value.isJsonArray) {
                    messages[key] = value.asJsonArray.mapNotNull {
                        it.takeIf(JsonElement::isJsonPrimitive)?.asString
                    }
                }
            }
            val retryAfter = json?.get("retry_after")?.takeUnless(JsonElement::isJsonNull)?.asDouble
                ?.takeIf { it > 0 }
                ?.times(1000)
                ?.toLong()
            val known = code > 0 || messages.isNotEmpty()
            val type = when {
                status == 500 -> Type.INTERNAL_SERVER_ERROR
                status in setOf(502, 503, 520, 521, 522, 525) -> Type.INTERMITTENT_CLOUD_FLARE
                status == 401 -> Type.UNAUTHORIZED
                status == 403 && contentType.contains("html") -> Type.FORBIDDEN_CLOUD_FLARE
                status == 413 -> Type.REQUEST_TOO_LARGE
                status == 400 -> Type.DISCORD_BAD_REQUEST
                status == 403 -> Type.FORBIDDEN_DISCORD
                known -> Type.DISCORD_REQUEST_ERROR
                status == 404 -> Type.DISCORD_REQUEST_RESOURCE_NOT_FOUND
                status == 429 -> Type.RATE_LIMITED
                else -> Type.DISCORD_REQUEST_ERROR_UNKNOWN
            }
            return RestApiError(
                type,
                status,
                code,
                message,
                retryAfter,
                json?.get("global")?.takeUnless(JsonElement::isJsonNull)?.asBoolean == true,
                messages,
                response?.raw()?.request?.url?.toString(),
                json?.get("captcha_sitekey")?.takeUnless(JsonElement::isJsonNull)?.asString,
                json?.get("captcha_rqtoken")?.takeUnless(JsonElement::isJsonNull)?.asString,
                throwable,
            )
        }

        private fun nonHttp(throwable: Throwable): RestApiError {
            val type = when (throwable) {
                is SSLException -> Type.SSL
                is UnknownHostException, is NoRouteToHostException, is SocketException -> Type.NETWORK
                is TimeoutException -> Type.TIMEOUT
                is IOException -> Type.NETWORK
                else -> Type.OTHER
            }
            return RestApiError(type, null, 0, throwable.message, null, false, emptyMap(), null, null, null, throwable)
        }

        private fun flattenErrors(node: JsonObject, path: String, output: MutableMap<String, List<String>>) {
            node.entrySet().forEach { (key, value) ->
                val childPath = if (path.isEmpty()) key else "$path.$key"
                if (key == "_errors" && value.isJsonArray) {
                    output[path.ifEmpty { "_misc" }] = value.asJsonArray.mapNotNull {
                        it.takeIf(JsonElement::isJsonObject)?.asJsonObject?.get("message")?.asString
                    }
                } else if (value.isJsonObject) {
                    flattenErrors(value.asJsonObject, childPath, output)
                }
            }
        }

        private fun safeUrl(url: String?): String? = url?.substringBefore('?')

        private fun redact(value: String?): String? = value
            ?.replace(Regex("(?i)bearer\\s+[^,;\\s)]+"), "Bearer <redacted>")
            ?.replace(Regex("(?i)(authorization|cookie|password|token)(\\s*[:=]?\\s*)[^,;\\s)]+"), "$1$2<redacted>")
    }
}
