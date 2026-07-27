package com.discord.widgets.guilds.invite

import com.discord.models.domain.ModelInvite
import com.discord.models.invite.InviteUtils
import java.io.Serializable

/**
 * The parts of a resolved [ModelInvite] that survive being put in a `Bundle`.
 *
 * `ModelInvite` carries whole `Guild`/`Channel`/`User` graphs and is not serialisable, so screens
 * that only need to *identify* an invite — the share sheet, the analytics calls — pass this
 * instead.
 *
 * [guildScheduledEventId] is kept only when the invite's scheduled event actually belongs to the
 * invite's channel. A `?event=` suffix pointing at a different channel would resolve to a
 * different destination than the link claims, so the reference drops it.
 */
data class GuildInvite(
    val inviteCode: String,
    val guildId: Long?,
    val channelId: Long?,
    val inviterId: Long?,
    val isStaticInvite: Boolean,
    val expirationTimeMs: Long,
    val guildScheduledEventId: Long?,
) : Serializable {

    fun toLink(): String = InviteUtils.createLinkFromCode(inviteCode, guildScheduledEventId)

    companion object {
        private const val serialVersionUID = 1L

        fun createFromModelInvite(invite: ModelInvite): GuildInvite {
            val event = invite.getGuildScheduledEvent()
            val eventChannelId = event?.channelId
            val eventMatchesInviteChannel =
                eventChannelId == null || eventChannelId == invite.channel?.id

            return GuildInvite(
                inviteCode = requireNotNull(invite.code) { "invite has no code" },
                guildId = invite.guild?.id,
                channelId = invite.channel?.id,
                inviterId = invite.getInviter()?.id,
                isStaticInvite = invite.isStatic(),
                expirationTimeMs = invite.getExpirationTime(),
                guildScheduledEventId = if (eventMatchesInviteChannel) event?.id else null,
            )
        }
    }
}
