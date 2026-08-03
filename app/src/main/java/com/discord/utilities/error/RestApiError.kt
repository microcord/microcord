package com.discord.utilities.error

import android.content.Context
import android.widget.Toast
import com.discord.R
import com.discord.utilities.captcha.CaptchaCancellationException
import com.discord.utilities.captcha.CaptchaErrorBody
import com.discord.utilities.captcha.CaptchaHelper
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import retrofit2.HttpException

/**
 * Structured, classified failure for every REST and Rx call in the app.
 *
 * This is the reconstruction of the reference `com.discord.utilities.error.Error` (plus its nested
 * `Error.Response`/`Error.SkemaError` model). The class is deliberately *not* named `Error` here:
 * `kotlin.Error` is a default import, so a first-party `Error` would shadow it at every call site
 * and force qualified imports throughout the app. The responsibility is unchanged, so the clearer
 * name is kept and the reference field names are preserved:
 *
 * | reference                      | here                        |
 * | ------------------------------ | --------------------------- |
 * | `Error.type`                   | [type]                      |
 * | `Error.throwable`              | [cause]                     |
 * | `Error.response.code`          | [code]                      |
 * | `Error.response.message`       | [message]                   |
 * | `Error.response.messages`      | [messages]                  |
 * | `Error.response.retryAfterMs`  | [retryAfterMs]              |
 * | `Error.response.global`        | [global]                    |
 * | `Error.response.isKnownResponse` | [isKnownResponse]         |
 * | `Error.metadata`               | [metadata]                  |
 * | `Error.showErrorToasts`        | [showErrorToasts]           |
 * | `Error.shouldLog`              | [shouldLog]                 |
 * | `Error.getDisplayString`       | [displayString]             |
 * | `Error.showToasts`             | [showToasts]                |
 * | `Error.handle`                 | [Companion.handle]          |
 * | `Error.init`                   | [Companion.init]            |
 *
 * The reference's flat `Error.Response` sub-object is folded into this class because nothing in the
 * app ever holds a `Response` without its owning error.
 *
 * [toString] redacts credentials and query strings even though the reference generated `toString`
 * did not, per the project's secret-handling rule.
 */
