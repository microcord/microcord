package com.discord.stores

import com.discord.models.domain.ModelApplicationStream
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.StreamCreateOrUpdate
import com.discord.models.domain.StreamServerUpdate
import com.discord.rtcconnection.RtcConnection
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import rx.Observable
import rx.subjects.BehaviorSubject

/**
 * The *second* RTC connection: the one that carries a screen share.
 *
 * Watching or publishing a stream uses a connection separate from the voice one, on its own media
 * server, so this store mirrors [StoreRtcConnection] rather than extending it. Only one stream
 * connection exists at a time.
 *
 * [streamVolume] lives here and not in [StoreMediaSettings] because it is not a user preference: it
 * tracks the device's media volume slider while a stream is playing, and resets to full for the next
 * stream.
 *
 * **Phase 11 boundary.** [streamConnectionController] stands in for building the transport; the
 * store's own state machine, gateway handlers and observation surface are complete.
 */
class StoreStreamRtcConnection(
    private val dispatcher: Dispatcher,
    private val userStore: StoreUser,
    private val streamConnectionController: StreamConnectionController =
        StreamConnectionController.NoOp,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) : StoreV2() {

    /** Transport seam; see [StoreRtcConnection.RtcConnectionController]. */
    interface StreamConnectionController {
        /** Builds the stream connection. [rtcServerId] comes from `STREAM_CREATE`. */
        fun connect(
            userId: Long,
            guildId: Long?,
            channelId: Long,
            sessionId: String,
            rtcServerId: String,
            streamOwnerId: Long,
            streamKey: String,
        )

        /** Points the existing connection at the endpoint from `STREAM_SERVER_UPDATE`. */
        fun updateServer(endpoint: String?, token: String?)

        fun disconnect()

        /** The channel the connection is currently on, or `null` when there is none. */
        val connectedChannelId: Long?

        /** The gateway session the connection was built with. */
        val connectedSessionId: String?

        fun setUserOutputVolume(userId: Long, volume: Float)

        /** Requests full resolution for one participant and thumbnails for the rest. */
        fun setFocusedParticipant(userId: Long?)

        object NoOp : StreamConnectionController {
            override fun connect(
                userId: Long,
                guildId: Long?,
                channelId: Long,
                sessionId: String,
                rtcServerId: String,
                streamOwnerId: Long,
                streamKey: String,
            ) = Unit
            override fun updateServer(endpoint: String?, token: String?) = Unit
            override fun disconnect() = Unit
            override val connectedChannelId: Long? get() = null
            override val connectedSessionId: String? get() = null
            override fun setUserOutputVolume(userId: Long, volume: Float) = Unit
            override fun setFocusedParticipant(userId: Long?) = Unit
        }
    }

    data class State(
        val isConnected: Boolean = false,
        val mediaSessionId: String? = null,
        val streamKey: String? = null,
    )

    private var state = State()
    private var stateSnapshot = State()

    private var sessionId: String? = null
    private var streamOwner: Long? = null
    private var streamVolume: Float = MAX_STREAM_VOLUME
    private val connectionQuality = BehaviorSubject.create(RtcConnection.Quality.UNKNOWN)

    fun getState(): State = stateSnapshot

    fun getStreamVolume(): Float = streamVolume

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        sessionId = payload.sessionId
    }

    /**
     * Builds the stream connection for `STREAM_CREATE`.
     *
     * Re-entrant on purpose: `STREAM_CREATE` is re-sent after a gateway resume, and rebuilding a
     * healthy connection would drop the viewer's video. The connection is therefore only replaced
     * when it is on a different channel, or was built with a stale session.
     */
    @StoreThread
    fun handleStreamCreate(streamCreate: StreamCreateOrUpdate) {
        val session = sessionId ?: return
        val rtcServerId = streamCreate.rtcServerId ?: return
        val stream = ModelApplicationStream.decodeStreamKey(streamCreate.streamKey)

        val alreadyConnected = streamConnectionController.connectedChannelId == stream.channelId &&
            streamConnectionController.connectedSessionId == session
        if (alreadyConnected) return

        val guildId = (stream as? ModelApplicationStream.GuildStream)?.guildId
        streamConnectionController.connect(
            userId = userStore.getMeInternal().id,
            guildId = guildId,
            channelId = stream.channelId,
            sessionId = session,
            rtcServerId = rtcServerId,
            streamOwnerId = stream.ownerId,
            streamKey = streamCreate.streamKey,
        )
        streamOwner = stream.ownerId
        state = state.copy(streamKey = streamCreate.streamKey)
        markChanged()
    }

    @StoreThread
    fun handleStreamDelete() {
        streamConnectionController.disconnect()
        streamOwner = null
        state = State()
        markChanged()
    }

    @StoreThread
    fun handleStreamServerUpdate(streamServerUpdate: StreamServerUpdate) =
        streamConnectionController.updateServer(streamServerUpdate.endpoint, streamServerUpdate.token)

    /**
     * Applies the stream volume as soon as the connection goes live.
     *
     * The gain is per-*user* on the media engine, and the streamer's audio track only exists once
     * connected, so setting it earlier would be dropped.
     */
    @StoreThread
    fun handleStreamRtcConnectionStateChange(isConnected: Boolean) {
        if (isConnected) {
            streamOwner?.let { streamConnectionController.setUserOutputVolume(it, streamVolume) }
        }
        state = state.copy(isConnected = isConnected)
        markChanged()
    }

    @StoreThread
    fun handleMediaSessionIdReceived(mediaSessionId: String?) {
        state = state.copy(mediaSessionId = mediaSessionId)
        markChanged()
    }

    fun observeState(): Observable<State> = observationDeck.connectRx(this) { getState() }

    fun observeIsConnected(): Observable<Boolean> =
        observationDeck.connectRx(this) { getState().isConnected }.distinctUntilChanged()

    fun observeStreamVolume(): Observable<Float> =
        observationDeck.connectRx(this) { getStreamVolume() }.distinctUntilChanged()

    fun observeConnectionQuality(): Observable<RtcConnection.Quality> =
        connectionQuality.distinctUntilChanged()

    @StoreThread
    fun handleQualityUpdate(quality: RtcConnection.Quality) = connectionQuality.onNext(quality)

    fun updateFocusedParticipant(focusedParticipant: Long?) =
        streamConnectionController.setFocusedParticipant(focusedParticipant)

    fun updateStreamVolume(volume: Float) = dispatcher.schedule {
        streamVolume = volume
        streamOwner?.let { streamConnectionController.setUserOutputVolume(it, volume) }
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        super.snapshotData()
        stateSnapshot = state
    }

    private companion object {
        /** Full volume on the media engine's 0..300 scale. */
        private const val MAX_STREAM_VOLUME = 300f
    }
}
