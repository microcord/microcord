package com.discord.restapi

import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import okio.Buffer
import okhttp3.Interceptor
import okhttp3.Response

/** Sanitized wire trace for the channel-position endpoint. */
object ChannelReorderDiagnostics : Interceptor {
    data class Event(
        val phase: String,
        val method: String,
        val path: String,
        val status: Int?,
        val requestBodyShape: String,
        val responseBodyShape: String?,
        val failureKind: String? = null,
        val failureType: String? = null,
    )

    @Volatile private var latest: Event? = null

    /** Test/device hook. Values and headers are never exposed. */
    @Volatile var listener: ((Event) -> Unit)? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "PATCH" || !CHANNELS_PATH.matches(request.url.encodedPath)) {
            return chain.proceed(request)
        }
        val path = request.url.encodedPath.replace(NUMERIC_PATH_SEGMENT, "/:id")
        val requestShape = runCatching {
            Buffer().use { buffer ->
                request.body?.writeTo(buffer)
                shape(buffer.readUtf8())
            }
        }.getOrDefault("unreadable")
        publish(Event("request", request.method, path, null, requestShape, null))

        return try {
            chain.proceed(request).also { response ->
                latest = Event(
                    "response",
                    request.method,
                    path,
                    response.code,
                    requestShape,
                    response.peekBody(MAX_PEEK_BYTES).string().let(::shape),
                )
            }
        } catch (failure: Throwable) {
            latest = Event(
                "error",
                request.method,
                path,
                null,
                requestShape,
                null,
                failureKind = "transport",
                failureType = failure.javaClass.simpleName,
            )
            throw failure
        }
    }

    fun recordSuccess() {
        val event = latest ?: return
        publish(event.copy(phase = "response"))
    }

    fun recordFailure(failure: Throwable) {
        val event = latest ?: return
        publish(
            event.copy(
                phase = "error",
                failureKind = when {
                    event.status == null -> "transport"
                    event.status in 200..299 -> "conversion"
                    else -> "http"
                },
                failureType = failure.javaClass.simpleName,
            ),
        )
    }

    private fun publish(event: Event) {
        latest = event
        listener?.invoke(event) ?: Log.i(TAG, event.toString())
    }

    private fun shape(body: String): String {
        if (body.isBlank()) return "empty"
        return runCatching { JsonParser.parseString(body).shape() }.getOrElse {
            when (body.firstOrNull()) {
                '[' -> "json-array"
                '{' -> "json-object"
                else -> "non-json"
            }
        }
    }

    private fun JsonElement.shape(): String = when {
        isJsonNull -> "null"
        isJsonArray -> asJsonArray.map { it.shape() }.distinct().sorted()
            .joinToString(prefix = "array<", postfix = ">", separator = "|")
        isJsonObject -> asJsonObject.entrySet().sortedBy { it.key }
            .joinToString(prefix = "object{", postfix = "}") { (key, value) -> "$key:${value.shape()}" }
        asJsonPrimitive.isBoolean -> "boolean"
        asJsonPrimitive.isNumber -> "number"
        else -> "string"
    }

    private const val TAG = "ChannelReorder"
    private const val MAX_PEEK_BYTES = 64L * 1024L
    private val CHANNELS_PATH = Regex(".*/guilds/\\d+/channels$")
    private val NUMERIC_PATH_SEGMENT = Regex("/\\d+(?=/|$)")
}
