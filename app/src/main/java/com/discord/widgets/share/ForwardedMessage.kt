package com.discord.widgets.share

import androidx.annotation.StringRes
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isSystemDM
import com.discord.api.message.Message as ApiMessage
import com.discord.api.message.MessageFlags
import com.discord.api.message.MessageSnapshot
import com.discord.api.permission.Permission
import com.discord.api.sticker.Sticker
import com.discord.api.sticker.StickerPartial
import com.discord.models.message.Message
import com.discord.restapi.RestAPIParams
import com.discord.restapi.RestAPIInterface
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.captcha.CaptchaRequestPolicy
import com.discord.utilities.rest.RestAPI
import rx.Observable

object ForwardingPolicy {
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

    fun canSendTo(destination: Channel, destinationPermissions: Long?): Boolean {
        if (destination.type !in SENDABLE_TYPES || destination.isSystemDM()) return false
        if (destination.guildId == 0L) return true
        val send = if (destination.type in THREAD_TYPES) {
            Permission.SEND_MESSAGES_IN_THREADS
        } else {
            Permission.SEND_MESSAGES
        }
        return PermissionUtils.can(Permission.VIEW_CHANNEL or send, destinationPermissions)
    }

    /**
     * Reference `getDestinationIsUnavailable`: the reason this destination cannot receive [message],
     * as a label to show on its row. Destinations stay listed and explain themselves rather than
     * disappearing.
     *
     * [destination] is null for a user with no open DM, which only the age-restriction rule reaches.
     */
    @StringRes
    fun destinationUnavailable(
        message: Message,
        sourceIsAgeRestricted: Boolean,
        destination: Channel?,
        destinationIsAgeRestricted: Boolean,
        destinationPermissions: Long?,
        guildStickers: Map<Long, Sticker> = emptyMap(),
    ): Int? {
        if (sourceIsAgeRestricted && !destinationIsAgeRestricted) {
            return R.string.message_forwarding_nsfw_not_allowed
        }
        if (destination == null || destination.guildId == 0L) return null

        val snapshots = message.messageSnapshots.orEmpty().map(MessageSnapshot::message)
        val hasAttachments = message.hasAttachments() || snapshots.any { !it.attachments.isNullOrEmpty() }
        if (hasAttachments && !PermissionUtils.can(Permission.ATTACH_FILES, destinationPermissions)) {
            return R.string.message_channel_attachments_disabled
        }

        val hasEmbeds = message.hasEmbeds() || snapshots.any { !it.embeds.isNullOrEmpty() }
        if (hasEmbeds && !PermissionUtils.can(Permission.EMBED_LINKS, destinationPermissions) &&
            !stripsEmbeds(message, snapshots)
        ) {
            return R.string.message_channel_embeds_disabled
        }

        if (!PermissionUtils.can(Permission.USE_EXTERNAL_STICKERS, destinationPermissions) &&
            stickerGuildIds(message, snapshots, guildStickers).any { it != destination.guildId }
        ) {
            return R.string.message_channel_external_stickers_disabled
        }

        val hasVoiceMessage = message.isVoiceMessage() ||
            snapshots.any { (it.flags ?: 0L) and MessageFlags.IS_VOICE_MESSAGE != 0L }
        if (hasVoiceMessage && !PermissionUtils.can(Permission.SEND_VOICE_MESSAGES, destinationPermissions)) {
            return R.string.message_channel_voice_messages_disabled
        }
        return null
    }

    /** Reference `shouldStripEmbeds`: the backend drops embeds rather than rejecting the send. */
    private fun stripsEmbeds(message: Message, snapshots: List<ApiMessage>) =
        !message.content.isNullOrEmpty() ||
            snapshots.any { !it.content.isNullOrEmpty() || !it.attachments.isNullOrEmpty() }

    /**
     * The owning guild of every guild sticker on the message. A sticker missing from the store fails
     * open, matching the reference — the send guard rejects it server-side if it really was external.
     */
    private fun stickerGuildIds(
        message: Message,
        snapshots: List<ApiMessage>,
        guildStickers: Map<Long, Sticker>,
    ): List<Long> {
        val ids = message.stickerItems.orEmpty().map(StickerPartial::id) +
            message.stickers.orEmpty().map(Sticker::id) +
            snapshots.flatMap { snapshot ->
                snapshot.stickerItems.orEmpty().map(StickerPartial::id) +
                    snapshot.stickers.orEmpty().map(Sticker::id)
            }
        return ids.distinct().mapNotNull { guildStickers[it]?.guildId }
    }

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
    )
}
