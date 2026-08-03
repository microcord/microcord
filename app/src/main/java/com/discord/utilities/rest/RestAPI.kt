package com.discord.utilities.rest

import com.discord.restapi.CaptchaInterceptor
import com.discord.restapi.ChannelReorderDiagnostics
import com.discord.restapi.ApplicationDirectoryAPI
import com.discord.restapi.EmbeddedActivitiesAPI
import com.discord.restapi.EmbeddedActivityOAuthAPI
import com.discord.restapi.RestAPIBuilder
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.ReactNativeHeadersInterceptor
import com.discord.utilities.analytics.AnalyticSuperProperties
import okhttp3.CookieJar
import okhttp3.Interceptor

/**
 * Process-wide access point for the authenticated REST client.
 *
 * The reference exposes the same thing as `RestAPI.api`, and every screen that talks to the API
 * reads it from there rather than threading a client down through fragment arguments. The clients
 * are still owned and configured by the store graph; this object only publishes the normal
 * and null-serializing variants used by 126.21.
 *
 * `StoreStream` must call [install] once it has built its `RestAPIInterface`; until it does,
 * [apiOrNull] is `null` and callers degrade instead of crashing.
 */
object RestAPI {

    @Volatile
    private var installed: RestAPIInterface? = null

    @Volatile
    private var installedSerializeNulls: RestAPIInterface? = null

    @Volatile
    private var installedReactNative: RestAPIInterface? = null

    @Volatile
    private var installedApplicationDirectory: ApplicationDirectoryAPI? = null

    @Volatile
    private var installedEmbeddedActivities: EmbeddedActivitiesAPI? = null

    @Volatile
    private var installedEmbeddedActivityOAuth: EmbeddedActivityOAuthAPI? = null

    @Volatile
    private var installedEmbeddedActivityOAuthToken: EmbeddedActivityOAuthAPI? = null

    /** Publishes the store graph's REST client. Safe to call more than once. */
    @JvmStatic
    fun install(
        api: RestAPIInterface,
        apiSerializeNulls: RestAPIInterface = api,
        reactNativeApi: RestAPIInterface = api,
    ) {
        installed = api
        installedSerializeNulls = apiSerializeNulls
        installedReactNative = reactNativeApi
    }

    /** Builds and publishes the authenticated client variants used by production. */
    @JvmStatic
    fun install(builder: RestAPIBuilder, interceptors: List<Interceptor>): RestAPIInterface {
        // Outermost, so a captcha retry re-runs the chain below it and picks up fresh headers.
        val chain = listOf(CaptchaInterceptor()) + interceptors
        val api = builder.build(
            RestAPIInterface::class.java,
            interceptors = chain,
            clientName = "main",
        )
        val apiSerializeNulls = builder.build(
            RestAPIInterface::class.java,
            serializeNulls = true,
            interceptors = chain + ChannelReorderDiagnostics,
            clientName = "serializeNulls",
        )
        val reactNativeApi = builder.build(
            RestAPIInterface::class.java,
            interceptors = chain + ReactNativeHeadersInterceptor,
            clientName = "reactNative",
        )
        installedApplicationDirectory = builder.build(
            ApplicationDirectoryAPI::class.java,
            interceptors = chain + ReactNativeHeadersInterceptor,
            clientName = "applicationDirectory",
        )
        installedEmbeddedActivities = builder.build(
            EmbeddedActivitiesAPI::class.java,
            interceptors = chain + ReactNativeHeadersInterceptor,
            clientName = "embeddedActivities",
        )
        installedEmbeddedActivityOAuth = builder.build(
            EmbeddedActivityOAuthAPI::class.java,
            interceptors = chain + ReactNativeHeadersInterceptor,
            clientName = "embeddedActivityOAuth",
        )
        installedEmbeddedActivityOAuthToken = builder.build(
            EmbeddedActivityOAuthAPI::class.java,
            interceptors = listOf(ReactNativeHeadersInterceptor),
            clientName = "embeddedActivityOAuthToken",
            cookieJar = CookieJar.NO_COOKIES,
        )
        install(api, apiSerializeNulls, reactNativeApi)
        return api
    }

    /** Drops the published client. Used by tests so one case cannot leak into the next. */
    @JvmStatic
    fun reset() {
        installed = null
        installedSerializeNulls = null
        installedReactNative = null
        installedApplicationDirectory = null
        installedEmbeddedActivities = null
        installedEmbeddedActivityOAuth = null
        installedEmbeddedActivityOAuthToken = null
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

    /** The 126.21 null-serializing client used by bodies with explicit JSON nulls. */
    @JvmStatic
    val apiSerializeNulls: RestAPIInterface
        get() = checkNotNull(installedSerializeNulls) {
            "RestAPI.install() has not been called; the store graph publishes the REST client."
        }

    @JvmStatic
    val reactNativeApiOrNull: RestAPIInterface?
        get() = installedReactNative

    @JvmStatic
    val applicationDirectoryApiOrNull: ApplicationDirectoryAPI?
        get() = installedApplicationDirectory

    @JvmStatic
    val embeddedActivitiesApiOrNull: EmbeddedActivitiesAPI?
        get() = installedEmbeddedActivities

    @JvmStatic
    val embeddedActivityOAuthApiOrNull: EmbeddedActivityOAuthAPI?
        get() = installedEmbeddedActivityOAuth

    @JvmStatic
    val embeddedActivityOAuthTokenApiOrNull: EmbeddedActivityOAuthAPI?
        get() = installedEmbeddedActivityOAuthToken
}
