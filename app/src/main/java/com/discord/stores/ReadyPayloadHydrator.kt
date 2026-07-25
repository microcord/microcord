package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.guild.Guild
import com.discord.api.guildmember.GuildMember
import com.discord.api.presence.Presence
import com.discord.api.user.User
import com.discord.models.domain.ModelPayload

internal class ReadyPayloadHydrator(
    private val guildStore: StoreGuilds,
    private val channelStore: StoreChannels,
) {
    fun hydrate(payload: ModelPayload): ModelPayload {
        val guilds = payload.guilds ?: return payload
        val users = LinkedHashMap<Long, User>().apply {
            payload.users.orEmpty().forEach { put(it.id, it) }
            payload.me?.let { put(it.id, it) }
            guilds.flatMap { it.members.orEmpty() }.forEach { member ->
                runCatching { member.user }.getOrNull()?.let { put(it.id, it) }
            }
        }

        val hydratedMembers = guilds.mapIndexed { index, guild ->
            val source = payload.guildMembers?.getOrNull(index).orEmpty().ifEmpty { guild.members.orEmpty() }
            source.mapNotNull { hydrateMember(it, guild.id, users) }
        }
        hydratedMembers.flatten().forEach { users[it.user.id] = it.user }

        val hydratedGuildPresences = guilds.mapIndexed { index, guild ->
            val source = payload.guildPresences?.getOrNull(index).orEmpty().ifEmpty { guild.presences.orEmpty() }
            source.mapNotNull { hydratePresence(it, guild.id, users) }
        }
        val hydratedPresences = payload.presences.orEmpty().mapNotNull { hydratePresence(it, it.guildId, users) }

        val hydratedGuilds = guilds.mapIndexed { index, guild ->
            val cached = guildStore.getGuildsInternal()[guild.id]
            val cachedChannels = channelStore.getChannelsByGuildInternal()[guild.id].orEmpty().values.toList()
            val cachedRoles = guildStore.getGuildRolesInternal()[guild.id].orEmpty().values.toList()
            guild.copy(
                roles = guild.roles?.takeUnless(List<*>::isEmpty) ?: cachedRoles,
                name = guild.name.ifEmpty { cached?.name.orEmpty() },
                ownerId = guild.ownerId.takeUnless { it == 0L } ?: cached?.ownerId ?: 0L,
                icon = guild.icon ?: cached?.icon,
                channels = guild.channels?.takeUnless(List<*>::isEmpty) ?: cachedChannels,
                members = hydratedMembers[index],
                presences = hydratedGuildPresences[index],
            )
        }

        val privateChannels = (payload.privateChannels ?: channelStore.getChannelsByGuildInternal()[0L].orEmpty().values.toList())
            .map { hydrateRecipients(it, users) }
        val relationships = payload.relationships?.map { it.hydrate(users) }

        return payload.withGuilds(hydratedGuilds).withHydratedUserData(
            relationships.orEmpty(),
            privateChannels,
            hydratedGuildPresences,
            hydratedMembers,
            hydratedPresences,
        )
    }

    private fun hydrateRecipients(channel: Channel, users: Map<Long, User>) = if (!channel.recipients.isNullOrEmpty()) {
        channel
    } else {
        channel.copy(recipients = channel.recipientIds.orEmpty().mapNotNull(users::get))
    }

    private fun hydrateMember(member: GuildMember, guildId: Long, users: Map<Long, User>): GuildMember? {
        val inline = runCatching { member.user }.getOrNull()
        val user = member.userId?.let(users::get) ?: inline ?: return null
        return member.copy(guildId = member.guildId.takeUnless { it == 0L } ?: guildId, user = user)
    }

    private fun hydratePresence(presence: Presence, guildId: Long?, users: Map<Long, User>): Presence? {
        val user = presence.user ?: presence.userId?.let(users::get)
        val userId = presence.userId ?: user?.id ?: return null
        return presence.copy(user = user, userId = userId, guildId = presence.guildId ?: guildId)
    }
}
