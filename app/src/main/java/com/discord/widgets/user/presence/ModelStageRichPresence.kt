package com.discord.widgets.user.presence

import com.discord.stores.StoreStream
import com.discord.stores.StoreUserPresence
import com.discord.utilities.presence.stageChannelRichPresencePartyData
import com.discord.utilities.presence.isStageChannelActivity
import rx.Observable

/**
 * A live stage, as advertised by one user's presence.
 *
 * Discord discovers stages you are not subscribed to purely from other people's presences: a stage
 * listener broadcasts a `LISTENING` activity whose party id encodes the stage's location and size.
 * Rows for the same stage seen through several users are merged by the consumer, which is why
 * [knownUserIds] and [speakerIds] are sets rather than counts — [speakerCount] and [audienceSize]
 * come from the stage itself and do not accumulate.
 */
data class ModelStageRichPresence(
    val channelId: Long,
    val stageInstanceId: Long,
    val userIsSpeaker: Boolean,
    val guildId: Long,
    val guildIsPartnered: Boolean,
    val guildIsVerified: Boolean,
    val guildName: String,
    val guildIcon: String?,
    val topic: String,
    /** Users this client has actually seen in the stage — one per source presence. */
    val knownUserIds: Set<Long>,
    val speakerIds: Set<Long>,
    val speakerCount: Long,
    val audienceSize: Long,
) {

    companion object {

        /** Every stage currently visible through any cached presence. */
        @JvmStatic
        @JvmOverloads
        fun observeStageRichPresence(
            storeUserPresence: StoreUserPresence = StoreStream.getPresences(),
        ): Observable<List<ModelStageRichPresence>> =
            storeUserPresence.observeAllPresences().map { userPresences ->
                userPresences.keys.asSequence()
                    .mapNotNull { userId -> fromPresence(userId, userPresences) }
                    .toList()
            }

        private fun fromPresence(
            userId: Long,
            userPresences: Map<Long, com.discord.models.presence.Presence>,
        ): ModelStageRichPresence? {
            val activity = userPresences[userId]?.activities
                ?.firstOrNull { it.isStageChannelActivity() }
                ?: return null
            val party = activity.stageChannelRichPresencePartyData ?: return null
            // The stage broadcast packs the guild into the activity's *small* asset slot.
            return ModelStageRichPresence(
                channelId = party.channelId,
                stageInstanceId = party.stageInstanceId,
                userIsSpeaker = party.userIsSpeaker,
                guildId = party.guildId,
                guildIsPartnered = party.guildIsPartnered,
                guildIsVerified = party.guildIsVerified,
                guildName = activity.assets?.smallText.orEmpty(),
                guildIcon = activity.assets?.smallImage,
                topic = activity.name,
                knownUserIds = setOf(userId),
                speakerIds = if (party.userIsSpeaker) setOf(userId) else emptySet(),
                speakerCount = party.speakerCount,
                audienceSize = party.audienceSize,
            )
        }
    }
}
