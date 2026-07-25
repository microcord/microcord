package com.discord.models.message

import com.discord.api.application.Application
import com.discord.api.botuikit.Component
import com.discord.api.channel.Channel
import com.discord.api.interaction.Interaction
import com.discord.api.message.LocalAttachment
import com.discord.api.message.MessageReference
import com.discord.api.message.activity.MessageActivity
import com.discord.api.message.activity.MessageActivityType
import com.discord.api.message.allowedmentions.MessageAllowedMentions
import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.call.MessageCall
import com.discord.api.message.embed.MessageEmbed
import com.discord.api.message.reaction.MessageReaction
import com.discord.api.message.role_subscription.RoleSubscriptionData
import com.discord.api.sticker.Sticker
import com.discord.api.sticker.StickerPartial
import com.discord.api.user.User
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.utilities.captcha.CaptchaHelper

data class Message(
    val id: Long,
    val channelId: Long = 0,
    val guildId: Long? = null,
    val author: User? = null,
    val content: String? = null,
    val timestamp: UtcDateTime? = null,
    val editedTimestamp: UtcDateTime? = null,
    val tts: Boolean? = null,
    val mentionEveryone: Boolean? = null,
    val mentions: List<User>? = null,
    val mentionRoles: List<Long>? = null,
    val attachments: List<MessageAttachment>? = null,
    val embeds: List<MessageEmbed>? = null,
    val reactions: List<MessageReaction>? = null,
    val nonce: String? = null,
    val pinned: Boolean? = null,
    val webhookId: Long? = null,
    val type: Int? = null,
    val activity: MessageActivity? = null,
    val application: Application? = null,
    val applicationId: Long? = null,
    val messageReference: MessageReference? = null,
    val flags: Long? = null,
    val stickers: List<Sticker>? = null,
    val stickerItems: List<StickerPartial>? = null,
    val referencedMessage: com.discord.api.message.Message? = null,
    val interaction: Interaction? = null,
    val thread: Channel? = null,
    val components: List<Component>? = null,
    val call: MessageCall? = null,
    val hit: Boolean? = null,
    val roleSubscriptionData: RoleSubscriptionData? = null,
    val hasLocalUploads: Boolean = false,
    val allowedMentions: MessageAllowedMentions? = null,
    val numRetries: Int? = null,
    val lastManualAttemptTimestamp: Long? = null,
    val initialAttemptTimestamp: Long? = null,
    val localAttachments: List<LocalAttachment>? = null,
    val captchaPayload: CaptchaHelper.CaptchaPayload? = null,
) {
    constructor(message: com.discord.api.message.Message) : this(
        id = message.id,
        channelId = message.channelId,
        guildId = message.guildId,
        author = message.author,
        content = message.content,
        timestamp = message.timestamp,
        editedTimestamp = message.editedTimestamp,
        tts = message.tts,
        mentionEveryone = message.mentionEveryone,
        mentions = message.mentions,
        mentionRoles = message.mentionRoles,
        attachments = message.attachments,
        embeds = message.embeds,
        reactions = message.reactions,
        nonce = message.nonce,
        pinned = message.pinned,
        webhookId = message.webhookId,
        type = message.type,
        activity = message.activity,
        application = message.application,
        applicationId = message.applicationId,
        messageReference = message.messageReference,
        flags = message.flags,
        stickers = message.stickers,
        stickerItems = message.stickerItems,
        referencedMessage = message.referencedMessage,
        interaction = message.interaction,
        thread = message.thread,
        components = message.components,
        call = message.call,
        hit = message.hit,
        roleSubscriptionData = message.roleSubscriptionData,
    )

    fun hasAttachments() = !attachments.isNullOrEmpty()
    fun hasEmbeds() = !embeds.isNullOrEmpty()
    fun hasStickers() = !stickers.isNullOrEmpty() || !stickerItems.isNullOrEmpty()
    fun hasFlag(flag: Long) = ((flags ?: 0L) and flag) == flag
    fun hasThread() = hasFlag(32)
    fun isCrossposted() = hasFlag(1)
    fun isCrosspost() = hasFlag(2)
    fun isSourceDeleted() = hasFlag(8)
    fun isUrgent() = hasFlag(16)
    fun isEphemeralMessage() = hasFlag(64)
    fun isLoading() = hasFlag(128)
    fun isApplicationMessage() = type == 20 || type == 23
    fun isEmbeddedMessageType() = type == 19 || type == 21
    fun isFailed() = type == -3 || type == -2 || type == -4 || type == -6 || type == -8
    fun canResend() = type == -2 || type == -6
    fun isInteraction() = type == -5 || type == -4 || interaction != null
    fun isLocalApplicationCommand() = type == -5 || type == -4
    fun isLocal() = type == -1 || type == -2 || type == -3 || isLocalApplicationCommand() || type == -6 || type == -8
    fun isUserMessage() = type == 0 || type == 19
    fun isSystemMessage() = !isUserMessage() && !isApplicationMessage()
    fun isWebhook() = webhookId != null
    fun isSpotifyListeningActivity() = activity?.type == MessageActivityType.LISTEN &&
        activity.partyId.contains("spotify", ignoreCase = true)
    fun shouldShowReplyPreviewAsAttachment() = activity != null && application != null
    fun getCallDuration() = call?.endedTimestamp?.let { end -> timestamp?.let { end.dateTimeMillis - it.dateTimeMillis } } ?: 0L
    fun getReactionsMap() = reactions.orEmpty().associateBy { it.emoji.reactionKey }

    fun merge(new: com.discord.api.message.Message) = copy(
        id = new.id,
        channelId = new.channelId.takeUnless { it == 0L } ?: channelId,
        guildId = new.guildId ?: guildId,
        author = new.author ?: author,
        content = new.content ?: content,
        timestamp = new.timestamp ?: timestamp,
        editedTimestamp = new.editedTimestamp ?: editedTimestamp,
        tts = new.tts ?: tts,
        mentionEveryone = new.mentionEveryone ?: mentionEveryone,
        mentions = new.mentions ?: mentions,
        mentionRoles = new.mentionRoles ?: mentionRoles,
        attachments = new.attachments ?: attachments,
        embeds = new.embeds ?: embeds,
        reactions = new.reactions ?: reactions,
        nonce = new.nonce ?: nonce,
        pinned = new.pinned ?: pinned,
        webhookId = new.webhookId ?: webhookId,
        type = new.type ?: type,
        activity = new.activity ?: activity,
        application = new.application ?: application,
        applicationId = new.applicationId ?: applicationId,
        messageReference = new.messageReference ?: messageReference,
        flags = new.flags ?: flags,
        stickers = new.stickers ?: stickers,
        stickerItems = new.stickerItems ?: stickerItems,
        referencedMessage = new.referencedMessage ?: referencedMessage,
        interaction = new.interaction ?: interaction,
        thread = new.thread ?: thread,
        components = new.components ?: components,
        call = new.call ?: call,
        roleSubscriptionData = new.roleSubscriptionData ?: roleSubscriptionData,
    )

    fun synthesizeApiMessage() = com.discord.api.message.Message(
        id, channelId, author, content, timestamp, editedTimestamp, tts, mentionEveryone,
        mentions, mentionRoles, attachments, embeds, reactions, nonce, pinned, webhookId, type,
        activity, application, applicationId, messageReference, flags, stickers, stickerItems,
        referencedMessage, interaction, thread, components, call, guildId,
        roleSubscriptionData = roleSubscriptionData,
    )

    override fun toString() =
        "Message(id=$id, channelId=$channelId, guildId=$guildId, content=<redacted>, nonce=<redacted>, " +
            "localAttachments=<redacted>, captchaPayload=<redacted>, type=$type, flags=$flags)"
}
