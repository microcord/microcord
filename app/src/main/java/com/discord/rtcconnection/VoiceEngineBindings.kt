package com.discord.rtcconnection

import co.discord.media_engine.RtcRegion
import co.discord.media_engine.VideoInputDeviceDescription
import com.discord.api.voice.server.VoiceServer
import com.discord.app.AppLog
import com.discord.models.domain.ModelRtcLatencyRegion
import com.discord.rtcconnection.mediaengine.MediaEngine

import com.discord.stores.StoreMediaEngine
import com.discord.stores.StoreRtcConnection
import com.discord.utilities.networking.NetworkMonitor
import com.discord.utilities.time.Clock
import javax.net.ssl.SSLSocketFactory

/**
 * Wires the reconstructed transport into the store-side seams that stood in for it.
 *
 * `StoreRtcConnection` and `StoreMediaEngine` were written against
 * [StoreRtcConnection.RtcConnectionController] and [StoreMediaEngine.MediaEngineController] so the
 * store logic could be finished before the native path existed. These two classes are the real
 * implementations; swapping `NoOp` for them is the only change either store needs.
 *
 * They live here rather than in `stores/` only because this package owns the transport. The
 * dependency direction is unusual (transport → stores) and this file is the sole place it occurs;
 * moving it to the store-construction site would remove that.
 */
class LiveRtcConnectionController(
    private val mediaEngine: MediaEngine,
    private val networkMonitor: NetworkMonitor,
    private val clock: Clock,
    /** The signed-in user; needed for `IDENTIFY` and to tell "me" from a peer. */
    private val currentUserId: () -> Long,
    /**
     * TLS factory for the voice socket. `null` uses OkHttp's own, which is correct for release
     * builds; the reference only supplies one so a local server can be pointed at.
     */
    private val sslSocketFactory: () -> SSLSocketFactory? = { null },
    /** Receives every connection as it is created, so the store can attach its listener. */
    private val onConnectionCreated: (RtcConnection) -> Unit = {},
) : StoreRtcConnection.RtcConnectionController {

    /** Locally muted peers and their volumes, kept across reconnects. */
    private val mutedUsers = LinkedHashMap<Long, Boolean>()
    private val userVolumes = LinkedHashMap<Long, Float>()

    var rtcConnection: RtcConnection? = null
        private set

    override fun connect(voiceServer: VoiceServer, channelId: Long, guildId: Long?, sessionId: String) {
        disconnect("Reconnecting to a new voice server")

        val rtcServerId = (guildId ?: channelId).toString()
        val connection = RtcConnection(
            guildId = guildId,
            channelId = channelId,
            sessionId = sessionId,
            // Verified from the reference's `StoreRtcConnection.createRtcConnection` bytecode: this
            // is a constant `true`. It advertises that the client *can* receive video; whether the
            // camera is on travels separately as the gateway voice state's `self_video`.
            isVideoEnabled = true,
            rtcServerId = rtcServerId,
            userId = currentUserId(),
            mediaEngine = mediaEngine,
            clock = clock,
            networkMonitor = networkMonitor,
            mutedUsers = mutedUsers,
            userVolumes = userVolumes,
            loggingTagPrefix = "StoreRtcConnection",
        )
        rtcConnection = connection
        onConnectionCreated(connection)
        connection.connect(voiceServer.endpoint, voiceServer.token, sslSocketFactory())
    }

    override fun disconnect(reason: String) {
        val connection = rtcConnection ?: return
        AppLog.recordBreadcrumb("Destroying RtcConnection: $reason", "StoreRtcConnection")
        connection.disconnect()
        rtcConnection = null
    }

    override val metadata: RtcConnection.Metadata?
        get() = rtcConnection?.getMetadata()

    override fun setUserMuted(userId: Long, muted: Boolean) {
        // Recorded even with no live connection so the next one starts with it applied.
        mutedUsers[userId] = muted
        rtcConnection?.setUserMuted(userId, muted)
    }

    override fun setUserOutputVolume(userId: Long, volume: Float) {
        userVolumes[userId] = volume
        rtcConnection?.setUserOutputVolume(userId, volume)
    }

    override fun setUserOffScreen(userId: Long, offScreen: Boolean) {
        rtcConnection?.setUserOffScreen(userId, offScreen)
    }
}

/**
 * [StoreMediaEngine.MediaEngineController] on top of the real [MediaEngine].
 *
 * The store speaks in terms of "the engine"; three of these calls are actually per-connection
 * ([setVideoInputEnabled], [setPttActive]) and are fanned out to every live connection, which is
 * what the reference does from `StoreMediaEngine` directly.
 */
class LiveMediaEngineController(
    private val mediaEngine: MediaEngine,
) : StoreMediaEngine.MediaEngineController {

    override fun getVideoInputDevices(callback: (List<VideoInputDeviceDescription>) -> Unit) =
        mediaEngine.getVideoInputDevices { devices -> callback(devices.toList()) }

    override fun setVideoInputDevice(index: Int) = mediaEngine.setVideoInputDevice(index)

    override fun setVideoInputEnabled(enabled: Boolean) =
        mediaEngine.getConnections().forEach { it.setVideoBroadcast(enabled) }

    override fun setAudioInputEnabled(enabled: Boolean) = mediaEngine.setAudioInputEnabled(enabled)

    override fun setPttActive(active: Boolean) =
        mediaEngine.getConnections().forEach { it.setPttActive(active) }

    override fun rankRtcRegions(
        regions: List<ModelRtcLatencyRegion>,
        callback: (List<String>) -> Unit,
    ) = mediaEngine.rankRtcRegions(
        regions.map { RtcRegion(it.region, it.ips.toTypedArray()) }.toTypedArray(),
    ) { ranked -> callback(ranked.toList()) }
}
