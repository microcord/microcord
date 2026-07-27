package com.discord.rtcconnection.audio

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.annotation.MainThread
import com.discord.app.AppLog
import com.discord.rtcconnection.enums.AudioManagerBroadcastAction
import com.discord.rtcconnection.enums.BluetoothHeadsetAudioState
import com.discord.rtcconnection.enums.BluetoothProfileConnectionState
import com.discord.rtcconnection.enums.ScoAudioState
import com.discord.rtcconnection.enums.WiredHeadsetState
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subjects.SerializedSubject

/**
 * The audio router: which physical output a call plays through, and everything the platform makes
 * you do to get there.
 *
 * The device list is a fixed four entries indexed by [DeviceTypes.value] — speakerphone, wired
 * headset, earpiece, Bluetooth headset — each carrying only an availability flag (plus an address
 * and name for Bluetooth). Keeping it fixed-length and index-addressed is what lets the whole
 * pipeline, including [com.discord.stores.StoreAudioManagerV2] and the output-selection dialog,
 * treat "is the earpiece available" as a constant-time lookup rather than a search.
 *
 * Three platform facts drive it, and they arrive on three different channels:
 * - the wired headset, from `ACTION_HEADSET_PLUG`;
 * - the Bluetooth headset, from the headset profile's connection and audio-state broadcasts plus a
 *   profile proxy that connects asynchronously;
 * - the SCO link, from `ACTION_SCO_AUDIO_STATE_UPDATED`.
 *
 * Bluetooth is the awkward one. Routing call audio to a Bluetooth headset means opening an SCO link,
 * which is a request that can be refused, can take seconds, and can drop on its own. [scoState]
 * therefore tracks what *we* asked for (`TURNING_ON`, `TURNING_OFF`) alongside what the system
 * reports, because "SCO is off" means something different when we never asked for it than when we
 * asked and it failed — the second case has to clear the Bluetooth device and re-route.
 *
 * Threading: every mutating entry point must be called on the thread that constructed the instance
 * (see [assertOwnerThread]); the broadcast receivers deliver on the main thread and only read
 * through [lock]. The reference enforces the same rule with a `HandlerThread` owned by the store.
 *
 * Reference: `com.discord.rtcconnection.audio.DiscordAudioManager`. Its bodies for
 * `refreshBluetoothHeadset` and `setCallActive` were recovered from the DEX; JADX could not
 * decompile either.
 */
class DiscordAudioManager(context: Context) {

    /**
     * One routing target.
     *
     * [id] and [name] are only populated for a Bluetooth headset, where the user needs to see which
     * device they are about to switch to.
     */
    data class AudioDevice(
        val type: DeviceTypes = DeviceTypes.INVALID,
        val isAvailable: Boolean = false,
        val id: String? = null,
        val name: String? = null,
    )

    /**
     * [value] is the device's index in the device list, *not* its ordinal: `DEFAULT` and `INVALID`
     * are not routable targets and deliberately sit outside the list at `-2` and `-1`.
     */
    enum class DeviceTypes(val value: Int) {
        DEFAULT(-2),
        INVALID(-1),
        SPEAKERPHONE(0),
        WIRED_HEADSET(1),
        EARPIECE(2),
        BLUETOOTH_HEADSET(3),
        ;

        /** `true` for the four entries that exist in the device list. */
        val isRoutable: Boolean get() = value >= 0
    }

    /** Our view of the SCO link, including the two in-flight states the platform does not report. */
    enum class BluetoothScoState(val value: Int) {
        INVALID(-1),
        OFF(0),
        ON(1),
        TURNING_ON(2),
        TURNING_OFF(3),
    }

    private val appContext: Context = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val contentResolver: ContentResolver = appContext.contentResolver

    private val permissions: AudioPermissions by lazy { AudioPermissions(appContext) }

    /** No telephony hardware means no earpiece to route to — a tablet, typically. */
    private val hasTelephony: Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    private val lock = Any()
    private val ownerThreadId: Long = Thread.currentThread().id

    private val bluetoothReceiver = BluetoothBroadcastReceiver(appContext, BluetoothListener())
    private val audioReceiver = AudioManagerBroadcastReceiver(appContext, AudioManagerListener())

