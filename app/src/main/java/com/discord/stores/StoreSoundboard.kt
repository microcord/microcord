package com.discord.stores

import com.discord.api.soundboard.SoundboardSound
import com.discord.api.soundboard.SoundboardSoundDelete
import com.discord.api.soundboard.SoundboardSounds
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.soundboard.SoundboardConstants.DEFAULT_SOUND_GUILD_ID
import rx.Observable

/**
 * Every soundboard sound the client knows about, keyed `guildId -> sounds`.
 *
 * The built-in sounds live under [DEFAULT_SOUND_GUILD_ID] alongside the guild ones, so every read
 * is a single lookup regardless of where a sound came from.
 *
 * Two sources feed this, and [maybeFetchSounds] starts both: the built-ins come from
 * `/soundboard-default-sounds`, and a guild's sounds arrive as a `SOUNDBOARD_SOUNDS` dispatch after
 * the gateway is asked for them. Only guilds with no entry are requested, so reopening the picker
 * costs nothing.
 */
class StoreSoundboard(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val guildIds: () -> Collection<Long>,
    private val requestGuildSounds: (List<Long>) -> Boolean,
    private val restApi: () -> RestAPIInterface? = { RestAPI.apiOrNull },
) : StoreV2() {

    private enum class FetchState { NOT_FETCHED, FETCHING, FETCHED }

    private val sounds = LinkedHashMap<Long, List<SoundboardSound>>()
    private var soundsSnapshot: Map<Long, List<SoundboardSound>> = emptyMap()

    private var guildFetchState = FetchState.NOT_FETCHED
    private var defaultFetchState = FetchState.NOT_FETCHED
    private var requested = false
    private var loadedSnapshot = false

    /** Reference-counted: the same sound can be playing from two people at once. */
    private val playingSoundIds = HashMap<Long, Int>()
    private var playingSoundIdsSnapshot: Set<Long> = emptySet()

    // region reads

    /**
     * @property loaded false until a [maybeFetchSounds] has run and settled, so an empty [sounds]
     * can be told apart from one that has simply not arrived yet.
     */
    data class State(val sounds: Map<Long, List<SoundboardSound>>, val loaded: Boolean)

    fun getSound(guildId: Long, soundId: Long): SoundboardSound? =
        soundsSnapshot[guildId]?.firstOrNull { it.soundId == soundId }

    fun observeSounds(): Observable<State> =
        observationDeck.connectRx(this) { State(soundsSnapshot, loadedSnapshot) }.distinctUntilChanged()

    fun observePlayingSoundIds(): Observable<Set<Long>> =
        observationDeck.connectRx(this) { playingSoundIdsSnapshot }.distinctUntilChanged()

    /**
     * Requests anything missing, then emits once nothing is in flight.
     *
     * A caller that needs the sounds *now* — the RPC command, which has to answer with them —
     * subscribes to this; the picker observes [observeSounds] and renders what it has so far.
     */
    fun fetchSounds(): Observable<Map<Long, List<SoundboardSound>>> {
        maybeFetchSounds()
        return observeSounds().filter(State::loaded).map(State::sounds).take(1)
    }

    // endregion

    fun maybeFetchSounds() = dispatcher.schedule { handleFetchSounds() }

    @StoreThread
    internal fun handleFetchSounds() {
        requested = true
        if (defaultFetchState == FetchState.NOT_FETCHED) requestDefaultSounds()
        val missing = guildIds().filterNot(sounds::containsKey)
        // A request made before the gateway session exists is dropped, and no dispatch would ever
        // arrive to clear the fetching state.
        if (missing.isNotEmpty() && requestGuildSounds(missing)) guildFetchState = FetchState.FETCHING
        markChanged()
    }

    // region gateway input

    @StoreThread
    fun handleSoundboardSounds(payload: SoundboardSounds) {
        guildFetchState = FetchState.FETCHED
        setSounds(payload.guildId, payload.soundboardSounds)
    }

    @StoreThread
    fun handleSoundCreateOrUpdate(sound: SoundboardSound) {
        val guildId = sound.guildId ?: return
        val soundId = sound.soundId ?: return
        val existing = sounds[guildId] ?: return
        val index = existing.indexOfFirst { it.soundId == soundId }
        setSounds(
            guildId,
            if (index < 0) existing + sound else existing.toMutableList().apply { set(index, sound) },
        )
    }

    @StoreThread
    fun handleSoundDelete(delete: SoundboardSoundDelete) {
        val existing = sounds[delete.guildId] ?: return
        val remaining = existing.filterNot { it.soundId == delete.soundId }
        if (remaining.size != existing.size) setSounds(delete.guildId, remaining)
    }

    @StoreThread
    fun handleGuildRemove(guildId: Long) {
        if (sounds.remove(guildId) != null) markChanged()
    }

    // endregion

    // region local playback

    fun reportSoundStartedPlaying(soundId: Long) = dispatcher.schedule { handleSoundPlayStart(soundId) }

    fun reportSoundFinishedPlaying(soundId: Long) = dispatcher.schedule { handleSoundPlayEnd(soundId) }

    @StoreThread
    internal fun handleSoundPlayStart(soundId: Long) {
        playingSoundIds[soundId] = (playingSoundIds[soundId] ?: 0) + 1
        markChanged()
    }

    @StoreThread
    internal fun handleSoundPlayEnd(soundId: Long) {
        val remaining = (playingSoundIds[soundId] ?: 0) - 1
        if (remaining <= 0) playingSoundIds.remove(soundId) else playingSoundIds[soundId] = remaining
        markChanged()
    }

    /** Leaving a call ends every sound with it; the end reports would otherwise never arrive. */
    @StoreThread
    fun handleVoiceChannelSelected() {
        if (playingSoundIds.isEmpty()) return
        playingSoundIds.clear()
        markChanged()
    }

    // endregion

    override fun snapshotData() {
        super.snapshotData()
        soundsSnapshot = LinkedHashMap(sounds)
        playingSoundIdsSnapshot = playingSoundIds.keys.toSet()
        val fetching =
            guildFetchState == FetchState.FETCHING || defaultFetchState == FetchState.FETCHING
        loadedSnapshot = requested && !fetching
    }

    @StoreThread
    internal fun reset() {
        sounds.clear()
        playingSoundIds.clear()
        guildFetchState = FetchState.NOT_FETCHED
        defaultFetchState = FetchState.NOT_FETCHED
        requested = false
        markChanged()
    }

    @StoreThread
    private fun setSounds(guildId: Long, guildSounds: List<SoundboardSound>) {
        // The gateway omits `guild_id` on the sounds inside a per-guild payload, and the built-ins
        // have no guild at all; stamping it here is what lets a sound be checked against a channel.
        sounds[guildId] = guildSounds.map { if (it.guildId == null) it.copy(guildId = guildId) else it }
        markChanged()
    }

    @StoreThread
    private fun requestDefaultSounds() {
        val api = restApi() ?: return
        defaultFetchState = FetchState.FETCHING
        markChanged()
        api.getDefaultSoundboardSounds()
            .restSubscribeOn(false)
            .appSubscribe(
                StoreSoundboard::class.java,
                errorHandler = { dispatcher.schedule { handleDefaultSoundsFailed() } },
            ) { defaults ->
                dispatcher.schedule { handleDefaultSounds(defaults) }
            }
    }

    @StoreThread
    internal fun handleDefaultSounds(defaults: List<SoundboardSound>) {
        defaultFetchState = FetchState.FETCHED
        setSounds(DEFAULT_SOUND_GUILD_ID, defaults)
    }

    @StoreThread
    internal fun handleDefaultSoundsFailed() {
        defaultFetchState = FetchState.NOT_FETCHED
        markChanged()
    }
}
