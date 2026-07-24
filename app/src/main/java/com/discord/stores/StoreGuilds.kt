package com.discord.stores

import android.content.Context
import com.discord.api.guild.Guild as ApiGuild
import com.discord.api.guild.GuildVerificationLevel
import com.discord.api.guildmember.GuildMember as ApiGuildMember
import com.discord.api.role.GuildRole
import com.discord.models.domain.ModelPayload
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import rx.Observable

class StoreGuilds(
    private val userStore: StoreUser,
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
    private val guildsCache: Persister<Map<Long, Guild>> = Persister(GUILDS_CACHE_KEY, emptyMap()),
    private val guildRolesCache: Persister<Map<Long, Map<Long, GuildRole>>> =
        Persister(GUILD_ROLES_CACHE_KEY, emptyMap()),
    private val guildsJoinedAtCache: Persister<Map<Long, Long>> =
        Persister(GUILD_JOINED_AT_CACHE_KEY, emptyMap()),
) : StoreV2() {
    private val guilds = HashMap<Long, Guild>()
    private val guildMembers = HashMap<Long, MutableMap<Long, ApiGuildMember>>()
    private val guildMembersComputed = HashMap<Long, MutableMap<Long, GuildMember>>()
    private val guildRoles = HashMap<Long, MutableMap<Long, GuildRole>>()
    private val guildsUnavailable = HashSet<Long>()
    private val guildsJoinedAt = HashMap<Long, Long>()

    private var guildsSnapshot: Map<Long, Guild> = emptyMap()
    private var guildMembersComputedSnapshot: Map<Long, Map<Long, GuildMember>> = emptyMap()
    private var guildRolesSnapshot: Map<Long, Map<Long, GuildRole>> = emptyMap()
    private var guildsUnavailableSnapshot: Set<Long> = emptySet()
    private var guildsJoinedAtSnapshot: Map<Long, Long> = emptyMap()

    override fun init(context: Context) = restore()

    internal fun restore() {
        guilds.putAll(guildsCache.get())
        guildRolesCache.get().forEach { (guildId, roles) -> guildRoles[guildId] = roles.toMutableMap() }
        guildsJoinedAt.putAll(guildsJoinedAtCache.get())
        markChanged(GuildsUpdate, ComputedMembersUpdate)
    }

    override fun snapshotData() {
        if (GuildsUpdate in updateSources) {
            guildsSnapshot = guilds.toMap()
            guildRolesSnapshot = guildRoles.mapValues { it.value.toMap() }
            guildsUnavailableSnapshot = guildsUnavailable.toSet()
            guildsJoinedAtSnapshot = guildsJoinedAt.toMap()
            guildsCache.set(guildsSnapshot)
            guildRolesCache.set(guildRolesSnapshot)
            guildsJoinedAtCache.set(guildsJoinedAtSnapshot)
        }
        if (ComputedMembersUpdate in updateSources) {
            guildMembersComputedSnapshot = guildMembersComputed.mapValues { it.value.toMap() }
        }
    }

    fun reset() {
        guilds.clear()
        guildMembers.clear()
        guildMembersComputed.clear()
        guildRoles.clear()
        guildsUnavailable.clear()
        guildsJoinedAt.clear()
        guildsCache.clear()
        guildRolesCache.clear()
        guildsJoinedAtCache.clear()
        markChanged(GuildsUpdate, ComputedMembersUpdate)
    }

    fun handleConnectionOpen(payload: ModelPayload) {
        guilds.clear()
        guildMembers.clear()
        guildMembersComputed.clear()
        guildRoles.clear()
        guildsUnavailable.clear()
        payload.guilds.orEmpty().forEach(::handleGuildAdd)
        markChanged(GuildsUpdate, ComputedMembersUpdate)
    }

    fun handleGuildAdd(guild: ApiGuild) {
        if (guild.unavailable) {
            if (guildsUnavailable.add(guild.id)) markChanged(GuildsUpdate)
            return
        }
        if (guildsUnavailable.remove(guild.id)) markChanged(GuildsUpdate)
        putRoles(guild.id, guild.roles.orEmpty())
        val merged = (guilds[guild.id] ?: Guild()).merge(guild)
        if (guilds.put(guild.id, merged) != merged) markChanged(GuildsUpdate)
        guild.members.orEmpty().forEach { handleGuildMember(it, guild.id) }
        updateJoinedAt(guild.id)
    }

    fun handleGuildRemove(guild: ApiGuild) {
        if (guild.unavailable) {
            if (guildsUnavailable.add(guild.id)) markChanged(GuildsUpdate)
            return
        }
        guildsUnavailable.remove(guild.id)
        val changed = guilds.remove(guild.id) != null || guildRoles.remove(guild.id) != null ||
            guildsJoinedAt.remove(guild.id) != null
        val membersChanged = guildMembers.remove(guild.id) != null || guildMembersComputed.remove(guild.id) != null
        if (changed) markChanged(GuildsUpdate)
        if (membersChanged) markChanged(ComputedMembersUpdate)
    }

    fun handleGuildMember(member: ApiGuildMember, guildId: Long = member.guildId, preserveProfile: Boolean = false) {
        val members = guildMembers.getOrPut(guildId) { HashMap() }
        val old = members[member.user.id]
        val stored = if (preserveProfile && old != null) {
            member.copy(avatar = old.avatar, bio = old.bio, banner = old.banner)
        } else member
        members[member.user.id] = stored
        val computed = GuildMember.from(stored, guildId, guildRoles[guildId])
        if (guildMembersComputed.getOrPut(guildId) { HashMap() }.put(member.user.id, computed) != computed) {
            markChanged(ComputedMembersUpdate)
        }
        if (member.user.id == userStore.getMeInternal().id) updateJoinedAt(guildId)
    }

    fun handleGuildMemberAdd(member: ApiGuildMember) = handleGuildMember(member)

    fun handleGuildMemberRemove(guildId: Long, userId: Long) {
        guildMembers[guildId]?.remove(userId)
        if (guildMembersComputed[guildId]?.remove(userId) != null) markChanged(ComputedMembersUpdate)
    }

    fun handleGuildMembersChunk(guildId: Long, members: Collection<ApiGuildMember>) =
        members.forEach { handleGuildMember(it, guildId) }

    fun handleThreadMembersUpdate(guildId: Long, members: Collection<ApiGuildMember>) =
        handleGuildMembersChunk(guildId, members)

    fun handleThreadMemberListUpdate(guildId: Long, members: Collection<ApiGuildMember>) =
        handleGuildMembersChunk(guildId, members)

    fun handleGuildScheduledEventUsersFetch(guildId: Long, members: Collection<ApiGuildMember>) =
        handleGuildMembersChunk(guildId, members)

    fun handleGuildRoleCreateOrUpdate(guildId: Long, role: GuildRole) {
        putRoles(guildId, listOf(role))
        recomputeMembers(guildId)
    }

    fun handleGuildRoleRemove(guildId: Long, roleId: Long) {
        if (guildRoles[guildId]?.remove(roleId) != null) markChanged(GuildsUpdate)
        recomputeMembers(guildId)
    }

    fun handleGuildMemberCommunicationEnabled(guildId: Long, userId: Long) {
        val member = guildMembers[guildId]?.get(userId) ?: return
        if (member.communicationDisabledUntil == null) return
        handleGuildMember(member.copy(communicationDisabledUntil = null), guildId)
    }

    fun getGuild(guildId: Long) = guildsSnapshot[guildId]
    fun getGuilds(): Map<Long, Guild> = guildsSnapshot
    fun getMembers(): Map<Long, Map<Long, GuildMember>> = guildMembersComputedSnapshot
    fun getMember(guildId: Long, userId: Long) = guildMembersComputedSnapshot[guildId]?.get(userId)
    fun getRoles(): Map<Long, Map<Long, GuildRole>> = guildRolesSnapshot
    fun getGuildsJoinedAt(): Map<Long, Long> = guildsJoinedAtSnapshot
    fun getUnavailableGuilds(): Set<Long> = guildsUnavailableSnapshot

    internal fun getGuildsInternal(): Map<Long, Guild> = guilds
    internal fun getGuildMembersComputedInternal(): Map<Long, Map<Long, GuildMember>> = guildMembersComputed
    internal fun getGuildRolesInternal(): Map<Long, Map<Long, GuildRole>> = guildRoles
    internal fun getGuildsJoinedAtInternal(): Map<Long, Long> = guildsJoinedAt
    internal fun getUnavailableGuildsInternal(): Set<Long> = guildsUnavailable

    fun observeGuilds(): Observable<Map<Long, Guild>> = observe(GuildsUpdate) { guildsSnapshot }
    fun observeGuild(guildId: Long): Observable<Guild?> = observeGuilds().map { it[guildId] }.distinctUntilChanged()
    fun observeGuildIds(): Observable<Set<Long>> = observeGuilds().map { it.keys }.distinctUntilChanged()
    fun observeComputed(): Observable<Map<Long, Map<Long, GuildMember>>> =
        observe(ComputedMembersUpdate) { guildMembersComputedSnapshot }
    fun observeComputed(guildId: Long): Observable<Map<Long, GuildMember>> =
        observeComputed().map { it[guildId].orEmpty() }.distinctUntilChanged()
    fun observeComputed(guildId: Long, userIds: Collection<Long>): Observable<Map<Long, GuildMember>> =
        observeComputed(guildId).map { members -> members.filterKeys(userIds.toSet()::contains) }.distinctUntilChanged()
    fun observeComputedMember(guildId: Long, userId: Long): Observable<GuildMember?> =
        observeComputed(guildId).map { it[userId] }.distinctUntilChanged()
    fun observeGuildMember(guildId: Long, userId: Long) = observeComputedMember(guildId, userId)
    fun observeGuildMembers(guildId: Long) = observeComputed(guildId)
    fun observeRoles(): Observable<Map<Long, Map<Long, GuildRole>>> = observe(GuildsUpdate) { guildRolesSnapshot }
    fun observeRoles(guildId: Long): Observable<Map<Long, GuildRole>> =
        observeRoles().map { it[guildId].orEmpty() }.distinctUntilChanged()
    fun observeRoles(guildId: Long, roleIds: Collection<Long>): Observable<Map<Long, GuildRole>> =
        observeRoles(guildId).map { roles -> roles.filterKeys(roleIds.toSet()::contains) }.distinctUntilChanged()
    fun observeSortedRoles(guildId: Long): Observable<List<GuildRole>> =
        observeRoles(guildId).map { it.values.sortedWith(compareByDescending<GuildRole> { role -> role.position }.thenByDescending { it.id }) }
            .distinctUntilChanged()
    fun observeJoinedAt(): Observable<Map<Long, Long>> = observe(GuildsUpdate) { guildsJoinedAtSnapshot }.distinctUntilChanged()
    fun observeJoinedAt(guildId: Long): Observable<Long?> = observeJoinedAt().map { it[guildId] }.distinctUntilChanged()
    fun observeUnavailableGuilds(): Observable<Set<Long>> = observe(GuildsUpdate) { guildsUnavailableSnapshot }
    fun observeVerificationLevel(guildId: Long): Observable<GuildVerificationLevel?> =
        observeGuild(guildId).map { it?.verificationLevel }.distinctUntilChanged()
    fun observeCommunicationDisabledGuildMembers(): Observable<List<GuildMember>> = observeComputed().map { byGuild ->
        byGuild.filterKeys(guildsSnapshot.keys::contains).values.flatMap { it.values }.filter { it.isCommunicationDisabled() }
    }.distinctUntilChanged()

    private fun putRoles(guildId: Long, roles: Collection<GuildRole>) {
        val stored = guildRoles.getOrPut(guildId) { HashMap() }
        var changed = false
        roles.forEach { role -> if (stored.put(role.id, role) != role) changed = true }
        if (changed) markChanged(GuildsUpdate)
    }

    private fun recomputeMembers(guildId: Long) {
        guildMembers[guildId].orEmpty().values.forEach { handleGuildMember(it, guildId) }
        updateJoinedAt(guildId)
    }

    private fun updateJoinedAt(guildId: Long) {
        val joinedAt = guildMembers[guildId]?.get(userStore.getMeInternal().id)?.joinedAt?.dateTimeMillis ?: return
        if (guildsJoinedAt.put(guildId, joinedAt) != joinedAt) markChanged(GuildsUpdate)
    }

    private fun <T> observe(source: ObservationDeck.UpdateSource, generator: () -> T): Observable<T> =
        observationDeck.connectRx(source, generator = generator)

    private fun Guild.merge(api: ApiGuild) = Guild(
        roles = emptyList(),
        emojis = api.emojis.orEmpty(),
        stickers = api.stickers.orEmpty(),
        name = api.name.ifEmpty { name },
        description = api.description ?: description,
        defaultMessageNotifications = api.defaultMessageNotifications ?: defaultMessageNotifications,
        id = api.id.takeUnless { it == 0L } ?: id,
        region = api.region ?: region,
        ownerId = api.ownerId.takeUnless { it == 0L } ?: ownerId,
        icon = api.icon ?: icon,
        verificationLevel = api.verificationLevel ?: verificationLevel,
        explicitContentFilter = api.explicitContentFilter ?: explicitContentFilter,
        unavailable = api.unavailable,
        mfaLevel = api.mfaLevel,
        afkTimeout = api.afkTimeout.takeUnless { it == 0 } ?: afkTimeout,
        afkChannelId = api.afkChannelId,
        systemChannelId = api.systemChannelId,
        features = api.features.toSet(),
        memberCount = api.memberCount,
        banner = api.banner,
        splash = api.splash ?: splash,
        premiumTier = api.premiumTier,
        premiumSubscriptionCount = api.premiumSubscriptionCount,
        systemChannelFlags = api.systemChannelFlags,
        joinedAt = api.joinedAt ?: joinedAt,
        rulesChannelId = api.rulesChannelId ?: rulesChannelId,
        publicUpdatesChannelId = api.publicUpdatesChannelId ?: publicUpdatesChannelId,
        preferredLocale = api.preferredLocale ?: preferredLocale,
        welcomeScreen = api.welcomeScreen ?: welcomeScreen,
        maxVideoChannelUsers = api.maxVideoChannelUsers ?: maxVideoChannelUsers,
        vanityUrlCode = api.vanityUrlCode ?: vanityUrlCode,
        approximatePresenceCount = api.approximatePresenceCount.takeUnless { it == 0 } ?: approximatePresenceCount,
        nsfw = api.nsfw,
        hubType = api.hubType,
    )

    companion object {
        const val GUILDS_CACHE_KEY = "STORE_GUILDS_V34"
        const val GUILD_ROLES_CACHE_KEY = "STORE_GUILD_ROLES_V7"
        const val GUILD_JOINED_AT_CACHE_KEY = "STORE_GUILD_JOINED_AT_V6"
        val GuildsUpdate = object : ObservationDeck.UpdateSource {}
        val ComputedMembersUpdate = object : ObservationDeck.UpdateSource {}
    }
}
