package com.discord.utilities.attachments

import com.discord.api.attachment.RefreshAttachmentUrlsBody
import com.discord.utilities.rest.RestAPI
import rx.Observable

/**
 * Reference `POST /attachments/refresh-urls`.
 *
 * Attachment links are signed and expire. A message that has been sitting in the store since before
 * its links lapsed still holds the stale ones, so anything about to display or download an
 * attachment re-signs it first rather than letting the CDN reject the request.
 */
object AttachmentUrlRefresher {

    /** Unix seconds at which the signature lapses, hex-encoded. */
    private const val EXPIRY_PARAM = "ex"

    /** The endpoint takes at most this many per call. */
    private const val MAX_BATCH = 50

    /**
     * Re-sign shortly before the deadline rather than exactly on it, so a link cannot lapse between
     * being handed out and being fetched.
     */
    private const val EXPIRY_MARGIN_MS = 60_000L

    /**
     * Whether [url] is a signed link that has expired, or is about to.
     *
     * An unsigned link — anything without the parameter — never expires and is left alone.
     */
    fun isExpired(url: String, nowMs: Long): Boolean {
        val expiresAtMs = expiryMs(url) ?: return false
        return nowMs + EXPIRY_MARGIN_MS >= expiresAtMs
    }

    /**
     * Read straight out of the query string rather than through `Uri`, so the rule stays pure
     * Kotlin — the Android parser is a stub off-device and would report every link as unsigned.
     */
    private val EXPIRY = Regex("""[?&]$EXPIRY_PARAM=([0-9a-fA-F]+)""")

    private fun expiryMs(url: String): Long? =
        EXPIRY.find(url)?.groupValues?.get(1)?.toLongOrNull(radix = 16)?.times(1_000L)

    /**
     * Re-signs whichever of [urls] have lapsed and returns every URL mapped to a usable one.
     *
     * Unexpired and unsigned links are answered from the input without a request, so a screen full
     * of fresh attachments costs nothing.
     */
    fun refreshExpired(urls: List<String>, nowMs: Long): Observable<Map<String, String>> {
        val expired = urls.distinct().filter { isExpired(it, nowMs) }
        if (expired.isEmpty()) return Observable.just(urls.associateWith { it })
        val api = RestAPI.apiOrNull ?: return Observable.just(urls.associateWith { it })
        return Observable.from(expired.chunked(MAX_BATCH))
            .concatMap { batch -> api.refreshAttachmentUrls(RefreshAttachmentUrlsBody(batch)) }
            .toList()
            .map { responses ->
                val refreshed = responses
                    .flatMap { it.refreshedUrls }
                    .associate { it.original to it.refreshed }
                // Anything the server declined to re-sign keeps its original URL rather than
                // disappearing from the result.
                urls.associateWith { refreshed[it] ?: it }
            }
    }
}
