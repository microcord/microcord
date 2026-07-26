package com.discord.utilities.permissions

import com.discord.api.channel.Channel
import com.discord.api.channel.getPermissionOverwriteForId
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isPrivateThread
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.channel.isThread
import com.discord.api.permission.Permission
import com.discord.api.permission.PermissionBit
import com.discord.api.permission.PermissionOverwrite
import com.discord.api.role.GuildRole
import com.discord.api.stageinstance.StageInstance
import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.models.member.GuildMember
import com.discord.utilities.guildmember.isLurker

/**
 * Which slow-mode cooldown a permission check is being made against.
 *
 * The reference passes `StoreSlowMode.Type`, a sealed class with `MessageSend` and
 * `ThreadCreate` objects. `StoreSlowMode` has not been reconstructed yet, so this enum
 * stands in for it; replace it with the store's own type when that store lands.
 */
enum class SlowModeType {
    MESSAGE_SEND,
    THREAD_CREATE,
}

/**
 * Permission arithmetic for guilds, channels, and threads.
 *
 * The computation is deliberately split three ways, matching the reference:
 *
 * * [computeNonThreadPermissions] resolves @everyone, then the member's roles, then the
 *   channel's overwrites (@everyone overwrite, then role overwrites, then the member
 *   overwrite). It knows nothing about threads, lurkers, or timeouts.
 * * [computeThreadPermissions] runs the above against the *parent* channel and then
 *   derives the thread-specific send/access rules.
 * * [computePermissions] picks between the two and then applies the lurker and
 *   communication-disabled allow-lists.
 *
 * Splitting them matters: stage speakers get their permissions from the stage instance
 * allow-list, and private-thread members get theirs from the parent channel plus their
 * membership, so a single flat "compute channel permissions" cannot express either.
 */
object PermissionUtils {

    /**
     * Whether the 2021-08 threads-permissions rework applies to [guildId].
     *
     * The reference reads `NewThreadsPermissionsFeatureFlag`, which ORs the
     * `2021-08_threads_permissions` guild experiment bucket with the
     * `NEW_THREAD_PERMISSIONS` guild feature. Neither `StoreExperiments` nor that flag
     * exists in this target yet, so this hook defaults to the legacy behaviour and is
     * settable by whoever reconstructs the flag.
     */
    @JvmStatic
    var newThreadsPermissionsEnabled: (guildId: Long) -> Boolean = { false }

    // region primitive checks

    /** Whether [computedPermissions] contains every bit of [permission]. */
    @JvmStatic
    fun can(permission: PermissionBit, computedPermissions: Long?): Boolean =
        ((computedPermissions ?: Permission.NONE) and permission) == permission

    /**
     * Whether the session is elevated enough to use [permission].
     *
     * Permissions outside [Permission.ELEVATED] never require elevation.
     */
    @JvmStatic
    fun isElevated(permission: PermissionBit, userMfaEnabled: Boolean, guildMfaLevel: Int): Boolean =
        if ((Permission.ELEVATED and permission) != permission) true
        else isElevated(userMfaEnabled, guildMfaLevel)

    /**
     * Whether the session satisfies the guild's MFA requirement.
     *
     * A guild with `mfa_level == 0` requires nothing; otherwise the acting user must
     * have MFA enabled on their account.
     */
    @JvmStatic
    fun isElevated(userMfaEnabled: Boolean, guildMfaLevel: Int): Boolean =
        guildMfaLevel == 0 || userMfaEnabled

    /** [can] and [isElevated] together. Use this for every destructive moderation action. */
    @JvmStatic
    fun canAndIsElevated(
        permission: PermissionBit,
        computedPermissions: Long?,
        userMfaEnabled: Boolean,
        guildMfaLevel: Int,
    ): Boolean = can(permission, computedPermissions) &&
        isElevated(permission, userMfaEnabled, guildMfaLevel)

    /**
     * Whether the guild's @everyone role can use [permission] in [channel], i.e. whether
     * the channel is "public".
     *
     * Threads inherit this from their parent, and private threads are never public.
     */
    @JvmStatic
    @JvmOverloads
    fun canEveryone(
        permission: PermissionBit,
        channel: Channel,
        parentChannel: Channel? = null,
        roles: Map<Long, GuildRole>,
    ): Boolean {
        if (channel.isThread()) {
            if (parentChannel == null || channel.isPrivateThread()) return false
            return canEveryone(permission, parentChannel, null, roles)
        }
        val everyoneRole = roles[channel.guildId]
        if (everyoneRole != null && !can(permission, everyoneRole.permissions)) return false
        return channel.permissionOverwrites?.none { it.denies(permission) } ?: true
    }

