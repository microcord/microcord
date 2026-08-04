package com.discord.widgets.stage

import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.utilities.user.UserUtils.getDisplayName

/**
 * A speaker as the stage-discovery cards render them.
 *
 * [displayName] is resolved once at construction from the guild nickname, falling back to the
 * username, so the cards never re-derive it while scrolling.
 */
data class StageCardSpeaker(
    val user: User,
    val guildMember: GuildMember?,
) {
    val displayName: String = user.getDisplayName(guildMember?.nick)
}
