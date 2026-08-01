package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.guild.Guild
import com.discord.api.guildhash.GuildHashes
import com.discord.api.guildmember.GuildMember
import com.discord.api.presence.Presence
import com.discord.api.role.GuildRole
import com.discord.api.user.User
import com.discord.models.domain.ModelPayload
import com.discord.models.guild.Guild as StoredGuild

internal class ReadyPayloadHydrator(
    private val guildStore: StoreGuilds,
    private val channelStore: StoreChannels,
) {
    private val cache = HashMap<Long, GuildCache>()

    sealed class HydrateResult {
        data class Success(val payload: ModelPayload) : HydrateResult()
        object Error : HydrateResult()
    }

    sealed class HydrateGuildResult {
        data class Success(val guild: Guild) : HydrateGuildResult()
        object Error : HydrateGuildResult()
    }

    /** Compatibility adapter until the separately owned StoreStream handles [HydrateResult.Error]. */
    fun hydrate(payload: ModelPayload): ModelPayload =
        (hydrateReadyPayload(payload) as? HydrateResult.Success)?.payload ?: payload

    @StoreThread
    fun hydrateReadyPayload(payload: ModelPayload): HydrateResult {
        snapshotCache()
        val guilds = payload.guilds ?: return HydrateResult.Success(payload)
        val users = LinkedHashMap<Long, User>().apply {
            payload.users.orEmpty().forEach { put(it.id, it) }
            payload.me?.let { put(it.id, it) }
            guilds.flatMap { it.members.orEmpty() }.forEach { member ->
                runCatching { member.user }.getOrNull()?.let { put(it.id, it) }
            }
        }

        val hydratedMembers = guilds.mapIndexed { index, guild ->
            val source = payload.guildMembers?.getOrNull(index).orEmpty().ifEmpty { guild.members.orEmpty() }
            source.map { hydrateMember(it, users) }
        }
        hydratedMembers.flatten().forEach { member ->
            runCatching { member.user }.getOrNull()?.let { users[it.id] = it }
        }

        val hydratedGuildPresences = guilds.mapIndexed { index, guild ->
            val source = payload.guildPresences?.getOrNull(index).orEmpty().ifEmpty { guild.presences.orEmpty() }
            source.map { hydratePresence(it, users) }
        }
        val hydratedPresences = payload.presences.orEmpty().map { hydratePresence(it, users) }

        val hydratedGuilds = ArrayList<Guild>(guilds.size)
        guilds.forEachIndexed { index, guild ->
            val members = hydratedMembers[index].associateByTo(LinkedHashMap()) { member ->
                runCatching { member.user.id }.getOrNull() ?: member.userId
                    ?: error("Guild member has neither user nor user id")
            }
            when (val result = hydrateGuild(guild, hydratedGuildPresences[index], members)) {
                HydrateGuildResult.Error -> return HydrateResult.Error
                is HydrateGuildResult.Success -> hydratedGuilds += result.guild
            }
        }

        val privateChannels = (payload.privateChannels
            ?: channelStore.getChannelsByGuildInternal()[0L].orEmpty().values.toList())
            .map { hydrateRecipients(it, users) }
        val relationships = payload.relationships?.map { it.hydrate(users) }
        val hydrated = payload.withGuilds(hydratedGuilds).withHydratedUserData(
            relationships.orEmpty(),
            privateChannels,
            hydratedGuildPresences,
            hydratedMembers,
            hydratedPresences,
        )
        return HydrateResult.Success(hydrated)
    }

    @StoreThread
    fun hydrateGuild(
        guild: Guild,
        presences: List<Presence>? = null,
        members: Map<Long, GuildMember>? = null,
    ): HydrateGuildResult {
        if (guild.unavailable) return HydrateGuildResult.Success(guild)

        val hashes = guild.guildHashes
        val metadataOmitted = hashes.isSupported && hashes?.metadata?.omitted == true
        val channelsOmitted = hashes.isSupported && hashes?.channels?.omitted == true
        val rolesOmitted = hashes.isSupported && hashes?.roles?.omitted == true
        val cached = cache.remove(guild.id)
        if ((metadataOmitted || channelsOmitted || rolesOmitted) && cached == null) {
            return HydrateGuildResult.Error
        }

        var hydrated = guild
        if (metadataOmitted) {
            val old = requireNotNull(cached).guild
            hydrated = hydrated.copy(
                emojis = old.emojis,
                stickers = old.stickers,
                name = old.name,
                description = old.description,
                defaultMessageNotifications = old.defaultMessageNotifications,
                region = old.region,
                ownerId = old.ownerId,
                icon = old.icon,
                verificationLevel = old.verificationLevel,
                explicitContentFilter = old.explicitContentFilter,
                mfaLevel = old.mfaLevel,
                afkTimeout = old.afkTimeout,
                afkChannelId = old.afkChannelId,
                systemChannelId = old.systemChannelId,
                features = old.features,
                banner = old.banner,
                splash = old.splash,
                premiumTier = old.premiumTier,
                systemChannelFlags = old.systemChannelFlags,
                rulesChannelId = old.rulesChannelId,
                publicUpdatesChannelId = old.publicUpdatesChannelId,
                preferredLocale = old.preferredLocale,
                maxVideoChannelUsers = old.maxVideoChannelUsers,
                vanityUrlCode = old.vanityUrlCode,
            )
        }
        if (channelsOmitted) hydrated = hydrateChannels(hydrated, requireNotNull(cached).channels)
        if (rolesOmitted) hydrated = hydrated.copy(roles = requireNotNull(cached).roles)
        if (presences != null) hydrated = hydrated.copy(presences = presences)
        if (members != null) hydrated = hydrated.copy(members = members.values.toList())
        return HydrateGuildResult.Success(hydrated)
    }

    private fun snapshotCache() {
        cache.clear()
        guildStore.getGuildsInternal().forEach { (guildId, guild) ->
            cache[guildId] = GuildCache(
                guild.toApiGuild(),
                channelStore.getChannelsByGuildInternal()[guildId]?.values?.toList(),
                guildStore.getGuildRolesInternal()[guildId]?.values?.toList(),
            )
        }
    }

    private fun hydrateChannels(guild: Guild, cachedChannels: List<Channel>?): Guild {
        if (cachedChannels == null) return guild
        val updates = guild.channelUpdates?.associateBy(Channel::id).orEmpty()
        return guild.copy(
            channels = cachedChannels.map { cached ->
                val update = updates[cached.id]
                if (update == null || update.lastMessageId == 0L) cached
                else cached.copy(lastMessageId = update.lastMessageId)
            },
            channelUpdates = null,
        )
    }

    private fun hydrateRecipients(channel: Channel, users: Map<Long, User>) = if (channel.recipientIds == null) {
        channel
    } else {
        channel.copy(recipients = channel.recipientIds.mapNotNull(users::get))
    }

    private fun hydrateMember(member: GuildMember, users: Map<Long, User>): GuildMember {
        val user = member.userId?.let(users::get) ?: return member
        return member.copy(user = user)
    }

    private fun hydratePresence(presence: Presence, users: Map<Long, User>): Presence =
        presence.copy(user = presence.userId?.let(users::get))

    private fun StoredGuild.toApiGuild() = Guild(
        roles = roles,
        emojis = emojis,
        stickers = stickers,
        name = name,
        description = description,
        defaultMessageNotifications = defaultMessageNotifications,
        id = id,
        region = region,
        ownerId = ownerId,
        icon = icon,
        verificationLevel = verificationLevel,
        explicitContentFilter = explicitContentFilter,
        presences = null,
        channels = null,
        members = null,
        voiceStates = null,
        unavailable = unavailable,
        mfaLevel = mfaLevel,
        afkTimeout = afkTimeout,
        afkChannelId = afkChannelId,
        systemChannelId = systemChannelId,
        features = features.toList(),
        memberCount = memberCount,
        banner = banner,
        splash = splash,
        premiumTier = premiumTier,
        premiumSubscriptionCount = premiumSubscriptionCount,
        systemChannelFlags = systemChannelFlags,
        joinedAt = joinedAt,
        rulesChannelId = rulesChannelId,
        publicUpdatesChannelId = publicUpdatesChannelId,
        preferredLocale = preferredLocale,
        welcomeScreen = welcomeScreen,
        maxVideoChannelUsers = maxVideoChannelUsers,
        vanityUrlCode = vanityUrlCode,
        approximateMemberCount = 0,
        approximatePresenceCount = approximatePresenceCount,
        permissions = null,
        guildHashes = null,
        channelUpdates = null,
        threads = null,
        nsfw = nsfw,
        stageInstances = null,
        guildScheduledEvents = null,
        embeddedActivities = emptyList(),
        hubType = hubType,
    )

    private data class GuildCache(
        val guild: Guild,
        val channels: List<Channel>?,
        val roles: List<GuildRole>?,
    )

    private val GuildHashes?.isSupported: Boolean
        get() = this?.version == SUPPORTED_GUILD_HASH_VERSION

    private companion object {
        const val SUPPORTED_GUILD_HASH_VERSION = 1
    }
}
