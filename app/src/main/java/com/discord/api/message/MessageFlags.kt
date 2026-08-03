package com.discord.api.message

/** Bit flags carried by [Message.flags]. */
object MessageFlags {
    const val CROSSPOSTED = 1L
    const val IS_CROSSPOST = 2L
    const val SUPPRESS_EMBEDS = 4L
    const val SOURCE_MESSAGE_DELETED = 8L
    const val URGENT = 16L
    const val HAS_THREAD = 32L
    const val EPHEMERAL = 64L
    const val LOADING = 128L
    const val FAILED_TO_MENTION_SOME_ROLES_IN_THREAD = 256L
    const val GUILD_FEED_HIDDEN = 512L
    const val SHOULD_SHOW_LINK_NOT_DISCORD_WARNING = 1_024L
    const val SUPPRESS_NOTIFICATIONS = 4_096L
    const val IS_VOICE_MESSAGE = 8_192L
    const val HAS_SNAPSHOT = 16_384L
    const val IS_COMPONENTS_V2 = 32_768L
    const val IS_GUILD_OFFICIAL = 524_288L
}
