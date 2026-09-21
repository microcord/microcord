package com.discord.api.message.embed

import com.discord.api.botuikit.Component
import com.discord.api.utcdatetime.UtcDateTime
import com.google.gson.annotations.SerializedName

data class MessageEmbed(
    val title: String?,
    val type: EmbedType?,
    val description: String?,
    val url: String?,
    val timestamp: UtcDateTime?,
    val color: Int?,
    val footer: EmbedFooter?,
    val image: EmbedImage?,
    val thumbnail: EmbedThumbnail?,
    val video: EmbedVideo?,
    val provider: EmbedProvider?,
    val author: EmbedAuthor?,
    val fields: List<EmbedField>?,
    /** Present only on `components` link-preview embeds. */
    val components: List<Component>? = null,
    val flags: Int? = null,
)

data class EmbedImage(val url: String?, val proxyUrl: String?, val width: Int?, val height: Int?)
data class EmbedThumbnail(val url: String?, val proxyUrl: String?, val height: Int?, val width: Int?)
data class EmbedVideo(val url: String?, val proxyUrl: String?, val height: Int?, val width: Int?)
data class EmbedAuthor(val name: String?, val url: String?, val iconUrl: String?, val proxyIconUrl: String?)
data class EmbedFooter(val text: String, val iconUrl: String?, val proxyIconUrl: String?)
data class EmbedProvider(val name: String?, val url: String?)
data class EmbedField(val name: String, val value: String, @SerializedName("inline") val inline: Boolean?)
