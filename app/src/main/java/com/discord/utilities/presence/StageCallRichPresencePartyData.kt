package com.discord.utilities.presence

import com.discord.api.activity.Activity

/**
 * The stage-call details Discord smuggles through an ordinary rich-presence "party".
 *
 * A stage listener broadcasts a normal `LISTENING` activity whose party id encodes where the stage
 * is and what the listener's role in it is, so any client that sees the presence can render a
 * join-able stage card without a separate gateway subscription. [Activity.stageChannelRichPresencePartyData]
 * decodes it; the encoder lives with the presence writer.
 *
 * Wire format: `stage:<guildId>:<channelId>:<flagsHex>:<stageInstanceId>`, with the party size list
 * carrying `[speakerCount, totalParticipants]`.
 */
data class StageCallRichPresencePartyData(
    val stageInstanceId: Long,
    val channelId: Long,
    val userIsSpeaker: Boolean,
    val guildId: Long,
    val guildIsPartnered: Boolean,
    val guildIsVerified: Boolean,
    val speakerCount: Long,
    /** Participants who are *not* speakers. Derived, never sent directly. */
    val audienceSize: Long,
)

/** Party-id prefix that marks a `LISTENING` activity as a stage broadcast. */
internal const val STAGE_PRESENCE_PARTY_PREFIX = "stage:"

private const val STAGE_PRESENCE_FLAG_USER_IS_SPEAKER = 1L
private const val STAGE_PRESENCE_FLAG_GUILD_IS_PARTNERED = 2L
private const val STAGE_PRESENCE_FLAG_GUILD_IS_VERIFIED = 4L

/**
 * The stage this activity is broadcasting, or `null` when it is not a stage broadcast.
 *
 * Every parse failure — wrong prefix, short id, unparsable number — yields `null` rather than
 * throwing, because the payload is attacker-influenced text from another user's client.
 */
val Activity.stageChannelRichPresencePartyData: StageCallRichPresencePartyData?
    get() = unpackStageChannelParty(this)

private fun unpackStageChannelParty(activity: Activity?): StageCallRichPresencePartyData? {
    val party = activity?.party ?: return null
    val partyId = party.id ?: return null
    if (!partyId.startsWith(STAGE_PRESENCE_PARTY_PREFIX)) return null

    // The size pair is `[speakers, everyone]`. Anything but exactly two entries is treated as an
    // empty stage rather than discarded, which is what the reference does.
    val size = party.size
    val (speakerCount, participantCount) = when {
        size.isNullOrEmpty() -> 0L to 0L
        size.size != 2 -> 0L to 0L
        else -> size[0] to size[1]
    }

    return runCatching {
        val audienceSize = (participantCount - speakerCount).coerceAtLeast(0L)
        val parts = partyId.split(':')
        val guildId = parts[1].toLong()
        val channelId = parts[2].toLong()
        val flags = parts[3].toLong(radix = 16)
        val stageInstanceId = parts[4].toLong()

        StageCallRichPresencePartyData(
            stageInstanceId = stageInstanceId,
            channelId = channelId,
            userIsSpeaker = (flags and STAGE_PRESENCE_FLAG_USER_IS_SPEAKER) != 0L,
            guildId = guildId,
            guildIsPartnered = (flags and STAGE_PRESENCE_FLAG_GUILD_IS_PARTNERED) != 0L,
            guildIsVerified = (flags and STAGE_PRESENCE_FLAG_GUILD_IS_VERIFIED) != 0L,
            speakerCount = speakerCount,
            audienceSize = audienceSize,
        )
    }.getOrNull()
}
