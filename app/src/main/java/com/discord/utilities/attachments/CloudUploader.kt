package com.discord.utilities.attachments

import android.content.ContentResolver
import com.discord.api.attachment.CloudAttachment
import com.discord.api.attachment.CreateAttachmentsBody
import com.discord.api.attachment.UploadAttachmentRequest
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.utils.CountingRequestBody
import com.discord.utilities.rest.AttachmentRequestBody
import com.discord.utilities.rest.FileUpload
import com.discord.utilities.rest.RestAPI
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import retrofit2.HttpException
import rx.Observable
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * Reference `lib/uploader/CloudUpload`.
 *
 * A file no longer travels inside the message. It is PUT to storage first and the message then
 * claims what landed there, so a large upload is a separate, retryable request rather than
 * something that makes the send itself enormous and all-or-nothing.
 */
object CloudUploader {

    /**
     * A file that reached storage and is waiting to be claimed.
     *
     * Only the storage-side name is kept: the message claims uploads by position, so nothing here
     * needs to carry the local file back to the caller.
     */
    data class StagedUpload(val uploadedFilename: String)

    private const val MAX_ATTEMPTS = 12
    private const val INITIAL_BACKOFF_MS = 500L
    private const val MAX_BACKOFF_MS = 30_000L

    /**
     * Presigned URLs are good for 24 hours; re-request well short of that so a slow queue cannot
     * reach the front holding a URL that has already lapsed.
     */
    private val URL_STALE_AFTER_MS = TimeUnit.HOURS.toMillis(12)

    /** Storage rejects the request rather than the upload, so retrying the same URL cannot help. */
    private val RETRYABLE_CLIENT_CODES = setOf(408, 429)

    /**
     * Reserves slots, uploads every file, and reports what the message should claim.
     *
     * The reservation is one request per file, matching the reference: batching them would mean a
     * single rejected file failing the whole set before any upload has been attempted.
     *
     * [onProgress] receives one [FileUpload] per file as its upload begins, so the composer can
     * show a live byte counter for each rather than only knowing the send is in flight.
     */
    fun stage(
        channelId: Long,
        resolver: ContentResolver,
        attachments: List<UploadAttachment>,
        nowMs: () -> Long = System::currentTimeMillis,
        onProgress: (FileUpload) -> Unit = {},
    ): Observable<List<StagedUpload>> {
        if (attachments.isEmpty()) return Observable.just(emptyList())
        val api = RestAPI.apiOrNull ?: return Observable.error(IllegalStateException("API unavailable"))
        return Observable.from(attachments)
            .concatMap { attachment -> stageOne(api, channelId, resolver, attachment, nowMs, onProgress) }
            .toList()
    }

    private fun stageOne(
        api: RestAPIInterface,
        channelId: Long,
        resolver: ContentResolver,
        attachment: UploadAttachment,
        nowMs: () -> Long,
        onProgress: (FileUpload) -> Unit,
    ): Observable<StagedUpload> = Observable.defer {
        // Held across attempts so a retry can reuse a slot that is still fresh, and request a new
        // one only once the old URL is too old to trust.
        var slot: CloudAttachment? = null
        var issuedAtMs = 0L
        var attempt = 0

        Observable.defer {
            val current = slot
            if (current != null && nowMs() - issuedAtMs < URL_STALE_AFTER_MS) {
                Observable.just(current)
            } else {
                reserve(api, channelId, attachment).doOnNext {
                    slot = it
                    issuedAtMs = nowMs()
                }
            }
        }
            .flatMap { reserved -> put(resolver, attachment, reserved, onProgress) }
            .retryWhen { failures ->
                failures.flatMap { error ->
                    attempt += 1
                    if (attempt >= MAX_ATTEMPTS || !isRetryable(error)) {
                        Observable.error(error)
                    } else {
                        // A URL that failed may simply have expired, so drop it and re-reserve.
                        if (isExpiredUrl(error)) slot = null
                        Observable.timer(backoffMs(attempt), TimeUnit.MILLISECONDS)
                    }
                }
            }
            .map { StagedUpload(requireNotNull(slot).uploadFilename) }
    }

    private fun reserve(
        api: RestAPIInterface,
        channelId: Long,
        attachment: UploadAttachment,
    ): Observable<CloudAttachment> = api.createAttachments(
        channelId,
        CreateAttachmentsBody(
            listOf(
                UploadAttachmentRequest(
                    id = attachment.id.toString(),
                    filename = attachment.fileName,
                    fileSize = attachment.size,
                ),
            ),
        ),
    ).map { response ->
        response.attachments.firstOrNull()
            ?: throw IOException("No upload slot returned for ${attachment.fileName}")
    }

    private fun put(
        resolver: ContentResolver,
        attachment: UploadAttachment,
        slot: CloudAttachment,
        onProgress: (FileUpload) -> Unit,
    ): Observable<Unit> = Observable.defer {
        val client = RestAPI.cloudUploadOrNull
            ?: return@defer Observable.error<Unit>(IllegalStateException("Upload client unavailable"))
        val body = CountingRequestBody(
            AttachmentRequestBody(
                resolver,
                attachment.uri,
                attachment.size,
                attachment.mimeType.toMediaTypeOrNull(),
            ),
        )
        onProgress(
            FileUpload(
                name = attachment.fileName,
                contentLength = attachment.size,
                part = null,
                mimeType = attachment.mimeType,
                bytesWrittenObservable = body.bytesWrittenObservable,
            ),
        )
        client.put(slot.uploadUrl, body, null).map { response ->
            // Storage answers 200 or 201; anything else leaves nothing to claim.
            if (!response.isSuccessful) {
                throw IOException("Upload of ${attachment.fileName} failed: ${response.code()}")
            }
            Unit
        }
    }

    /**
     * A transport failure is worth another attempt; a refusal is not.
     *
     * Storage answering 4xx means it understood and declined, so repeating the same request would
     * only waste the user's data — except for the two codes that explicitly invite a retry.
     */
    private fun isRetryable(error: Throwable): Boolean = when (error) {
        is HttpException ->
            error.code() >= 500 || error.code() in RETRYABLE_CLIENT_CODES || isExpiredUrl(error)
        is InterruptedIOException -> false
        is IOException -> true
        else -> false
    }

    /** Storage reports a lapsed signature as a plain refusal, so it is recognised by code. */
    private fun isExpiredUrl(error: Throwable): Boolean =
        error is HttpException && (error.code() == 403 || error.code() == 400)

    private fun backoffMs(attempt: Int): Long =
        (INITIAL_BACKOFF_MS shl (attempt - 1).coerceAtMost(6)).coerceAtMost(MAX_BACKOFF_MS)

    /** Releases slots the message never claimed, so an abandoned upload does not linger. */
    fun discard(uploadedFilenames: List<String>) {
        val api = RestAPI.apiOrNull ?: return
        uploadedFilenames.forEach { filename ->
            api.deleteAttachment(filename).subscribe({}, {})
        }
    }
}
