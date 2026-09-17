package com.discord.utilities.messagesend

import android.content.ContentResolver
import com.discord.models.message.Message
import com.discord.restapi.PayloadJSON
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.utilities.captcha.CaptchaRequestPolicy
import com.discord.utilities.error.RestApiError
import com.discord.utilities.networking.Backoff
import com.discord.utilities.rest.FileUpload
import com.discord.utilities.attachments.CloudUploader
import com.discord.utilities.attachments.UploadAttachment
import com.discord.utilities.developer.DeveloperSettings
import com.discord.utilities.rest.SendUtils
import com.discord.utilities.rest.contextPropertiesOf
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.filterIs
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.premium.grantsAccessToPremiumStickers
import com.discord.utilities.time.Clock
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import rx.Observable
import rx.Scheduler
import rx.Subscription
import rx.schedulers.Schedulers

/**
 * Serialises message sends and edits for one channel.
 *
 * Reconstruction of the reference `com.discord.utilities.messagesend.MessageQueue`. The reference
 * keeps one instance per channel id in `StoreMessages.messageQueues`, all sharing a single
 * single-threaded executor: each channel gets its own ordering, backoff and in-flight slot, while
 * every queue's bookkeeping runs on one thread so no locking is needed around [queue],
 * [inFlightRequest], [isDraining] or [retrySubscription].
 *
 * Ordering guarantees:
 * - At most one request is in flight at a time ([isDraining]).
 * - Requests drain FIFO; a request that must be retried is pushed back to the **front** so it keeps
 *   its place ahead of anything queued behind it.
 * - A request older than [MESSAGE_TIMEOUT_MS] is completed as [MessageResult.Timeout] without ever
 *   being sent, so work queued while offline does not resurface hours later.
 *
 * Retry policy, matching the reference exactly:
 * - [MessageResult.RateLimited] re-queues at the front after the server's `retry_after`.
 * - [MessageResult.NetworkFailure] re-queues at the front and arms [networkBackoff]. While that
 *   backoff [Backoff.isPending], [processNextRequest] refuses to start anything, so a dead
 *   connection is probed once rather than once per queued message. [handleConnected] cancels the
 *   pending backoff and drains immediately when connectivity returns.
 * - Every other result is terminal: it reaches the request's `onCompleted` and resets the backoff.
 */
