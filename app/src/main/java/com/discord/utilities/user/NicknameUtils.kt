package com.discord.utilities.user

import com.discord.api.channel.isPrivate
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.utilities.user.UserUtils.getDisplayName
import com.discord.api.user.User as ApiUser

/**
 * Resolves the name to show for a user in a given context.
 *
 * The two kinds of nickname never compete: a guild nickname applies inside that guild, a friend
 * nickname only outside guilds and only in a DM or group DM. So a friend you have renamed still
 * shows their server nickname in a server.
 *
 * Callers that already hold the relevant nickname should use [UserUtils.getDisplayName] directly;
 * this is for the ones that only have ids.
 */
object NicknameUtils {

    fun getNickname(guildId: Long?, channelId: Long?, userId: Long): String? {
        if (guildId != null) return StoreStream.getGuilds().getMember(guildId, userId)?.nick
        if (channelId != null) {
            val channel = StoreStream.getChannels().getChannel(channelId) ?: return null
            if (channel.isPrivate()) return StoreStream.getUserRelationships().getNickname(userId)
        }
        return null
    }

    fun getNickname(guildId: Long?, channelId: Long?, user: User?): String? =
        user?.let { getNickname(guildId, channelId, it.id) }

    /** The nickname for this context, else the account display name, else the username. */
    fun getName(guildId: Long?, channelId: Long?, user: User?): String =
        user?.getDisplayName(getNickname(guildId, channelId, user)).orEmpty()

    fun getName(guildId: Long?, channelId: Long?, user: ApiUser?): String =
        user?.getDisplayName(getNickname(guildId, channelId, user.id)).orEmpty()
}
