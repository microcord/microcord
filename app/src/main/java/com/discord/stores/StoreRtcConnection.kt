package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.voice.server.VoiceServer
import com.discord.api.voice.state.VoiceState
import com.discord.gateway.io.VoiceStateUpdate
import com.discord.models.domain.ModelPayload
import com.discord.rtcconnection.RtcConnection
import com.discord.utilities.rx.computationLatest
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subjects.PublishSubject
import rx.subjects.SerializedSubject

/**
 * Owns the client's *intent* to be in a voice channel, and the observable facts about that.
 *
 * The store keeps three things in sync that arrive from three different places:
 * - [currentVoiceState] — what we last told the gateway. Rebuilt whenever the selection or a local
 *   mute/deafen/video toggle changes, then sent as opcode 4.
 * - [voiceServer] — the endpoint the gateway assigned, from `VOICE_SERVER_UPDATE`.
 * - the metadata and speaking feed the UI renders.
 *
 * Both halves of a connection have to be present before the transport can be built: the gateway
 * sends `VOICE_STATE_UPDATE` (session) and `VOICE_SERVER_UPDATE` (endpoint) in either order, which
 * is why [checkForVoiceServerUpdate] re-runs on both paths instead of assuming an order.
 *
 * **Phase 11 boundary.** Creating and tearing down the actual `RtcConnection` is delegated to
 * [rtcConnectionController]; the reference does it inline. Everything above the transport — state
 * tracking, gateway messaging, the speaking feed, the metadata subject — is real here.
 */
