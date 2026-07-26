package com.discord.stores

import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import rx.Observable

/**
 * Who is currently talking in the connected voice channel.
 *
 * Fed from the RTC connection's speaking events ([StoreRtcConnection.SpeakingUserUpdate]), already
 * coalesced into 300 ms batches upstream, so a whole batch produces at most one dispatch here.
 * Leaving voice clears the set — otherwise the last speaking indicator would stay lit on rejoin.
 */
class StoreVoiceSpeaking(
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) : StoreV2() {

    private val speakingUsers = HashSet<Long>()
    private var speakingUsersSnapshot: Set<Long> = HashSet()

    fun getSpeakingUsers(): Set<Long> = speakingUsersSnapshot

    @StoreThread
    fun handleSpeakingUpdates(speakingList: List<StoreRtcConnection.SpeakingUserUpdate>) {
        var changed = false
        speakingList.forEach { (userId, isSpeaking) ->
            val mutated = if (isSpeaking) speakingUsers.add(userId) else speakingUsers.remove(userId)
            if (mutated) changed = true
        }
        if (changed) markChanged()
    }

    @StoreThread
    fun handleVoiceChannelSelected(voiceChannelId: Long) {
        if (voiceChannelId > 0L) return
        speakingUsers.clear()
        markChanged()
    }

    fun observeSpeakingUsers(): Observable<Set<Long>> =
        observationDeck.connectRx(this) { getSpeakingUsers() }.distinctUntilChanged()

    @StoreThread
    override fun snapshotData() {
        super.snapshotData()
        speakingUsersSnapshot = HashSet(speakingUsers)
    }
}
