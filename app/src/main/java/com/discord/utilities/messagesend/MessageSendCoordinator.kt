package com.discord.utilities.messagesend

import android.content.ContentResolver
import com.discord.api.message.MessageReference
import com.discord.api.premium.PremiumTier
import com.discord.api.message.Message as ApiMessage
import com.discord.models.commands.ApplicationCommandLocalSendData
import com.discord.api.message.allowedmentions.MessageAllowedMentions
import com.discord.api.message.allowedmentions.MessageAllowedMentionsTypes
import com.discord.api.user.User
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.api.sticker.Sticker
import com.discord.models.message.Message
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.StorePendingReplies
import com.discord.utilities.attachments.UploadAttachment
import com.discord.utilities.captcha.CaptchaSolution
import com.discord.utilities.rest.FileUpload
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rest.SendUtils
import com.discord.utilities.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Scheduler
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import rx.subjects.AsyncSubject

/**
 * Owns the optimistic-message lifecycle around [MessageQueue].
 *
 * The split follows the reference: `MessageQueue` only knows how to serialise and execute one
 * channel's requests, while the caller (`StoreMessages` there, this class here) owns nonces, the
 * optimistic local message, retry/captcha bookkeeping and the projection into store state.
 *
 * There is one [MessageQueue] per channel, all sharing [queueExecutor], exactly as
 * `StoreMessages.getOrCreateMessageQueue` does — two channels can send in parallel, but a single
 * channel stays strictly ordered.
 */
