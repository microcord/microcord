package com.discord.models.message

import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.sticker.Sticker
import com.discord.api.sticker.StickerPartial
import com.discord.api.user.User

data class Message(
    val id: Long,
    val author: User?,
    val content: String?,
    val mentions: List<User>? = null,
    val attachments: List<MessageAttachment>? = null,
    val embeds: List<PreviewEmbed>? = null,
    val stickers: List<Sticker>? = null,
    val stickerItems: List<StickerPartial>? = null,
) {
    fun hasAttachments() = !attachments.isNullOrEmpty()
    fun hasEmbeds() = !embeds.isNullOrEmpty()
    fun hasStickers() = !stickers.isNullOrEmpty() || !stickerItems.isNullOrEmpty()
}

data class PreviewEmbed(val type: String? = null)
