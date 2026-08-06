package com.discord.utilities.rest

import android.content.ContentResolver
import com.discord.restapi.utils.CountingRequestBody
import com.discord.utilities.attachments.UploadAttachment
import com.discord.utilities.attachments.uniqueUploadFileNames
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import rx.Observable
import rx.Subscription
import rx.subjects.BehaviorSubject

data class UploadProgress(val bytesWritten: Long, val contentLength: Long) {
    val fraction: Float
        get() = if (contentLength > 0) (bytesWritten.toDouble() / contentLength).coerceIn(0.0, 1.0).toFloat() else 0f
}

/**
 * One multipart part plus a live byte counter for it.
 *
 * Mirrors the reference `SendUtils.FileUpload(name, contentLength, part, mimeType,
 * bytesWrittenObservable)`. [progress] is a convenience projection of [bytesWrittenObservable] that
 * the upload row binds to directly.
 */
data class FileUpload(
    val name: String,
    val contentLength: Long,
    /** Null for a cloud upload, whose bytes go straight to storage instead of into the request. */
    val part: MultipartBody.Part?,
    val mimeType: String,
    val bytesWrittenObservable: Observable<Long>,
) {
    val progress: Observable<UploadProgress>
        get() = bytesWrittenObservable.map { UploadProgress(it, contentLength) }

    companion object {
        const val SIZE_UNKNOWN = -1L
    }
}

class MessageUploadPayload internal constructor(
    val body: MultipartBody,
    val uploads: List<FileUpload>,
    val aggregateProgress: Observable<UploadProgress>,
    private val requestBodies: List<AttachmentRequestBody>,
    private val subscriptions: List<Subscription>,
    private val aggregateSubject: BehaviorSubject<UploadProgress>,
) : AutoCloseable {
    override fun close() {
        requestBodies.forEach(AttachmentRequestBody::close)
        subscriptions.forEach(Subscription::unsubscribe)
        aggregateSubject.onCompleted()
    }
}

object MessageUploadPayloadBuilder {

    /**
     * Names and sizes only, for a send whose bytes are uploaded to storage separately.
     *
     * The rows exist so the UI can list the files while they upload; the real byte counts arrive
     * from `CloudUploader`, so opening a request body here would only read every file twice.
     */
    fun describeUploads(attachments: List<UploadAttachment>): List<FileUpload> {
        val names = uniqueUploadFileNames(attachments)
        return attachments.mapIndexed { index, attachment ->
            FileUpload(
                name = names[index],
                contentLength = attachment.size,
                part = null,
                mimeType = attachment.mimeType,
                bytesWrittenObservable = Observable.just(0L),
            )
        }
    }

    /**
     * Builds the multipart parts for [attachments] without wiring up an aggregate progress stream.
     *
     * Used by the application-command send, which still puts its files in the request body.
     */
    fun buildUploads(
        resolver: ContentResolver,
        attachments: List<UploadAttachment>,
    ): List<FileUpload> {
        val names = uniqueUploadFileNames(attachments)
        return attachments.mapIndexed { index, attachment ->
            val counting = CountingRequestBody(
                AttachmentRequestBody(resolver, attachment.uri, attachment.size, attachment.mimeType.toMediaType()),
            )
            FileUpload(
                name = names[index],
                contentLength = attachment.size,
                part = MultipartBody.Part.createFormData("files[$index]", names[index], counting),
                mimeType = attachment.mimeType,
                bytesWrittenObservable = counting.bytesWrittenObservable,
            )
        }
    }

    fun build(
        resolver: ContentResolver,
        payloadJson: String,
        attachments: List<UploadAttachment>,
    ): MessageUploadPayload {
        val names = uniqueUploadFileNames(attachments)
        val rawBodies = attachments.map { AttachmentRequestBody(resolver, it.uri, it.size, it.mimeType.toMediaType()) }
        val countingBodies = rawBodies.map(::CountingRequestBody)
        val written = LongArray(attachments.size)
        val total = attachments.sumOf { it.size.coerceAtLeast(0L) }
        val aggregate = BehaviorSubject.create(UploadProgress(0, total))
        val subscriptions = countingBodies.mapIndexed { index, body ->
            body.bytesWrittenObservable.subscribe { bytes ->
                synchronized(written) {
                    written[index] = bytes
                    aggregate.onNext(UploadProgress(written.sum(), total))
                }
            }
        }
        val uploads = attachments.mapIndexed { index, attachment ->
            val body = countingBodies[index]
            FileUpload(
                name = names[index],
                contentLength = attachment.size,
                part = MultipartBody.Part.createFormData("files[$index]", names[index], body),
                mimeType = attachment.mimeType,
                bytesWrittenObservable = body.bytesWrittenObservable,
            )
        }
        val multipart = buildMultipartBody(payloadJson, uploads.mapNotNull(FileUpload::part))
        return MessageUploadPayload(multipart, uploads, aggregate.distinctUntilChanged(), rawBodies, subscriptions, aggregate)
    }
}

internal fun buildMultipartBody(payloadJson: String, files: List<MultipartBody.Part>): MultipartBody =
    MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("payload_json", null, payloadJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
        .apply { files.forEach(::addPart) }
        .build()
