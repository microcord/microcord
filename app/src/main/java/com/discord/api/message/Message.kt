package com.discord.api.message

import com.discord.api.application.Application
import com.discord.api.botuikit.Component
import com.discord.api.channel.Channel
import com.discord.api.guildmember.GuildMember
import com.discord.api.interaction.Interaction
import com.discord.api.message.activity.MessageActivity
import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.call.MessageCall
import com.discord.api.message.embed.MessageEmbed
import com.discord.api.message.reaction.MessageReaction
import com.discord.api.message.role_subscription.RoleSubscriptionData
import com.discord.api.sticker.Sticker
import com.discord.api.sticker.StickerPartial
import com.discord.api.user.User
import com.discord.api.utcdatetime.UtcDateTime

data class Message(
    val id: Long,
    val channelId: Long,
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
    val referencedMessage: Message? = null,
    val interaction: Interaction? = null,
    val thread: Channel? = null,
    val components: List<Component>? = null,
    val call: MessageCall? = null,
    val guildId: Long? = null,
    val member: GuildMember? = null,
    val hit: Boolean? = null,
    val roleSubscriptionData: RoleSubscriptionData? = null,
) {
    override fun toString() =
        "Message(id=$id, channelId=$channelId, guildId=$guildId, content=<redacted>, nonce=<redacted>)"
}

data class MessageReference(
    val guildId: Long?,
    val channelId: Long?,
    val messageId: Long?,
)
