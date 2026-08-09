package com.discord.widgets.share

import com.discord.api.channel.Channel
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isSystemDM
import com.discord.api.message.MessageFlags
import com.discord.api.permission.Permission
import com.discord.models.message.Message
import com.discord.restapi.RestAPIParams
import com.discord.restapi.RestAPIInterface
import com.discord.utilities.messagesend.MessageSendLocation
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.captcha.CaptchaRequestPolicy
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rest.contextPropertiesOf
import rx.Observable

object ForwardingPolicy {
    enum class DestinationIssue { CHANNEL, SEND_MESSAGES, ATTACHMENTS, EMBEDS, STICKERS, VOICE }

    private val forwardableTypes = setOf(0, 19, 20, 23, 35)
    private const val BLOCKED_FLAGS =
        MessageFlags.CROSSPOSTED or
            MessageFlags.IS_CROSSPOST or
            MessageFlags.SUPPRESS_EMBEDS or
            MessageFlags.URGENT or
            MessageFlags.HAS_THREAD or
            MessageFlags.FAILED_TO_MENTION_SOME_ROLES_IN_THREAD or
            MessageFlags.GUILD_FEED_HIDDEN or
            MessageFlags.SHOULD_SHOW_LINK_NOT_DISCORD_WARNING or
            MessageFlags.SUPPRESS_NOTIFICATIONS or
            MessageFlags.IS_VOICE_MESSAGE or
            MessageFlags.HAS_SNAPSHOT or
            MessageFlags.IS_COMPONENTS_V2 or
            MessageFlags.IS_GUILD_OFFICIAL

    fun canForward(message: Message, source: Channel?, sourcePermissions: Long?): Boolean =
        source != null && !message.isLocal() && message.type?.let(forwardableTypes::contains) == true && message.poll == null &&
            message.activity == null && message.call == null && (message.flags.orZero() and BLOCKED_FLAGS) == 0L &&
            (source.isPrivate() || PermissionUtils.can(Permission.READ_MESSAGE_HISTORY, sourcePermissions))

    fun destinationIssue(
        message: Message,
        source: Channel,
        destination: Channel,
        destinationPermissions: Long?,
    ): DestinationIssue? {
        if (destination.type !in SENDABLE_TYPES || destination.isSystemDM()) return DestinationIssue.CHANNEL
        if (destination.guildId != 0L) {
            val send = if (destination.type in THREAD_TYPES) {
                Permission.SEND_MESSAGES_IN_THREADS
            } else {
                Permission.SEND_MESSAGES
            }
            if (!PermissionUtils.can(Permission.VIEW_CHANNEL or send, destinationPermissions)) {
                return DestinationIssue.SEND_MESSAGES
            }
        }
        if (message.hasAttachments() && !can(destination, destinationPermissions, Permission.ATTACH_FILES)) {
            return DestinationIssue.ATTACHMENTS
        }
        if (message.hasEmbeds() && !can(destination, destinationPermissions, Permission.EMBED_LINKS)) {
            return DestinationIssue.EMBEDS
        }
        if (message.hasStickers() && source.guildId != destination.guildId &&
            !can(destination, destinationPermissions, Permission.USE_EXTERNAL_STICKERS)
        ) {
            return DestinationIssue.STICKERS
        }
        if (message.isVoiceMessage() && !can(destination, destinationPermissions, Permission.SEND_VOICE_MESSAGES)) {
            return DestinationIssue.VOICE
        }
        return null
    }

    private fun can(channel: Channel, permissions: Long?, permission: Long) =
        channel.guildId == 0L || PermissionUtils.can(permission, permissions)

    private fun Long?.orZero() = this ?: 0L

    private val SENDABLE_TYPES = setOf(
        Channel.GUILD_TEXT,
        Channel.DM,
        Channel.GROUP_DM,
        Channel.GUILD_ANNOUNCEMENT,
        Channel.ANNOUNCEMENT_THREAD,
        Channel.PUBLIC_THREAD,
        Channel.PRIVATE_THREAD,
    )
    private val THREAD_TYPES = setOf(Channel.ANNOUNCEMENT_THREAD, Channel.PUBLIC_THREAD, Channel.PRIVATE_THREAD)
}

object ForwardMessageSender {
    const val REFERENCE_TYPE_FORWARD = 1

    fun body(source: Message, sourceChannel: Channel) = RestAPIParams.Message(
        messageReference = RestAPIParams.Message.MessageReference(
            guildId = sourceChannel.guildId.takeUnless { it == 0L },
            channelId = sourceChannel.id,
            messageId = source.id,
            type = REFERENCE_TYPE_FORWARD,
        ),
    )

    fun send(
        destinationChannelId: Long,
        source: Message,
        sourceChannel: Channel,
        api: RestAPIInterface = RestAPI.api,
    ): Observable<com.discord.api.message.Message> = api.sendMessage(
        destinationChannelId,
        body(source, sourceChannel),
        CaptchaRequestPolicy(),
        contextPropertiesOf("location" to MessageSendLocation.FORWARDING),
    )
}
