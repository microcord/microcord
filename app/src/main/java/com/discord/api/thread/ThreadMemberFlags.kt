package com.discord.api.thread

/** Bit flags carried by [ThreadMember.flags]. */
object ThreadMemberFlags {
    const val HAS_INTERACTED = 1
    const val ALL_MESSAGES = 2
    const val ONLY_MENTIONS = 4
    const val NO_MESSAGES = 8
}
