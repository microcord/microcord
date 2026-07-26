package com.discord.utilities.permissions

import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.api.role.GuildRole
import com.discord.models.guild.Guild
import com.discord.models.message.Message
import com.discord.models.user.MeUser
import com.discord.models.user.User
import com.discord.utilities.guilds.RoleUtils

/**
 * Message types the user is allowed to delete. Everything else — join notices, boost
 * notices, and so on — is permanent even for moderators.
 */
val DELETEABLE_MESSAGE_TYPES: Set<Int> =
    setOf(0, 6, 7, 8, 9, 10, 11, 12, 18, 19, 20, 22, 23, 25)

/** Whether this message may be deleted at all, ignoring who is asking. */
fun Message.isDeleteable(): Boolean =
    type?.let { it in DELETEABLE_MESSAGE_TYPES } == true || isLocal()

/**
 * Which server-management affordances to offer for a guild.
 *
 * Built once per guild rather than checked ad hoc at each menu item, so the overflow
 * menu and the settings screen can never disagree about what the user may do.
 */
data class ManageGuildContext(
    val canManageServer: Boolean,
    val canManageChannels: Boolean,
    val canManageRoles: Boolean,
    val canManageBans: Boolean,
    val canManageNicknames: Boolean,
    val canManageEmojisAndStickers: Boolean,
    val canViewAuditLogs: Boolean,
    val canManageEvents: Boolean,
    val isOwnerWithRequiredMFALevel: Boolean,
) {
    /**
     * Whether the server-settings entry point should be shown at all.
     *
     * Nicknames and events deliberately do not open server settings on their own.
     */
    fun canManage(): Boolean = canManageServer || canManageChannels || canManageRoles ||
        canManageBans || canViewAuditLogs || canManageEmojisAndStickers ||
        isOwnerWithRequiredMFALevel

    companion object {
        /**
         * @param categories the guild's category channels; being able to manage any one
         *   of them is enough to offer channel management.
         * @param guildPermissions the acting user's guild-level permissions.
         * @param channelPermissions the acting user's per-channel permissions.
         */
        @JvmStatic
        fun from(
            categories: List<Channel>,
            isOwner: Boolean,
            guildPermissions: Long?,
            channelPermissions: Map<Long, Long>,
            guildMfaLevel: Int,
            isMeMfaEnabled: Boolean,
        ): ManageGuildContext {
            val canManageAnyCategory = categories.any {
                PermissionUtils.can(Permission.MANAGE_CHANNELS, channelPermissions[it.id])
            }
            fun can(permission: Long) =
                isOwner || PermissionUtils.can(permission, guildPermissions)
            return ManageGuildContext(
                canManageServer = can(Permission.MANAGE_GUILD),
                canManageChannels = can(Permission.MANAGE_CHANNELS) || canManageAnyCategory,
                canManageRoles = can(Permission.MANAGE_ROLES),
                canManageBans = can(Permission.BAN_MEMBERS),
                canManageNicknames = can(Permission.MANAGE_NICKNAMES),
                canManageEmojisAndStickers = can(Permission.MANAGE_EMOJIS_AND_STICKERS),
                canViewAuditLogs = can(Permission.VIEW_AUDIT_LOG),
                canManageEvents = can(Permission.MANAGE_EVENTS),
                isOwnerWithRequiredMFALevel = isOwner &&
                    PermissionUtils.isElevated(isMeMfaEnabled, guildMfaLevel),
            )
        }
    }
}

/**
 * Which actions the long-press message menu should offer.
 *
 * Archived threads are read-only, so [isArchivedThread] suppresses everything except
 * nothing at all — including marking unread.
 */
