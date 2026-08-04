package com.discord.utilities.rest

import android.content.ContentResolver
import com.discord.api.commands.ApplicationCommandType
import com.discord.models.commands.ApplicationCommandLocalSendData
import com.discord.restapi.RestAPIParams
import com.discord.stores.mapApplicationCommandValues
import com.discord.utilities.attachments.UploadAttachment
import com.discord.utilities.attachments.uniqueUploadFileNames
import rx.Observable

/**
 * Turns a message body plus its attachments into the payload the send endpoint needs.
 *
 * Reconstruction of the reference `com.discord.utilities.rest.SendUtils`. The observable contract is
 * the load-bearing part, because `MessageQueue.doSend` mirrors it exactly:
 *
 * - **No attachments** — a single [SendPayload.ReadyToSend] with an empty upload list.
 * - **With attachments** — [SendPayload.Preprocessing] first, emitted synchronously on subscribe
 *   before any file is touched, so the UI can show "preparing 3 files" immediately; then, once every
 *   part has been built, a single [SendPayload.ReadyToSend] carrying the multipart parts.
 *
 * `MessageQueue` consumes that stream with `doOnNext` side effects
 * (`onPreprocessing` / `onProgress`), then `filterIs<ReadyToSend>().take(1)` before issuing the REST
 * call — so the Preprocessing emission never reaches the network stage.
 *
 * Deviations from the reference, both recorded in `PLAN.md`:
 * - Attachments are the project's [UploadAttachment] rather than FlexInput's `Attachment<*>`;
 *   [UploadAttachment.from] is the adapter at the UI boundary.
 * - The reference additionally strips "link" attachments out of the list and appends their URLs to
 *   the message content (`AttachmentUtilsKt.extractLinks`/`appendLinks`). That utility is not
 *   reconstructed yet, so link attachments are uploaded as files here.
 * - [FileUpload] and [UploadProgress] stay top-level in this package instead of nested inside
 *   `SendUtils`, because `StoreMessageUploads` already imports them at those names.
 */
object SendUtils {

    const val MAX_MESSAGE_CHARACTER_COUNT = 2000
    const val MAX_MESSAGE_CHARACTER_COUNT_PREMIUM = 4000

    sealed class SendPayload {
        /**
         * Emitted before any file work starts. [name] and [mimeType] are populated only when there
         * is exactly one attachment; for zero or several the reference leaves them null and the UI
         * falls back to a count-based label.
         */
        data class Preprocessing(
            val numFiles: Int,
            val name: String? = null,
            val mimeType: String? = null,
        ) : SendPayload()

        /** The message body and its built multipart parts, ready for the send endpoint. */
        data class ReadyToSend(
            val message: RestAPIParams.Message,
            val uploads: List<FileUpload>,
        ) : SendPayload()

        data class ReadyToSendCommand(
            val command: ApplicationCommandLocalSendData,
            val uploads: List<FileUpload>,
        ) : SendPayload()
    }

    /**
     * Builds the send payload for [message].
     *
     * [contentResolver] is only required when [attachments] is non-empty; a text-only send emits
     * immediately and never touches it.
     */
    fun getSendPayload(
        contentResolver: ContentResolver?,
        message: RestAPIParams.Message,
        attachments: List<UploadAttachment>?,
    ): Observable<SendPayload> {
        if (attachments.isNullOrEmpty()) {
            return Observable.just(SendPayload.ReadyToSend(message, emptyList()))
        }
        requireNotNull(contentResolver) { "A ContentResolver is required to upload attachments" }
        val preprocessing = Observable.just<SendPayload>(preprocessingFor(attachments))
        val readyToSend = Observable.fromCallable {
            val prepared = uniqueUploadFileNames(attachments).mapIndexed { index, name ->
                attachments[index].copy(displayName = name)
            }
            val messageWithAttachments = message.copy(
                attachments = prepared.mapIndexed { index, attachment ->
                    RestAPIParams.Message.Attachment(
                        id = index,
                        filename = attachment.fileName,
                        isSpoiler = attachment.spoiler,
                    )
                },
            )
            SendPayload.ReadyToSend(messageWithAttachments, buildUploads(contentResolver, prepared)) as SendPayload
        }
        return Observable.concat(preprocessing, readyToSend)
    }

    fun getSendCommandPayload(
        contentResolver: ContentResolver?,
        command: ApplicationCommandLocalSendData,
        attachments: List<UploadAttachment>?,
    ): Observable<SendPayload> {
        if (attachments == null) {
            return Observable.just(SendPayload.ReadyToSendCommand(command, emptyList()))
        }
        val uniqueAttachments = uniqueUploadFileNames(attachments).mapIndexed { index, name ->
            attachments[index].copy(displayName = name)
        }
        val correctedCommand = rewriteCommandAttachmentNames(
            command,
            uniqueAttachments.map { it.uri.toString() to it.displayName },
        )
        if (uniqueAttachments.isEmpty()) {
            return Observable.just(SendPayload.ReadyToSendCommand(correctedCommand, emptyList()))
        }
        requireNotNull(contentResolver) { "A ContentResolver is required to upload attachments" }
        return Observable.concat(
            Observable.just<SendPayload>(preprocessingFor(uniqueAttachments)),
            Observable.fromCallable {
                SendPayload.ReadyToSendCommand(
                    correctedCommand,
                    buildUploads(contentResolver, uniqueAttachments),
                ) as SendPayload
            },
        )
    }

    internal fun rewriteCommandAttachmentNames(
        command: ApplicationCommandLocalSendData,
        attachmentNamesByUri: List<Pair<String, String>>,
    ): ApplicationCommandLocalSendData = command.copy(
        applicationCommandsValues = command.applicationCommandsValues.mapApplicationCommandValues { value ->
            if (value.type == ApplicationCommandType.ATTACHMENT.type) {
                value.copy(value = attachmentNamesByUri.first { it.first == value.value }.second)
            } else {
                value
            }
        },
    )

    private fun preprocessingFor(attachments: List<UploadAttachment>): SendPayload.Preprocessing {
        val single = attachments.singleOrNull()
            ?: return SendPayload.Preprocessing(attachments.size)
        return SendPayload.Preprocessing(1, single.fileName, single.mimeType)
    }

    /**
     * Builds one [FileUpload] per attachment, de-duplicating display names first so the server does
     * not receive two parts called `image.png`.
     */
    internal fun buildUploads(
        contentResolver: ContentResolver,
        attachments: List<UploadAttachment>,
    ): List<FileUpload> = MessageUploadPayloadBuilder.buildUploads(contentResolver, attachments)
}