class MessageSendCoordinator @JvmOverloads constructor(
    private val api: RestAPIInterface,
    private val now: () -> Long = System::currentTimeMillis,
    private val nonceGenerator: NonceGenerator = NonceGenerator(now),
    private val contentResolver: ContentResolver? = null,
    private val queueExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MessageQueue").apply { isDaemon = true }
    },
    private val retryScheduler: Scheduler = Schedulers.computation(),
    private val characterLimit: Int = DEFAULT_CHARACTER_LIMIT,
) {
    data class Send(
        val channelId: Long,
        val guildId: Long? = null,
        val content: String,
        val author: User? = null,
        val canSend: Boolean = true,
        val communicationDisabled: Boolean = false,
        val systemDm: Boolean = false,
        val allowedMentions: RestAPIParams.Message.AllowedMentions? = null,
        val reply: StorePendingReplies.PendingReply? = null,
        val captcha: CaptchaSolution? = null,
        val attachments: List<UploadAttachment>? = null,
        val flags: Long? = null,
        val characterLimit: Int? = null,
        /**
         * Stickers to send with the message.
         *
         * These ride on the optimistic message, which is also what [MessageQueue] serialises, so
         * they reach both the local row and `sticker_ids` on the wire. Without this a sticker-only
         * send posted as an empty message.
         */
        val stickers: List<Sticker>? = null,
        /** Reference `SendMessageOptions.location`; see [MessageSendLocation]. */
        val location: String = MessageSendLocation.OTHER,
    )

    sealed class Submission {
        data class Accepted(val requestId: String, val optimistic: Message) : Submission()
        data class Rejected(val result: MessageResult) : Submission()
    }

    sealed class Update {
        abstract val requestId: String

        data class Optimistic(override val requestId: String, val message: Message) : Update()
        data class Reconciled(override val requestId: String, val message: Message) : Update()
        data class Failed(override val requestId: String, val message: Message, val result: MessageResult) : Update()
        data class Cancelled(override val requestId: String) : Update()
    }

    /**
     * Attachment-upload progress for a queued send.
     *
     * Deliberately a separate stream from [Update] rather than more [Update] variants: [Update] is
     * consumed by an exhaustive `when` in the store, and upload progress is an orthogonal concern
     * that only the upload UI cares about. The reference keeps the same separation by routing
     * `MessageRequest.Send.onPreprocessing`/`onProgress` straight into `StoreMessageUploads` instead
     * of through the message-result path.
     */
    sealed class UploadEvent {
        abstract val requestId: String

        /** Attachment count is known and files are about to be read. */
        data class Preprocessing(
            override val requestId: String,
            val preprocessing: SendUtils.SendPayload.Preprocessing,
        ) : UploadEvent()

        /** Multipart parts exist; [uploads] carry the live per-file byte counters. */
        data class Progress(override val requestId: String, val uploads: List<FileUpload>) : UploadEvent()
    }

    private class Operation(
        val channelId: Long,
        val queueRequestId: String,
        var optimistic: Message,
        val original: Message? = null,
        val isEdit: Boolean = false,
        val attachments: List<UploadAttachment>? = null,
        val location: String = MessageSendLocation.OTHER,
    )

    private val operations = ConcurrentHashMap<String, Operation>()
    private val commandChannels = ConcurrentHashMap<String, Long>()
    private val updates = PublishSubject.create<Update>().toSerialized()
    private val uploadEvents = PublishSubject.create<UploadEvent>().toSerialized()
    private val queues = ConcurrentHashMap<Long, MessageQueue>()
    private val typingAt = ConcurrentHashMap<Long, Long>()

    fun observeUpdates(): Observable<Update> = updates

    /** Attachment preprocessing and per-file upload progress for in-flight sends. */
    fun observeUploadEvents(): Observable<UploadEvent> = uploadEvents

    fun sendApplicationCommand(
        message: ApiMessage,
        command: ApplicationCommandLocalSendData,
        attachments: List<UploadAttachment>? = null,
    ): Observable<MessageResult> {
        val result = AsyncSubject.create<MessageResult>()
        val requestId = message.nonce ?: command.nonceString
        val request = MessageRequest.SendApplicationCommand(
            message = message,
            applicationCommandSendData = command,
            attachments = attachments,
            onPreprocessing = {
                uploadEvents.onNext(UploadEvent.Preprocessing(command.nonceString, it))
            },
            onProgress = {
                uploadEvents.onNext(UploadEvent.Progress(command.nonceString, it))
            },
            onCompleted = { settled, _ ->
                commandChannels.remove(requestId)
                result.onNext(settled)
                result.onCompleted()
            },
            attemptTimestamp = now(),
        )
        commandChannels[request.requestId] = command.channelId
        queue(command.channelId).enqueue(request)
        return result
    }

    fun send(input: Send): Submission {
        validate(input)?.let { return Submission.Rejected(it) }
        val nonce = nonceGenerator.nonce().toString()
        val reference = input.reply?.messageReference
        val optimistic = Message(
            id = nonce.toLong(),
            channelId = input.channelId,
            guildId = input.guildId,
            author = input.author,
            content = input.content,
            timestamp = UtcDateTime(now()),
            nonce = nonce,
            type = TYPE_SENDING,
            messageReference = reference?.let { MessageReference(it.guildId, input.channelId, it.messageId) },
            allowedMentions = allowedMentionsFor(input),
            captchaSolution = input.captcha,
            stickers = input.stickers,
            hasLocalUploads = !input.attachments.isNullOrEmpty(),
            localAttachments = input.attachments?.map(UploadAttachment::toLocalAttachment),
            flags = input.flags,
            initialAttemptTimestamp = now(),
        )
        val operation = Operation(
            channelId = input.channelId,
            queueRequestId = nonce,
            optimistic = optimistic,
            attachments = input.attachments,
            location = input.location,
        )
        operations[nonce] = operation
        updates.onNext(Update.Optimistic(nonce, optimistic))
        enqueueSend(nonce, operation)
        return Submission.Accepted(nonce, optimistic)
    }

    fun edit(
        message: Message,
        content: String,
        allowedMentions: RestAPIParams.Message.AllowedMentions? = null,
        characterLimit: Int = this.characterLimit,
    ): Submission {
        if (message.id <= 0 || message.channelId <= 0) return Submission.Rejected(MessageResult.NoValidContent)
        validateContent(content, characterLimit)?.let { return Submission.Rejected(it) }
        val requestId = "edit:${message.channelId}:${message.id}:${nonceGenerator.nonce()}"
        val operation = Operation(
            channelId = message.channelId,
            queueRequestId = message.id.toString(),
            optimistic = message.copy(content = content, type = TYPE_SENDING),
            original = message,
            isEdit = true,
        )
        operations[requestId] = operation
        updates.onNext(Update.Optimistic(requestId, operation.optimistic))
        queue(operation.channelId).enqueue(
            MessageRequest.Edit(
                channelId = message.channelId,
                content = content,
                messageId = message.id,
                allowedMentions = allowedMentions?.toApiModel(),
                attemptTimestamp = now(),
                requestId = operation.queueRequestId,
                onCompleted = { result, _ -> settle(requestId, result) },
            ),
        )
        return Submission.Accepted(requestId, operation.optimistic)
    }

    /** Re-sends a previously failed request, optionally attaching a freshly solved captcha. */
    fun retry(requestId: String, captcha: CaptchaSolution? = null): Boolean {
        val operation = operations[requestId] ?: return false
        if (operation.isEdit) return false
        operation.optimistic = operation.optimistic.copy(
            type = TYPE_SENDING,
            captchaSolution = captcha ?: operation.optimistic.captchaSolution,
            numRetries = (operation.optimistic.numRetries ?: 0) + 1,
            lastManualAttemptTimestamp = now(),
        )
        updates.onNext(Update.Optimistic(requestId, operation.optimistic))
        enqueueSend(requestId, operation)
        return true
    }

    /** Re-queues a local message recovered from persistence after a restart. */
    fun resume(message: Message): Boolean {
        val nonce = message.nonce ?: return false
        if (!message.isLocal() || message.channelId <= 0) return false
        val operation = Operation(
            channelId = message.channelId,
            queueRequestId = nonce,
            optimistic = message.copy(type = TYPE_SENDING),
        )
        operations[nonce] = operation
        enqueueSend(nonce, operation)
        return true
    }

    fun cancel(requestId: String) {
        operations.remove(requestId)?.let { operation ->
            updates.onNext(Update.Cancelled(requestId))
            queues[operation.channelId]?.cancel(operation.queueRequestId)
            return
        }
        commandChannels.remove(requestId)?.let { queues[it]?.cancel(requestId) }
    }

    /** The gateway already delivered this message, so the queued attempt is redundant. */
    fun reconcileFromGateway(requestId: String) {
        operations.remove(requestId)?.let { queues[it.channelId]?.cancel(it.queueRequestId) }
    }

    fun handleConnected() = queues.values.forEach(MessageQueue::handleConnected)

    fun cancelAll() {
        queues.values.forEach(MessageQueue::cancelAll)
        operations.keys.toList().forEach { updates.onNext(Update.Cancelled(it)) }
        operations.clear()
        commandChannels.clear()
    }

    /**
     * Re-queues [messages] and blocks until each settles or [timeoutMs] elapses.
     *
     * Used by the background send worker, which must report success or failure synchronously.
     */
    fun resumeAndWait(messages: List<Message>, timeoutMs: Long = 60_000L): Boolean {
        val ids = messages.mapNotNull(Message::nonce).toSet()
        if (ids.isEmpty()) return true
        val remaining = java.util.Collections.synchronizedSet(HashSet(ids))
        var failed = false
        val latch = CountDownLatch(ids.size)
        val subscription = observeUpdates().subscribe { update ->
            if (update.requestId !in remaining) return@subscribe
            when (update) {
                is Update.Reconciled -> Unit
                is Update.Failed, is Update.Cancelled -> failed = true
                is Update.Optimistic -> return@subscribe
            }
            if (remaining.remove(update.requestId)) latch.countDown()
        }
        messages.forEach { message ->
            val nonce = message.nonce ?: return@forEach
            if (!resume(message) && remaining.remove(nonce)) {
                failed = true
                latch.countDown()
            }
        }
        val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        subscription.unsubscribe()
        return completed && !failed
    }

    fun typing(channelId: Long, content: CharSequence) {
        if (channelId <= 0 || content.isEmpty()) return
        val timestamp = now()
        val previous = typingAt[channelId]
        if (previous != null && timestamp - previous < TYPING_INTERVAL_MS) return
        typingAt[channelId] = timestamp
        api.setUserTyping(channelId)
            .subscribeOn(Schedulers.io())
            .appSubscribeQuietly()
    }

    private fun enqueueSend(requestId: String, operation: Operation) {
        queue(operation.channelId).enqueue(
            MessageRequest.Send(
                message = operation.optimistic,
                activity = null,
                attachments = operation.attachments,
                location = operation.location,
                onCompleted = { result, _ -> settle(requestId, result) },
                onPreprocessing = { uploadEvents.onNext(UploadEvent.Preprocessing(requestId, it)) },
                onProgress = { uploadEvents.onNext(UploadEvent.Progress(requestId, it)) },
                attemptTimestamp = operation.optimistic.lastManualAttemptTimestamp
                    ?: operation.optimistic.initialAttemptTimestamp
                    ?: now(),
            ),
        )
    }

    private fun settle(requestId: String, result: MessageResult) {
        val operation = operations[requestId] ?: return
        when (result) {
            is MessageResult.Success -> {
                operations.remove(requestId)
                updates.onNext(Update.Reconciled(requestId, Message(result.message)))
            }

            MessageResult.UserCancelled -> {
                operations.remove(requestId)
                updates.onNext(Update.Cancelled(requestId))
            }

            else -> {
                operation.optimistic = operation.original
                    ?: operation.optimistic.copy(type = failedTypeFor(result))
                updates.onNext(Update.Failed(requestId, operation.optimistic, result))
            }
        }
    }

    private fun failedTypeFor(result: MessageResult) = when (result) {
        is MessageResult.CaptchaRequired -> TYPE_CAPTCHA
        is MessageResult.AutoModBlock -> TYPE_AUTOMOD
        else -> TYPE_FAILED
    }

    private fun queue(channelId: Long) = queues.getOrPut(channelId) {
        MessageQueue(
            contentResolver = contentResolver,
            executorService = queueExecutor,
            clock = Clock { now() },
            api = api,
            retryScheduler = retryScheduler,
        )
    }

    private fun validate(input: Send): MessageResult? = when {
        input.channelId <= 0 || input.systemDm -> MessageResult.NoValidContent
        !input.canSend -> MessageResult.ValidationError("Missing send permission")
        input.communicationDisabled -> MessageResult.ValidationError("Communication disabled")
        input.content.length > (input.characterLimit ?: characterLimit) ->
            MessageResult.ValidationError("Message exceeds ${input.characterLimit ?: characterLimit} characters")
        !input.attachments.isNullOrEmpty() -> null
        !input.stickers.isNullOrEmpty() -> null
        else -> validateContent(input.content, input.characterLimit ?: characterLimit)
    }

    private fun validateContent(content: String, limit: Int): MessageResult? = when {
        content.isBlank() -> MessageResult.NoValidContent
        content.length > limit -> MessageResult.ValidationError("Message exceeds $limit characters")
        else -> null
    }

    /**
     * The reference stores allowed mentions on the local message and lets `MessageQueue.doSend`
     * translate them into the request body, so the reply's "ping the author" toggle has to be folded
     * in here rather than at send time.
     */
    private fun allowedMentionsFor(input: Send): MessageAllowedMentions? {
        val base = input.allowedMentions?.toApiModel()
        val reply = input.reply ?: return base
        return base?.copy(repliedUser = reply.shouldMention)
            ?: MessageAllowedMentions(repliedUser = reply.shouldMention)
    }

    companion object {
        const val DEFAULT_CHARACTER_LIMIT = 2_000
        const val PREMIUM_CHARACTER_LIMIT = 4_000
        const val TEXT_FILE_MIME_TYPE = "text/plain"

        fun characterLimit(premiumTier: PremiumTier) = when (premiumTier) {
            PremiumTier.NONE, PremiumTier.PREMIUM_GUILD_SUBSCRIPTION_ONLY -> DEFAULT_CHARACTER_LIMIT
            PremiumTier.TIER_0, PremiumTier.TIER_1, PremiumTier.TIER_2 -> PREMIUM_CHARACTER_LIMIT
        }
        const val TYPING_INTERVAL_MS = 9_000L
        const val TYPE_SENDING = -1
        const val TYPE_FAILED = -2
        const val TYPE_CAPTCHA = -6
        const val TYPE_AUTOMOD = -8
    }
}

private fun RestAPIParams.Message.AllowedMentions.toApiModel() = MessageAllowedMentions(
    parse = parse?.mapNotNull { value ->
        MessageAllowedMentionsTypes.entries.firstOrNull { it.apiStringRepresentation == value }
    },
    users = users,
    roles = roles,
    repliedUser = repliedUser,
)

/**
 * Fire-and-forget subscription for a call whose failure is genuinely not actionable.
 *
 * Typing indicators expire on their own, so a lost one needs no user-facing error. This is kept as a
 * named helper so it is obvious that the empty error handler is deliberate rather than the
 * `subscribe({}, {})` pattern that hides real failures.
 */
private fun <T> Observable<T>.appSubscribeQuietly() = subscribe({ }, { })
