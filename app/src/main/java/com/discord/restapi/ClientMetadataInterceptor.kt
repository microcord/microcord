package com.discord.restapi

import com.discord.utilities.analytics.AnalyticSuperProperties
import java.util.TimeZone
import okhttp3.Interceptor

/** Client identity every request carries, authenticated or not. */
object ClientMetadataInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain) = chain.proceed(
        chain.request().newBuilder()
            .header("User-Agent", AnalyticSuperProperties.USER_AGENT)
            .header("X-Super-Properties", AnalyticSuperProperties.superPropertiesStringBase64)
            .header("X-Discord-Timezone", TimeZone.getDefault().id)
            .build(),
    )
}
