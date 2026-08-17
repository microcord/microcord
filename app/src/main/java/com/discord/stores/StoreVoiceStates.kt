package com.discord.stores

import com.discord.api.guild.Guild
import com.discord.api.voice.state.VoiceState
import com.discord.models.domain.ModelPayload
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import rx.Observable

/**
 * Every voice state the client knows about, keyed guild -> user.
 *
 * Private-call voice states have no guild, so they are filed under [PRIVATE_CHANNELS_GUILD_ID]
 * (`0L`) — the same convention `StoreChannels` uses for DM channels. Observers ask for a guild and
 * optionally a channel; the store never indexes by channel because a user can only be in one voice
 * channel per guild at a time, so scanning a guild's map is cheap.
 *
 * Snapshots are rebuilt per dirty guild rather than wholesale: a busy guild must not force every
 * other guild's map to be copied on each dispatch.
 */
class StoreVoiceStates(
    private val notifyVoiceStatesUpdated: (guildId: Long, channelId: Long, userId: Long) -> Unit,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) : StoreV2() {

    private val voiceStates = HashMap<Long, HashMap<Long, VoiceState>>()
    private var voiceStatesSnapshot: Map<Long, Map<Long, VoiceState>> = HashMap(voiceStates)
    private val dirtyGuildIds = HashSet<Long>()

    private var myUserId: Long? = null
    private var sessionId: String? = null

    @StoreThread
    private fun clear() {
        dirtyGuildIds.addAll(voiceStates.keys)
        voiceStates.clear()
        markChanged()
    }

    /**
     * Applies one voice state.
     *
     * Two behaviours are load-bearing:
     * - A voice state for *us* from a **different session** means another client of ours took over
     *   the channel, so the entry is dropped instead of stored; keeping it would make this device
     *   render itself as connected to a call it is not in.
     * - A state with no channel is a leave signal ([isRemoveSignal]) and removes the entry.
     *
     * [notifyVoiceStatesUpdated] is called with the channel the user is *now* associated with (the
     * one they left when removed, the one they were previously in otherwise) so downstream stores
     * can recompute that channel.
     */
    @StoreThread
    private fun updateVoiceState(voiceState: VoiceState, guildId: Long? = null) {
        val effectiveGuildId = guildId ?: voiceState.guildId
        val userId = voiceState.userId

        val isStaleSelfState =
            voiceState.sessionId != sessionId && myUserId != null && userId == myUserId
        if (isStaleSelfState) {
            val guildStates = voiceStates[effectiveGuildId]
            if (guildStates != null && guildStates.remove(userId) != null) {
                dirtyGuildIds.add(effectiveGuildId)
            }
        } else {
            var changed = false
            var affectedChannelId = 0L
            val guildStates = voiceStates[effectiveGuildId] ?: HashMap()

            if (voiceState.isRemoveSignal) {
                val removed = guildStates.remove(userId)
                if (removed != null) {
                    affectedChannelId = removed.channelId ?: 0L
                    changed = true
                }
            } else if (voiceState != guildStates[userId]) {
                affectedChannelId = guildStates[userId]?.channelId ?: 0L
                guildStates[userId] = voiceState
                changed = true
            }

            voiceStates[effectiveGuildId] = guildStates
            if (changed) {
                dirtyGuildIds.add(effectiveGuildId)
                notifyVoiceStatesUpdated(effectiveGuildId, affectedChannelId, userId)
            }
        }

        if (dirtyGuildIds.isNotEmpty()) markChanged()
    }

    fun get(): Map<Long, Map<Long, VoiceState>> = voiceStatesSnapshot

    fun getForChannel(guildId: Long, channelId: Long): Map<Long, VoiceState> =
        voiceStatesSnapshot[guildId].orEmpty().filterValues { it.channelId == channelId }

    @StoreThread
    internal fun getInternal(): Map<Long, Map<Long, VoiceState>> = voiceStates

    @StoreThread
    fun handleAuthToken(authToken: String?) {
        if (authToken == null) clear()
    }

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        sessionId = payload.sessionId
        myUserId = payload.me?.id
        clear()
        payload.guilds?.forEach { guild ->
            guild.voiceStates?.forEach { updateVoiceState(it, guild.id) }
        }
    }

    @StoreThread
    fun handleGuildAdd(guild: Guild) {
        guild.voiceStates?.forEach { updateVoiceState(it, guild.id) }
    }

    @StoreThread
    fun handleGuildRemove(guild: Guild) {
        voiceStates.remove(guild.id)
        dirtyGuildIds.add(guild.id)
        markChanged()
    }

    @StoreThread
    fun handleVoiceStateUpdate(voiceState: VoiceState) = updateVoiceState(voiceState)

    /** Batched entry point for `VOICE_STATE_UPDATE`; see `StoreGatewayConnection.voiceStateUpdate`. */
    @StoreThread
    fun handleVoiceStateUpdates(voiceStates: List<VoiceState>, guildId: Long? = null) =
        voiceStates.forEach { updateVoiceState(it, guildId) }

    fun observe(guildId: Long): Observable<Map<Long, VoiceState>> =
        observationDeck.connectRx(this) { voiceStatesSnapshot[guildId].orEmpty() }

    fun observe(guildId: Long, channelId: Long): Observable<Map<Long, VoiceState>> =
        observe(guildId)
            .map { states -> states.filterValues { it.channelId == channelId } }
            .distinctUntilChanged()

    /** Private calls carry no guild, so their states live under [PRIVATE_CHANNELS_GUILD_ID]. */
    fun observeForPrivateChannels(channelId: Long): Observable<Map<Long, VoiceState>> =
        observe(PRIVATE_CHANNELS_GUILD_ID, channelId)

    @StoreThread
    override fun snapshotData() {
        super.snapshotData()
        val updated = HashMap<Long, Map<Long, VoiceState>>(voiceStates.size)
        for ((guildId, states) in voiceStates) {
            if (guildId in dirtyGuildIds) {
                updated[guildId] = HashMap(states)
            } else {
                voiceStatesSnapshot[guildId]?.let { updated[guildId] = it }
            }
        }
        voiceStatesSnapshot = updated
        dirtyGuildIds.clear()
    }

    companion object {
        const val PRIVATE_CHANNELS_GUILD_ID = 0L
    }
}
