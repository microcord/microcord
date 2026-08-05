package com.discord.utilities.rest

import com.discord.restapi.CaptchaInterceptor
import com.discord.restapi.ChannelReorderDiagnostics
import com.discord.restapi.ApplicationDirectoryAPI
import com.discord.restapi.EmbeddedActivitiesAPI
import com.discord.restapi.EmbeddedActivityOAuthAPI
import com.discord.restapi.MfaInterceptor
import com.discord.restapi.RestAPIBuilder
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.ClientMetadataInterceptor
import okhttp3.CookieJar
import okhttp3.Interceptor

/**
 * Process-wide access point for the authenticated REST client.
 *
 * The reference exposes the same thing as `RestAPI.api`, and every screen that talks to the API
 * reads it from there rather than threading a client down through fragment arguments. The clients
 * are still owned and configured by the store graph; this object only publishes the normal
 * and null-serializing variants.
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
    private var installedApplicationDirectory: ApplicationDirectoryAPI? = null

    @Volatile
    private var installedEmbeddedActivities: EmbeddedActivitiesAPI? = null

    @Volatile
    private var installedEmbeddedActivityOAuth: EmbeddedActivityOAuthAPI? = null

    @Volatile
    private var installedEmbeddedActivityOAuthToken: EmbeddedActivityOAuthAPI? = null

    /** Publishes the store graph's REST client. Safe to call more than once. */
    @JvmStatic
    fun install(api: RestAPIInterface, apiSerializeNulls: RestAPIInterface = api) {
        installed = api
        installedSerializeNulls = apiSerializeNulls
    }

    /** Builds and publishes the authenticated client variants used by production. */
    @JvmStatic
    fun install(builder: RestAPIBuilder, interceptors: List<Interceptor>): RestAPIInterface {
        // Both retry by re-running the chain below them, so each picks up fresh headers. MFA sits
        // outside captcha because its retry then carries the elevation through a captcha replay;
        // the other order re-prompts the user for a code the request already earned.
        val chain = listOf(MfaInterceptor(), CaptchaInterceptor()) + interceptors + ClientMetadataInterceptor
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
        installedApplicationDirectory = builder.build(
            ApplicationDirectoryAPI::class.java,
            interceptors = chain,
            clientName = "applicationDirectory",
        )
        installedEmbeddedActivities = builder.build(
            EmbeddedActivitiesAPI::class.java,
            interceptors = chain,
            clientName = "embeddedActivities",
        )
        installedEmbeddedActivityOAuth = builder.build(
            EmbeddedActivityOAuthAPI::class.java,
            interceptors = chain,
            clientName = "embeddedActivityOAuth",
        )
        installedEmbeddedActivityOAuthToken = builder.build(
            EmbeddedActivityOAuthAPI::class.java,
            interceptors = listOf(ClientMetadataInterceptor),
            clientName = "embeddedActivityOAuthToken",
            cookieJar = CookieJar.NO_COOKIES,
        )
        install(api, apiSerializeNulls)
        return api
    }

    /** Drops the published client. Used by tests so one case cannot leak into the next. */
    @JvmStatic
    fun reset() {
        installed = null
        installedSerializeNulls = null
        installedApplicationDirectory = null
        installedEmbeddedActivities = null
        installedEmbeddedActivityOAuth = null
        installedEmbeddedActivityOAuthToken = null
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

    /** The null-serializing client used by bodies with explicit JSON nulls. */
    @JvmStatic
    val apiSerializeNulls: RestAPIInterface
        get() = checkNotNull(installedSerializeNulls) {
            "RestAPI.install() has not been called; the store graph publishes the REST client."
        }

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