data class ManageMessageContext(
    val canManageMessages: Boolean,
    val canEdit: Boolean,
    val canDelete: Boolean,
    val canAddReactions: Boolean,
    val canTogglePinned: Boolean,
    val canMarkUnread: Boolean,
) {
    companion object {
        /**
         * @param myPermissions the acting user's permissions in the message's channel.
         * @param guildMfaLevel `null` outside a guild; a guild message can never be
         *   moderated without knowing the guild's MFA requirement.
         * @param isPrivateChannel true in a DM or group DM, where the participants act as
         *   their own moderators.
         * @param isSystemDM true in the official Discord DM, which nobody may moderate.
         */
        @JvmStatic
        fun from(
            message: Message,
            myPermissions: Long?,
            meUser: MeUser,
            guildMfaLevel: Int?,
            isPrivateChannel: Boolean,
            isSystemDM: Boolean,
            isArchivedThread: Boolean,
        ): ManageMessageContext {
            val isUserMessage = message.isUserMessage()
            val isAuthor = message.author?.id == meUser.id
            val isOwnPrivateChannel = isPrivateChannel && !isSystemDM
            val canManageMessages = guildMfaLevel != null &&
                PermissionUtils.canAndIsElevated(
                    Permission.MANAGE_MESSAGES, myPermissions, meUser.mfaEnabled, guildMfaLevel,
                ) &&
                !isArchivedThread
            val canModerate = canManageMessages || isOwnPrivateChannel
            val canRemove = canManageMessages || isAuthor
            return ManageMessageContext(
                canManageMessages = canManageMessages,
                // Stickers cannot be edited, so a message carrying one is frozen.
                canEdit = isAuthor && isUserMessage && !message.hasStickers() && !isArchivedThread,
                canDelete = canRemove && !isArchivedThread && message.isDeleteable(),
                canAddReactions = !message.isLocal() &&
                    (isOwnPrivateChannel ||
                        PermissionUtils.can(Permission.ADD_REACTIONS, myPermissions)) &&
                    !isArchivedThread,
                canTogglePinned = canModerate && isUserMessage && !isSystemDM && !isArchivedThread,
                canMarkUnread = !isArchivedThread,
            )
        }
    }
}

/**
 * Which moderation actions to offer against another member.
 *
 * Every destructive action is gated twice: by permission *and* by role rank. A moderator
 * may never act on someone whose highest role is at or above their own, and nobody but
 * the owner may act on the owner.
 */
data class ManageUserContext(
    val canManageRoles: Boolean,
    val canKick: Boolean,
    val canBan: Boolean,
    val canMute: Boolean,
    val canDeafen: Boolean,
    val canMove: Boolean,
    val canChangeNickname: Boolean,
    val canDisableCommunication: Boolean,
    private val canTransferOwnership: Boolean,
) {
    /** Whether the moderation section of the user sheet should be shown at all. */
    fun canManage(): Boolean = canManageRoles || canKick || canBan || canMute || canDeafen ||
        canMove || canChangeNickname || canTransferOwnership

    companion object {
        /**
         * @param actingUserRoles the acting user's role ids in [guild].
         * @param userRoles the target user's role ids in [guild].
         * @param myPermissions the acting user's guild-level permissions.
         */
        @JvmStatic
        fun from(
            guild: Guild,
            actingUser: MeUser,
            targetUser: User,
            actingUserRoles: Collection<Long>,
            userRoles: Collection<Long>,
            myPermissions: Long?,
            guildRoles: Map<Long, GuildRole>,
        ): ManageUserContext {
            val targetId = targetUser.id
            val actingId = actingUser.id
            val isSelf = actingId == targetId
            val isOwner = actingId == guild.ownerId
            val outranksTarget = isOwner || (
                targetId != guild.ownerId &&
                    RoleUtils.rankIsHigher(
                        RoleUtils.getHighestRole(guildRoles, actingUserRoles),
                        RoleUtils.getHighestRole(guildRoles, userRoles),
                    )
                )
            val mfaLevel = guild.mfaLevel
            val mfaEnabled = actingUser.mfaEnabled

            fun canElevated(permission: Long) =
                PermissionUtils.canAndIsElevated(permission, myPermissions, mfaEnabled, mfaLevel)

            val isAdministrator = isOwner || canElevated(Permission.ADMINISTRATOR)
            fun canModerate(permission: Long) =
                !isSelf && outranksTarget && (isAdministrator || canElevated(permission))

            return ManageUserContext(
                canManageRoles = (isSelf || outranksTarget) &&
                    (isAdministrator || canElevated(Permission.MANAGE_ROLES)),
                canKick = canModerate(Permission.KICK_MEMBERS),
                canBan = canModerate(Permission.BAN_MEMBERS),
                // Voice moderation is not elevation gated; it is reversible.
                canMute = isAdministrator ||
                    PermissionUtils.can(Permission.MUTE_MEMBERS, myPermissions),
                canDeafen = isAdministrator ||
                    PermissionUtils.can(Permission.DEAFEN_MEMBERS, myPermissions),
                canMove = isAdministrator ||
                    PermissionUtils.can(Permission.MOVE_MEMBERS, myPermissions),
                canChangeNickname = if (isSelf) {
                    isAdministrator || PermissionUtils.can(Permission.CHANGE_NICKNAME, myPermissions)
                } else {
                    outranksTarget &&
                        (isAdministrator || canElevated(Permission.MANAGE_NICKNAMES))
                },
                canDisableCommunication = canModerate(Permission.MODERATE_MEMBERS),
                canTransferOwnership = !isSelf && isOwner,
            )
        }
    }
}
