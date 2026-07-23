package com.discord.api.message.embed

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
)

enum class EmbedType(val apiValue: String) {
    ARTICLE("article"),
    IMAGE("image"),
    VIDEO("video"),
    TWEET("tweet"),
    LINK("link"),
    HTML("html"),
    FILE("file"),
    GIFV("gifv"),
    RICH("rich"),
    APPLICATION_NEWS("application_news"),
    UNKNOWN("unknown"),
}

data class EmbedImage(val url: String?, val proxyUrl: String?, val width: Int?, val height: Int?)
data class EmbedThumbnail(val url: String?, val proxyUrl: String?, val height: Int?, val width: Int?)
data class EmbedVideo(val url: String?, val proxyUrl: String?, val height: Int?, val width: Int?)
data class EmbedAuthor(val name: String?, val url: String?, val iconUrl: String?, val proxyIconUrl: String?)
data class EmbedFooter(val text: String, val iconUrl: String?, val proxyIconUrl: String?)
data class EmbedProvider(val name: String?, val url: String?)
data class EmbedField(val name: String, val value: String, @SerializedName("inline") val inline: Boolean?)
