package com.discord.gateway.rest

import com.discord.restapi.RequiredHeadersInterceptor.HeadersProvider
import okhttp3.Interceptor

data class RestConfig(
    val baseUrl: String,
    val headersProvider: HeadersProvider,
    val optionalInterceptors: List<Interceptor> = emptyList(),
)