    /** Whether [role], after [roleOverwrite] is applied, can use [permission]. */
    fun canRole(
        permission: PermissionBit,
        role: GuildRole?,
        roleOverwrite: PermissionOverwrite?,
    ): Boolean {
        if (role == null) return false
        val deny = roleOverwrite?.deny ?: Permission.NONE
        val allow = roleOverwrite?.allow ?: Permission.NONE
        val allowed = (role.permissions and deny.inv()) or allow
        return (allowed and permission) == permission
    }

    /** [canRole] for the guild's @everyone role and its overwrite on [channel]. */
    fun canEveryoneRole(
        permission: PermissionBit,
        channel: Channel,
        roles: Map<Long, GuildRole>,
    ): Boolean {
        val everyoneRoleId = channel.guildId
        val everyoneRole = roles[everyoneRoleId]
        val overwrite = everyoneRole?.let { channel.getPermissionOverwriteForId(everyoneRoleId) }
        return canRole(permission, everyoneRole, overwrite)
    }

    /**
     * Whether any of the member-management affordances should be offered at all.
     *
     * Note that changing nicknames is deliberately *not* elevation gated.
     */
    @JvmStatic
    fun canManageGuildMembers(
        isOwner: Boolean,
        userMfaEnabled: Boolean,
        guildMfaLevel: Int,
        permissions: Long?,
    ): Boolean = isOwner ||
        canAndIsElevated(Permission.ADMINISTRATOR, permissions, userMfaEnabled, guildMfaLevel) ||
        canAndIsElevated(Permission.KICK_MEMBERS, permissions, userMfaEnabled, guildMfaLevel) ||
        canAndIsElevated(Permission.BAN_MEMBERS, permissions, userMfaEnabled, guildMfaLevel) ||
        canAndIsElevated(Permission.MANAGE_ROLES, permissions, userMfaEnabled, guildMfaLevel) ||
        can(Permission.MANAGE_NICKNAMES, permissions)

    // endregion

    // region access checks

    /** Whether the channel should appear in the channel list at all. */
    @JvmStatic
    fun hasAccess(channel: Channel, permissions: Map<Long, Long>): Boolean =
        hasAccess(channel, permissions[channel.id])

    /**
     * Whether the channel should appear in the channel list at all.
     *
     * Private channels are always visible. Voice and stage channels additionally require
     * [Permission.CONNECT], because a voice channel you cannot join is not shown.
     */
    fun hasAccess(channel: Channel, computedPermission: Long?): Boolean {
        if (channel.isPrivate()) return true
        val required = if (channel.isGuildVocalChannel()) {
            Permission.VIEW_CHANNEL or Permission.CONNECT
        } else {
            Permission.VIEW_CHANNEL
        }
        return can(required, computedPermission)
    }

    /** Whether the user can both see and post in the channel. */
    fun hasAccessWrite(channel: Channel, channelPermissions: Long?): Boolean =
        channel.isPrivate() ||
            can(Permission.VIEW_CHANNEL or Permission.SEND_MESSAGES, channelPermissions)

    /** Whether the user is exempt from the channel's slow mode for [type]. */
    fun hasBypassSlowmodePermissions(channelPermissions: Long?, type: SlowModeType): Boolean =
        when (type) {
            SlowModeType.MESSAGE_SEND ->
                can(Permission.MANAGE_CHANNELS, channelPermissions) ||
                    can(Permission.MANAGE_MESSAGES, channelPermissions)
            SlowModeType.THREAD_CREATE -> can(Permission.MANAGE_THREADS, channelPermissions)
        }

    // endregion

    // region computation

    /**
     * The effective permissions for [userId] in [channel].
     *
     * @param parentChannel required when [channel] is a thread; the thread itself carries
     *   no overwrites.
     * @param stageInstances used to widen a lurker's permissions in a public stage.
     * @param hasJoinedThread whether the user is a member of [channel] when it is a
     *   private thread.
     */
    @JvmStatic
    fun computePermissions(
        userId: Long,
        channel: Channel,
        parentChannel: Channel?,
        guildOwnerId: Long,
        member: GuildMember?,
        guildRoles: Map<Long, GuildRole>?,
        stageInstances: Map<Long, StageInstance>?,
        hasJoinedThread: Boolean,
    ): PermissionBit {
        val permissions = if (channel.isThread()) {
            if (parentChannel == null) {
                Permission.NONE
            } else {
                computeThreadPermissions(
                    userId, channel, parentChannel, guildOwnerId, member, guildRoles, hasJoinedThread,
                )
            }
        } else {
            computeNonThreadPermissions(
                userId,
                channel.guildId,
                guildOwnerId,
                member,
                guildRoles,
                channel.permissionOverwrites.orEmpty(),
            )
        }
        return when {
            member.isLurker() ->
                permissions and channel.computeLurkerPermissionsAllowList(stageInstances.orEmpty())
            member?.isCommunicationDisabled() == true ->
                permissions and Permission.AllowList.COMMUNICATIONS_DISABLED_DEFAULT
            else -> permissions
        }
    }

