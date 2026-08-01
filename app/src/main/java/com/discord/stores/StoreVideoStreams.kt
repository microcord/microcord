package com.discord.stores

import com.discord.api.voice.state.VoiceState
import com.discord.rtcconnection.VideoMetadata
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import rx.Observable

/**
 * The video tracks the media engine has for each user in the connected voice channel.
 *
 * A user can publish two tracks at once — their camera (`callStream*`) and a screen share
 * (`applicationStream*`) — so both are tracked separately and combined in [UserStreams]. Entries are
 * removed as soon as they become empty so an absent key means "no video", which is what the
 * participant grid keys its layout off.
 *
 * State here is engine-derived, not gateway-derived: it is dropped when we leave voice
 * ([handleVoiceChannelSelected]) or when a user leaves ([handleVoiceStateUpdates]).
 */
class StoreVideoStreams(
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) : StoreV2() {

    private val userStreamsByUser = HashMap<Long, UserStreams>()
    private var userStreamsByUserSnapshot: Map<Long, UserStreams> = emptyMap()

    data class UserStreams(
        val callStreamId: Int? = null,
        val callStreamMetadata: VideoMetadata? = null,
        val applicationStreamId: Int? = null,
        val applicationStreamMetadata: VideoMetadata? = null,
    ) {
        /**
         * The reference caches this in a constructor-assigned field. That makes [Empty] itself
         * report `false`, because the companion field is still null while [Empty] is being built.
         * Nothing depends on that quirk — every write goes through `copy()` — so it is computed
         * here instead, which cannot mis-read a half-initialised companion.
         */
        val isEmpty: Boolean get() = this == Empty

        companion object {
            val Empty = UserStreams()
        }
    }

    private fun currentUserStreams(userId: Long): UserStreams =
        userStreamsByUser[userId] ?: UserStreams.Empty

    private fun setUserStreams(userId: Long, userStreams: UserStreams) {
        if (!userStreams.isEmpty) userStreamsByUser[userId] = userStreams else userStreamsByUser.remove(userId)
        markChanged()
    }

    /** Resolves the screen-share owner from the engine's stream id, which is all a sink knows. */
    @StoreThread
    fun findUserForApplicationStream(streamId: Int): Long? =
        userStreamsByUser.entries.firstOrNull { it.value.applicationStreamId == streamId }?.key

    @StoreThread
    fun handleApplicationStreamUpdate(userId: Long, streamId: Int?) =
        setUserStreams(userId, currentUserStreams(userId).copy(applicationStreamId = streamId))

    @StoreThread
    fun handleApplicationStreamVideoMetadataUpdate(metadata: VideoMetadata) =
        setUserStreams(
            metadata.userID,
            currentUserStreams(metadata.userID).copy(applicationStreamMetadata = metadata),
        )

    @StoreThread
    fun handleVideoMetadataUpdate(metadata: VideoMetadata) =
        setUserStreams(
            metadata.userID,
            currentUserStreams(metadata.userID).copy(callStreamMetadata = metadata),
        )

    @StoreThread
    fun handleVideoStreamUpdate(userId: Long, streamId: Int?) =
        setUserStreams(userId, currentUserStreams(userId).copy(callStreamId = streamId))

    @StoreThread
    fun handleVoiceChannelSelected(channelId: Long) {
        if (channelId != 0L || userStreamsByUser.isEmpty()) return
        userStreamsByUser.clear()
        markChanged()
    }

    @StoreThread
    fun handleVoiceStateUpdates(voiceState: VoiceState) {
        if (!voiceState.isRemoveSignal) return
        if (userStreamsByUser.remove(voiceState.userId) != null) markChanged()
    }

    fun observeUserStreams(): Observable<Map<Long, UserStreams>> =
        observationDeck.connectRx(this) { userStreamsByUserSnapshot }

    @StoreThread
    override fun snapshotData() {
        super.snapshotData()
        userStreamsByUserSnapshot = HashMap(userStreamsByUser)
    }
}
