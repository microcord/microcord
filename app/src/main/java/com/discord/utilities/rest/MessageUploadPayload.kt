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

data class FileUpload(
    val name: String,
    val contentLength: Long,
    val part: MultipartBody.Part,
    val mimeType: String,
    val progress: Observable<UploadProgress>,
)

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
                names[index],
                attachment.size,
                MultipartBody.Part.createFormData("files[$index]", names[index], body),
                attachment.mimeType,
                body.bytesWrittenObservable.map { UploadProgress(it, attachment.size) },
            )
        }
        val multipart = buildMultipartBody(payloadJson, uploads.map(FileUpload::part))
        return MessageUploadPayload(multipart, uploads, aggregate.distinctUntilChanged(), rawBodies, subscriptions, aggregate)
    }
}

internal fun buildMultipartBody(payloadJson: String, files: List<MultipartBody.Part>): MultipartBody =
    MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("payload_json", null, payloadJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
        .apply { files.forEach(::addPart) }
        .build()
