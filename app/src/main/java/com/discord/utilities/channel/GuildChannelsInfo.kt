package com.discord.utilities.channel

import com.discord.api.channel.Channel
import com.discord.api.channel.getSortByNameAndType
import com.discord.api.channel.isCategory
import com.discord.api.guild.GuildFeature
import com.discord.api.permission.Permission
import com.discord.api.role.GuildRole
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.guild.Guild
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.ManageGuildContext
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.leadingEdgeThrottle
import java.util.SortedMap
import java.util.TreeMap
import java.util.TreeSet
import java.util.concurrent.TimeUnit
import rx.Observable

/**
 * Everything the channel list needs about a guild that is *not* the channels themselves.
 *
 * Assembled once per guild from eight stores and then reused for every row, so the list does not
 * re-derive permissions and notification settings per channel.
 *
 * Reference: `com.discord.utilities.channel.GuildChannelsInfo`.
 */
data class GuildChannelsInfo(
    val guild: Guild?,
    val everyoneRole: GuildRole?,
    val notificationSettings: ModelNotificationSettings,
    val hideMutedChannels: Boolean,
    /** Computed permissions per channel id. */
    val channelPermissions: Map<Long, Long>,
    /** Whether the invite affordance should be offered — either the permission or a vanity URL. */
    val ableToInstantInvite: Boolean,
    val unelevated: Boolean,
    val isVerifiedServer: Boolean,
    val manageGuildContext: ManageGuildContext,
    val canChangeNickname: Boolean,
    val guildRoles: Map<Long, GuildRole>?,
) {

    /**
     * Group [guildChannels] by category and sort both levels.
     *
     * The key of a category is its own id; every other channel is keyed by its `parentId`, so
     * uncategorised channels collect under `0`. Categories are therefore members of their own
     * group, which is what [getSortedVisibleChannels] relies on.
     *
     * Both the group order and the within-group order use `Channel.getSortByNameAndType()`, so a
     * category sorts by the same rule as the channels under it.
     */
    fun getSortedCategories(guildChannels: Map<Long, Channel?>): SortedMap<Long, MutableCollection<Channel>> {
        val comparator = Channel.getSortByNameAndType()
        val grouped = TreeMap<Long, MutableCollection<Channel>>(
            Comparator { first, second ->
                comparator.compare(guildChannels[first], guildChannels[second])
            },
        )
        guildChannels.values.filterNotNull().forEach { channel ->
            val categoryId = if (channel.isCategory()) channel.id else channel.parentId
            grouped.getOrPut(categoryId) { TreeSet(comparator) }.add(channel)
        }
        return grouped
    }

    /**
     * [getSortedCategories], flattened to the channels the viewer may actually see.
     *
     * A group that ends up with exactly one accessible channel is dropped: since a category is a
     * member of its own group, one survivor means the category header with nothing under it.
     * (The same rule also drops a lone uncategorised channel — that is the reference's behaviour
     * and it is reproduced rather than corrected.)
     */
    fun getSortedVisibleChannels(guildChannels: Map<Long, Channel>): List<Channel> =
        getSortedCategories(guildChannels).values.flatMap { group ->
            val accessible = group.filter {
                PermissionUtils.hasAccess(it, channelPermissions[it.id])
            }
            if (accessible.size == 1) emptyList() else accessible
        }

    companion object {
        private const val THROTTLE_MS = 500L

        /**
         * Observe the guild-level state behind the channel list.
         *
         * Throttled on the leading edge because the eight sources change together during
         * hydration — without it the list would rebuild once per store emission.
         *
         * **Deviation:** the reference lifts a custom `ObservableWithLeadingEdgeThrottle` operator
         * that throttles *inside* the combine. This throttles the combined stream instead, which
         * differs only in that a burst is collapsed after combining rather than before.
         */
        @JvmStatic
        fun get(guildId: Long): Observable<GuildChannelsInfo> = Observable.combineLatest(
            StoreStream.getUsers().observeMe(true),
            StoreStream.getGuilds().observeGuild(guildId),
            StoreStream.getUserGuildSettings().observeGuildSettings(guildId),
            StoreStream.getUserGuildSettings().observeHideMutedChannels(guildId),
            StoreStream.getGuilds().observeRoles(guildId),
            StoreStream.getPermissions().observePermissionsForGuild(guildId),
            StoreStream.getPermissions().observeChannelPermissionsForGuild(guildId),
            StoreStream.getChannels().observeChannelCategories(guildId),
        ) { me, guild, guildSettings, hideMuted, roles, guildPermissions, channelPermissions, categories ->
            val mfaEnabled = me?.mfaEnabled == true
            val mfaLevel = guild?.mfaLevel ?: 0

            // `isElevated` for a non-elevated permission is trivially true, so this is really
            // "the session satisfies the guild's MFA requirement".
            val elevated = PermissionUtils.isElevated(Permission.MANAGE_CHANNELS, mfaEnabled, mfaLevel)
            val canManageChannelsElevated =
                elevated && PermissionUtils.can(Permission.MANAGE_CHANNELS, guildPermissions)

            // Reference bug reproduced verbatim: `canManageChannelsElevated` already implies
            // `elevated`, so this conjunction can never be true and `unelevated` is always false.
            // Left as-is for parity; no consumer branches on it today.
            val unelevated = !elevated && canManageChannelsElevated

            val ableToInstantInvite =
                PermissionUtils.can(Permission.CREATE_INSTANT_INVITE, guildPermissions) ||
                    guild?.vanityUrlCode != null

            val isOwner = guild != null && me != null && me.id == guild.ownerId

            GuildChannelsInfo(
                guild = guild,
                // The @everyone role always carries the guild's own id.
                everyoneRole = guild?.let { roles[it.id] },
                notificationSettings = guildSettings,
                hideMutedChannels = hideMuted,
                channelPermissions = channelPermissions,
                ableToInstantInvite = ableToInstantInvite,
                unelevated = unelevated,
                isVerifiedServer = guild?.features?.contains(GuildFeature.VERIFIED) == true,
                manageGuildContext = ManageGuildContext.from(
                    categories = categories,
                    isOwner = isOwner,
                    guildPermissions = guildPermissions,
                    channelPermissions = channelPermissions,
                    guildMfaLevel = mfaLevel,
                    isMeMfaEnabled = mfaEnabled,
                ),
                canChangeNickname = PermissionUtils.can(Permission.CHANGE_NICKNAME, guildPermissions),
                guildRoles = roles,
            )
        }
            .leadingEdgeThrottle(THROTTLE_MS, TimeUnit.MILLISECONDS)
            .distinctUntilChanged()
    }
}
