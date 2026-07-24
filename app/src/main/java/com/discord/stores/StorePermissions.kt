package com.discord.stores

import android.content.Context
import com.discord.api.channel.Channel
import com.discord.api.guild.Guild as ApiGuild
import com.discord.api.guildmember.GuildMember as ApiGuildMember
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.persister.Persister
import rx.Observable

class StorePermissions(
    private val storeUser: StoreUser,
    private val storeChannels: StoreChannels,
    private val storeGuilds: StoreGuilds,
    private val observationDeck: ObservationDeck,
    private val permissionsForGuildsCache: Persister<Map<Long, Long>> =
        Persister(STORE_GUILD_PERMISSIONS_V5, emptyMap()),
    private val permissionsForChannelsCache: Persister<Map<Long, Map<Long, Long>>> =
        Persister(STORE_CHANNEL_PERMISSIONS_BY_GUILD_V5, emptyMap()),
) : StoreV2() {
    private val permissionsForGuilds = HashMap<Long, Long>()
    private val permissionsForChannels = HashMap<Long, Map<Long, Long>>()
    private var permissionsForGuildsSnapshot: Map<Long, Long> = emptyMap()
    private var permissionsForChannelsSnapshot: Map<Long, Map<Long, Long>> = emptyMap()
    private var permissionsForChannelsFlattenedSnapshot: Map<Long, Long> = emptyMap()

    override fun init(context: Context) = init()

    fun init() {
        permissionsForGuilds.putAll(permissionsForGuildsCache.get())
        permissionsForChannels.putAll(permissionsForChannelsCache.get())
        markChanged()
    }

    fun reset() {
        permissionsForGuilds.clear()
        permissionsForChannels.clear()
        permissionsForGuildsCache.clear()
        permissionsForChannelsCache.clear()
        markChanged()
    }

    fun getGuildPermissions(): Map<Long, Long> = permissionsForGuildsSnapshot
    fun getPermissionsByChannel(): Map<Long, Long> = permissionsForChannelsFlattenedSnapshot
    internal fun getPermissionsByChannelInternal(guildId: Long): Map<Long, Long> =
        permissionsForChannels[guildId].orEmpty()
    internal fun getPermissionsByChannelInternal(): Map<Long, Long> =
        permissionsForChannels.values.flatMap { it.entries }.associate { it.toPair() }

    fun observeAllPermissions(): Observable<Map<Long, Map<Long, Long>>> =
        observationDeck.connectRx(this, generator = { permissionsForChannelsSnapshot })
    fun observeChannelPermissionsForGuild(guildId: Long): Observable<Map<Long, Long>> =
        observeAllPermissions().map { it[guildId].orEmpty() }.distinctUntilChanged()
    fun observePermissionsForAllChannels(): Observable<Map<Long, Long>> =
        observationDeck.connectRx(this, generator = { permissionsForChannelsFlattenedSnapshot }).distinctUntilChanged()
    fun observePermissionsForChannel(channelId: Long): Observable<Long?> =
        observePermissionsForAllChannels().map { it[channelId] }.distinctUntilChanged()
    fun observePermissionsForGuild(guildId: Long): Observable<Long?> =
        observationDeck.connectRx(this, generator = { permissionsForGuildsSnapshot[guildId] }).distinctUntilChanged()

    fun recomputeAllPermissions() {
        permissionsForGuilds.clear()
        permissionsForChannels.clear()
        storeGuilds.getGuildsInternal().keys.forEach(::updateGuildPermissions)
        markChanged()
    }

    fun handleConnectionOpen() = recomputeAllPermissions()
    fun handleConnectionOpen(@Suppress("UNUSED_PARAMETER") payload: com.discord.models.domain.ModelPayload) =
        recomputeAllPermissions()
    fun handleGuildAdd(guild: ApiGuild) = updateGuildPermissions(guild.id)
    fun handleGuildRemove(guild: ApiGuild) {
        permissionsForGuilds.remove(guild.id)
        permissionsForChannels.remove(guild.id)
        markChanged()
    }
    fun handleGuildMemberAdd(member: ApiGuildMember) {
        if (member.user.id == storeUser.getMeInternal().id) updateGuildPermissions(member.guildId)
    }
    fun handleGuildRolesChanged(guildId: Long) = updateGuildPermissions(guildId)
    fun handleChannelOrThreadCreateOrUpdate(channel: Channel) = updateGuildPermissions(channel.guildId)
    fun handleChannelOrThreadDelete(channel: Channel) = updateGuildPermissions(channel.guildId)
    fun handleChannelCreateOrUpdate(channel: Channel) = handleChannelOrThreadCreateOrUpdate(channel)
    fun handleChannelDelete(channel: Channel) = handleChannelOrThreadDelete(channel)

    override fun snapshotData() {
        permissionsForGuildsSnapshot = permissionsForGuilds.toMap()
        permissionsForChannelsSnapshot = permissionsForChannels.mapValues { it.value.toMap() }
        permissionsForChannelsFlattenedSnapshot = permissionsForChannelsSnapshot.values
            .flatMap(Map<Long, Long>::entries).associate { it.toPair() }
        permissionsForGuildsCache.set(permissionsForGuildsSnapshot, persist = true)
        permissionsForChannelsCache.set(permissionsForChannelsSnapshot, persist = true)
    }

    private fun updateGuildPermissions(guildId: Long) {
        val guild = storeGuilds.getGuildsInternal()[guildId] ?: return
        val userId = storeUser.getMeInternal().id
        if (userId == 0L) return
        val member = storeGuilds.getGuildMembersComputedInternal()[guildId]?.get(userId)
        val roles = storeGuilds.getGuildRolesInternal()[guildId]
        permissionsForGuilds[guildId] = PermissionUtils.computeGuildPermissions(
            userId, guildId, guild.ownerId, member, roles,
        )
        val channels = storeChannels.getChannelsByGuildInternal()[guildId].orEmpty().values +
            storeChannels.getThreadsForGuildInternal(guildId)
        permissionsForChannels[guildId] = channels.associate { channel ->
            val parent = channel.parentId.takeIf { it != 0L }?.let {
                storeChannels.getGuildChannelInternal(guildId, it)
            }
            channel.id to PermissionUtils.computeChannelPermissions(
                userId, guildId, guild.ownerId, member, roles, channel, parent,
            )
        }
        markChanged()
    }

    companion object {
        const val STORE_GUILD_PERMISSIONS_V5 = "STORE_GUILD_PERMISSIONS_V5"
        const val STORE_CHANNEL_PERMISSIONS_BY_GUILD_V5 = "STORE_CHANNEL_PERMISSIONS_BY_GUILD_V5"
    }
}
