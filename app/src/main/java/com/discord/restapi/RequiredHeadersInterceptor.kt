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
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .apply {
                headersProvider.getAuthToken()?.let { header("Authorization", it) }
                headersProvider.getFingerprint()?.let { header("X-Fingerprint", it) }
                headersProvider.getLocale()?.let { header("X-Discord-Locale", it) }
                headersProvider.getAcceptLanguages()?.let { header("Accept-Language", it) }
            }
            .build()
        return chain.proceed(request)
    }
}
