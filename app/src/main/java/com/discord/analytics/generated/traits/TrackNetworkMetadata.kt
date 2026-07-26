package com.discord.analytics.generated.traits

import com.discord.api.science.AnalyticsSchema

/**
 * Request/response metadata attached to a `network_action_*` schema.
 *
 * `logNetworkAction` builds one of these from the Retrofit [retrofit2.Response] (or from the
 * failure) and assigns it to the schema the call site produced, so the analytics event carries the
 * outcome of the REST call that triggered it.
 *
 * The [url] is the request *path* the reference records (`Request.url.encodedPath`), never the full
 * URL with its query string: query strings on this API carry invite codes, tokens and search terms.
 */
data class TrackNetworkMetadata(
    val url: CharSequence? = null,
    val requestMethod: CharSequence? = null,
    val statusCode: Long? = null,
    val errorCode: Long? = null,
    val errorMessage: CharSequence? = null,
)

/**
 * Implemented by every generated `network_action_*` schema.
 *
 * The property is a `var` because `logNetworkAction` fills it in after the call completes; the
 * schema itself is built before the response exists.
 */
interface TrackNetworkMetadataReceiver : AnalyticsSchema {
    var trackNetworkMetadata: TrackNetworkMetadata?
}
