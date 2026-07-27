package com.discord.utilities.rest

import com.discord.restapi.RestAPIInterface
import com.discord.utilities.analytics.AnalyticSuperProperties
import okhttp3.Interceptor

/**
 * Process-wide access point for the authenticated REST client.
 *
 * The reference exposes the same thing as `RestAPI.api`, and every screen that talks to the API
 * reads it from there rather than threading a client down through fragment arguments. The client
 * itself is still owned and configured by the store graph — this object only publishes it, so there
 * is exactly one OkHttp client, one cookie jar and one rate-limit interceptor in the process.
 *
 * `StoreStream` must call [install] once it has built its `RestAPIInterface`; until it does,
 * [apiOrNull] is `null` and callers degrade instead of crashing.
 */
object RestAPI {

    @Volatile
    private var installed: RestAPIInterface? = null

    /** Publishes the store graph's REST client. Safe to call more than once. */
    @JvmStatic
    fun install(api: RestAPIInterface) {
        installed = api
    }

    /** Drops the published client. Used by tests so one case cannot leak into the next. */
    @JvmStatic
    fun reset() {
        installed = null
    }

    /** Adds the same client metadata header as Discord Android 126.21. */
    @JvmStatic
    fun buildAnalyticsInterceptor(
        superPropertiesProvider: () -> String = { AnalyticSuperProperties.superPropertiesStringBase64 },
    ): Interceptor = Interceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .addHeader("X-Super-Properties", superPropertiesProvider())
                .build(),
        )
    }

    /** The REST client, or `null` before the store graph has published one. */
    @JvmStatic
    val apiOrNull: RestAPIInterface?
        get() = installed

    /** The REST client. Only call from a surface that cannot be reached before login. */
    @JvmStatic
    val api: RestAPIInterface
        get() = checkNotNull(installed) {
            "RestAPI.install() has not been called; the store graph publishes the REST client."
        }
}
