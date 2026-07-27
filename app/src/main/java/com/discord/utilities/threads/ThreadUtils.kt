package com.discord.utilities.threads

import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.models.user.User
import com.discord.utilities.permissions.PermissionUtils

/**
 * Permission predicates for threads.
 *
 * Reference: `com.discord.utilities.threads.ThreadUtils`. Only the predicates the channel screens
 * need are reconstructed here; the reference object also carries thread-creation and
 * auto-archive helpers.
 */
object ThreadUtils {

    /**
     * Whether [user] may rename, archive, lock or delete [channel].
     *
     * Two independent routes: the thread's own creator may manage it as long as they can still
     * write in it, and anyone holding `MANAGE_THREADS` may manage it as long as they can see it.
     */
    @JvmStatic
    fun canManageThread(user: User, channel: Channel, channelPermissions: Long?): Boolean {
        val isOwner = channel.ownerId == user.id &&
            PermissionUtils.hasAccessWrite(channel, channelPermissions)
        val isModerator = PermissionUtils.can(Permission.MANAGE_THREADS, channelPermissions) &&
            PermissionUtils.hasAccess(channel, channelPermissions)
        return isOwner || isModerator
    }

    /**
     * Whether the viewer may take a thread out of the archive. A *locked* thread additionally
     * requires `MANAGE_THREADS`; an ordinary archived thread only requires write access.
     */
    @JvmStatic
    fun canUnarchiveThread(channel: Channel, channelPermissions: Long?): Boolean =
        if (channel.threadMetadata?.locked == true) {
            PermissionUtils.can(Permission.MANAGE_THREADS, channelPermissions) &&
                PermissionUtils.hasAccessWrite(channel, channelPermissions)
        } else {
            PermissionUtils.hasAccessWrite(channel, channelPermissions)
        }

    /** Whether the viewer may browse private threads they were not added to. */
    @JvmStatic
    fun canViewAllPrivateThreads(channelPermissions: Long?): Boolean =
        PermissionUtils.can(Permission.MANAGE_THREADS, channelPermissions) &&
            PermissionUtils.can(Permission.MANAGE_MESSAGES, channelPermissions)
}
