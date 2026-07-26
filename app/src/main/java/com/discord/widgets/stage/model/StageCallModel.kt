package com.discord.widgets.stage.model

import com.discord.api.channel.Channel
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.role.GuildRole
import com.discord.api.stageinstance.StageInstance
import com.discord.api.voice.state.StageRequestToSpeakState
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.stores.StoreStageChannels
import com.discord.stores.StoreStream
import com.discord.stores.StoreVoiceParticipants.VoiceUser
import com.discord.stores.stageRequestToSpeakState
import com.discord.utilities.guilds.RoleUtils
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.widgets.stage.StageRoles
import com.discord.widgets.stage.StageStoreAccess
import com.discord.widgets.voice.fullscreen.stage.StageCallItem
import java.util.concurrent.TimeUnit
import rx.Observable

/**
 * Everything the fullscreen stage surface renders, already split into the three sections it draws:
 * speakers, video/stream tiles, and the audience list.
 *
 * A connected speaker lands in exactly one of [speakerItems] or [mediaParticipants] — the moment
 * they turn a camera on they move out of the speaker grid and into the media grid. A screen share
 * is additive, so a speaker who is streaming *and* on camera contributes two [mediaParticipants]
 * and no [speakerItems] entry.
 */
data class StageCallModel(
    val myStageRoles: StageRoles,
    val speakerItems: List<StageCallItem.SpeakerItem>,
    val mediaParticipants: List<StageMediaParticipant>,
    val audience: List<StageCallItem.AudienceItem>,
    /** Speakers currently talking, excluding the local user. Drives the "X is speaking" strip. */
    val speakingVoiceUsers: List<VoiceUser>,
    val requestingToSpeakCount: Int,
    val stageInstance: StageInstance?,
    val numBlockedUsers: Int,
    val guild: Guild?,
    val isLurking: Boolean,
    val numSpeakers: Int,
) {
    companion object {

        /** The reference throttles the participant map; it re-emits on every speaking frame. */
        private const val PARTICIPANTS_THROTTLE_MS = 250L

        /** Speakers are laid out at most three to a row. */
        private const val MAX_SPEAKERS_PER_ROW = 3

        /** Wire value of the "blocked" relationship type. */
        private const val RELATIONSHIP_TYPE_BLOCKED = 2

        /**
         * The stage model for [channelId], or `null` when the channel is unknown or is not a stage
         * channel.
         */
        @JvmStatic
        fun observeStageCallModel(channelId: Long): Observable<StageCallModel?> =
            StoreStream.getChannels().observeChannel(channelId).switchMap { channel ->
                when {
                    channel == null -> Observable.just(null)
                    !channel.isStageVoiceChannel() -> Observable.just(null)
                    !StageStoreAccess.isInstalled -> Observable.just(null)
                    else -> observeForStageChannel(channelId, channel)
                }
            }

        private fun observeForStageChannel(
            channelId: Long,
            channel: Channel,
        ): Observable<StageCallModel?> = Observable.combineLatest(
            StoreStream.getVoiceParticipants().get(channel.id)
                .leadingEdgeThrottle(PARTICIPANTS_THROTTLE_MS, TimeUnit.MILLISECONDS),
            StoreStream.getGuilds().observeComputed(channel.guildId),
            StoreStream.getGuilds().observeRoles(channel.guildId),
            StoreStream.getGuilds().observeGuild(channel.guildId),
            StoreStream.getUserRelationships().observeForType(RELATIONSHIP_TYPE_BLOCKED),
            StoreStream.getStageInstances().observeStageInstanceForChannel(channelId),
            StageStoreAccess.requireLurking().isLurkingObs(channel.guildId),
        ) { participants, guildMembers, guildRoles, guild, blockedUsers, stageInstance, isLurking ->
            create(
                channel = channel,
                guildMembers = guildMembers,
                guildRoles = guildRoles,
                guild = guild,
                participants = participants,
                blockedUsers = blockedUsers.keys,
                stageInstance = stageInstance,
                isLurking = isLurking,
            )
        }

        /**
         * Splits the connected participants into the stage's three sections.
         *
         * The one subtle part is [StageCallItem.SpeakerItem.positionKey]. Speaker tiles are drawn
         * three to a row and the row width changes as speakers join, so the key encodes the tile's
         * column *and*, while there are three or fewer speakers in total, how many there are — that
         * makes the diffing rebind a tile whose width is about to change. Past three speakers the
         * row is always full, so the count drops out of the key and only the column matters.
         */
        @Suppress("LongParameterList")
        internal fun create(
            channel: Channel,
            guildMembers: Map<Long, GuildMember>,
            guildRoles: Map<Long, GuildRole>,
            guild: Guild?,
            participants: Map<Long, VoiceUser>,
            blockedUsers: Set<Long>,
            stageInstance: StageInstance?,
            isLurking: Boolean,
            stagesStore: StoreStageChannels = StageStoreAccess.requireStageChannels(),
        ): StageCallModel {
            val connected = participants.values.filter { it.isConnected }
            val rolesByUser = connected.associate { voiceUser ->
                voiceUser.user.id to
                    (stagesStore.getUserRoles(voiceUser.user.id, channel.id) ?: StageRoles.AUDIENCE)
            }

            val numSpeakers = rolesByUser.values.count { it.isSpeaker }
            // Only speakers *without* video occupy the speaker grid, so that — not numSpeakers —
            // decides how wide each speaker tile is.
            val speakersInGrid = connected.count { voiceUser ->
                rolesByUser[voiceUser.user.id]?.isSpeaker == true &&
                    voiceUser.voiceState?.selfVideo != true
            }
            val speakersPerRow = minOf(speakersInGrid, MAX_SPEAKERS_PER_ROW)

            val speakerItems = mutableListOf<StageCallItem.SpeakerItem>()
            val mediaParticipants = mutableListOf<StageMediaParticipant>()
            val audienceItems = mutableListOf<StageCallItem.AudienceItem>()
            var requestingToSpeakCount = 0
            var numBlockedUsers = 0

            for (voiceUser in connected) {
                val userId = voiceUser.user.id
                if (voiceUser.voiceState.stageRequestToSpeakState ==
                    StageRequestToSpeakState.REQUESTED_TO_SPEAK
                ) {
                    requestingToSpeakCount++
                }
                val isBlocked = blockedUsers.contains(userId)
                if (isBlocked) numBlockedUsers++

                val roles = rolesByUser[userId]
                if (roles?.isSpeaker != true) {
                    audienceItems += StageCallItem.AudienceItem(
                        voiceUser = voiceUser,
                        channel = channel,
                        hoistedGuildRole =
                            RoleUtils.getHighestHoistedRole(guildRoles, guildMembers[userId]),
                        isBlocked = isBlocked,
                    )
                    continue
                }

                if (voiceUser.streamContext != null) {
                    mediaParticipants += StageMediaParticipant(
                        id = "STREAM-$userId",
                        voiceUser = voiceUser,
                        channel = channel,
                        // A screen share never carries the moderator crown; the owner's own tile
                        // does.
                        isModerator = false,
                        isBlocked = isBlocked,
                        mediaType = StageCallItem.MediaType.STREAM,
                    )
                }

                if (voiceUser.voiceState?.selfVideo == true) {
                    mediaParticipants += StageMediaParticipant(
                        id = "USER-$userId",
                        voiceUser = voiceUser,
                        channel = channel,
                        isModerator = roles.isModerator,
                        isBlocked = isBlocked,
                        mediaType = StageCallItem.MediaType.USER,
                    )
                } else {
                    val column = speakerItems.size % MAX_SPEAKERS_PER_ROW
                    speakerItems += StageCallItem.SpeakerItem(
                        voiceUser = voiceUser,
                        channel = channel,
                        isModerator = roles.isModerator,
                        positionKey = if (numSpeakers > MAX_SPEAKERS_PER_ROW) {
                            column.toString()
                        } else {
                            "$column-$numSpeakers"
                        },
                        speakersPerRow = speakersPerRow,
                        isBlocked = isBlocked,
                    )
                }
            }

            return StageCallModel(
                myStageRoles = stagesStore.getMyRoles(channel.id) ?: StageRoles.AUDIENCE,
                speakerItems = speakerItems,
                mediaParticipants = mediaParticipants,
                audience = audienceItems.sorted(),
                speakingVoiceUsers = speakerItems
                    .filter { it.voiceUser.isSpeaking && !it.voiceUser.isMe }
                    .map { it.voiceUser },
                requestingToSpeakCount = requestingToSpeakCount,
                stageInstance = stageInstance,
                numBlockedUsers = numBlockedUsers,
                guild = guild,
                isLurking = isLurking,
                numSpeakers = numSpeakers,
            )
        }
    }
}