    /**
     * The effective permissions for [userId] ignoring threads, lurker state, and timeouts.
     *
     * Application order is @everyone role, member roles, @everyone overwrite, role
     * overwrites, member overwrite. Owners and administrators short-circuit to
     * [Permission.ALL].
     *
     * @param permissionOverwrites pass an empty collection to compute guild-level
     *   permissions rather than channel-level ones.
     */
    @JvmStatic
    fun computeNonThreadPermissions(
        userId: Long,
        guildId: Long,
        guildOwnerId: Long,
        member: GuildMember?,
        guildRoles: Map<Long, GuildRole>?,
        permissionOverwrites: Collection<PermissionOverwrite>?,
    ): PermissionBit {
        if (guildOwnerId == userId) return Permission.ALL
        val rolePermissions = applyRoles(member, guildRoles, applyEveryone(guildId, guildRoles))
        if (can(Permission.ADMINISTRATOR, rolePermissions)) return Permission.ALL
        if (permissionOverwrites.isNullOrEmpty()) return rolePermissions
        return applyRoleOverwrites(
            member,
            permissionOverwrites,
            applyEveryoneOverwrites(guildId, permissionOverwrites, rolePermissions),
            userId,
        )
    }

    /**
     * The effective permissions for [userId] inside [thread].
     *
     * Threads have no overwrites of their own, so the parent channel's permissions are
     * computed first and then adjusted:
     *
     * * With the new-threads-permissions flag on, posting is governed purely by
     *   [Permission.SEND_MESSAGES_IN_THREADS], and a private thread the user has not
     *   joined is invisible unless they can manage threads.
     * * With the flag off, the *create* permission for the thread's visibility grants
     *   posting, and an unjoined private thread still requires thread management.
     */
    @JvmStatic
    fun computeThreadPermissions(
        userId: Long,
        thread: Channel,
        parentChannel: Channel,
        guildOwnerId: Long,
        member: GuildMember?,
        guildRoles: Map<Long, GuildRole>?,
        hasJoined: Boolean,
    ): PermissionBit {
        var permissions = computeNonThreadPermissions(
            userId,
            parentChannel.guildId,
            guildOwnerId,
            member,
            guildRoles,
            parentChannel.permissionOverwrites.orEmpty(),
        )
        if (newThreadsPermissionsEnabled(thread.guildId)) {
            if (thread.isPrivateThread() && !hasJoined &&
                !can(Permission.MANAGE_THREADS, permissions)
            ) {
                return Permission.NONE
            }
            return if (can(Permission.SEND_MESSAGES_IN_THREADS, permissions)) {
                permissions or Permission.SEND_MESSAGES
            } else {
                permissions and Permission.SEND_MESSAGES.inv()
            }
        }
        if (thread.isPrivateThread()) {
            if (can(Permission.CREATE_PRIVATE_THREADS, permissions)) {
                permissions = permissions or Permission.SEND_MESSAGES
            }
            if (!hasJoined && !can(Permission.MANAGE_THREADS, permissions)) return Permission.NONE
        } else if (can(Permission.CREATE_PUBLIC_THREADS, permissions)) {
            permissions = permissions or Permission.SEND_MESSAGES
        }
        return permissions
    }

    /**
     * Only the bits [userId] gains or loses from [permissionOverwrites], with no role
     * permissions mixed in. Used by the channel-permission editor.
     */
    fun computeChannelOverwrite(
        userId: Long,
        guildId: Long,
        member: GuildMember?,
        permissionOverwrites: Collection<PermissionOverwrite>?,
    ): PermissionBit {
        if (permissionOverwrites.isNullOrEmpty()) return Permission.NONE
        return applyRoleOverwrites(
            member,
            permissionOverwrites,
            applyEveryoneOverwrites(guildId, permissionOverwrites, Permission.NONE),
            userId,
        )
    }

    /**
     * Guild-level permissions for [userId].
     *
     * Kept for the stores written before the three-way split; it is exactly
     * [computeNonThreadPermissions] with no channel overwrites.
     */
    @JvmStatic
    @Deprecated(
        "Use computeNonThreadPermissions with an empty overwrite collection",
        ReplaceWith("PermissionUtils.computeNonThreadPermissions(userId, guildId, ownerId, member, roles, emptyList())"),
    )
    fun computeGuildPermissions(
        userId: Long,
        guildId: Long,
        ownerId: Long,
        member: GuildMember?,
        roles: Map<Long, GuildRole>?,
    ): PermissionBit =
        computeNonThreadPermissions(userId, guildId, ownerId, member, roles, emptyList())

