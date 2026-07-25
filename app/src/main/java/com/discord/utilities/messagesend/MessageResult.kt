package com.discord.utilities.messagesend

sealed class MessageResult {
    data object Success : MessageResult()
    data class Slowmode(val cooldownMs: Long) : MessageResult()
    data class RateLimited(val retryAfterMs: Long) : MessageResult()
    data object NetworkFailure : MessageResult()
    data class CaptchaRequired(val siteKey: String?, val rqtoken: String?) : MessageResult()
    data object AutoModBlock : MessageResult()
    data class UnknownFailure(val cause: Throwable? = null) : MessageResult()
    data class ValidationError(val message: String) : MessageResult()
    data object NoValidContent : MessageResult()
    data object Timeout : MessageResult()
    data object UserCancelled : MessageResult()
}
