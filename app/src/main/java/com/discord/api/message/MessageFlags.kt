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
}
