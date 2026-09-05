package com.discord.restapi

import com.discord.BuildConfig
import com.discord.app.AppLog
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.Model
import com.discord.utilities.logging.AppRestRequestLogger
import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import retrofit2.Retrofit
import retrofit2.adapter.rxjava.RxJavaCallAdapterFactory

internal fun OkHttpClient.Builder.addDebugRestLogging(
    enabled: Boolean,
    logger: (String) -> Unit = { AppLog.d("REST", it) },
    verbose: () -> Boolean = AppRestRequestLogger::isEnabled,
) = apply {
    if (enabled) addInterceptor(RestMetadataLoggingInterceptor(logger, verbose))
}

internal class RestMetadataLoggingInterceptor(
    private val logger: (String) -> Unit,
    private val verbose: () -> Boolean = { false },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startedAt = System.nanoTime()
        val prefix = "method=${request.method} path=${request.url.encodedPath}"
        val verbose = verbose()
        if (verbose) {
            logger("$prefix requestHeaders=${request.headers.sanitized()} requestBody=${request.body.sanitized()}")
        }
        return try {
            chain.proceed(request).also { response ->
                logger("$prefix status=${response.code} durationMs=${elapsedMillis(startedAt)}")
                if (verbose) {
                    logger(
                        "$prefix responseHeaders=${response.headers.sanitized()} " +
                            "responseBody=${RestLogRedactor.redact(response.peekBody(MAX_LOG_BODY_BYTES).string())}",
                    )
                }
            }
        } catch (failure: Exception) {
            logger("$prefix status=failed durationMs=${elapsedMillis(startedAt)} error=${failure.javaClass.simpleName}")
            throw failure
        }
    }

    private fun elapsedMillis(startedAt: Long) =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private fun okhttp3.Headers.sanitized(): String = names().sorted().joinToString(prefix = "{", postfix = "}") { name ->
        "$name=${if (name.lowercase() in SENSITIVE_HEADERS) "<redacted>" else values(name).joinToString() }"
    }

    private fun RequestBody?.sanitized(): String {
        this ?: return "<none>"
        if (this is MultipartBody) return parts.mapIndexed { index, part ->
            val disposition = part.headers?.get("Content-Disposition").orEmpty()
            if (disposition.contains("name=\"payload_json\"")) {
                "payload_json=${part.body.readAndRedact()}"
            } else {
                "file[$index]=<redacted> contentType=${part.body.contentType()} size=${runCatching { part.body.contentLength() }.getOrDefault(-1)}"
            }
        }.joinToString(prefix = "[", postfix = "]")
        return if (contentType()?.subtype == "json") readAndRedact() else "<redacted ${contentType()}>"
    }

    private fun RequestBody.readAndRedact(): String = runCatching {
        if (contentLength() > MAX_LOG_BODY_BYTES) return@runCatching "<redacted body too large>"
        Buffer().also(::writeTo).readUtf8().let(RestLogRedactor::redact)
    }.getOrDefault("<redacted unreadable body>")

    private companion object {
        const val MAX_LOG_BODY_BYTES = 65_536L
        val SENSITIVE_HEADERS = setOf("authorization", "cookie", "set-cookie", "x-super-properties")
    }
}

internal object RestLogRedactor {
    private val sensitiveKeys = setOf(
        "authorization", "captcha_key", "captcha_rqtoken", "client_secret", "content", "email",
        "filename", "mfa_code", "name", "nonce", "password", "phone", "session_id", "token",
        "author_summary", "channel_summary", "message_summary", "notes",
    )

    fun redact(raw: String): String = runCatching {
        JsonParser.parseString(raw).also(::redact).toString()
    }.getOrDefault("<redacted non-json body>")

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

class RestAPIBuilder(
    private val baseApiUrl: String,
    private val cookieJar: CookieJar,
) {
    fun <T> build(
        apiDefinition: Class<T>,
        serializeNulls: Boolean = false,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        interceptors: List<Interceptor> = emptyList(),
        clientName: String? = null,
        addVersion: Boolean = true,
        contentType: String = CONTENT_TYPE_JSON,
        cookieJar: CookieJar = this.cookieJar,
    ): T {
        require(contentType == CONTENT_TYPE_JSON || contentType == CONTENT_TYPE_TEXT)
        val gson = gson(serializeNulls)
        val client = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .apply { interceptors.forEach(::addInterceptor) }
            .addDebugRestLogging(BuildConfig.DEBUG)
            .build()
        clientName?.let { clientCallback(it, client) }

        return Retrofit.Builder()
            .baseUrl(baseApiUrl + if (addVersion) "v$API_VERSION/" else "")
            .client(client)
            .addCallAdapterFactory(RxJavaCallAdapterFactory.create())
            .addConverterFactory(PayloadJsonConverterFactory(gson))
            .apply {
                if (contentType == CONTENT_TYPE_JSON) {
                    addConverterFactory(LoggingGsonConverterFactory(gson))
                }
            }
            .build()
            .create(apiDefinition)
    }

    private fun gson(serializeNulls: Boolean): Gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .registerDiscordApiTypeAdapters()
        .registerTypeAdapterFactory(Model.TypeAdapterFactory())
        // A channel reorder must be able to write `"parent_id": null` to move a channel out of its
        // category while still omitting the key when the parent is unchanged, which the default
        // reflective adapter cannot express.
        .registerTypeAdapter(
            RestAPIParams.ChannelPosition::class.java,
            RestAPIParams.ChannelPosition.Serializer(),
        )
        .apply { if (serializeNulls) serializeNulls() }
        .create()

    companion object {
        const val CONTENT_TYPE_JSON = "application/json"
        const val CONTENT_TYPE_TEXT = "text/plain"
        const val API_VERSION = 9
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L

        @JvmStatic
        var clientCallback: (String, OkHttpClient) -> Unit = { _, _ -> }
    }
}
