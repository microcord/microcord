package com.discord.gateway.rest

import android.content.Context
import com.discord.restapi.RequiredHeadersInterceptor
import com.discord.restapi.RestAPIBuilder
import com.discord.restapi.cookie.CookieJars
import java.io.IOException
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.schedulers.Schedulers

internal object RestClient {
    private lateinit var restApi: RestApi

    fun init(restConfig: RestConfig, context: Context) {
        restApi = RestAPIBuilder(restConfig.baseUrl, CookieJars.persistent(context)).build(
            RestApi::class.java,
            interceptors = listOf(RequiredHeadersInterceptor(restConfig.headersProvider)) +
                restConfig.optionalInterceptors,
        )
    }

    fun getGateway(): Observable<String> = restApi.getGateway()
        .subscribeOn(Schedulers.io())
        .retryWhen { errors ->
            var retries = 0
            errors.flatMap { error ->
                if (error is IOException && retries < MAX_RETRIES) {
                    Observable.timer(RETRY_DELAY_MS shl retries++, TimeUnit.MILLISECONDS)
                } else {
                    Observable.error(error)
                }
            }
        }
        .flatMap { gateway ->
            gateway.url?.let { Observable.just(it) }
                ?: Observable.error<String>(IllegalArgumentException("Gateway response missing url"))
        }

    private const val RETRY_DELAY_MS = 1_000L
    private const val MAX_RETRIES = 3
}
