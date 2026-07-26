package com.discord.utilities.search.validation

import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.models.guild.UserGuildMember
import com.discord.models.member.GuildMember
import com.discord.models.user.CoreUser
import com.discord.models.user.MeUser
import com.discord.models.user.User

/**
 * The names a search query is allowed to refer to, and the indexes that turn those names back into
 * ids.
 *
 * This is what makes `from:alice#0001` resolvable offline: the client never asks the API to look a
 * name up, it only sends ids it already knows. A guild search indexes the whole member list and
 * every channel the viewer can read; a private-channel search indexes just the recipients plus the
 * viewer.
 */
data class SearchData(
    private val usernameAndDiscriminatorIndex: Map<String, Long> = emptyMap(),
    val channelNameIndex: Map<String, Long> = emptyMap(),
    val users: Map<Long, UserGuildMember> = emptyMap(),
    val channels: Map<Long, Channel> = emptyMap(),
    val channelPermissions: Map<Long, Long> = emptyMap(),
) {

    fun getUserId(username: String, discriminator: Int): Long? =
        usernameAndDiscriminatorIndex[username + discriminator]

    class Builder {

        private val User.usernameWithDiscrim: String get() = "$username$discriminator"

        /** DM and group-DM search: only the people in the conversation can be named. */
        fun buildForChannel(
            channel: Channel?,
            meUser: MeUser,
            guildMembers: Map<Long, GuildMember>,
        ): SearchData {
            val users = HashMap<Long, UserGuildMember>()
            val usernameAndDiscriminatorIndex = HashMap<String, Long>()

            fun index(user: User) {
                users[user.id] = UserGuildMember(user, guildMembers[user.id])
                usernameAndDiscriminatorIndex[user.usernameWithDiscrim] = user.id
            }

            channel?.recipients?.forEach { index(CoreUser(it)) }
            index(meUser)

            return SearchData(
                usernameAndDiscriminatorIndex = usernameAndDiscriminatorIndex,
                users = users,
            )
        }

        /**
         * Guild search. Only users present in both the member map and the user map are indexed —
         * a member the client has not hydrated a user for cannot be named or rendered.
         */
        fun buildForGuild(
            guildMembers: Map<Long, GuildMember>,
            allUsers: Map<Long, User>,
            guildSearchableChannels: Map<Long, Channel>,
            guildChannelPermissions: Map<Long, Long>,
        ): SearchData {
            val channelNameIndex = HashMap<String, Long>()
            guildSearchableChannels.values.forEach { channel ->
                channelNameIndex[channel.getDisplayName()] = channel.id
            }

            val users = HashMap<Long, UserGuildMember>()
            val usernameAndDiscriminatorIndex = HashMap<String, Long>()
            allUsers.keys.forEach { userId ->
                val member = guildMembers[userId]
                val user = allUsers[userId]
                if (member != null && user != null) {
                    users[userId] = UserGuildMember(user, member)
                    usernameAndDiscriminatorIndex[user.usernameWithDiscrim] = userId
                }
            }

            return SearchData(
                usernameAndDiscriminatorIndex = usernameAndDiscriminatorIndex,
                channelNameIndex = channelNameIndex,
                users = users,
                channels = guildSearchableChannels,
                channelPermissions = guildChannelPermissions,
            )
        }
    }
}
