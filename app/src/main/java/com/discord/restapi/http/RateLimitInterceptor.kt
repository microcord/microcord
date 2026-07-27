package com.discord.restapi.http

import com.google.gson.JsonParser
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class RateLimitInterceptor(
    private val maxRetries: Int = 0,
    private val maxDelayMillis: Long = 60_000,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
) : Interceptor {
    private var globalUntilMillis = 0L
    private val routeUntilMillis = mutableMapOf<String, Long>()

    init {
        require(maxRetries >= 0)
        require(maxDelayMillis >= 0)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val route = route(request)
        var retries = 0
        while (true) {
            waitUntilAvailable(route)
            val response = chain.proceed(request)
            if (response.code != 429) {
                recordExhaustedRoute(response, route)
                return response
            }

            val body = response.peekBody(64 * 1024).string()
            val retryAfter = retryAfterMillis(body, response).coerceIn(0, maxDelayMillis)
            val global = response.header("X-RateLimit-Global") == "true" || isGlobal(body)
            recordLimit(route, retryAfter, global)
            if (retries++ >= maxRetries) return response
            response.close()
        }
    }

    private fun waitUntilAvailable(route: String) {
        val delay = synchronized(this) {
            maxOf(globalUntilMillis, routeUntilMillis[route] ?: 0L) - nowMillis()
        }
        if (delay > 0) sleep(delay)
    }

    private fun recordLimit(route: String, delayMillis: Long, global: Boolean) = synchronized(this) {
        val until = nowMillis() + delayMillis
        if (global) globalUntilMillis = maxOf(globalUntilMillis, until)
        else routeUntilMillis[route] = maxOf(routeUntilMillis[route] ?: 0L, until)
    }

    private fun recordExhaustedRoute(response: Response, route: String) {
        if (response.header("X-RateLimit-Remaining") != "0") return
        val delay = response.header("X-RateLimit-Reset-After")
            ?.toDoubleOrNull()
            ?.times(1000)
            ?.toLong()
            ?.coerceIn(0, maxDelayMillis)
            ?: return
        recordLimit(route, delay, false)
    }

    private fun retryAfterMillis(body: String, response: Response): Long {
        val bodySeconds = runCatching {
            JsonParser.parseString(body).asJsonObject.get("retry_after")?.asDouble
        }.getOrNull()
        val seconds = bodySeconds ?: response.header("Retry-After")?.toDoubleOrNull() ?: 0.0
        return (seconds * 1000).toLong()
    }

    private fun isGlobal(body: String): Boolean = runCatching {
        JsonParser.parseString(body).asJsonObject.get("global")?.asBoolean == true
    }.getOrDefault(false)

    private fun route(request: Request) = "${request.method} ${request.url.encodedPath}"
}