    private var headsetProfile: BluetoothHeadset? = null
    private var scoState: BluetoothScoState = BluetoothScoState.INVALID

    private var audioFocusChangeListener: AudioManager.OnAudioFocusChangeListener? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private var devices: List<AudioDevice> = initialDevices()
    private val devicesSubject =
        SerializedSubject<List<AudioDevice>, List<AudioDevice>>(BehaviorSubject.create(devices))

    private var activeDevice: DeviceTypes = DeviceTypes.INVALID
    private val activeDeviceSubject =
        SerializedSubject<DeviceTypes, DeviceTypes>(BehaviorSubject.create(activeDevice))

    private var mediaVolumeObserver: ContentObserver? = null
    private var currentMediaVolume: Int = 0

    /** The highest index the system media slider goes to; `0` when the stream is unavailable. */
    val maxMediaVolume: Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    private val mediaVolumeSubject =
        SerializedSubject<Int, Int>(BehaviorSubject.create(currentMediaVolume))

    /** The device the user last explicitly asked for; `DEFAULT` means "pick the best one". */
    private var requestedDevice: DeviceTypes = DeviceTypes.DEFAULT

    private var savedIsSpeakerphoneOn = false
    private var savedIsMicrophoneMute = false
    private var savedIsBluetoothScoOn = false
    private var inCommunicationMode = false

    init {
        // Broadcast registration has to happen on the main thread — `registerReceiver` binds the
        // delivery looper — while the instance itself is constructed on the audio thread.
        Handler(Looper.getMainLooper()).post {
            audioReceiver.register(
                listOf(
                    AudioManagerBroadcastAction.HeadsetPlug,
                    AudioManagerBroadcastAction.ScoAudioStateUpdated,
                ),
            )
            bluetoothReceiver.register()
            synchronized(lock) {
                audioFocusChangeListener =
                    AudioManager.OnAudioFocusChangeListener { focusChange ->
                        AppLog.i(TAG, "onAudioFocusChange: $focusChange")
                    }
            }
        }
    }

    // region Observation

    fun observeAudioDevices(): Observable<List<AudioDevice>> = devicesSubject

    fun observeActiveAudioDevice(): Observable<DeviceTypes> = activeDeviceSubject

    /** The system media volume index, republished whenever the user moves the hardware keys. */
    fun observeMediaVolume(): Observable<Int> = mediaVolumeSubject

    fun getAudioDevices(): List<AudioDevice> = synchronized(lock) { devices }

    fun getActiveAudioDevice(): DeviceTypes = synchronized(lock) { activeDevice }

    // endregion

    // region Commands

    /**
     * Routes to [deviceType], or re-derives the best available one when it is [DeviceTypes.DEFAULT].
     *
     * A request for a device that is not currently available is dropped rather than remembered: the
     * user chose from a list of what exists, so a stale choice means the list moved underneath them
     * and the automatic pick is the better answer.
     */
    fun setDevice(deviceType: DeviceTypes) {
        assertOwnerThread()
        if (!permissions.modifyAudioSettings) {
            AppLog.w(TAG, "Can't setDevice, requires MODIFY_AUDIO_SETTINGS.")
            return
        }
        AppLog.i(TAG, "setDevice(deviceType=$deviceType)")

        val snapshot = synchronized(lock) { devices }
        if (deviceType == DeviceTypes.DEFAULT) {
            synchronized(lock) { requestedDevice = DeviceTypes.DEFAULT }
            activateDefaultDevice(snapshot)
            return
        }
        if (!deviceType.isRoutable) return
        if (snapshot.getOrNull(deviceType.value)?.isAvailable != true) return

        synchronized(lock) { requestedDevice = deviceType }
        activateDevice(deviceType)
    }

    /**
     * Enters or leaves the in-call audio world.
     *
     * Entering takes audio focus, switches the platform into `MODE_IN_COMMUNICATION` so the volume
     * keys and routing follow the call, and starts watching the media slider. Leaving puts every one
     * of those back, including the speakerphone, microphone-mute and SCO flags that were sampled on
     * the way in — Discord is not the only thing using the audio hardware, and a call that ends must
     * not leave the device muted or stuck on speaker.
     *
     * Reference: `DiscordAudioManager.h(boolean)` (recovered from DEX).
     */
    fun setCallActive(active: Boolean) {
        assertOwnerThread()
        if (!permissions.modifyAudioSettings) {
            AppLog.w(TAG, "MODIFY_AUDIO_SETTINGS is missing. Client will run with reduced functionality")
            return
        }
        if (active) startCall() else endCall()
    }

