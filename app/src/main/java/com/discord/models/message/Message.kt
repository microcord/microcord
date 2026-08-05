package com.discord.models.message

import com.discord.api.application.Application
import com.discord.api.botuikit.Component
import com.discord.api.channel.Channel
import com.discord.api.guildmember.GuildMember
import com.discord.api.interaction.Interaction
import com.discord.api.message.LocalAttachment
import com.discord.api.message.MessageReference
import com.discord.api.message.MessagePoll
import com.discord.api.message.MessageResolvedData
import com.discord.api.message.MessageSnapshot
import com.discord.api.message.MessagePurchaseNotification
import com.discord.api.message.MessageGiftInfo
import com.discord.api.message.Potion
import com.discord.api.message.SharedClientTheme
import com.discord.api.message.activity.MessageActivity
import com.discord.api.message.activity.MessageActivityInstance
import com.discord.api.message.activity.MessageActivityType
import com.discord.api.message.allowedmentions.MessageAllowedMentions
import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.call.MessageCall
import com.discord.api.message.embed.MessageEmbed
import com.discord.api.message.reaction.MessageReaction
import com.discord.api.message.role_subscription.RoleSubscriptionData
import com.discord.api.soundboard.SoundboardSound
import com.discord.api.sticker.Sticker
import com.discord.api.sticker.StickerPartial
import com.discord.api.user.User
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.utilities.captcha.CaptchaSolution

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
    val messageSnapshots: List<MessageSnapshot>? = null,
    val poll: MessagePoll? = null,
    val resolved: MessageResolvedData? = null,
    val member: GuildMember? = null,
    val hasLocalUploads: Boolean = false,
    val allowedMentions: MessageAllowedMentions? = null,
    val numRetries: Int? = null,
    val lastManualAttemptTimestamp: Long? = null,
    val initialAttemptTimestamp: Long? = null,
    val localAttachments: List<LocalAttachment>? = null,
    val captchaSolution: CaptchaSolution? = null,
    val activityInstance: MessageActivityInstance? = null,
    val interactionMetadata: Interaction? = null,
    val lobbyId: Long? = null,
    val mentionChannels: List<Channel>? = null,
    val purchaseNotification: MessagePurchaseNotification? = null,
    val giftInfo: MessageGiftInfo? = null,
    val changelogId: Long? = null,
    val soundboardSounds: List<SoundboardSound>? = null,
    val potions: List<Potion>? = null,
    val sharedClientTheme: SharedClientTheme? = null,
) {
    constructor(message: com.discord.api.message.Message) : this(
        id = message.id,
        channelId = message.channelId,
        guildId = message.guildId,
        author = message.author,
        member = message.member,
        content = message.content,
        timestamp = message.timestamp,
        editedTimestamp = message.editedTimestamp,
        tts = message.tts,
        mentionEveryone = message.mentionEveryone,
        mentions = message.mentions,
        mentionChannels = message.mentionChannels,
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
        interactionMetadata = message.interactionMetadata,
        lobbyId = message.lobbyId,
        thread = message.thread,
        components = message.components,
        call = message.call,
        hit = message.hit,
        roleSubscriptionData = message.roleSubscriptionData,
        messageSnapshots = message.messageSnapshots,
        poll = message.poll,
        purchaseNotification = message.purchaseNotification,
        resolved = message.resolved,
        giftInfo = message.giftInfo,
        changelogId = message.changelogId,
        soundboardSounds = message.soundboardSounds,
        potions = message.potions,
        sharedClientTheme = message.sharedClientTheme,
        activityInstance = message.activityInstance,
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
    fun isVoiceMessage() = hasFlag(8_192)
    fun hasSnapshot() = hasFlag(16_384)
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
    fun shouldShowReplyPreviewAsAttachment() = poll != null || activity != null && application != null
    fun getCallDuration() = call?.endedTimestamp?.let { end -> timestamp?.let { end.dateTimeMillis - it.dateTimeMillis } } ?: 0L
    fun getReactionsMap() = reactions.orEmpty().associateBy { it.emoji.reactionKey }

    fun merge(new: com.discord.api.message.Message) = copy(
        id = new.id,
        channelId = new.channelId.takeUnless { it == 0L } ?: channelId,
        guildId = new.guildId ?: guildId,
        author = new.author ?: author,
        member = new.member ?: member,
        content = new.content ?: content,
        timestamp = new.timestamp ?: timestamp,
        editedTimestamp = new.editedTimestamp ?: editedTimestamp,
        tts = new.tts ?: tts,
        mentionEveryone = new.mentionEveryone ?: mentionEveryone,
        mentions = new.mentions ?: mentions,
        mentionChannels = new.mentionChannels ?: mentionChannels,
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
        interactionMetadata = new.interactionMetadata ?: interactionMetadata,
        lobbyId = new.lobbyId ?: lobbyId,
        thread = new.thread ?: thread,
        components = new.components ?: components,
        call = new.call ?: call,
        roleSubscriptionData = new.roleSubscriptionData ?: roleSubscriptionData,
        messageSnapshots = new.messageSnapshots ?: messageSnapshots,
        poll = mergePoll(poll, new.poll),
        purchaseNotification = new.purchaseNotification ?: purchaseNotification,
        resolved = new.resolved ?: resolved,
        giftInfo = new.giftInfo ?: giftInfo,
        changelogId = new.changelogId ?: changelogId,
        soundboardSounds = new.soundboardSounds ?: soundboardSounds,
        potions = new.potions ?: potions,
        sharedClientTheme = new.sharedClientTheme ?: sharedClientTheme,
        activityInstance = new.activityInstance ?: activityInstance,
    )

    fun synthesizeApiMessage() = com.discord.api.message.Message(
        id = id,
        channelId = channelId,
        author = author,
        member = member,
        content = content,
        timestamp = timestamp,
        editedTimestamp = editedTimestamp,
        tts = tts,
        mentionEveryone = mentionEveryone,
        mentions = mentions,
        mentionChannels = mentionChannels,
        mentionRoles = mentionRoles,
        attachments = attachments,
        embeds = embeds,
        reactions = reactions,
        nonce = nonce,
        pinned = pinned,
        webhookId = webhookId,
        type = type,
        activity = activity,
        application = application,
        applicationId = applicationId,
        messageReference = messageReference,
        flags = flags,
        stickers = stickers,
        stickerItems = stickerItems,
        referencedMessage = referencedMessage,
        interaction = interaction,
        interactionMetadata = interactionMetadata,
        lobbyId = lobbyId,
        thread = thread,
        components = components,
        call = call,
        guildId = guildId,
        roleSubscriptionData = roleSubscriptionData,
        messageSnapshots = messageSnapshots,
        poll = poll,
        purchaseNotification = purchaseNotification,
        resolved = resolved,
        giftInfo = giftInfo,
        changelogId = changelogId,
        soundboardSounds = soundboardSounds,
        potions = potions,
        sharedClientTheme = sharedClientTheme,
        activityInstance = activityInstance,
    )

    override fun toString() =
        "Message(id=$id, channelId=$channelId, guildId=$guildId, content=<redacted>, nonce=<redacted>, " +
            "localAttachments=<redacted>, captchaSolution=<redacted>, type=$type, flags=$flags)"
}

private fun mergePoll(current: MessagePoll?, update: MessagePoll?): MessagePoll? {
    update ?: return current
    val currentResults = current?.results ?: return update
    val updateResults = update.results ?: return update.copy(results = currentResults)
    val myAnswerIds = currentResults.answerCounts.filter { it.meVoted }.mapTo(HashSet()) { it.id }
    return update.copy(
        results = updateResults.copy(
            answerCounts = updateResults.answerCounts.map { count ->
                if (count.id in myAnswerIds) count.copy(meVoted = true) else count
            },
        ),
    )
}
