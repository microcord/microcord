package com.discord.widgets.chat

import android.app.Activity
import android.content.Context
import android.content.pm.ShortcutManager
import android.os.Build
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.fragment.app.FragmentActivity
import com.discord.R
import com.discord.api.message.allowedmentions.MessageAllowedMentions
import com.discord.api.message.allowedmentions.MessageAllowedMentionsTypes
import com.discord.api.premium.PremiumTier
import com.discord.api.sticker.BaseSticker
import com.discord.api.sticker.Sticker
import com.discord.api.sticker.StickerType
import com.discord.models.guild.Guild
import com.discord.models.user.MeUser
import com.discord.models.user.User
import com.discord.stores.StoreApplicationInteractions
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreMessageReplies
import com.discord.stores.StoreMessages
import com.discord.stores.StorePendingReplies
import com.discord.stores.StoreSlowMode
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.utilities.attachments.UploadAttachment
import com.discord.widgets.captcha.WidgetCaptchaBottomSheet
import com.discord.utilities.error.RestApiError
import com.discord.utilities.messagesend.MessageResult
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.widgets.chat.input.models.ApplicationCommandData
import com.lytefast.flexinput.model.Attachment
import rx.Observable

class MessageManager(
    private val context: Context,
    private val storeMessages: StoreMessages = StoreStream.getMessages(),
    private val storeApplicationInteractions: StoreApplicationInteractions = StoreStream.getInteractions(),
    private val storeUser: StoreUser = StoreStream.getUsers(),
    private val storeChannelsSelected: StoreChannelsSelected = StoreStream.getChannelsSelected(),
    private val storeSlowMode: StoreSlowMode = StoreStream.getSlowMode(),
    private val storeGuilds: StoreGuilds = StoreStream.getGuilds(),
    private val storePendingReplies: StorePendingReplies = StoreStream.getPendingReplies(),
    private val storeMessageReplies: StoreMessageReplies = StoreStream.getMessageReplies(),
) {
    private sealed class AttachmentValidationResult {
        object EmptyAttachments : AttachmentValidationResult()
        data class FilesTooLarge(val attachmentsRequest: AttachmentsRequest) : AttachmentValidationResult()
        object Success : AttachmentValidationResult()
    }

    class AttachmentsRequest(
        val currentFileSizeMB: Float,
        val maxFileSizeMB: Int,
        val attachments: List<Attachment<*>>? = null,
    )

    private sealed class ContentValidationResult {
        object EmptyContent : ContentValidationResult()
        data class MessageTooLong(val maxMessageLength: Int) : ContentValidationResult()
        object Success : ContentValidationResult()
    }

    data class MessageSendResult(
        val messageResult: MessageResult,
        val guild: Guild?,
    )

    private val defaultMessageResultHandler: (MessageSendResult) -> Unit = { result ->
        when (val messageResult = result.messageResult) {
            is MessageResult.CaptchaRequired -> showCaptcha(messageResult)
            is MessageResult.UnknownFailure -> messageResult.error.showToasts(context)
            is MessageResult.Slowmode -> com.discord.app.AppToast.show(context, R.string.channel_slowmode_desc_short)
            else -> Unit
        }
    }

    private fun getAllowedMentionsForMessageEdit(channelId: Long, messageId: Long): MessageAllowedMentions? {
        val message = storeMessages.getMessage(channelId, messageId) ?: return null
        if (message.type != 19) return null
        val referencedMessageId = message.messageReference?.messageId ?: return null
        val state = storeMessageReplies.getAllMessageReferences()[referencedMessageId]
        if (state !is StoreMessageReplies.MessageState.Loaded) return null
        val referencedAuthorId = state.message.author?.id
        if (message.mentions.orEmpty().any { it.id == referencedAuthorId }) return null
        return MessageAllowedMentions(
            parse = MessageAllowedMentionsTypes.entries,
            repliedUser = false,
        )
    }

    private fun validateAttachments(request: AttachmentsRequest?): AttachmentValidationResult = when {
        request?.attachments.isNullOrEmpty() -> AttachmentValidationResult.EmptyAttachments
        request!!.currentFileSizeMB >= request.maxFileSizeMB -> AttachmentValidationResult.FilesTooLarge(request)
        else -> AttachmentValidationResult.Success
    }

    private fun validateMessageContent(
        content: String,
        stickers: List<BaseSticker>,
        meUser: MeUser,
        isEditing: Boolean,
        previousMessageLength: Int? = null,
    ): ContentValidationResult {
        val canUsePremiumLimit = meUser.premiumTier == PremiumTier.TIER_2 ||
            isEditing && previousMessageLength != null && previousMessageLength > STANDARD_MESSAGE_LIMIT
        val limit = if (canUsePremiumLimit) PREMIUM_MESSAGE_LIMIT else STANDARD_MESSAGE_LIMIT
        return when {
            content.isEmpty() && stickers.isEmpty() -> ContentValidationResult.EmptyContent
            content.length > limit -> ContentValidationResult.MessageTooLong(limit)
            else -> ContentValidationResult.Success
        }
    }

    fun editMessage(
        messageId: Long,
        channelId: Long,
        content: String,
        onMessageTooLong: ((Int, Int) -> Unit)? = null,
        previousMessageLength: Int? = null,
    ): Boolean = when (
        val validation = validateMessageContent(
            content,
            emptyList(),
            storeUser.getMe(),
            isEditing = true,
            previousMessageLength = previousMessageLength,
        )
    ) {
        is ContentValidationResult.MessageTooLong -> {
            onMessageTooLong?.invoke(content.length, validation.maxMessageLength)
            false
        }
        ContentValidationResult.EmptyContent -> false
        ContentValidationResult.Success -> {
            storeMessages.editMessage(
                messageId,
                channelId,
                content,
                getAllowedMentionsForMessageEdit(channelId, messageId),
            )
            true
        }
    }

    fun isValidForumPostFirstMessage(
        content: String,
        attachmentsRequest: AttachmentsRequest? = null,
        onMessageTooLong: ((Int, Int) -> Unit)?,
        onFilesTooLarge: ((Int, Boolean) -> Unit)?,
    ): Boolean {
        val me = storeUser.getMe()
        val contentValidation = validateMessageContent(content, emptyList(), me, isEditing = false)
        if (contentValidation is ContentValidationResult.MessageTooLong) {
            onMessageTooLong?.invoke(content.length, contentValidation.maxMessageLength)
            return false
        }
        val attachmentValidation = validateAttachments(attachmentsRequest)
        if (attachmentValidation is AttachmentValidationResult.FilesTooLarge) {
            onFilesTooLarge?.invoke(attachmentValidation.attachmentsRequest.maxFileSizeMB, me.isPremium())
            return false
        }
        return true
    }

    fun sendCommand(
        channelId: Long,
        guildId: Long?,
        version: String?,
        data: ApplicationCommandData,
        attachmentRequest: AttachmentsRequest? = null,
        onSuccess: () -> Unit,
        onFail: (RestApiError) -> Unit,
        onFilesTooLarge: ((Int, Boolean) -> Unit)? = null,
    ): Boolean {
        val validation = validateAttachments(attachmentRequest)
        if (validation is AttachmentValidationResult.FilesTooLarge) {
            onFilesTooLarge?.invoke(validation.attachmentsRequest.maxFileSizeMB, storeUser.getMe().isPremium())
            return false
        }
        storeApplicationInteractions.sendApplicationCommand(
            channelId,
            guildId,
            version,
            data,
            attachmentRequest.toUploadAttachments(),
            onSuccess,
            onFail,
        )
        return true
    }

    fun sendMessage(
        content: String = "",
        mentions: List<User>? = null,
        attachmentsRequest: AttachmentsRequest? = null,
        channelId: Long? = null,
        stickers: List<BaseSticker> = emptyList(),
        consumePendingReply: Boolean = true,
        onMessageTooLong: ((Int, Int) -> Unit)? = null,
        onFilesTooLarge: ((Int, Boolean) -> Unit)? = null,
        messageSendResultHandler: (MessageSendResult) -> Unit = defaultMessageResultHandler,
    ): Boolean {
        val me = storeUser.getMe()
        val contentValidation = validateMessageContent(content, stickers, me, isEditing = false)
        if (contentValidation is ContentValidationResult.MessageTooLong) {
            onMessageTooLong?.invoke(content.length, contentValidation.maxMessageLength)
            return false
        }
        val attachmentValidation = validateAttachments(attachmentsRequest)
        if (attachmentValidation is AttachmentValidationResult.FilesTooLarge) {
            onFilesTooLarge?.invoke(attachmentValidation.attachmentsRequest.maxFileSizeMB, me.isPremium())
            return false
        }

        val targetChannelId = channelId ?: storeChannelsSelected.getId()
        reportShortcutUsed(targetChannelId)
        val pendingReply = if (consumePendingReply) storePendingReplies.getPendingReply(targetChannelId) else null
        val allowedMentions = if (pendingReply?.shouldMention == false) {
            MessageAllowedMentions(parse = MessageAllowedMentionsTypes.entries, repliedUser = false)
        } else {
            null
        }

        val result = storeSlowMode.observeCooldownSecs(channelId, StoreSlowMode.Type.MessageSend)
            .take(1)
            .flatMap { cooldownSeconds ->
                if (cooldownSeconds > 0) {
                    Observable.just<MessageResult>(MessageResult.Slowmode(cooldownSeconds * 1_000L))
                } else {
                    pendingReply?.let { storePendingReplies.onDeletePendingReply(targetChannelId) }
                    storeMessages.sendMessage(
                        channelId = targetChannelId,
                        author = me,
                        content = content,
                        mentions = mentions,
                        attachments = attachmentsRequest.toUploadAttachments(),
                        stickers = stickers.map { it.toSendableSticker() },
                        messageReference = pendingReply?.messageReference,
                        allowedMentions = allowedMentions,
                    )
                }
            }
        Observable.combineLatest(
            result,
            storeGuilds.observeFromChannelId(targetChannelId),
        ) { messageResult, guild -> MessageSendResult(messageResult, guild) }
            .take(1)
            .restSubscribeOn()
            .ui()
            .appSubscribe(MessageManager::class.java, context = context, onNext = messageSendResultHandler)
        return true
    }

    private fun reportShortcutUsed(channelId: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val shortcutId = channelId.toString()
        if (ShortcutManagerCompat.getDynamicShortcuts(context).none { it.id == shortcutId }) return
        context.getSystemService(ShortcutManager::class.java)?.reportShortcutUsed(shortcutId)
    }

    private fun showCaptcha(result: MessageResult.CaptchaRequired) {
        val activity = context as? FragmentActivity ?: return result.error.showToasts(context)
        val challenge = result.challenge ?: return result.error.showToasts(context)
        WidgetCaptchaBottomSheet.show(
            activity.supportFragmentManager,
            result.nonce ?: result.channelId.toString(),
            challenge,
        ) { solution ->
            if (solution != null) {
                storeMessages.resendMessageWithCaptcha(result.channelId, result.nonce, solution)
            }
        }
    }

    private fun AttachmentsRequest?.toUploadAttachments(): List<UploadAttachment>? =
        this?.attachments?.map { UploadAttachment.from(context.contentResolver, it) }

    private fun BaseSticker.toSendableSticker(): Sticker = this as? Sticker ?: toStickerPartial().let { partial ->
        Sticker(
            id = partial.id,
            packId = null,
            guildId = null,
            name = partial.name,
            description = "",
            formatType = partial.formatType,
            tags = "",
            type = StickerType.UNKNOWN,
        )
    }

    private fun MeUser.isPremium() = premiumTier != PremiumTier.NONE &&
        premiumTier != PremiumTier.PREMIUM_GUILD_SUBSCRIPTION_ONLY

    private companion object {
        const val STANDARD_MESSAGE_LIMIT = 2_000
        const val PREMIUM_MESSAGE_LIMIT = 4_000
    }
}
