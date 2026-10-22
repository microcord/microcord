package com.discord.utilities.threads

import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.models.user.User
import com.discord.restapi.RestAPIParams
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import rx.Observable
import com.discord.api.channel.isThread
import com.discord.api.channel.isVoiceChannel
import com.discord.models.guild.Guild
import com.discord.models.message.Message

/**
 * Permission predicates for threads.
 *
 * Reference: `com.discord.utilities.threads.ThreadUtils`. Only the predicates the channel screens
 * need are reconstructed here; the reference object also carries thread-creation and
 * auto-archive helpers.
 */
object ThreadUtils {

    @JvmStatic
    fun createThread(
        parentChannelId: Long,
        parentMessageId: Long?,
        type: Int,
        name: String,
        autoArchiveDuration: Int?,
        location: String?,
    ): Observable<Channel> {
        val settings = RestAPIParams.ThreadCreationSettings(name, type, autoArchiveDuration)
        return if (parentMessageId == null) {
            RestAPI.api.createThread(parentChannelId, location, settings)
        } else {
            RestAPI.api.createThreadFromMessage(parentChannelId, parentMessageId, location, settings)
        }
    }

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
            PermissionUtils.can(Permission.READ_MESSAGE_HISTORY, channelPermissions)

    /** Whether a public thread can be started in [channel], from [message] when there is one. */
    @JvmStatic
    fun canCreatePublicThread(permissions: Long?, channel: Channel, message: Message?, guild: Guild?): Boolean {
        guild ?: return false
        val permissionAllowed = PermissionUtils.can(Permission.CREATE_PUBLIC_THREADS, permissions) &&
            (message == null || PermissionUtils.can(Permission.READ_MESSAGE_HISTORY, permissions))
        val channelAllowed = message?.isSystemMessage() != true && !channel.isThread() &&
            message?.hasThread() != true && !channel.isVoiceChannel()
        return permissionAllowed && channelAllowed
    }
}
