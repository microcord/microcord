package com.discord.models.member

object GuildMemberFlags {
    const val COMPLETED_ONBOARDING = 1 shl 1
    const val STARTED_ONBOARDING = 1 shl 3

    fun has(flags: Int, flag: Int) = flags and flag == flag
}
