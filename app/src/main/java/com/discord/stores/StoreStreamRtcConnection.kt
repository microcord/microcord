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
 * The RTC connections that carry screen shares, one per stream.
 *
 * Watching or publishing a stream uses a connection separate from the voice one, on its own media
 * server, so this store mirrors [StoreRtcConnection] rather than extending it. Several streams can
 * be watched at once, so connections and their state are keyed by stream key.
 *
 * [streamVolume] lives here and not in [StoreMediaSettings] because it is not a user preference: it
 * tracks the device's media volume slider while a stream is playing, and resets to full for the next
 * stream. It applies to every stream being watched.
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
        /** Builds the connection for [streamKey]. [rtcServerId] comes from `STREAM_CREATE`. */
        fun connect(
            userId: Long,
            guildId: Long?,
            channelId: Long,
            sessionId: String,
            rtcServerId: String,
            streamOwnerId: Long,
            streamKey: String,
        )

        /** Points [streamKey]'s connection at the endpoint from `STREAM_SERVER_UPDATE`. */
        fun updateServer(streamKey: String, endpoint: String?, token: String?)

        /** Disconnects [streamKey]'s connection, or every one when it is null. */
        fun disconnect(streamKey: String?)

        /** The gateway session [streamKey]'s connection was built with, or null when there is none. */
        fun connectedSessionId(streamKey: String): String?

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
            override fun updateServer(streamKey: String, endpoint: String?, token: String?) = Unit
            override fun disconnect(streamKey: String?) = Unit
            override fun connectedSessionId(streamKey: String): String? = null
            override fun setUserOutputVolume(userId: Long, volume: Float) = Unit
            override fun setFocusedParticipant(userId: Long?) = Unit
        }
    }

    data class State(
        val isConnected: Boolean = false,
        val mediaSessionId: String? = null,
        val streamKey: String? = null,
    )

    private val states = HashMap<String, State>()
    private var statesSnapshot: Map<String, State> = emptyMap()

    private var sessionId: String? = null
    private var streamVolume: Float = MAX_STREAM_VOLUME
    private val connectionQualities = BehaviorSubject.create(emptyMap<String, RtcConnection.Quality>())
    private val epochAuthenticator = BehaviorSubject.create(null as String?)

    /** Connection state per stream key; a missing key has no connection. */
    fun getStates(): Map<String, State> = statesSnapshot

    fun getStreamVolume(): Float = streamVolume

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        sessionId = payload.sessionId
    }

    /**
     * Builds the stream connection for `STREAM_CREATE`.
     *
     * Re-entrant on purpose: `STREAM_CREATE` is re-sent after a gateway resume, and rebuilding a
     * healthy connection would drop the viewer's video. A connection is therefore only replaced when
     * it was built with a stale session.
     */
    @StoreThread
    fun handleStreamCreate(streamCreate: StreamCreateOrUpdate) {
        val session = sessionId ?: return
        val rtcServerId = streamCreate.rtcServerId ?: return
        val key = streamCreate.streamKey
        if (streamConnectionController.connectedSessionId(key) == session) return

        val stream = ModelApplicationStream.decodeStreamKey(key)
        streamConnectionController.connect(
            userId = userStore.getMeInternal().id,
            guildId = (stream as? ModelApplicationStream.GuildStream)?.guildId,
            channelId = stream.channelId,
            sessionId = session,
            rtcServerId = rtcServerId,
            streamOwnerId = stream.ownerId,
            streamKey = key,
        )
        states[key] = State(streamKey = key)
        markChanged()
    }

    /** Ends [streamKey]'s connection, or every stream connection when it is null. */
    @StoreThread
    fun handleStreamDelete(streamKey: String? = null) {
        streamConnectionController.disconnect(streamKey)
        if (streamKey == null) states.clear() else states.remove(streamKey)
        connectionQualities.onNext(connectionQualities.value.filterKeys { it in states })
        markChanged()
    }

    @StoreThread
    fun handleStreamServerUpdate(streamServerUpdate: StreamServerUpdate) =
        streamConnectionController.updateServer(
            streamServerUpdate.streamKey,
            streamServerUpdate.endpoint,
            streamServerUpdate.token,
        )

    /**
     * Applies the stream volume as soon as the connection goes live.
     *
     * The gain is per-*user* on the media engine, and the streamer's audio track only exists once
     * connected, so setting it earlier would be dropped. Late callbacks from a connection already
     * deleted are ignored rather than bringing its state back.
     */
    @StoreThread
    fun handleStreamRtcConnectionStateChange(streamKey: String, isConnected: Boolean) {
        val state = states[streamKey] ?: return
        if (isConnected) {
            streamConnectionController.setUserOutputVolume(ownerOf(streamKey), streamVolume)
        } else {
            epochAuthenticator.onNext(null)
        }
        states[streamKey] = state.copy(isConnected = isConnected)
        markChanged()
    }

    @StoreThread
    fun handleMediaSessionIdReceived(streamKey: String, mediaSessionId: String?) {
        val state = states[streamKey] ?: return
        states[streamKey] = state.copy(mediaSessionId = mediaSessionId)
        markChanged()
    }

    fun observeStates(): Observable<Map<String, State>> =
        observationDeck.connectRx(this) { getStates() }.distinctUntilChanged()

    fun observeStreamVolume(): Observable<Float> =
        observationDeck.connectRx(this) { getStreamVolume() }.distinctUntilChanged()

    /** Connection quality per stream key. */
    fun observeConnectionQualities(): Observable<Map<String, RtcConnection.Quality>> =
        connectionQualities.distinctUntilChanged()

    @StoreThread
    fun handleQualityUpdate(streamKey: String, quality: RtcConnection.Quality) {
        if (streamKey in states) connectionQualities.onNext(connectionQualities.value + (streamKey to quality))
    }

    fun observeEpochAuthenticator(): Observable<String?> = epochAuthenticator.distinctUntilChanged()

    /** Same payload as the call connection's; only the authenticator is rendered. */
    @StoreThread
    fun handleSecureFramesState(stateUpdateJson: String) {
        val authenticator = try {
            org.json.JSONObject(stateUpdateJson).optString(KEY_EPOCH_AUTHENTICATOR)
        } catch (e: org.json.JSONException) {
            ""
        }
        epochAuthenticator.onNext(authenticator.takeIf { it.isNotEmpty() })
    }

    fun updateFocusedParticipant(focusedParticipant: Long?) =
        streamConnectionController.setFocusedParticipant(focusedParticipant)

    fun updateStreamVolume(volume: Float) = dispatcher.schedule {
        streamVolume = volume
        states.keys.forEach { streamConnectionController.setUserOutputVolume(ownerOf(it), volume) }
        markChanged()
    }

    private fun ownerOf(streamKey: String): Long = ModelApplicationStream.decodeStreamKey(streamKey).ownerId

    @StoreThread
    override fun snapshotData() {
        super.snapshotData()
        statesSnapshot = HashMap(states)
    }

    private companion object {
        /** Full volume on the media engine's 0..300 scale. */
        private const val MAX_STREAM_VOLUME = 300f

        /** Spelled as the engine emits it; see `epochAuthenticator` in libdiscord. */
        private const val KEY_EPOCH_AUTHENTICATOR = "epochAuthenticator"
    }
}
