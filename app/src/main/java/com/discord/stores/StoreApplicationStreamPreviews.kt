package com.discord.stores

import com.discord.models.domain.ModelApplicationStream
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.error.RestApiError
import com.discord.utilities.streams.StreamContext
import com.discord.utilities.time.Clock
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Subscription

/**
 * Thumbnails for other people's screen shares, keyed by encoded stream key.
 *
 * Previews are pull-based: a surface that wants one calls [fetchStreamPreviewIfNotFetching], and the
 * store deduplicates concurrent requests per key through [fetchStreamPreviewSubscriptions]. A failed
 * fetch retries on a ladder rather than giving up, because a preview usually fails only because the
 * streamer has not uploaded one yet:
 *
 * - `429` waits for the server's `Retry-After`, falling back to the linear delay
 * - `401` / `403` are terminal — we are not allowed to see this stream, and retrying would just burn
 *   rate limit
 * - anything else waits `attempts * 10 s`, so a persistently failing stream backs off linearly
 *
 * A failure still publishes `Resolved(null)`, so the UI shows "no preview" instead of spinning
 * forever while the retry is pending.
 */
class StoreApplicationStreamPreviews(
    private val dispatcher: Dispatcher,
    private val clock: Clock,
    /** `GET /streams/{streamKey}/preview?version=<now>`; emits the preview URL. */
    private val fetchPreview: (streamKey: String, version: Long) -> Observable<String?>,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) : StoreV2() {

    private val streamKeyToPreviewMap = HashMap<String, StreamPreview>()
    private var streamKeyToPreviewMapSnapshot: Map<String, StreamPreview> = emptyMap()

    /** Consecutive failures per key; drives the linear backoff and is cleared on success. */
    private val fetchAttempts = HashMap<String, Int>()

    /** In-flight fetch *or* pending retry timer per key, so a new request can cancel the old one. */
    private val fetchStreamPreviewSubscriptions = HashMap<String, Subscription>()

    sealed interface StreamPreview {
        data object Fetching : StreamPreview
        data class Resolved(val url: String?) : StreamPreview
    }

    private fun fetchStreamPreview(streamKey: String) {
        dispatcher.schedule { handleFetchStart(streamKey) }
        fetchStreamPreviewSubscriptions[streamKey]?.unsubscribe()

        val subscription = fetchPreview(streamKey, clock.currentTimeMillis()).subscribe(
            { url -> dispatcher.schedule { handleFetchSuccess(streamKey, url) } },
            { throwable ->
                fetchStreamPreviewSubscriptions.remove(streamKey)
                dispatcher.schedule { handleFetchFailed(streamKey, RestApiError.from(throwable)) }
            },
            { fetchStreamPreviewSubscriptions.remove(streamKey) },
        )
        // A stream that already terminated has nothing left to cancel; recording it would leave a
        // dead handle behind the key and mask the next in-flight request.
        if (!subscription.isUnsubscribed) fetchStreamPreviewSubscriptions[streamKey] = subscription
    }

    private fun fetchStreamPreviewIfNotFetching(preview: StreamPreview?, streamKey: String) {
        if (preview !is StreamPreview.Fetching) fetchStreamPreview(streamKey)
    }

    @StoreThread
    private fun fetchStreamPreviewIfNotFetching(streamKey: String) =
        fetchStreamPreviewIfNotFetching(streamKeyToPreviewMap[streamKey], streamKey)

    fun fetchStreamPreviewIfNotFetching(streamContext: StreamContext) =
        fetchStreamPreviewIfNotFetching(streamContext.preview, streamContext.stream.encodedStreamKey)

    fun getStreamKeyToPreviewMap(): Map<String, StreamPreview> = streamKeyToPreviewMapSnapshot

    @StoreThread
    fun handleFetchStart(streamKey: String) {
        streamKeyToPreviewMap[streamKey] = StreamPreview.Fetching
        markChanged()
    }

    @StoreThread
    fun handleFetchSuccess(streamKey: String, url: String?) {
        streamKeyToPreviewMap[streamKey] = StreamPreview.Resolved(url)
        fetchAttempts.remove(streamKey)
        markChanged()
    }

    @StoreThread
    fun handleFetchFailed(streamKey: String, error: RestApiError) {
        streamKeyToPreviewMap[streamKey] = StreamPreview.Resolved(null)

        val attempts = (fetchAttempts[streamKey] ?: 0) + 1
        val linearDelayMs = attempts * READ_PREVIEW_DEFAULT_RETRY_DELAY_MS

        val retryDelayMs: Long? = when (error.statusCode) {
            HTTP_TOO_MANY_REQUESTS -> error.retryAfterMs ?: linearDelayMs
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> null
            else -> linearDelayMs
        }

        if (retryDelayMs != null) {
            fetchStreamPreviewSubscriptions[streamKey]?.unsubscribe()
            fetchStreamPreviewSubscriptions[streamKey] =
                Observable.timer(retryDelayMs, TimeUnit.MILLISECONDS).subscribe(
                    { dispatcher.schedule { fetchStreamPreviewIfNotFetching(streamKey) } },
                    { fetchStreamPreviewSubscriptions.remove(streamKey) },
                    { fetchStreamPreviewSubscriptions.remove(streamKey) },
                )
        }

        fetchAttempts[streamKey] = attempts
        markChanged()
    }

    fun observeStreamPreview(applicationStream: ModelApplicationStream): Observable<StreamPreview?> =
        observeStreamPreview(applicationStream.encodedStreamKey)

    fun observeStreamPreview(streamKey: String): Observable<StreamPreview?> =
        observationDeck.connectRx(this) { getStreamKeyToPreviewMap()[streamKey] }
            .distinctUntilChanged()

    @StoreThread
    override fun snapshotData() {
        super.snapshotData()
        streamKeyToPreviewMapSnapshot = HashMap(streamKeyToPreviewMap)
    }

    private companion object {
        private const val READ_PREVIEW_DEFAULT_RETRY_DELAY_MS = 10_000L
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
