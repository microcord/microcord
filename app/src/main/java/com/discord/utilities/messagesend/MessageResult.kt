package com.discord.utilities.messagesend

import com.discord.models.message.Message
import com.discord.utilities.captcha.CaptchaErrorBody
import com.discord.utilities.error.RestApiError

/**
 * Every terminal outcome a queued message operation can have.
 *
 * Reconstruction of the reference `com.discord.utilities.messagesend.MessageResult`. The payloads
 * are load-bearing — dropping them is what makes a "message failed" toast unactionable — so each
 * variant carries what its consumers actually need:
 *
 * - [Success] carries the created API message, which the store uses to replace the optimistic local
 *   message and to feed the slow-mode cooldown tracker.
 * - [CaptchaRequired] carries the channel and nonce so a solved challenge can re-send *that*
 *   message (`resendMessageWithCaptcha(channelId, nonce, solution)`), plus the [RestApiError] whose
 *   challenge body the captcha prompt needs.
 * - [AutoModBlock] carries the blocked message so the AutoMod notice row can be rendered in place.
 *
 * [RateLimited] and [NetworkFailure] are intercepted by [MessageQueue] itself and retried; they only
 * reach a request's `onCompleted` if a caller drives the queue directly.
 */
sealed class MessageResult {

    /** The message was created. [message] is the server's canonical copy. */
    data class Success(val message: com.discord.api.message.Message) : MessageResult()

    /** Channel slow mode rejected the send (API error code 20016). */
    data class Slowmode(val cooldownMs: Long) : MessageResult()

    /** The request was rate limited; the queue re-attempts after [retryAfterMs]. */
    data class RateLimited(val retryAfterMs: Long) : MessageResult()

    /** Connectivity failure; the queue re-attempts under its [com.discord.utilities.networking.Backoff]. */
    data object NetworkFailure : MessageResult()

    /**
     * The API demands a solved captcha before it will accept this message.
     *
     * @param channelId channel the blocked message belongs to.
     * @param nonce nonce of the blocked local message, used to find and re-send it.
     */
    data class CaptchaRequired(
        val error: RestApiError,
        val channelId: Long,
        val nonce: String?,
    ) : MessageResult() {
        val challenge: CaptchaErrorBody? get() = error.captchaChallenge
    }

    /**
     * AutoMod blocked the message (API error code 200000).
     *
     * [message] is the local message that was blocked. It is null for edits, which the reference
     * sends without a client message attached.
     */
    data class AutoModBlock(
        val error: RestApiError,
        val message: Message? = null,
    ) : MessageResult()

    /** Any other classified failure. */
    data class UnknownFailure(val error: RestApiError) : MessageResult()

    /** The request was rejected before it was sent. [message] is a developer-facing reason. */
    data class ValidationError(val message: String) : MessageResult()

    /** There was nothing worth sending: no content, no attachments, no stickers. */
    data object NoValidContent : MessageResult()

    /** The request sat in the queue past the one-hour deadline and was dropped. */
    data object Timeout : MessageResult()

    /** The user cancelled the request, either while queued or in flight. */
    data object UserCancelled : MessageResult()
}
