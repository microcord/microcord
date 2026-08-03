package com.discord.restapi

import com.discord.BuildConfig
import com.discord.app.AppLog
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.Model
import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Invocation
import retrofit2.Retrofit
import retrofit2.adapter.rxjava.RxJavaCallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HEAD
import retrofit2.http.HTTP
import retrofit2.http.OPTIONS
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT

internal fun OkHttpClient.Builder.addDebugRestLogging(
    enabled: Boolean,
    logger: (String) -> Unit = { AppLog.d("REST", it) },
) = apply {
    if (enabled) addInterceptor(RestMetadataLoggingInterceptor(logger))
}

internal class RestMetadataLoggingInterceptor(
    private val logger: (String) -> Unit,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startedAt = System.nanoTime()
        val prefix = "method=${request.method} path=${request.routeTemplate()}"
        return try {
            chain.proceed(request).also { response ->
                logger("$prefix status=${response.code} durationMs=${elapsedMillis(startedAt)}")
            }
        } catch (failure: Exception) {
            logger("$prefix status=failed durationMs=${elapsedMillis(startedAt)} error=${failure.javaClass.simpleName}")
            throw failure
        }
    }

    private fun okhttp3.Request.routeTemplate(): String {
        val method = tag(Invocation::class.java)?.method() ?: return REDACTED_PATH
        val route = when (this.method) {
            "DELETE" -> method.getAnnotation(DELETE::class.java)?.value
            "GET" -> method.getAnnotation(GET::class.java)?.value
                ?: method.getAnnotation(HEAD::class.java)?.value
            "PATCH" -> method.getAnnotation(PATCH::class.java)?.value
            "POST" -> method.getAnnotation(POST::class.java)?.value
            "PUT" -> method.getAnnotation(PUT::class.java)?.value
            "OPTIONS" -> method.getAnnotation(OPTIONS::class.java)?.value
            else -> null
        } ?: method.getAnnotation(HTTP::class.java)?.path
        return route?.substringBefore('?')?.takeIf(String::isNotBlank)?.let { "/${it.trimStart('/')}" }
            ?: REDACTED_PATH
    }

    private fun elapsedMillis(startedAt: Long) =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private companion object {
        const val REDACTED_PATH = "[redacted]"
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
                    addConverterFactory(GsonConverterFactory.create(gson))
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
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L

        @JvmStatic
        var clientCallback: (String, OkHttpClient) -> Unit = { _, _ -> }
    }
}