    private fun startCall() {
        val observer = MediaVolumeObserver(Handler(Looper.myLooper() ?: Looper.getMainLooper()))
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        mediaVolumeObserver = observer

        savedIsSpeakerphoneOn = audioManager.isSpeakerphoneOn
        savedIsMicrophoneMute = audioManager.isMicrophoneMute
        savedIsBluetoothScoOn = audioManager.isBluetoothScoOn

        setCommunicationMode(true)

        val listener = synchronized(lock) { audioFocusChangeListener }
        if (listener != null) {
            val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requestAudioFocusApi26(listener)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    listener,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN,
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
            if (granted) {
                AppLog.i(TAG, "Successful requestAudioFocus()")
            } else {
                AppLog.w(TAG, "Unable to requestAudioFocus()")
            }
        }
        updateAudioDevices()
    }

    private fun endCall() {
        mediaVolumeObserver?.let(contentResolver::unregisterContentObserver)
        mediaVolumeObserver = null

        // Runs before the mode is dropped: `activateDevice` refuses to touch routing outside
        // communication mode, and this is what clears the speakerphone flag we set.
        activateDevice(DeviceTypes.INVALID)
        synchronized(lock) { requestedDevice = DeviceTypes.DEFAULT }
        setCommunicationMode(false)

        val listener = synchronized(lock) { audioFocusChangeListener }
        val abandoned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = synchronized(lock) { audioFocusRequest }
            request != null &&
                audioManager.abandonAudioFocusRequest(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            listener != null &&
                audioManager.abandonAudioFocus(listener) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        if (abandoned) {
            AppLog.i(TAG, "Successful releaseAudioFocus()")
        } else {
            AppLog.w(TAG, "Unable to releaseAudioFocus()")
        }

        if (audioManager.isMicrophoneMute != savedIsMicrophoneMute) {
            audioManager.isMicrophoneMute = savedIsMicrophoneMute
        }
        if (audioManager.isSpeakerphoneOn != savedIsSpeakerphoneOn) {
            audioManager.isSpeakerphoneOn = savedIsSpeakerphoneOn
        }
        if (savedIsBluetoothScoOn) startBluetoothSco()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun requestAudioFocusApi26(listener: AudioManager.OnAudioFocusChangeListener): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            // Accepting delayed gain means a call started while another app holds exclusive focus
            // becomes audible when that app yields, instead of being silently denied.
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(
                listener,
                Handler(Looper.myLooper() ?: Looper.getMainLooper()),
            )
            .setAudioAttributes(attributes)
            .build()
        synchronized(lock) { audioFocusRequest = request }
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /**
     * Sets the system media volume from a `0f..1f` ratio.
     *
     * Used by the Go Live viewer so the in-app volume slider and the hardware keys address the same
     * thing rather than fighting each other.
     */
    fun setMediaVolume(ratio: Float) {
        assertOwnerThread()
        if (ratio !in 0f..1f) return
        if (!permissions.modifyAudioSettings) {
            AppLog.w(TAG, "Can't setMediaVolume, requires MODIFY_AUDIO_SETTINGS.")
            return
        }
        runCatching {
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                (ratio * maxMediaVolume).toInt(),
                0,
            )
        }.onFailure { AppLog.w(TAG, "Unable to set media volume", it) }
    }

    /**
     * Takes the earpiece out of the routing list while the local camera is on, and puts it back when
     * it goes off.
     *
     * Nobody holds a phone to their ear to look at video, and leaving the earpiece as the automatic
     * pick would make a video call inaudible the moment a headset disconnects.
     *
     * Reference: the body inlined in `StoreAudioManagerV2.handleVoiceStatesUpdated`.
     */
    fun setVideoActive(isMyVideoOn: Boolean) {
        assertOwnerThread()
        val earpieceIndex = DeviceTypes.EARPIECE.value
        val target = if (isMyVideoOn) false else hasTelephony
        val changed = synchronized(lock) {
            if (devices.getOrNull(earpieceIndex)?.isAvailable == target) return@synchronized false
            publishDevices(
                devices.map { device ->
                    if (device.type == DeviceTypes.EARPIECE) device.copy(isAvailable = target) else device
                },
            )
            true
        }
        if (changed) updateAudioDevices()
    }

    // endregion

    // region Device state

    /**
     * Re-derives availability and re-routes.
     *
     * The explicit request wins when it is still available; otherwise the automatic pick runs. This
     * is the single funnel every device-change path ends in, so "what should we be playing through
     * now" is answered in one place.
     *
     * Reference: `DiscordAudioManager.l()`.
     */
    fun updateAudioDevices() {
        val requested = synchronized(lock) { requestedDevice }
        refreshBluetoothHeadset()
        val snapshot = synchronized(lock) { devices }
        if (requested != DeviceTypes.DEFAULT && snapshot.getOrNull(requested.value)?.isAvailable == true) {
            activateDevice(requested)
        } else {
            activateDefaultDevice(snapshot)
        }
    }

    /**
     * Recomputes whether a Bluetooth headset is usable, and which one.
     *
     * "Usable" is deliberately two different questions. Normally it is the adapter's profile
     * connection state — a paired, connected headset. But once we are routed to Bluetooth in a call,
     * the answer becomes "is there an audio-connected device", because the adapter can still report
     * a connected profile after the SCO link has died.
     *
     * Reference: `DiscordAudioManager.g()` (recovered from DEX; JADX could not decompile it).
     */
    private fun refreshBluetoothHeadset() {
        if (!permissions.bluetooth) {
            AppLog.w(TAG, "refreshBluetoothHeadset() requires BLUETOOTH permission")
            return
        }

        val profile = headsetProfile
        val activeBluetoothDevice = profile?.let { headset ->
            try {
                headset.connectedDevices?.firstOrNull { device -> headset.isAudioConnected(device) }
            } catch (_: SecurityException) {
                null
            } catch (_: Throwable) {
                null
            } ?: HiddenBluetoothApi.getActiveDevice(headset)
        }

        val hasHeadset = try {
            val adapter = bluetoothManager?.adapter
            adapter != null && adapter.isEnabled &&
                adapter.getProfileConnectionState(BluetoothProfile.HEADSET) ==
                BluetoothAdapter.STATE_CONNECTED
        } catch (_: SecurityException) {
            false
        } catch (_: Throwable) {
            false
        }

        synchronized(lock) {
            val routedToBluetooth = inCommunicationMode && activeDevice == DeviceTypes.BLUETOOTH_HEADSET
            // Once routed to Bluetooth, "available" means an audio link exists — the adapter keeps
            // reporting a connected profile after the SCO link has died.
            val available = if (routedToBluetooth) activeBluetoothDevice != null else hasHeadset
            publishDevices(
                devices.map { device ->
                    if (device.type != DeviceTypes.BLUETOOTH_HEADSET) {
                        device
                    } else {
                        AudioDevice(
                            type = DeviceTypes.BLUETOOTH_HEADSET,
                            isAvailable = available,
                            id = activeBluetoothDevice?.address(),
                            name = activeBluetoothDevice?.deviceName(),
                        )
                    }
                },
            )
        }
    }

    /**
     * Picks the highest-priority available device.
     *
     * Priority runs wired headset > Bluetooth headset > speakerphone > earpiece; a physically
     * connected accessory is always a deliberate act by the user, so it outranks anything automatic.
     *
     * Reference: `DiscordAudioManager.a(List)`.
     */
    private fun activateDefaultDevice(snapshot: List<AudioDevice>) {
        val target = pickDefaultDevice(snapshot)
        AppLog.i(TAG, "Default device to activate: $target")
        activateDevice(target)
    }

    /**
     * Actually routes.
     *
     * Refuses to do anything outside communication mode: setting `isSpeakerphoneOn` while the
     * platform is in `MODE_NORMAL` changes routing for whatever else is playing.
     *
     * Reference: `DiscordAudioManager.b(DeviceTypes)`.
     */
    private fun activateDevice(deviceType: DeviceTypes) {
        val active = synchronized(lock) { inCommunicationMode }
        if (!active) {
            AppLog.w(TAG, "Unable to activate audio output outside Discord-requested communication mode")
            return
        }

        if (deviceType == DeviceTypes.BLUETOOTH_HEADSET) startBluetoothSco() else stopBluetoothSco()

        val speakerphone = deviceType == DeviceTypes.SPEAKERPHONE
        if (audioManager.isSpeakerphoneOn != speakerphone) {
            audioManager.isSpeakerphoneOn = speakerphone
        }

        synchronized(lock) {
            activeDevice = deviceType
            activeDeviceSubject.onNext(deviceType)
        }
        AppLog.i(TAG, "Activated device: $deviceType")
    }

    private fun setCommunicationMode(enabled: Boolean) {
        try {
            audioManager.mode =
                if (enabled) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
        } catch (ignored: SecurityException) {
            // Some OEM builds refuse the mode change; routing degrades but the call still runs.
        }
        synchronized(lock) { inCommunicationMode = enabled }
    }

    /**
     * Opens the SCO link, unless one is already open or being opened.
     *
     * `synchronized` on the instance rather than [lock]: the state is a single field and both
     * transitions are edge-triggered from broadcast callbacks that can overlap the caller.
     */
    @Synchronized
    @Suppress("DEPRECATION")
    private fun startBluetoothSco() {
        if (scoState == BluetoothScoState.ON || scoState == BluetoothScoState.TURNING_ON) return
        if (audioManager.isBluetoothScoOn) {
            scoState = BluetoothScoState.ON
            return
        }
        scoState = BluetoothScoState.TURNING_ON
        runCatching { audioManager.startBluetoothSco() }
            .onFailure { AppLog.w(TAG, "startBluetoothSco failed", it) }
    }

    @Synchronized
    @Suppress("DEPRECATION")
    private fun stopBluetoothSco() {
        if (scoState != BluetoothScoState.ON && scoState != BluetoothScoState.TURNING_ON) return
        if (!audioManager.isBluetoothScoOn) {
            scoState = BluetoothScoState.OFF
            return
        }
        scoState = BluetoothScoState.TURNING_OFF
        runCatching { audioManager.stopBluetoothSco() }
            .onFailure { AppLog.w(TAG, "stopBluetoothSco failed", it) }
    }

    /** Must hold [lock]. */
    private fun publishDevices(updated: List<AudioDevice>) {
        devices = updated
        devicesSubject.onNext(updated)
    }

    /** Must hold [lock]. */
    private fun setDeviceAvailability(type: DeviceTypes, available: Boolean) {
        publishDevices(
            devices.map { if (it.type == type) it.copy(isAvailable = available) else it },
        )
    }

    // endregion

    // region Platform callbacks

    private inner class AudioManagerListener : AudioManagerBroadcastListener {

        override fun onAudioBecomingNoisy(context: Context) = Unit

        override fun onMicrophoneMuteChanged(context: Context, muted: Boolean) = Unit

        override fun onSpeakerphoneStateChanged(context: Context, enabled: Boolean) = Unit

        /**
         * The SCO link changed under us.
         *
         * `Connected` only records the fact. `Disconnected` is where the work is: if we were `ON`,
         * the link dropped on its own and is worth one attempt to re-open; if we were `TURNING_ON`,
         * the request was refused and the Bluetooth device has to be dropped from the list so
         * routing falls back to something that works.
         */
        @MainThread
        override fun onScoAudioStateUpdate(context: Context, update: ScoAudioState.Update) {
            when (update.state) {
                ScoAudioState.Connected -> {
                    AppLog.i(TAG, "[onScoAudioStateUpdate] scoAudioStateUpdate = $update")
                    startBluetoothSco()
                }
                ScoAudioState.Disconnected -> {
                    AppLog.i(TAG, "[onScoAudioStateUpdate] scoAudioStateUpdate = $update")
                    val previous = scoState
                    stopBluetoothSco()
                    when (previous) {
                        BluetoothScoState.ON -> {
                            if (synchronized(lock) { inCommunicationMode }) {
                                AppLog.i(TAG, "SCO off detected directly from ON. Refreshing Bluetooth device")
                                startBluetoothSco()
                                updateAudioDevices()
                            }
                        }
                        BluetoothScoState.TURNING_ON -> {
                            AppLog.i(
                                TAG,
                                "Unable to turn on SCO. Clearing Bluetooth device. mode: ${audioManager.mode}",
                            )
                            val snapshot = synchronized(lock) {
                                publishDevices(
                                    devices.map { device ->
                                        if (device.type == DeviceTypes.BLUETOOTH_HEADSET) {
                                            AudioDevice(DeviceTypes.BLUETOOTH_HEADSET)
                                        } else {
                                            device
                                        }
                                    },
                                )
                                devices
                            }
                            activateDefaultDevice(snapshot)
                        }
                        else -> Unit
                    }
                }
                else -> Unit
            }
        }

        /**
         * A wired headset takes the earpiece's place while it is plugged in, and hands it back on
         * unplug — on a device that has an earpiece at all.
         */
        @MainThread
        override fun onWiredHeadsetPlug(context: Context, wiredHeadsetState: WiredHeadsetState) {
            AppLog.i(TAG, "[onWiredHeadsetPlug] wiredHeadsetState = $wiredHeadsetState")
            synchronized(lock) {
                if (wiredHeadsetState.isPlugged) {
                    setDeviceAvailability(DeviceTypes.WIRED_HEADSET, true)
                    setDeviceAvailability(DeviceTypes.EARPIECE, false)
                } else {
                    setDeviceAvailability(DeviceTypes.WIRED_HEADSET, false)
                    setDeviceAvailability(DeviceTypes.EARPIECE, hasTelephony)
                }
            }
            updateAudioDevices()
        }
    }

    private inner class BluetoothListener : BluetoothBroadcastListener {

        @MainThread
        override fun onActiveDeviceChanged(device: BluetoothDevice?) {
            if (device != null) updateAudioDevices()
        }

        @MainThread
        override fun onHeadsetAudioStateChanged(
            context: Context,
            update: BluetoothHeadsetAudioState.Update,
        ) {
            val state = update.state ?: BluetoothHeadsetAudioState.Disconnected
            AppLog.i(
                TAG,
                "[onHeadsetAudioStateChanged] state: $state, device: ${update.device?.deviceName()}",
            )
            when (state) {
                BluetoothHeadsetAudioState.Disconnected -> updateAudioDevices()
                BluetoothHeadsetAudioState.Connected -> synchronized(lock) {
                    publishDevices(
                        devices.map { device ->
                            if (device.type != DeviceTypes.BLUETOOTH_HEADSET) {
                                device
                            } else {
                                AudioDevice(
                                    type = DeviceTypes.BLUETOOTH_HEADSET,
                                    isAvailable = true,
                                    id = update.device?.address(),
                                    name = update.device?.deviceName(),
                                )
                            }
                        },
                    )
                }
                BluetoothHeadsetAudioState.Connecting -> Unit
            }
        }

        /**
         * Pairing changed.
         *
         * The disconnect branch only re-routes when the device that left is the one we were tracking
         * — a second, unrelated headset disconnecting must not knock the call off the one in use.
         */
        @MainThread
        override fun onHeadsetConnectionStateChanged(
            context: Context,
            update: BluetoothProfileConnectionState.Update,
        ) {
            val state = update.state ?: BluetoothProfileConnectionState.Disconnected
            AppLog.i(
                TAG,
                "[onHeadsetConnectionStateChanged] state: $state, device: ${update.device?.deviceName()}",
            )
            when (state) {
                BluetoothProfileConnectionState.Disconnected -> {
                    val trackedId = synchronized(lock) {
                        devices.getOrNull(DeviceTypes.BLUETOOTH_HEADSET.value)?.id
                    }
                    if (trackedId != null && update.device?.address() == trackedId) {
                        updateAudioDevices()
                    }
                }
                BluetoothProfileConnectionState.Connected -> {
                    val active = synchronized(lock) { activeDevice }
                    if (active == DeviceTypes.BLUETOOTH_HEADSET) {
                        refreshBluetoothHeadset()
                    } else {
                        updateAudioDevices()
                    }
                }
                else -> AppLog.i(TAG, "[onHeadsetConnectionStateChanged] $state...")
            }
        }

        @MainThread
        override fun onHeadsetProfileConnected(headsetProfile: BluetoothHeadset?) {
            this@DiscordAudioManager.headsetProfile = headsetProfile
        }
    }

    /**
     * Republishes the media volume when the user moves the hardware keys.
     *
     * `Settings.System` is watched wholesale because there is no narrower URI for the media stream
     * index; the observer therefore fires for unrelated settings too and the value is simply re-read.
     */
    private inner class MediaVolumeObserver(handler: Handler) : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            currentMediaVolume = volume
            mediaVolumeSubject.onNext(volume)
        }
    }

    // endregion

    /** Diagnostics snapshot. Carries no user content; the Bluetooth device name is deliberately omitted. */
    fun describe(): Map<String, Any?> = synchronized(lock) {
        mapOf(
            "activeDevice" to activeDevice.name,
            "requestedDevice" to requestedDevice.name,
            "scoState" to scoState.name,
            "inCommunicationMode" to inCommunicationMode,
            "hasTelephony" to hasTelephony,
            "hasHeadsetProfile" to (headsetProfile != null),
            "availableDevices" to devices.filter { it.isAvailable }.map { it.type.name },
            "permissions" to permissions.toString(),
        )
    }

    /** Releases the receivers. Only used by tests and by a process that is tearing voice down. */
    fun release() {
        audioReceiver.unregister()
        bluetoothReceiver.unregister()
    }

    private fun assertOwnerThread() {
        check(Thread.currentThread().id == ownerThreadId) {
            "Method was not called from a valid thread"
        }
    }

    @Suppress("DEPRECATION")
    private fun initialDevices(): List<AudioDevice> = listOf(
        AudioDevice(DeviceTypes.SPEAKERPHONE, isAvailable = true),
        AudioDevice(DeviceTypes.WIRED_HEADSET, isAvailable = audioManager.isWiredHeadsetOn),
        AudioDevice(DeviceTypes.EARPIECE, isAvailable = hasTelephony),
        AudioDevice(DeviceTypes.BLUETOOTH_HEADSET, isAvailable = false),
    )

    private fun BluetoothDevice.address(): String? = runCatching { address }.getOrNull()

    private fun BluetoothDevice.deviceName(): String? = try {
        name
    } catch (_: SecurityException) {
        null
    } catch (_: Throwable) {
        null
    }

    companion object {
        private const val TAG = "DiscordAudioManager"

        /**
         * Routing preference, lowest first. Scanned backwards, so a wired headset wins over
         * Bluetooth, which wins over the speaker, which wins over the earpiece.
         */
        private val DEVICE_PRIORITY = listOf(
            DeviceTypes.EARPIECE,
            DeviceTypes.SPEAKERPHONE,
            DeviceTypes.BLUETOOTH_HEADSET,
            DeviceTypes.WIRED_HEADSET,
        )

        /**
         * The automatic routing decision, as a pure function of the device list.
         *
         * Split out from the router so it can be tested without a `Context`: this is the rule a user
         * actually notices — plugging a headset in mid-call must move the audio — and it is the one
         * piece of the router that has no platform dependency at all.
         */
        @JvmStatic
        fun pickDefaultDevice(devices: List<AudioDevice>): DeviceTypes =
            DEVICE_PRIORITY.lastOrNull { devices.getOrNull(it.value)?.isAvailable == true }
                ?: DeviceTypes.SPEAKERPHONE

        /**
         * The four routing targets with nothing known about them yet.
         *
         * Used as the store's initial state so the UI has a stable, correctly ordered list to index
         * into before the router has published anything.
         */
        @JvmStatic
        fun defaultAudioDevices(): List<AudioDevice> = listOf(
            AudioDevice(DeviceTypes.SPEAKERPHONE),
            AudioDevice(DeviceTypes.WIRED_HEADSET),
            AudioDevice(DeviceTypes.EARPIECE),
            AudioDevice(DeviceTypes.BLUETOOTH_HEADSET),
        )
    }
}
