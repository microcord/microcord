package com.discord.utilities.textprocessing

import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.models.domain.emoji.EmojiSet
import com.discord.models.guild.Guild
import com.discord.models.user.User
import com.discord.utilities.user.UserUtils

object MessageUnparser {
    private val entity = Regex(
        """<(a)?:([A-Za-z0-9_]+):(\d+)>|<#?(\d+)>|<@!?(\d+)>|<@&?(\d+)>""",
    )

    @JvmStatic
    fun unparse(
        rawMessageContent: String,
        guild: Guild?,
        channels: Map<Long, Channel>,
        users: Map<Long, User>,
        emojiSet: EmojiSet,
    ): CharSequence = entity.replace(rawMessageContent) { match ->
        when {
            match.groups[3] != null -> {
                val name = emojiSet.emojiIndex[match.groupValues[3]]?.firstName
                    ?: match.groupValues[2]
                ":$name:"
            }
            match.groups[4] != null -> match.groupValues[4].toLongOrNull()
                ?.let(channels::get)
                ?.let { "#${it.getDisplayName()}" }
                ?: match.value
            match.groups[5] != null -> match.groupValues[5].toLongOrNull()
                ?.let(users::get)
                ?.let { "@${it.username}${UserUtils.discriminatorSuffix(it.discriminator)}" }
                ?: match.value
            else -> match.groupValues[6].toLongOrNull()
                ?.let { id -> guild?.roles?.firstOrNull { it.id == id } }
                ?.let { "@${it.name}" }
                ?: match.value
        }
    }
}
