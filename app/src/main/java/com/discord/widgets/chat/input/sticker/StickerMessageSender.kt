package com.discord.widgets.chat.input.sticker

import android.content.Context
import android.net.Uri
import com.discord.api.channel.Channel
import com.discord.api.message.MessageReference
import com.discord.api.sticker.Sticker
import com.discord.api.sticker.StickerFormatType
import com.discord.api.user.User
import com.discord.models.message.Message
import com.discord.stores.StorePendingReplies
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.utilities.attachments.UploadAttachment
import com.discord.utilities.feature.FeatureFlags
import com.discord.utilities.file.DownloadUtils
import com.discord.utilities.lifecycle.ApplicationProvider
import com.discord.utilities.messagesend.MessageSendCoordinator
import com.discord.utilities.messagesend.MessageSendLocation
import com.discord.utilities.stickers.StickerGifConverter
import com.discord.utilities.stickers.StickerUtils
import com.discord.utilities.user.UserUtils.synthesizeApiUser
import com.discord.widgets.chat.input.WidgetChatInput
import com.discord.widgets.stickers.spoofedStickerContent
import java.io.File
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers

/** Sends sticker-only messages through the same reply-aware coordinator as text and GIF sends. */
class StickerMessageSender(
    private val storeUserProvider: () -> StoreUser = StoreStream::getUsers,
    private val storePendingRepliesProvider: () -> StorePendingReplies = StoreStream::getPendingReplies,
    private val storePermissionsProvider: () -> StorePermissions = StoreStream::getPermissions,
    private val coordinatorProvider: () -> MessageSendCoordinator? =
        { WidgetChatInput.coordinatorProvider?.invoke() },
) {
    fun send(channel: Channel, sticker: Sticker): Boolean {
        val storePendingReplies = storePendingRepliesProvider()
        val pendingReply = storePendingReplies.getPendingReply(channel.id)
        val accepted = submit(channel, sticker, pendingReply, MessageSendLocation.CHAT_INPUT)
        if (accepted && pendingReply != null) storePendingReplies.onDeletePendingReply(channel.id)
        return accepted
    }

    fun sendReply(channel: Channel, sticker: Sticker, originalMessage: Message): Boolean = submit(
        channel = channel,
        sticker = sticker,
        pendingReply = StorePendingReplies.PendingReply(
            messageReference = MessageReference(
                channel.guildId.takeUnless { it == 0L },
                channel.id,
                originalMessage.id,
            ),
            originalMessage = originalMessage,
            shouldMention = !FeatureFlags.noReplyPingEnabled(),
        ),
        location = MessageSendLocation.STICKER_REPLY,
    )

    private fun submit(
        channel: Channel,
        sticker: Sticker,
        pendingReply: StorePendingReplies.PendingReply?,
        location: String,
    ): Boolean {
        val coordinator = coordinatorProvider() ?: return false
        val me = storeUserProvider().getMe()
        val permissions = storePermissionsProvider().getPermissionsByChannel()[channel.id]
        val spoofedContent = spoofedStickerContent(sticker, me, channel, permissions)
        val author = me.synthesizeApiUser()
        if (spoofedContent != null && sticker.formatType == StickerFormatType.APNG) {
            sendAsGif(channel, sticker, author, pendingReply, location, spoofedContent)
            return true
        }
        return coordinator.send(
            stickerSendRequest(channel, sticker, author, pendingReply, location, spoofedContent),
        ) is MessageSendCoordinator.Submission.Accepted
    }

    /**
     * A linked APNG shows as a still frame, so a Fake-Nitro APNG sticker is converted to a GIF and
     * uploaded instead. Falls back to the link if the download or conversion fails.
     */
    private fun sendAsGif(
        channel: Channel,
        sticker: Sticker,
        author: User,
        pendingReply: StorePendingReplies.PendingReply?,
        location: String,
        spoofedContent: String,
    ) {
        val context = ApplicationProvider.get()
        StickerUtils.fetchSticker(context, sticker)
            .first { it !is DownloadUtils.DownloadState.InProgress }
            .map { state ->
                if (state is DownloadUtils.DownloadState.Completed) stickerGif(context, sticker, state.file)
                else throw (state as DownloadUtils.DownloadState.Failure).exception
            }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { gif ->
                    val upload = UploadAttachment(
                        id = System.currentTimeMillis(),
                        uri = Uri.fromFile(gif),
                        displayName = "${sticker.name}.gif",
                        spoiler = false,
                        mimeType = "image/gif",
                        size = gif.length(),
                        permissionPersisted = false,
                    )
                    coordinatorProvider()?.send(
                        stickerSendRequest(channel, sticker, author, pendingReply, location, "")
                            .copy(attachments = listOf(upload)),
                    )
                },
                {
                    coordinatorProvider()?.send(
                        stickerSendRequest(channel, sticker, author, pendingReply, location, spoofedContent),
                    )
                },
            )
    }

    private fun stickerGif(context: Context, sticker: Sticker, apng: File): File {
        val directory = File(context.cacheDir, "sticker-gifs").apply { mkdirs() }
        val gif = File(directory, "${sticker.id}.gif")
        if (!gif.isFile || gif.length() == 0L) {
            StickerGifConverter.convert(apng, gif, StickerUtils.DEFAULT_STICKER_SIZE_DP)
        }
        return gif
    }
}

internal fun stickerSendRequest(
    channel: Channel,
    sticker: Sticker,
    author: User,
    pendingReply: StorePendingReplies.PendingReply?,
    location: String = MessageSendLocation.CHAT_INPUT,
    spoofedContent: String? = null,
) = MessageSendCoordinator.Send(
    channelId = channel.id,
    guildId = channel.guildId.takeUnless { it == 0L },
    // A Fake-Nitro sticker travels as a link (or an uploaded GIF) rather than as a sticker id.
    content = spoofedContent.orEmpty(),
    author = author,
    stickers = if (spoofedContent == null) listOf(sticker) else emptyList(),
    reply = pendingReply,
    location = location,
)
