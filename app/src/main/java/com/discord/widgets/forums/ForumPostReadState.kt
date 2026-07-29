package com.discord.widgets.forums

data class ForumPostReadState(
    val channelId: Long,
    val hasSeenBefore: Boolean,
    val hasUnread: Boolean,
) {
    companion object {
        @JvmStatic
        fun createDefault(channelId: Long) = ForumPostReadState(channelId, true, false)
    }
}
