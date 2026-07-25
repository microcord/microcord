package com.discord.utilities.messagesend

import com.discord.api.message.MessageReference
import com.discord.api.user.User
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.message.Message
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.StorePendingReplies
import com.discord.utilities.captcha.CaptchaHelper
import com.discord.utilities.error.RestApiError
import java.util.concurrent.ConcurrentHashMap
import rx.Observable
import rx.Single
import rx.subjects.PublishSubject
import rx.schedulers.Schedulers

class MessageSendCoordinator(
    private val api: RestAPIInterface,
    private val now: () -> Long = System::currentTimeMillis,
    private val nonceGenerator: NonceGenerator = NonceGenerator(now),
    private val queueScheduler: MessageQueueScheduler = ExecutorMessageQueueScheduler(),
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
        val captcha: CaptchaHelper.CaptchaPayload? = null,
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

    private data class Operation(
        val channelId: Long,
        val messageId: Long?,
        var body: RestAPIParams.Message,
        var optimistic: Message,
        val original: Message? = null,
    )

    private val operations = ConcurrentHashMap<String, Operation>()
    private val updates = PublishSubject.create<Update>().toSerialized()
    private val queues = ConcurrentHashMap<Long, MessageQueue>()
    private val typingAt = ConcurrentHashMap<Long, Long>()

    fun observeUpdates(): Observable<Update> = updates

    fun send(input: Send): Submission {
        validate(input)?.let { return Submission.Rejected(it) }
        val nonce = nonceGenerator.nonce().toString()
        val reference = input.reply?.messageReference
        val mentions = input.allowedMentions.withReplyMention(input.reply)
        val body = RestAPIParams.Message(
            content = input.content,
            nonce = nonce,
            messageReference = reference?.let {
                RestAPIParams.Message.MessageReference(it.guildId, input.channelId, it.messageId)
            },
            allowedMentions = mentions,
            captchaKey = input.captcha?.captchaKey,
            captchaRqtoken = input.captcha?.captchaRqtoken,
            enforceNonce = true,
        )
        val optimistic = Message(
            id = nonce.toLong(), channelId = input.channelId, guildId = input.guildId,
            author = input.author, content = input.content, timestamp = UtcDateTime(now()),
            nonce = nonce, type = TYPE_SENDING,
            messageReference = reference?.let { MessageReference(it.guildId, it.channelId, it.messageId) },
            captchaPayload = input.captcha,
        )
        return enqueue(nonce, Operation(input.channelId, null, body, optimistic))
    }

    fun edit(message: Message, content: String, allowedMentions: RestAPIParams.Message.AllowedMentions? = null): Submission {
        if (message.id <= 0 || message.channelId <= 0) return Submission.Rejected(MessageResult.NoValidContent)
        validateContent(content)?.let { return Submission.Rejected(it) }
        val requestId = "edit:${message.channelId}:${message.id}:${nonceGenerator.nonce()}"
        return enqueue(requestId, Operation(
            message.channelId,
            message.id,
            RestAPIParams.Message(content = content, allowedMentions = allowedMentions),
            message.copy(content = content, type = TYPE_SENDING),
            message,
        ))
    }

    fun retry(requestId: String, captcha: CaptchaHelper.CaptchaPayload? = null): Boolean {
        val operation = operations[requestId] ?: return false
        operation.body = operation.body.copy(
            captchaKey = captcha?.captchaKey ?: operation.body.captchaKey,
            captchaRqtoken = captcha?.captchaRqtoken ?: operation.body.captchaRqtoken,
        )
        operation.optimistic = operation.optimistic.copy(type = TYPE_SENDING, captchaPayload = captcha)
        updates.onNext(Update.Optimistic(requestId, operation.optimistic))
        queue(operation.channelId).enqueue(boundRequest(operation, requestId))
        return true
    }

    fun resume(message: Message): Boolean {
        val nonce = message.nonce ?: return false
        if (!message.isLocal() || message.channelId <= 0) return false
        val reference = message.messageReference
        val body = RestAPIParams.Message(
            content = message.content,
            nonce = nonce,
            messageReference = reference?.let {
                RestAPIParams.Message.MessageReference(it.guildId, message.channelId, it.messageId)
            },
            captchaKey = message.captchaPayload?.captchaKey,
            captchaRqtoken = message.captchaPayload?.captchaRqtoken,
        )
        operations[nonce] = Operation(message.channelId, null, body, message.copy(type = TYPE_SENDING))
        queue(message.channelId).enqueue(boundRequest(operations.getValue(nonce), nonce))
        return true
    }

    fun cancel(requestId: String) {
        operations.remove(requestId)?.let {
            updates.onNext(Update.Cancelled(requestId))
            queues[it.channelId]?.cancel(requestId)
        }
    }
    fun reconcileFromGateway(requestId: String) {
        operations.remove(requestId)?.let { queues[it.channelId]?.cancel(requestId) }
    }
    fun handleConnected() = queues.values.forEach(MessageQueue::handleConnected)
    fun cancelAll() {
        queues.values.forEach(MessageQueue::cancelAll)
        operations.keys.toList().forEach { updates.onNext(Update.Cancelled(it)) }
        operations.clear()
    }

    fun resumeAndWait(messages: List<Message>, timeoutMs: Long = 60_000L): Boolean {
        val ids = messages.mapNotNull(Message::nonce).toSet()
        if (ids.isEmpty()) return true
        val remaining = java.util.Collections.synchronizedSet(HashSet(ids))
        var failed = false
        val latch = java.util.concurrent.CountDownLatch(ids.size)
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
            if (!resume(message) && remaining.remove(nonce)) { failed = true; latch.countDown() }
        }
        val completed = latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        subscription.unsubscribe()
        return completed && !failed
    }

    fun typing(channelId: Long, content: CharSequence) {
        if (channelId <= 0 || content.isEmpty()) return
        val timestamp = now()
        val previous = typingAt[channelId]
        if (previous != null && timestamp - previous < TYPING_INTERVAL_MS) return
        typingAt[channelId] = timestamp
        api.setUserTyping(channelId).subscribeOn(Schedulers.io()).subscribe({}, {})
    }

    private fun enqueue(requestId: String, operation: Operation): Submission.Accepted {
        operations[requestId] = operation
        updates.onNext(Update.Optimistic(requestId, operation.optimistic))
        queue(operation.channelId).enqueue(boundRequest(operation, requestId))
        return Submission.Accepted(requestId, operation.optimistic)
    }

    private fun execute(request: MessageRequest): Single<MessageResult> = operations[request.requestId]?.let { operation ->
        val call = operation.messageId?.let { api.editMessage(operation.channelId, it, operation.body) }
            ?: api.sendMessage(operation.channelId, operation.body)
        call.map { apiMessage ->
            val message = Message(apiMessage)
            operation.optimistic = message
            updates.onNext(Update.Reconciled(request.requestId, message))
            MessageResult.Success as MessageResult
        }.onErrorReturn { classify(it) }.toSingle()
    } ?: Single.just(MessageResult.UserCancelled)

    private fun completed(requestId: String, result: MessageResult) {
        val operation = operations[requestId] ?: return
        when (result) {
            MessageResult.Success -> operations.remove(requestId)
            MessageResult.UserCancelled -> updates.onNext(Update.Cancelled(requestId))
            else -> {
                operation.optimistic = operation.original ?: operation.optimistic.copy(type = when (result) {
                    is MessageResult.CaptchaRequired -> TYPE_CAPTCHA
                    MessageResult.AutoModBlock -> TYPE_AUTOMOD
                    else -> TYPE_FAILED
                })
                updates.onNext(Update.Failed(requestId, operation.optimistic, result))
            }
        }
    }

    private fun boundRequest(operation: Operation, requestId: String): MessageRequest =
        if (operation.messageId == null) MessageRequest.Send(requestId, now()) { result, _ -> completed(requestId, result) }
        else MessageRequest.Edit(requestId, now()) { result, _ -> completed(requestId, result) }

    private fun queue(channelId: Long) = queues.getOrPut(channelId) { MessageQueue(::execute, queueScheduler) }

    private fun validate(input: Send): MessageResult? = when {
        input.channelId <= 0 || input.systemDm -> MessageResult.NoValidContent
        !input.canSend -> MessageResult.ValidationError("Missing send permission")
        input.communicationDisabled -> MessageResult.ValidationError("Communication disabled")
        else -> validateContent(input.content)
    }

    private fun validateContent(content: String): MessageResult? = when {
        content.isBlank() -> MessageResult.NoValidContent
        content.length > characterLimit -> MessageResult.ValidationError("Message exceeds $characterLimit characters")
        else -> null
    }

    private fun classify(throwable: Throwable): MessageResult {
        val error = RestApiError.from(throwable)
        return when {
            error.type == RestApiError.Type.NETWORK || error.type == RestApiError.Type.TIMEOUT -> MessageResult.NetworkFailure
            error.statusCode == 429 -> MessageResult.RateLimited(error.retryAfterMs ?: 1_000L)
            error.code == 20016 -> MessageResult.Slowmode(error.retryAfterMs ?: 1_000L)
            error.captchaSitekey != null || "captcha_key" in error.messages || "captcha_rqtoken" in error.messages ->
                MessageResult.CaptchaRequired(error.captchaSitekey, error.captchaRqtoken)
            error.code == AUTOMOD_BLOCK_CODE -> MessageResult.AutoModBlock
            error.type == RestApiError.Type.DISCORD_BAD_REQUEST -> MessageResult.ValidationError(error.message ?: "Invalid message")
            else -> MessageResult.UnknownFailure(throwable)
        }
    }

    private fun RestAPIParams.Message.AllowedMentions?.withReplyMention(reply: StorePendingReplies.PendingReply?) = when {
        reply == null -> this
        this == null -> RestAPIParams.Message.AllowedMentions(repliedUser = reply.shouldMention)
        else -> copy(repliedUser = reply.shouldMention)
    }

    private companion object {
        const val DEFAULT_CHARACTER_LIMIT = 2_000
        const val TYPING_INTERVAL_MS = 9_000L
        const val AUTOMOD_BLOCK_CODE = 200000
        const val TYPE_SENDING = -1
        const val TYPE_FAILED = -2
        const val TYPE_CAPTCHA = -6
        const val TYPE_AUTOMOD = -8
    }
}
