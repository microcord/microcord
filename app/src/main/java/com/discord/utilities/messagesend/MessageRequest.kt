package com.discord.utilities.messagesend

sealed class MessageRequest(
    open val requestId: String,
    open val attemptTimestamp: Long,
    open val onCompleted: (MessageResult, Boolean) -> Unit,
) {
    data class Send(
        override val requestId: String,
        override val attemptTimestamp: Long,
        override val onCompleted: (MessageResult, Boolean) -> Unit,
    ) : MessageRequest(requestId, attemptTimestamp, onCompleted)

    data class Edit(
        override val requestId: String,
        override val attemptTimestamp: Long,
        override val onCompleted: (MessageResult, Boolean) -> Unit,
    ) : MessageRequest(requestId, attemptTimestamp, onCompleted)
}