class MessageQueue @JvmOverloads constructor(
    private val contentResolver: ContentResolver?,
    private val executorService: ExecutorService,
    private val clock: Clock,
    private val api: RestAPIInterface,
    private val networkBackoff: Backoff = Backoff(
        minBackoffMs = NETWORK_INITIAL_FAILURE_RETRY_MS,
        maxBackoffMs = MESSAGE_TIMEOUT_MS,
    ),
    private val retryScheduler: Scheduler = Schedulers.computation(),
) {

    private val queue = ArrayDeque<MessageRequest>()
    private var inFlightRequest: InflightRequest? = null
    private var isDraining = false
    private var retrySubscription: Subscription? = null

    /** Adds [request] to the tail of this channel's queue and drains if nothing is in flight. */
    fun enqueue(request: MessageRequest) {
        executorService.execute {
            queue.add(request)
            processNextRequest()
        }
    }

    /**
     * Cancels the queued or in-flight request identified by [requestId].
     *
     * A cancelled in-flight request has its network subscription torn down; [DrainListener] makes
     * the completion idempotent, so a response that was already on the wire cannot also complete it.
     */
    fun cancel(requestId: String) {
        executorService.execute {
            val inFlight = inFlightRequest
            if (inFlight != null && inFlight.baseRequest.requestId == requestId) {
                inFlight.networkSubscription.unsubscribe()
                inFlight.drainListener.complete(MessageResult.UserCancelled)
                return@execute
            }
            val queued = queue.firstOrNull { it.requestId == requestId } ?: return@execute
            queue.remove(queued)
            queued.onCompleted(MessageResult.UserCancelled, queue.isEmpty())
        }
    }

    /** Called when the gateway reconnects: drops the network backoff and resumes draining. */
    fun handleConnected() {
        executorService.execute {
            if (networkBackoff.isPending) {
                networkBackoff.cancel()
                processNextRequest()
            }
        }
    }

    /**
     * Cancels everything, completing each request as [MessageResult.UserCancelled].
     *
     * The waiting queue is drained *before* the in-flight request is cancelled: settling the
     * in-flight one re-enters [processNextRequest], which would otherwise pick up and send the very
     * requests this call is meant to discard.
     */
    fun cancelAll() {
        executorService.execute {
            retrySubscription?.unsubscribe()
            retrySubscription = null
            networkBackoff.cancel()
            while (queue.isNotEmpty()) {
                queue.remove().onCompleted(MessageResult.UserCancelled, queue.isEmpty())
            }
            inFlightRequest?.let { inFlight ->
                inFlight.networkSubscription.unsubscribe()
                inFlight.drainListener.complete(MessageResult.UserCancelled)
            }
        }
    }

    private fun processNextRequest() {
        if (queue.isEmpty() || retrySubscription != null || networkBackoff.isPending || isDraining) return
        val request = queue.remove() ?: return

        if (clock.currentTimeMillis() - request.attemptTimestamp > MESSAGE_TIMEOUT_MS) {
            request.onCompleted(MessageResult.Timeout, queue.isEmpty())
            networkBackoff.succeed()
            processNextRequest()
            return
        }

        isDraining = true
        val drainListener = DrainListener { result -> onRequestSettled(request, result) }
        when (request) {
            is MessageRequest.Send -> doSend(request, drainListener)
            is MessageRequest.Edit -> doEdit(request, drainListener)
            is MessageRequest.SendApplicationCommand -> doSendApplicationCommand(request, drainListener)
        }
    }

    /**
     * The queue's reaction to a settled request.
     *
     * Rate limits and network failures are absorbed here and never surface to the caller; everything
     * else completes the request and clears the backoff.
     */
    private fun onRequestSettled(request: MessageRequest, result: MessageResult) {
        when (result) {
            is MessageResult.RateLimited ->
                Observable.timer(result.retryAfterMs, TimeUnit.MILLISECONDS, retryScheduler)
                    .appSubscribe(
                        errorClass = MessageQueue::class.java,
                        subscriptionHandler = { retrySubscription = it },
                        onNext = {
                            executorService.execute {
                                queue.addFirst(request)
                                retrySubscription = null
                                onDrainingCompleted()
                                processNextRequest()
                            }
                        },
                    )

            MessageResult.NetworkFailure -> executorService.execute {
                onDrainingCompleted()
                queue.addFirst(request)
                networkBackoff.fail { executorService.execute { processNextRequest() } }
            }

            else -> executorService.execute {
                onDrainingCompleted()
                request.onCompleted(result, queue.isEmpty())
                networkBackoff.succeed()
                processNextRequest()
            }
        }
    }

    private fun onDrainingCompleted() {
        isDraining = false
        inFlightRequest = null
    }

    /**
     * Sends a message, uploading its attachments first when it has any.
     *
     * Order of operations is taken from the DEX for
     * `MessageQueue.doSend(MessageRequest$Send, MessageQueue$DrainListener)` because JADX could not
     * decompile the method body:
     *
     * 1. `validateMessage()` runs **first** and short-circuits — an invalid request never reaches
     *    preprocessing, so no attachment is read and no progress callback fires.
     * 2. The `RestAPIParams.Message` body is built from the local message.
     * 3. `SendUtils.getSendPayload(...)` is subscribed with a `doOnNext` that forwards
     *    `Preprocessing` to `onPreprocessing` and a non-empty `ReadyToSend.uploads` to `onProgress`.
     * 4. `filterIs<ReadyToSend>().take(1)` then flat-maps into the REST call.
     * 5. `restSubscribeOn(retry = message.nonce != null)` — transport retries are only safe when the
     *    message carries a nonce the server can de-duplicate on.
     * 6. `appSubscribe` records the subscription as the in-flight request, funnels failures through
     *    [handleError] and successes through [handleSuccess].
     */
    private fun doSend(request: MessageRequest.Send, drainListener: DrainListener) {
        val validationError = request.validateMessage()
        if (validationError != null) {
            drainListener.complete(validationError)
            return
        }

        val message = request.message
        val body = RestAPIParams.Message(
            content = message.content,
            nonce = message.nonce,
            applicationId = message.application?.id,
            activity = sendActivity(request),
            stickerIds = message.stickerItems?.map { it.id } ?: message.stickers?.map { it.id },
            messageReference = message.messageReference?.let { reference ->
                RestAPIParams.Message.MessageReference(
                    guildId = reference.guildId,
                    channelId = requireNotNull(reference.channelId) { "A message reference needs a channel" },
                    messageId = reference.messageId,
                )
            },
            allowedMentions = message.allowedMentions?.toRestApiParam(),
            flags = message.flags,
        )
        val captchaPolicy = CaptchaRequestPolicy.preSolved(message.captchaSolution)
        val uploadInline = DeveloperSettings.isLegacyMessageUploadsEnabled()

        SendUtils.getSendPayload(contentResolver, body, request.attachments, inlineFileBodies = uploadInline)
            .doOnNext { payload ->
                when (payload) {
                    is SendUtils.SendPayload.Preprocessing -> request.onPreprocessing(payload)
                    is SendUtils.SendPayload.ReadyToSend ->
                        if (payload.uploads.isNotEmpty()) request.onProgress(payload.uploads)

                    is SendUtils.SendPayload.ReadyToSendCommand -> Unit
                }
            }
            .filterIs<SendUtils.SendPayload.ReadyToSend>()
            .take(1)
            .flatMap { ready ->
                sendReadyPayload(
                    message.channelId,
                    ready,
                    request.attachments,
                    captchaPolicy,
                    contextPropertiesOf("location" to request.location),
                    api,
                    request.onProgress,
                    uploadInline,
                )
            }
            .restSubscribeOn(retry = message.nonce != null)
            .appSubscribe(
                errorClass = MessageQueue::class.java,
                subscriptionHandler = { subscription ->
                    inFlightRequest = InflightRequest(request, subscription, drainListener)
                },
                errorHandler = { error -> handleError(error, drainListener, message) },
                onNext = { resultMessage -> handleSuccess(resultMessage, drainListener) },
                // A stream that ends without ever producing a message or an error would otherwise
                // leave the request unsettled: the queue stays marked as draining and every later
                // send for this channel stalls behind a message that just sits there. Settling it
                // as a failure is recoverable — the row shows as failed and can be retried — where
                // a stalled queue is not. `DrainListener` ignores this once already settled.
                onTerminated = { drainListener.complete(MessageResult.NoResult) },
            )
    }

    /**
     * Uploads the files to storage first, then sends a message that claims them.
     *
     * The bytes no longer ride inside the send, so the request stays small however large the files
     * are, and a failed upload costs no message. Anything already staged when a later file fails is
     * released rather than left orphaned in the bucket.
     */
    private fun sendReadyPayload(
        channelId: Long,
        ready: SendUtils.SendPayload.ReadyToSend,
        attachments: List<UploadAttachment>?,
        captchaPolicy: CaptchaRequestPolicy,
        context: String,
        client: RestAPIInterface = api,
        onProgress: (List<FileUpload>) -> Unit = {},
        uploadInline: Boolean = false,
    ): Observable<com.discord.api.message.Message> {
        if (attachments.isNullOrEmpty() || contentResolver == null) {
            return client.sendMessage(channelId, ready.message, captchaPolicy, context)
        }
        if (uploadInline) {
            val parts = ready.uploads.mapNotNull(FileUpload::part).toTypedArray()
            return if (parts.isEmpty()) {
                client.sendMessage(channelId, ready.message, captchaPolicy, context)
            } else {
                client.sendMessage(channelId, PayloadJSON(ready.message), parts, captchaPolicy, context)
            }
        }
        return CloudUploader.stageAndSend(
            channelId,
            contentResolver,
            attachments,
            onProgress = { upload -> onProgress(listOf(upload)) },
        ) { uploads ->
            client.sendMessage(channelId, claimUploads(ready.message, uploads), captchaPolicy, context)
        }
    }

    private fun doSendApplicationCommand(
        request: MessageRequest.SendApplicationCommand,
        drainListener: DrainListener,
    ) {
        SendUtils.getSendCommandPayload(contentResolver, request.applicationCommandSendData, request.attachments)
            .doOnNext { payload ->
                when (payload) {
                    is SendUtils.SendPayload.Preprocessing -> request.onPreprocessing(payload)
                    is SendUtils.SendPayload.ReadyToSendCommand ->
                        if (payload.uploads.isNotEmpty()) request.onProgress(payload.uploads)

                    is SendUtils.SendPayload.ReadyToSend -> Unit
                }
            }
            .filterIs<SendUtils.SendPayload.ReadyToSendCommand>()
            .take(1)
            .flatMap { ready ->
                val body = ready.command.toRestApiParam()
                val parts = ready.uploads.mapNotNull(FileUpload::part)
                if (parts.isEmpty()) {
                    api.sendApplicationCommand(body)
                } else {
                    api.sendApplicationCommand(PayloadJSON(body), parts.toTypedArray())
                }
            }
            .restSubscribeOn(retry = false)
            .appSubscribe(
                errorClass = MessageQueue::class.java,
                subscriptionHandler = { subscription ->
                    inFlightRequest = InflightRequest(request, subscription, drainListener)
                },
                errorHandler = { error -> handleError(error, drainListener) },
                onNext = { handleSuccess(request.message, drainListener) },
            )
    }

    /**
     * Edits a message.
     *
     * Edits are never retried at the transport level (`restSubscribeOn(retry = false)` in the
     * reference) because there is no nonce to de-duplicate a repeated PATCH, and no client message
     * is handed to [handleError] — which is why an edit can never produce
     * [MessageResult.CaptchaRequired].
     */
    private fun doEdit(request: MessageRequest.Edit, drainListener: DrainListener) {
        api.editMessage(
            request.channelId,
            request.messageId,
            RestAPIParams.Message(
                content = request.content,
                allowedMentions = request.allowedMentions?.toRestApiParam(),
            ),
        )
            .restSubscribeOn(retry = false)
            .appSubscribe(
                errorClass = MessageQueue::class.java,
                subscriptionHandler = { subscription ->
                    inFlightRequest = InflightRequest(request, subscription, drainListener)
                },
                errorHandler = { error -> handleError(error, drainListener) },
                onNext = { resultMessage -> handleSuccess(resultMessage, drainListener) },
            )
    }

    private fun handleSuccess(message: com.discord.api.message.Message, drainListener: DrainListener) {
        drainListener.complete(MessageResult.Success(message))
    }

    /**
     * Classifies a REST failure into a [MessageResult].
     *
     * [clientMessage] is only available for sends; edits pass null, so their AutoMod block carries
     * no message and they can never produce a captcha challenge.
     */
    private fun handleError(
        error: RestApiError,
        drainListener: DrainListener,
        clientMessage: Message? = null,
    ) {
        val result = when {
            error.code == SLOW_MODE_ERROR_CODE ->
                MessageResult.Slowmode(error.retryAfterMs ?: DEFAULT_RETRY_MS)

            error.type == RestApiError.Type.RATE_LIMITED ->
                MessageResult.RateLimited(error.retryAfterMs ?: DEFAULT_RETRY_MS)

            error.type == RestApiError.Type.NETWORK -> MessageResult.NetworkFailure

            error.type == RestApiError.Type.DISCORD_BAD_REQUEST &&
                error.isCaptchaRequired &&
                clientMessage != null ->
                MessageResult.CaptchaRequired(error, clientMessage.channelId, clientMessage.nonce)

            error.code == AUTOMOD_BLOCK_ERROR_CODE -> MessageResult.AutoModBlock(error, clientMessage)

            else -> MessageResult.UnknownFailure(error)
        }
        drainListener.complete(result)
    }

    private fun sendActivity(request: MessageRequest.Send): RestAPIParams.Message.Activity? {
        val messageActivity = request.message.activity ?: return null
        val sessionId = request.activity?.sessionId ?: return null
        return RestAPIParams.Message.Activity(messageActivity.type, messageActivity.partyId, sessionId)
    }

    /**
     * Guarantees a request settles exactly once.
     *
     * Both the network callbacks and [cancel] can race to finish the same request; the reference
     * uses this same compare-and-set so a cancelled request is not also completed by a late
     * response.
     */
    class DrainListener(private val onCompleted: (MessageResult) -> Unit) {
        private val isCompleted = AtomicBoolean(false)

        @Synchronized
        fun complete(result: MessageResult) {
            if (!isCompleted.getAndSet(true)) onCompleted(result)
        }
    }

    private class InflightRequest(
        val baseRequest: MessageRequest,
        val networkSubscription: Subscription,
        val drainListener: DrainListener,
    )

    companion object {
        /** Requests older than an hour are dropped rather than sent. */
        const val MESSAGE_TIMEOUT_MS = 3_600_000L

        /**
         * Seed for the network-failure backoff, which grows jittered up to [MESSAGE_TIMEOUT_MS].
         * Note this is the seed, not the first delay: see [Backoff.fail] for the growth formula.
         */
        const val NETWORK_INITIAL_FAILURE_RETRY_MS = 5_000L

        /** Fallback delay when the API reports a limit without a `retry_after`. */
        const val DEFAULT_RETRY_MS = 100L

        /** API error code for "this channel is in slow mode". */
        const val SLOW_MODE_ERROR_CODE = 20016

        /** API error code for "AutoMod blocked this message". */
        const val AUTOMOD_BLOCK_ERROR_CODE = 200000
    }
}

private fun com.discord.api.message.allowedmentions.MessageAllowedMentions.toRestApiParam() =
    RestAPIParams.Message.AllowedMentions(
        parse = parse?.map { it.serialize() },
        users = users,
        roles = roles,
        repliedUser = repliedUser,
    )

/**
 * Points each attachment entry at the file it just uploaded.
 *
 * Matched by position, not by name: `SendUtils` renames the entries on the message so that two
 * files chosen with the same name stay distinguishable, while the uploads keep the names they were
 * picked with. Keying on the filename therefore misses exactly the attachments that were renamed,
 * and an entry with no `uploaded_filename` is one the server cannot resolve. Position is reliable
 * because the entries are built from the same list, in order, and the uploads run through
 * `concatMap`, which preserves it.
 */
internal fun claimUploads(
    message: RestAPIParams.Message,
    uploads: List<CloudUploader.StagedUpload>,
): RestAPIParams.Message = message.copy(
    attachments = message.attachments?.mapIndexed { index, attachment ->
        attachment.copy(uploadedFilename = uploads.getOrNull(index)?.uploadedFilename)
    },
)
