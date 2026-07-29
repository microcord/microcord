package com.discord.widgets.hubs

import com.discord.stores.StoreStream
import retrofit2.Response
import rx.Observable

/**
 * Unwraps a Retrofit [Response] envelope into its body.
 *
 * The hub endpoints are declared as `Observable<Response<T>>` (they are in the reference too) because
 * the reference pipes them through `RestCallStateKt.logNetworkAction`, which reports the request's
 * status/latency to analytics and then maps `Response<T> -> T`. This project has no
 * `com.discord.analytics.generated` package, so only the mapping half is reproduced; the analytics
 * half is recorded as a gap in the handoff report.
 *
 * A response with no body (an empty 2xx) fails the stream, which surfaces as `Failure` on the
 * screen — the same outcome the reference gets from `Response.body()!!`.
 */
internal fun <T> Observable<Response<T>>.unwrapBody(): Observable<T> = map { response ->
    checkNotNull(response.body()) { "Empty response body (HTTP ${response.code()})" }
}

internal fun isMultiDomainEnabled(): Boolean =
    StoreStream.getExperiments().getUserExperiment(MULTI_DOMAIN_EXPERIMENT, trackExposure = true)?.bucket == 1

private const val MULTI_DOMAIN_EXPERIMENT = "2021-08_hub_multi_domain_mobile"
