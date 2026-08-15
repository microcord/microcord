package com.discord.utilities.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.api.channel.getDisplayName
import com.discord.rtcconnection.RtcConnection
import com.discord.rtcconnection.VoiceTransport
import com.discord.rtcconnection.audio.DiscordAudioManager
import com.discord.stores.StoreAudioManagerV2
import com.discord.stores.StoreMediaSettings
import com.discord.stores.StoreRtcConnection
import com.discord.stores.StoreMediaEngine
import com.discord.stores.StoreStream
import com.discord.stores.StoreVoiceChannelSelected
import com.discord.stores.utilities.leadingEdgeThrottle
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.view.ToastManager
import com.discord.widgets.voice.model.CallModel
import com.discord.utilities.networking.NetworkMonitor
import com.discord.utilities.time.Clock
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Subscription

/**
 * Produces and controls the state consumed by the voice foreground service.
 *
 * The Android service remains a separate route. It subscribes to [notificationDataObservable] and
 * invokes the three action methods below; keeping that route out of this class also makes the
 * controller usable before the service and manifest entry are reconstructed.
 */
class VoiceEngineServiceController(
    private val audioManagerStore: StoreAudioManagerV2,
    private val mediaSettingsStore: StoreMediaSettings,
    private val voiceChannelSelectedStore: StoreVoiceChannelSelected,
    private val rtcConnectionStore: StoreRtcConnection,
    connectionStates: Observable<RtcConnection.StateChange> =
        rtcConnectionStore.getConnectionState(),
    private val setScreenshare: (Intent?) -> Unit = { permissionIntent ->
        checkNotNull(VoiceTransport.getOrNull()) { "Voice transport is not initialized" }
            .setScreenshare(permissionIntent)
    },
) {
    private var serviceBinding: VoiceEngineForegroundService.Connection? = null
    private var serviceSubscription: Subscription? = null

    internal val notificationDataObservable: Observable<NotificationData> = connectionStates
        .switchMap { stateChange ->
            val state = stateChange.state
            if (state == RtcConnection.State.RtcDisconnected) {
                Observable.just(NOTIFICATION_DATA_DISCONNECTED)
            } else {
                voiceChannelSelectedStore.observeSelectedVoiceChannelId().switchMap { channelId ->
                    Observable.combineLatest(
                        mediaSettingsStore.getVoiceConfig(),
                        audioManagerStore.observeAudioManagerState(),
                        CallModel.get(channelId),
                    ) { voiceConfiguration, audioManagerState, callModel ->
                        val isVideo = callModel?.isVideoCall == true
                        NotificationData(
                            rtcConnectionState = state,
                            channelName = callModel?.channel?.getDisplayName().orEmpty(),
                            isSelfMuted = voiceConfiguration.isSelfMuted(),
                            isSelfDeafened = voiceConfiguration.isSelfDeafened,
                            isSelfStreaming = callModel?.isStreaming == true,
                            isVideo = isVideo,
                            channelId = callModel?.channel?.id ?: -1L,
                            guildId = callModel?.channel?.guildId?.takeIf { it > 0L },
                            proximityLockEnabled = !isVideo &&
                                audioManagerState.activeAudioDevice ==
                                DiscordAudioManager.DeviceTypes.EARPIECE,
                            canSpeak = callModel?.isSuppressed != true,
                        )
                    }.leadingEdgeThrottle(NOTIFICATION_THROTTLE_MS, TimeUnit.MILLISECONDS)
                }
            }
        }
        .computationLatest()
        .distinctUntilChanged()
        .replay(1)
        .refCount()

    internal fun onDisconnect() = voiceChannelSelectedStore.clear()

    internal fun onToggleSelfDeafen() = mediaSettingsStore.toggleSelfDeafened()

    internal fun onToggleSelfMute(): StoreMediaSettings.SelfMuteFailure? =
        mediaSettingsStore.toggleSelfMuted()

    fun init(context: Context) {
        val appContext = context.applicationContext
        val canRecordAudio = {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        }
        VoiceEngineForegroundService.onDisconnect = ::onDisconnect
        VoiceEngineForegroundService.onToggleSelfDeafen = ::onToggleSelfDeafen
        VoiceEngineForegroundService.onToggleSelfMute = {
            if (onToggleSelfMute() == StoreMediaSettings.SelfMuteFailure.CANNOT_USE_VAD) {
                ToastManager().show(appContext, R.string.vad_permission_small)
            }
        }
        val binding = VoiceEngineForegroundService.Connection(appContext)
        serviceBinding = binding
        serviceSubscription?.unsubscribe()
        var permissionDisconnectRequested = false
        notificationDataObservable.appSubscribe(
            errorClass = VoiceEngineServiceController::class.java,
            subscriptionHandler = { serviceSubscription = it },
        ) { data ->
            if (data.rtcConnectionState is RtcConnection.State.Disconnected) {
                permissionDisconnectRequested = false
                DiscordOverlayService.resetDismissal()
                DiscordOverlayService.launchForClose(appContext)
                VoiceEngineForegroundService.stopForegroundAndUnbind(binding)
            } else if (!canRecordAudio()) {
                if (!permissionDisconnectRequested) {
                    permissionDisconnectRequested = true
                    voiceChannelSelectedStore.clear()
                }
            } else {
                permissionDisconnectRequested = false
                VoiceEngineForegroundService.startForegroundAndBind(
                    connection = binding,
                    title = appContext.getString(data.stateString),
                    subtitle = data.channelName,
                    selfMute = data.isSelfMuted,
                    selfDeafen = data.isSelfDeafened,
                    selfStream = data.isSelfStreaming,
                    channelId = data.channelId,
                    guildId = data.guildId,
                    isProximityLockEnabled = data.proximityLockEnabled,
                    canSpeak = data.canSpeak,
                )
                if (data.rtcConnectionState == RtcConnection.State.RtcConnected) {
                    DiscordOverlayService.launchForVoice(appContext)
                }
            }
        }
    }

    fun startStream(permissionIntent: Intent) {
        serviceBinding?.let { VoiceEngineForegroundService.startStream(it, permissionIntent) }
            ?: setScreenshare(permissionIntent)
    }

    fun stopStream() {
        serviceBinding?.let { VoiceEngineForegroundService.stopStream(it) } ?: setScreenshare(null)
    }

    internal data class NotificationData(
        val rtcConnectionState: RtcConnection.State,
        val channelName: String,
        val isSelfMuted: Boolean,
        val isSelfDeafened: Boolean,
        val isSelfStreaming: Boolean,
        val isVideo: Boolean,
        val channelId: Long,
        val guildId: Long?,
        val proximityLockEnabled: Boolean,
        val canSpeak: Boolean,
    ) {
        @StringRes
        val stateString: Int = when (rtcConnectionState) {
            is RtcConnection.State.Disconnected -> R.string.connection_status_disconnected
            RtcConnection.State.AwaitingEndpoint -> R.string.connection_status_awaiting_endpoint
            RtcConnection.State.Authenticating -> R.string.connection_status_authenticating
            RtcConnection.State.Connecting -> R.string.connection_status_connecting
            RtcConnection.State.RtcDisconnected -> R.string.connection_status_rtc_disconnected
            RtcConnection.State.RtcConnecting -> R.string.connection_status_rtc_connecting
            RtcConnection.State.RtcConnected -> when {
                isSelfStreaming -> R.string.connection_status_stream_self_connected
                isVideo -> R.string.connection_status_video_connected
                else -> R.string.connection_status_voice_connected
            }
            RtcConnection.State.NoRoute -> R.string.connection_status_no_route
        }
    }

    companion object {
        private const val NOTIFICATION_THROTTLE_MS = 300L

        private val NOTIFICATION_DATA_DISCONNECTED = NotificationData(
            rtcConnectionState = RtcConnection.State.Disconnected(false),
            channelName = "",
            isSelfMuted = false,
            isSelfDeafened = false,
            isSelfStreaming = false,
            isVideo = false,
            channelId = -1L,
            guildId = null,
            proximityLockEnabled = false,
            canSpeak = false,
        )

        @JvmStatic
        val INSTANCE: VoiceEngineServiceController by lazy {
            VoiceEngineServiceController(
                StoreStream.getAudioManagerV2(),
                StoreStream.getMediaSettings(),
                StoreStream.getVoiceChannelSelected(),
                StoreStream.getRtcConnection(),
            )
        }
    }
}
