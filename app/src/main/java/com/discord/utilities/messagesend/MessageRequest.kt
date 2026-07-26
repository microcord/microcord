package com.discord.utilities.messagesend

import com.discord.api.activity.Activity
import com.discord.api.message.allowedmentions.MessageAllowedMentions
import com.discord.models.message.Message
import com.discord.utilities.attachments.UploadAttachment
import com.discord.utilities.rest.FileUpload
import com.discord.utilities.rest.SendUtils

/**
 * A unit of work for [MessageQueue].
 *
 * Reconstruction of the reference `com.discord.utilities.messagesend.MessageRequest`. The reference
 * derives [requestId] from the payload rather than accepting one: a send is identified by its
 * message nonce and an edit by its message id, which is exactly what `cancel(requestId)` is called
 * with from the store.
 *
 * @param requestId stable identity used to cancel or dedupe this request.
 * @param onCompleted terminal callback. The second argument is true when this was the last item in
 *   the queue, which the store uses to tear down its background send worker.
 * @param attemptTimestamp when the user asked for this. The queue drops requests older than one hour
 *   instead of sending them, so a message queued while offline does not appear days later.
 */
sealed class MessageRequest(
    val requestId: String,
    val onCompleted: (MessageResult, Boolean) -> Unit,
    val attemptTimestamp: Long,
) {

    /**
     * Send a new message, optionally with attachments.
     *
     * @param message the optimistic local message. Its nonce is this request's identity and is sent
     *   to the API so a retried send is de-duplicated server side.
     * @param activity rich-presence activity being shared, for "invite to listen"-style sends.
     * @param onPreprocessing invoked once with the attachment count before any file is read, so the
     *   chat row can show a placeholder immediately.
     * @param onProgress invoked once the multipart parts exist, handing over the per-file byte
     *   counters that drive the upload progress bars.
     */
    class Send(
        val message: Message,
        val activity: Activity? = null,
        val attachments: List<UploadAttachment>? = null,
        onCompleted: (MessageResult, Boolean) -> Unit,
        val onPreprocessing: (SendUtils.SendPayload.Preprocessing) -> Unit = {},
        val onProgress: (List<FileUpload>) -> Unit = {},
        attemptTimestamp: Long,
    ) : MessageRequest(
        requestId = requireNotNull(message.nonce) { "A queued send must carry a nonce" },
        onCompleted = onCompleted,
        attemptTimestamp = attemptTimestamp,
    ) {
        /**
         * Rejects payloads the API cannot represent, before anything is uploaded.
         *
         * Returns null when the request is valid. `MessageQueue.doSend` calls this first — DEX
         * evidence: the first instruction of
         * `MessageQueue.doSend` invokes `MessageRequest$Send.validateMessage()` and returns early on
         * a non-null result, so no [onPreprocessing] callback and no file I/O happen for an invalid
         * request.
         */
        fun validateMessage(): MessageResult.ValidationError? {
            if (message.activity != null && !attachments.isNullOrEmpty()) {
                return MessageResult.ValidationError("Cannot send attachments with activity action")
            }
            return null
        }
    }

    /**
     * Edit an existing message's content.
     *
     * @param requestId defaults to the message id, matching the reference. The store passes an
     *   explicit id when it needs to distinguish an edit from a send in its own routing.
     */
    class Edit(
        val channelId: Long,
        val content: String,
        val messageId: Long,
        val allowedMentions: MessageAllowedMentions? = null,
        attemptTimestamp: Long,
        requestId: String = messageId.toString(),
        onCompleted: (MessageResult, Boolean) -> Unit,
    ) : MessageRequest(
        requestId = requestId,
        onCompleted = onCompleted,
        attemptTimestamp = attemptTimestamp,
    )
}