class RestApiError private constructor(
    val type: Type,
    val statusCode: Int?,
    val code: Int,
    val message: String?,
    val retryAfterMs: Long?,
    val global: Boolean,
    val messages: Map<String, List<String>>,
    val requestUrl: String?,
    /** Set only when `CaptchaInterceptor` did not resolve the challenge itself. */
    val captchaChallenge: CaptchaErrorBody?,
    val cause: Throwable,
    val metadata: Map<String, String>? = null,
) {
    enum class Type {
        FORBIDDEN_CLOUD_FLARE,
        FORBIDDEN_DISCORD,
        INTERMITTENT_CLOUD_FLARE,
        DISCORD_REQUEST_ERROR,
        DISCORD_REQUEST_RESOURCE_NOT_FOUND,
        DISCORD_BAD_REQUEST,
        INTERNAL_SERVER_ERROR,
        REQUEST_TOO_LARGE,
        UNAUTHORIZED,
        DISCORD_REQUEST_ERROR_UNKNOWN,
        RATE_LIMITED,
        NETWORK,
        SSL,
        TIMEOUT,
        CAPTCHA_KNOWN_FAILURE,
        CAPTCHA_CANCELLED,
        IMAGE_NOT_FOUND,
        AUTOMOD_MESSAGE_BLOCKED,
        OTHER,
    }

    private val showErrorToastsFlag = AtomicBoolean(true)
    private val shouldLogFlag = AtomicBoolean(true)

    /** When false, [showToasts] is a no-op. Screens that render the failure inline clear this. */
    var showErrorToasts: Boolean
        get() = showErrorToastsFlag.get()
        set(value) = showErrorToastsFlag.set(value)

    /** When false, [handle] will not report the failure to the unhandled-error sink. */
    var shouldLog: Boolean
        get() = shouldLogFlag.get()
        set(value) = shouldLogFlag.set(value)

    /** Mirrors `Error.Response.isKnownResponse`: the body carried a Discord error shape. */
    val isKnownResponse: Boolean
        get() = code > 0 || messages.isNotEmpty()

    /** True when the API asked the client to solve a captcha before retrying. */
    val isCaptchaRequired: Boolean
        get() = captchaChallenge != null

    /**
     * Reference `Error.Response.getMessageToast`: prefer the API's own message, then the first
     * field-level validation message, then the supplied fallback.
     */
    private fun messageToast(context: Context, fallbackResId: Int): String =
        message
            ?: messages.values.firstNotNullOfOrNull { values -> values.firstOrNull() }
            ?: context.getString(fallbackResId)

    /** Reference `Error.getDisplayString` / `getToastMessages`. */
    fun displayString(context: Context): String = when (type) {
        Type.FORBIDDEN_CLOUD_FLARE -> context.getString(R.string.network_error_cloudflare_unauthorized)
        Type.FORBIDDEN_DISCORD -> messageToast(context, R.string.network_error_forbidden)
        Type.INTERMITTENT_CLOUD_FLARE -> context.getString(R.string.network_error_cloudflare_intermittent)
        Type.DISCORD_REQUEST_ERROR,
        Type.DISCORD_REQUEST_ERROR_UNKNOWN,
        -> messageToast(context, R.string.network_error_rest_request)
        Type.DISCORD_BAD_REQUEST -> messageToast(context, R.string.network_error_bad_request)
        Type.INTERNAL_SERVER_ERROR -> context.getString(R.string.internal_server_error)
        Type.REQUEST_TOO_LARGE -> context.getString(R.string.network_error_request_too_large)
        Type.UNAUTHORIZED -> context.getString(R.string.network_error_unauthorized)
        Type.RATE_LIMITED -> context.getString(R.string.rate_limited)
        Type.NETWORK -> context.getString(R.string.network_error_connection)
        Type.SSL -> context.getString(R.string.network_error_ssl)
        Type.TIMEOUT -> context.getString(R.string.timeout_error)
        Type.CAPTCHA_KNOWN_FAILURE,
        Type.CAPTCHA_CANCELLED,
        -> context.getString(R.string.captcha_failed)
        Type.DISCORD_REQUEST_RESOURCE_NOT_FOUND,
        Type.IMAGE_NOT_FOUND,
        Type.AUTOMOD_MESSAGE_BLOCKED,
        Type.OTHER,
        -> context.getString(R.string.network_error_unknown)
    }

    /** Reference `Error.showToasts`. Must be called on the main thread. */
    fun showToasts(context: Context?) {
        if (!showErrorToasts || context == null) return
        Toast.makeText(context, displayString(context), Toast.LENGTH_SHORT).show()
    }

    override fun toString(): String = "RestApiError(type=$type, statusCode=$statusCode, " +
        "code=$code, message=${redact(message)}, retryAfterMs=$retryAfterMs, global=$global, " +
        "messages=${messages.mapValues { (_, values) -> values.map(::redact) }}, requestUrl=${safeUrl(requestUrl)})"

    companion object {
        /**
         * Process-wide sink for failures nobody handled, installed once during application startup.
         * Mirrors the reference `Error.init(Action3<String, Throwable, Map<String, String>>)`.
         */
        @Volatile
        private var onUnhandledError: ((tag: String, throwable: Throwable, metadata: Map<String, String>?) -> Unit)? = null

        @JvmStatic
        fun init(sink: (tag: String, throwable: Throwable, metadata: Map<String, String>?) -> Unit) {
            onUnhandledError = sink
        }

        /**
         * The error funnel used by `ObservableExtensionsKt.appSubscribe`.
         *
         * Classifies [throwable], gives [errorHandler] the first chance to consume it, reports
         * anything unclassified to the unhandled sink, and finally shows the user-facing toast
         * unless [suppressToasts] is set. A handler that itself throws is reported rather than
         * propagated, exactly as in the reference, so one bad screen cannot kill the stream's
         * thread.
         */
        @JvmStatic
        @JvmOverloads
        fun handle(
            throwable: Throwable,
            tag: String,
            errorHandler: ((RestApiError) -> Unit)? = null,
            context: Context? = null,
            suppressToasts: Boolean = false,
        ) {
            val error = from(throwable)
            if (errorHandler != null) {
                try {
                    errorHandler(error)
                } catch (handlerFailure: Exception) {
                    onUnhandledError?.invoke(
                        tag,
                        IllegalStateException("$tag / $error", handlerFailure),
                        null,
                    )
                    return
                }
            }
            error.log(tag)
            if (!suppressToasts) error.showToasts(context)
        }

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
            val requestUrl = response?.raw()?.request?.url?.toString()
            return RestApiError(
                type,
                status,
                code,
                message,
                retryAfter,
                json?.get("global")?.takeUnless(JsonElement::isJsonNull)?.asBoolean == true,
                messages,
                requestUrl,
                if (status == 400) CaptchaErrorBody.createFromString(body) else null,
                throwable,
                metadata = if (type == Type.DISCORD_REQUEST_ERROR_UNKNOWN) {
                    metadataOf(requestUrl, status, contentType, response?.raw()?.header("CF-Ray"))
                } else {
                    null
                },
            )
        }

        private fun nonHttp(throwable: Throwable): RestApiError {
            val type = when {
                throwable is CaptchaHelper.Failure -> Type.CAPTCHA_KNOWN_FAILURE
                // Before the IOException arm: it is one, but it is a user decision, not a fault.
                throwable is CaptchaCancellationException -> Type.CAPTCHA_CANCELLED
                throwable is AppCancellationException -> Type.OTHER
                throwable is SSLException -> Type.SSL
                throwable is UnknownHostException -> Type.NETWORK
                throwable is IOException -> if (isTransportInterruption(throwable)) Type.NETWORK else Type.OTHER
                throwable is TimeoutException -> Type.TIMEOUT
                else -> Type.OTHER
            }
            return RestApiError(
                type, null, 0, throwable.message, null, false, emptyMap(),
                null, null, throwable,
            ).apply {
                if (type == Type.CAPTCHA_CANCELLED) {
                    showErrorToasts = false
                    shouldLog = false
                }
            }
        }

        /**
         * Reference `Error.create` treats cancelled/reset/interrupted IO as ordinary connectivity
         * loss rather than a bug worth reporting.
         */
        private fun isTransportInterruption(throwable: IOException): Boolean {
            val text = throwable.message.orEmpty()
            return text.contains("Canceled") ||
                text.contains("Connection reset by peer") ||
                text.contains("stream was reset:") ||
                throwable is NoRouteToHostException ||
                throwable is SocketException ||
                throwable is InterruptedIOException ||
                throwable.cause is InterruptedException ||
                throwable.cause is SocketTimeoutException
        }

        private fun metadataOf(url: String?, status: Int, contentType: String, cfRay: String?) = buildMap {
            put("responseCode", status.toString())
            safeUrl(url)?.let { put("requestUrl", it) }
            put("content-type", contentType)
            cfRay?.let { put("CF-Ray", it) }
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

    /**
     * Reference `Error.logError`: only unclassified failures and unknown Discord request errors
     * reach the crash reporter; expected connectivity and validation failures stay quiet.
     */
    private fun log(tag: String) {
        if (!shouldLog) return
        when (type) {
            Type.DISCORD_REQUEST_ERROR_UNKNOWN -> onUnhandledError?.invoke(type.name, cause, metadata)
            Type.OTHER -> onUnhandledError?.invoke(tag, cause, metadata)
            else -> Unit
        }
    }
}