    /**
     * Channel-level permissions for [userId].
     *
     * Kept for the stores written before the three-way split. It cannot express stage
     * instances or private-thread membership, so new call sites must use
     * [computePermissions] instead.
     */
    @JvmStatic
    @Deprecated(
        "Use computePermissions, which also takes stage instances and thread membership",
        ReplaceWith(
            "PermissionUtils.computePermissions(userId, channel, parentChannel, ownerId, member, roles, null, channel.member != null)",
        ),
    )
    @JvmOverloads
    fun computeChannelPermissions(
        userId: Long,
        guildId: Long,
        ownerId: Long,
        member: GuildMember?,
        roles: Map<Long, GuildRole>?,
        channel: Channel,
        parentChannel: Channel? = null,
    ): PermissionBit {
        if (channel.isPrivate()) return Permission.ALL
        return computePermissions(
            userId = userId,
            channel = channel,
            parentChannel = parentChannel,
            guildOwnerId = ownerId,
            member = member,
            guildRoles = roles,
            stageInstances = null,
            hasJoinedThread = channel.member != null,
        )
    }

    // endregion

    // region computation steps

    /**
     * The @everyone role's permissions.
     *
     * When the role is missing the guild is only partially hydrated, so fall back to
     * [Permission.DEFAULT] rather than [Permission.NONE] — returning NONE here would
     * hide every channel until the roles arrive.
     */
    private fun applyEveryone(guildId: Long, guildRoles: Map<Long, GuildRole>?): PermissionBit =
        guildRoles?.get(guildId)?.permissions ?: Permission.DEFAULT

    /** Unions in every role the member holds. Role permissions are additive. */
    private fun applyRoles(
        member: GuildMember?,
        guildRoles: Map<Long, GuildRole>?,
        permissions: PermissionBit,
    ): PermissionBit {
        var result = permissions
        member?.getRoles()?.forEach { roleId ->
            guildRoles?.get(roleId)?.let { result = result or it.permissions }
        }
        return result
    }

    /** Applies the overwrite targeting the @everyone role, deny before allow. */
    private fun applyEveryoneOverwrites(
        guildId: Long,
        overwrites: Collection<PermissionOverwrite>,
        permissions: PermissionBit,
    ): PermissionBit {
        val overwrite = overwrites.firstOrNull { it.id == guildId } ?: return permissions
        return overwrite.applyTo(permissions)
    }

    /**
     * Applies the member's role overwrites and then their personal overwrite.
     *
     * All role denies are unioned and applied before all role allows, so any role that
     * allows a permission wins over any role that denies it. The member overwrite is
     * applied last and beats everything.
     */
    private fun applyRoleOverwrites(
        member: GuildMember?,
        overwrites: Collection<PermissionOverwrite>,
        permissions: PermissionBit,
        userId: Long,
    ): PermissionBit {
        var allow = Permission.NONE
        var deny = Permission.NONE
        member?.getRoles()?.forEach { roleId ->
            overwrites.firstOrNull { it.id == roleId }?.let {
                allow = allow or it.allow
                deny = deny or it.deny
            }
        }
        val afterRoles = (permissions and deny.inv()) or allow
        val memberOverwrite = overwrites.firstOrNull { it.id == userId } ?: return afterRoles
        return memberOverwrite.applyTo(afterRoles)
    }

    /**
     * The permissions a member who has not completed membership screening keeps.
     *
     * Lurkers may speak in a public stage channel so they can be invited on stage.
     */
    private fun Channel.computeLurkerPermissionsAllowList(
        stageInstances: Map<Long, StageInstance>,
    ): PermissionBit {
        if (isStageVoiceChannel() &&
            stageInstances[id]?.privacyLevel == StageInstancePrivacyLevel.PUBLIC
        ) {
            return Permission.AllowList.LURKER_STAGE_CHANNEL
        }
        return Permission.AllowList.LURKER_DEFAULT
    }

    private fun PermissionOverwrite.applyTo(permissions: PermissionBit): PermissionBit =
        (permissions and deny.inv()) or allow

    // endregion
}

/**
 * Whether this overwrite denies any bit of [permission].
 *
 * The reference keeps this in `com.discord.utilities.PermissionOverwriteUtils` together
 * with `allows`, `grantsAccessTo`, and `deniesAccessTo`; that file has not been
 * reconstructed yet, so the one predicate [PermissionUtils.canEveryone] needs lives here.
 */
internal fun PermissionOverwrite.denies(permission: PermissionBit): Boolean =
    (permission and deny) != 0L
