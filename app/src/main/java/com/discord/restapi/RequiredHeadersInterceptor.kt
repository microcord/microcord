package com.discord.restapi

import okhttp3.Interceptor
import okhttp3.Response

class RequiredHeadersInterceptor(private val headersProvider: HeadersProvider) : Interceptor {
    interface HeadersProvider {
        fun getAuthToken(): String?
        fun getFingerprint(): String?
        fun getLocale(): String?
        fun getAcceptLanguages(): String?
        fun getSpotifyToken(): String?

        /** Comma-separated debug switches, or null when none are on. */
        fun getDebugOptions(): String? = null

        /** The install's tracking token, or null without consent. */
        fun getInstallationId(): String? = null
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        // A call that carries its own Authorization keeps it: multi-account work has to reach the
        // API as an account other than the active one, or as nobody at all when the header is blank.
        val explicitAuthorization = chain.request().header("Authorization") != null
        val request = chain.request().newBuilder()
            .apply {
                if (!explicitAuthorization) headersProvider.getAuthToken()?.let { header("Authorization", it) }
                headersProvider.getFingerprint()?.let { header("X-Fingerprint", it) }
                headersProvider.getLocale()?.let { header("X-Discord-Locale", it) }
                headersProvider.getAcceptLanguages()?.let { header("Accept-Language", it) }
                headersProvider.getDebugOptions()?.let { header("X-Debug-Options", it) }
                headersProvider.getInstallationId()?.let { header("X-Installation-ID", it) }
            }
            .build()
        return chain.proceed(request)
    }
}
