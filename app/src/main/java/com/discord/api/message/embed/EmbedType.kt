package com.discord.api.message.embed

/**
 * Snapshot of `EmbedType.values()`, taken once so [EmbedTypeTypeAdapter] does not clone the
 * constant array for every embed it reads.
 */
internal val EMBED_TYPES: Array<EmbedType> = EmbedType.values()

/** The `type` discriminator of a [MessageEmbed]. */
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
    COMPONENTS("components"),
    UNKNOWN("unknown"),
}
