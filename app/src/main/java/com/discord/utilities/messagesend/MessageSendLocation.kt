package com.discord.utilities.messagesend

/**
 * Reference `MessageSendLocation`: the surface a message was sent from, reported as
 * `{"location": …}` in `X-Context-Properties`.
 *
 * The reference tags every send, defaulting untagged ones to [OTHER] rather than dropping the
 * header, so this is never null on the wire. Only the values surfaces here can produce are listed.
 */
object MessageSendLocation {
    const val PUSH_NOTIFICATION = "push_notification"
    const val CHAT_INPUT = "chat_input"
    const val SHARE_MODAL = "share_modal"
    const val THREAD_CREATION = "thread_creation"
    const val FORWARDING = "forwarding"
    const val RETRY = "explicit_retry"
    const val APP_COMMAND = "app_command"
    const val POLL_CREATION = "poll_creation"
    const val GIF_REPLY = "gif_reply"
    const val STICKER_REPLY = "sticker_reply"
    const val GREET = "greet"
    const val OTHER = "other"
}
