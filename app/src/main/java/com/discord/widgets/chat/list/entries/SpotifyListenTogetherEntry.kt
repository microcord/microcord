package com.discord.widgets.chat.list.entries

import com.discord.models.message.Message
import com.discord.api.message.activity.MessageActivity

/** Spotify listen-along invitation. */
data class SpotifyListenTogetherEntry(
    val userId: Long,
    val messageId: Long,
    val activity: MessageActivity,
    val message: Message,
) : ChatListEntry() {

    override val highlightMessage: Message get() = message
    override val type: Int = ChatListEntry.SPOTIFY_LISTEN_TOGETHER
    override val key: String = "${ChatListEntry.SPOTIFY_LISTEN_TOGETHER} -- $messageId"
}