class StoreRtcConnection(
    private val dispatcher: Dispatcher,
    private val storeRtcRegion: StoreRtcRegion,
    /** `StoreChannels.findChannelByIdInternal`. */
    private val findChannel: (channelId: Long) -> Channel?,
    /** `StoreGatewayConnection.voiceStateUpdate` — opcode 4. */
    private val sendVoiceStateUpdate: (
        guildId: Long?,
        channelId: Long?,
        selfMute: Boolean,
        selfDeaf: Boolean,
        selfVideo: Boolean,
        preferredRegion: String?,
        shouldIncludePreferredRegion: Boolean,
    ) -> Unit,
    private val rtcConnectionController: RtcConnectionController = RtcConnectionController.NoOp,
) {

    /**
     * The transport this store drives. Reconstructed in Phase 11 on top of
     * `com.discord.rtcconnection.RtcConnection`; until then the store is a complete state machine
     * with a no-op transport.
     */
    interface RtcConnectionController {
        /** Builds a connection for the current channel/server pair, replacing any existing one. */
        fun connect(voiceServer: VoiceServer, channelId: Long, guildId: Long?, sessionId: String)

        /** Tears the current connection down. [reason] is recorded in the voice breadcrumb log. */
        fun disconnect(reason: String)

        /** Metadata of the live connection, or `null` when there is none. */
        val metadata: RtcConnection.Metadata?

        fun setUserMuted(userId: Long, muted: Boolean)
        fun setUserOutputVolume(userId: Long, volume: Float)
        fun setUserOffScreen(userId: Long, offScreen: Boolean)

        object NoOp : RtcConnectionController {
            override fun connect(voiceServer: VoiceServer, channelId: Long, guildId: Long?, sessionId: String) = Unit
            override fun disconnect(reason: String) = Unit
            override val metadata: RtcConnection.Metadata? get() = null
            override fun setUserMuted(userId: Long, muted: Boolean) = Unit
            override fun setUserOutputVolume(userId: Long, volume: Float) = Unit
            override fun setUserOffScreen(userId: Long, offScreen: Boolean) = Unit
        }
    }

    /** One user's speaking transition, as reported by the media engine. */
    data class SpeakingUserUpdate(val userId: Long, val isSpeaking: Boolean)

    interface Listener {
        fun onConnected()
        fun onConnecting()
        fun onFirstFrameReceived(ssrc: Long)
        fun onFirstFrameSent()
    }

    abstract class DefaultListener : Listener {
        override fun onConnected() = Unit
        override fun onConnecting() = Unit
        override fun onFirstFrameReceived(ssrc: Long) = Unit
        override fun onFirstFrameSent() = Unit
    }

    private val listeners = mutableSetOf<Listener>()

    private val speakingUsersSubject = PublishSubject.create<SpeakingUserUpdate>()

    /**
     * Speaking transitions, coalesced into 300 ms batches.
     *
     * The engine reports every voice-activity edge; in a busy channel that is dozens of events per
     * second, and each one would otherwise cost a store dispatch and a full participant recompute.
     */
    val speakingUpdates: Observable<List<SpeakingUserUpdate>> =
        speakingUsersSubject.buffer(SPEAKING_UPDATES_BUFFER_MS, TimeUnit.MILLISECONDS)
            .computationLatest()
            .distinctUntilChanged()

    private val rtcConnectionMetadataSubject = BehaviorSubject.create<RtcConnection.Metadata?>(null)
    private var rtcConnectionMetadata: RtcConnection.Metadata? = null

    private val connectionStateSubject =
        SerializedSubject<Boolean, Boolean>(BehaviorSubject.create(false))

    /** `true` while the transport reports a live media connection. */
    val connectionState: Observable<Boolean> =
        connectionStateSubject.computationLatest().distinctUntilChanged()

    private var currentVoiceState = VoiceStateUpdate(null, null, false, false, false, null)
    private var selectedVoiceChannel: Channel? = null
    private var hasSelectedVoiceChannel = false
    private var voiceServer: VoiceServer? = null
    private var sessionId: String? = null

    val connectedGuildId: Long get() = rtcConnectionMetadata?.guildId ?: 0L

    fun addListener(listener: Listener) { listeners += listener }

    fun removeListener(listener: Listener) { listeners -= listener }

    // region Gateway and store input

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        sessionId = payload.sessionId
    }

    @StoreThread
    fun handleConnectionReady(connected: Boolean) {
        // A resumed session has to re-announce the voice state; the backend forgot it with the old
        // session, so silently staying "connected" locally would strand us in a channel nobody sees.
        if (connected) handleVoiceChannelSelected(selectedVoiceChannel?.id ?: 0L)
    }

    @StoreThread
    fun handleVoiceChannelSelected(channelId: Long?) {
        val previousChannelId = selectedVoiceChannel?.id
        if (channelId != previousChannelId) {
            rtcConnectionController.disconnect("Channel ID changed, $channelId != $previousChannelId")
        }

        val channel = channelId?.let(findChannel)
        selectedVoiceChannel = channel
        currentVoiceState = currentVoiceState.copy(
            guildId = channel?.guildId?.takeIf { it > 0L },
            channelId = channel?.id,
        )

        if (channel != null) {
            hasSelectedVoiceChannel = true
            checkForVoiceServerUpdate()
        } else {
            // Dropping the server here is what makes a later VOICE_SERVER_UPDATE for the *new*
            // channel the only one that can build a connection.
            voiceServer = null
        }
        onVoiceStateUpdated()
    }

    @StoreThread
    fun handleVoiceServerUpdate(voiceServer: VoiceServer) {
        this.voiceServer = voiceServer
        checkForVoiceServerUpdate()
    }

    @StoreThread
    @Suppress("UNUSED_PARAMETER")
    fun handleVoiceStateUpdate(voiceState: VoiceState) = Unit

    @StoreThread
    fun handleSelfMuted(selfMuted: Boolean) {
        currentVoiceState = currentVoiceState.copy(selfMute = selfMuted)
        onVoiceStateUpdated()
    }

    @StoreThread
    fun handleSelfDeafened(selfDeafened: Boolean) {
        currentVoiceState = currentVoiceState.copy(selfDeaf = selfDeafened)
        onVoiceStateUpdated()
    }

    @StoreThread
    fun handleSelfVideo(selfVideo: Boolean) {
        currentVoiceState = currentVoiceState.copy(selfVideo = selfVideo)
        onVoiceStateUpdated()
    }

    @StoreThread
    fun handleUsersMuted(usersMuted: Map<Long, Boolean>) =
        usersMuted.forEach { (userId, muted) -> rtcConnectionController.setUserMuted(userId, muted) }

    @StoreThread
    fun handleUsersOffScreen(usersOffScreen: Map<Long, Boolean>) =
        usersOffScreen.forEach { (userId, offScreen) ->
            rtcConnectionController.setUserOffScreen(userId, offScreen)
        }

    @StoreThread
    fun handleUsersVolume(usersVolume: Map<Long, Float>) =
        usersVolume.forEach { (userId, volume) ->
            rtcConnectionController.setUserOutputVolume(userId, volume)
        }

    // endregion

    // region Transport callbacks

    fun onSpeaking(userId: Long, isSpeaking: Boolean) =
        speakingUsersSubject.onNext(SpeakingUserUpdate(userId, isSpeaking))

    fun onConnectedChanged(connected: Boolean) {
        connectionStateSubject.onNext(connected)
        if (connected) listeners.forEach(Listener::onConnected) else listeners.forEach(Listener::onConnecting)
        dispatcher.schedule { updateMetadata() }
    }

    fun onMediaSessionIdReceived() = dispatcher.schedule { updateMetadata() }

    // endregion

    @StoreThread
    fun getRtcConnectionMetadata(): RtcConnection.Metadata? = rtcConnectionMetadata

    fun observeRtcConnectionMetadata(): Observable<RtcConnection.Metadata?> = rtcConnectionMetadataSubject

    /**
     * Builds the transport once the channel selection, the session and the voice server all agree.
     *
     * The server update is discarded when it names a different guild or channel than the one we are
     * on: an update for a channel we already left would otherwise reconnect us to it.
     */
    @StoreThread
    private fun checkForVoiceServerUpdate() {
        val server = voiceServer ?: return
        val channel = selectedVoiceChannel ?: return
        val session = sessionId ?: return
        val guildId = channel.guildId.takeIf { it > 0L }

        if (server.guildId != null && server.guildId != guildId) return
        if (server.channelId != null && server.channelId != channel.id) return

        rtcConnectionController.connect(server, channel.id, guildId, session)
        updateMetadata()
    }

    @StoreThread
    private fun updateMetadata() {
        rtcConnectionMetadata = rtcConnectionController.metadata
        rtcConnectionMetadataSubject.onNext(rtcConnectionMetadata)
    }

    /**
     * Publishes [currentVoiceState] to the gateway.
     *
     * The preferred region is only expressed while we are actually joining a channel; leaving must
     * not pin a region, and sending the field at all when we have no measurement would override the
     * backend's own geo choice.
     */
    @StoreThread
    private fun onVoiceStateUpdated() {
        val preferredRegion =
            if (currentVoiceState.channelId != null) storeRtcRegion.getPreferredRegion() else null
        sendVoiceStateUpdate(
            currentVoiceState.guildId,
            currentVoiceState.channelId,
            currentVoiceState.selfMute,
            currentVoiceState.selfDeaf,
            currentVoiceState.selfVideo,
            preferredRegion,
            storeRtcRegion.shouldIncludePreferredRegion(currentVoiceState.guildId),
        )
    }

    private companion object {
        private const val SPEAKING_UPDATES_BUFFER_MS = 300L
    }
}
