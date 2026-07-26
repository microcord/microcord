package com.discord.widgets.chat.list.entries

import com.discord.api.channel.Channel
import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.embed.MessageEmbed
import com.discord.api.role.GuildRole
import com.discord.api.sticker.BaseSticker
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.stores.StoreMessageState

/**
 * The rows produced from a message's attachments, embeds and stickers.
 *
 * They all key on the message id alone, matching the reference: several embed rows for one message
 * therefore share a diff key. That is deliberate — the whole embed block of a message is treated as
 * one unit by the differ, so adding an embed rebinds the block rather than shuffling rows.
 */

/**
 * One uploaded file. [embedIndex] positions this card within the message's combined
 * attachment-then-embed sequence and is what revealed-spoiler state is keyed on.
 */
data class AttachmentEntry(
    val embedIndex: Int,
    val guildId: Long,
    val message: Message,
    val messageState: StoreMessageState.State?,
    val attachment: MessageAttachment,
    val isBlockedExpanded: Boolean,
    val allowAnimatedEmojis: Boolean,
    val autoPlayGifs: Boolean,
    val isThreadStarterMessage: Boolean,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.MESSAGE_ATTACHMENT
    override val key: String = "${ChatListEntry.MESSAGE_ATTACHMENT}${message.id}"

    override fun isInExpandedBlockedMessageChunk(): Boolean = isBlockedExpanded
}

/** One rich embed: link preview, bot embed, or article card. */
data class EmbedEntry(
    val embedIndex: Int,
    val guildId: Long,
    val message: Message,
    val messageState: StoreMessageState.State?,
    val embed: MessageEmbed,
    val isBlockedExpanded: Boolean,
    val allowAnimatedEmojis: Boolean,
    val autoPlayGifs: Boolean,
    val isThreadStarterMessage: Boolean,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.MESSAGE_EMBED
    override val key: String = "${ChatListEntry.MESSAGE_EMBED}${message.id}"

    override fun isInExpandedBlockedMessageChunk(): Boolean = isBlockedExpanded
}

/**
 * The embed attached to an AutoMod action system message. It renders differently from an ordinary
 * embed — it carries the blocked content plus a "report a false positive" call to action — so it
 * gets its own row and needs the author/role context an ordinary embed does not.
 */
data class AutoModSystemMessageEmbedEntry(
    val channel: Channel,
    val message: Message,
    val messageState: StoreMessageState.State?,
    val embed: MessageEmbed,
    val author: GuildMember?,
    val animateEmojis: Boolean,
    val roles: Map<Long, GuildRole>?,
    val nickOrUsernames: Map<Long, String>,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.AUTOMOD_SYSTEM_MESSAGE
    override val key: String = "${ChatListEntry.AUTOMOD_SYSTEM_MESSAGE}${message.id}"
}

/** One sticker sent with the message. Keyed per sticker so several stickers diff independently. */
data class StickerEntry(val message: Message, val sticker: BaseSticker) : ChatListEntry() {
    override val type: Int = ChatListEntry.STICKER
    override val key: String =
        "${ChatListEntry.STICKER} -- ${message.id} -- ${sticker.getStickerId()}"
}
