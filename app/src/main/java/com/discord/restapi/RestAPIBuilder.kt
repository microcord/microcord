package com.discord.restapi

import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.adapter.rxjava.RxJavaCallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory

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
    ): T {
        require(contentType == CONTENT_TYPE_JSON || contentType == CONTENT_TYPE_TEXT)
        val gson = gson(serializeNulls)
        val client = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .apply { interceptors.forEach(::addInterceptor) }
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
