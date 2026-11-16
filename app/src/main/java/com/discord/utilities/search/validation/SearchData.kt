package com.discord.utilities.search.validation

import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.models.guild.UserGuildMember
import com.discord.models.member.GuildMember
import com.discord.models.user.CoreUser
import com.discord.models.user.MeUser
import com.discord.models.user.User
import java.util.Locale

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
    private val usernameIndex: Map<String, Long> = emptyMap(),
    val channelNameIndex: Map<String, List<Long>> = emptyMap(),
    val users: Map<Long, UserGuildMember> = emptyMap(),
    val channels: Map<Long, Channel> = emptyMap(),
    val channelPermissions: Map<Long, Long> = emptyMap(),
    private val meId: Long? = null,
) {

    fun getUserId(username: String, discriminator: Int): Long? =
        usernameAndDiscriminatorIndex[username + discriminator]

    /** RN's `from:` answer: a raw id, `@me`, or a unique username. */
    fun getUserId(username: String): Long? = when {
        SNOWFLAKE.matches(username) -> username.toLong()
        username.equals("@me", ignoreCase = true) -> meId
        else -> usernameIndex[username.lowercase(Locale.ROOT)]
    }

    /** RN's `in:` answer: a raw id, or every searchable channel with that name. */
    fun getChannelIds(name: String): List<Long> =
        if (SNOWFLAKE.matches(name)) listOf(name.toLong()) else channelNameIndex[name].orEmpty()

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
            val usernameIndex = HashMap<String, Long>()

            fun index(user: User) {
                users[user.id] = UserGuildMember(user, guildMembers[user.id])
                usernameAndDiscriminatorIndex[user.usernameWithDiscrim] = user.id
                if (user.discriminator == 0) usernameIndex[user.username.lowercase(Locale.ROOT)] = user.id
            }

            channel?.recipients?.forEach { index(CoreUser(it)) }
            index(meUser)

            return SearchData(
                usernameAndDiscriminatorIndex = usernameAndDiscriminatorIndex,
                usernameIndex = usernameIndex,
                users = users,
                meId = meUser.id,
            )
        }

        /**
         * Guild search. Suggestions ([users]) only offer loaded members, but a typed name resolves
         * against every cached user, as RN's `findByTag` does: the member list of a large guild is
         * rarely loaded, and the server decides whether the author is in the guild anyway.
         */
        fun buildForGuild(
            guildMembers: Map<Long, GuildMember>,
            allUsers: Map<Long, User>,
            guildSearchableChannels: Map<Long, Channel>,
            guildChannelPermissions: Map<Long, Long>,
            meId: Long? = null,
        ): SearchData {
            val channelNameIndex = guildSearchableChannels.values
                .groupBy({ it.getDisplayName() }, { it.id })

            val users = HashMap<Long, UserGuildMember>()
            val usernameAndDiscriminatorIndex = HashMap<String, Long>()
            val usernameIndex = HashMap<String, Long>()
            allUsers.forEach { (userId, user) ->
                guildMembers[userId]?.let { users[userId] = UserGuildMember(user, it) }
                usernameAndDiscriminatorIndex[user.usernameWithDiscrim] = userId
                if (user.discriminator == 0) usernameIndex[user.username.lowercase(Locale.ROOT)] = userId
            }

            return SearchData(
                usernameAndDiscriminatorIndex = usernameAndDiscriminatorIndex,
                usernameIndex = usernameIndex,
                channelNameIndex = channelNameIndex,
                users = users,
                channels = guildSearchableChannels,
                channelPermissions = guildChannelPermissions,
                meId = meId,
            )
        }
    }

    private companion object {
        val SNOWFLAKE = Regex("""\d{17,20}""")
    }
}
