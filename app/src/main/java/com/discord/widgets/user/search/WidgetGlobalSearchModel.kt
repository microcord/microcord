package com.discord.widgets.user.search

import com.discord.api.channel.Channel
import com.discord.models.guild.Guild
import com.discord.models.presence.Presence
import com.discord.models.user.User

class WidgetGlobalSearchModel private constructor() {
    data class MatchedResult(
        val value: CharSequence,
        val firstMatchIndex: Int,
        val filterLength: Int,
    )

    interface ItemDataPayload {
        val type: Int
        val key: String
        val channel: Channel?
        val matchedResult: MatchedResult
        val mentions: Int
        val unread: Boolean
    }

    data class ItemChannel(
        override val matchedResult: MatchedResult,
        override val channel: Channel,
        val parentChannel: Channel? = null,
        val guild: Guild? = null,
        override val mentions: Int = 0,
        override val unread: Boolean = false,
        val presence: Presence? = null,
    ) : ItemDataPayload {
        override val type = TYPE_CHANNEL
        override val key = channel.id.toString()
    }

    data class ItemGuild(
        override val matchedResult: MatchedResult,
        val guild: Guild,
        override val channel: Channel? = null,
        override val mentions: Int = 0,
        override val unread: Boolean = false,
    ) : ItemDataPayload {
        override val type = TYPE_GUILD
        override val key = guild.id.toString()
    }

    data class ItemUser(
        override val matchedResult: MatchedResult,
        val user: User,
        val aliases: List<CharSequence>,
        val isFriend: Boolean,
        val presence: Presence? = null,
        override val channel: Channel? = null,
        override val mentions: Int = 0,
        override val unread: Boolean = false,
    ) : ItemDataPayload {
        override val type = TYPE_USER
        override val key = user.id.toString()
    }

    companion object {
        const val TYPE_CHANNEL = 0
        const val TYPE_USER = 1
        const val TYPE_GUILD = 2
    }
}
