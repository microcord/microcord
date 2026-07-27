package com.discord.rtcconnection.audio

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import com.discord.app.AppLog
import com.discord.rtcconnection.enums.AudioManagerBroadcastAction
import com.discord.rtcconnection.enums.BluetoothBroadcastAction
import com.discord.rtcconnection.enums.BluetoothHeadsetAudioState
import com.discord.rtcconnection.enums.BluetoothProfileConnectionState
import com.discord.rtcconnection.enums.ScoAudioState
import com.discord.rtcconnection.enums.WiredHeadsetState
import java.lang.reflect.Method
import org.webrtc.ThreadUtils

/**
 * Which audio-related permissions this process actually holds, sampled once.
 *
 * The router is expected to work with any subset of them: a build or a user that withheld
 * `MODIFY_AUDIO_SETTINGS` gets no routing rather than a crash, and one without `BLUETOOTH` gets no
 * Bluetooth device rather than a `SecurityException` on every device refresh.
 *
 * Reference: `b.a.q.k0.b`.
 */
internal class AudioPermissions(context: Context) {
    val modifyAudioSettings: Boolean = context.holds(android.Manifest.permission.MODIFY_AUDIO_SETTINGS)
    val recordAudio: Boolean = context.holds(android.Manifest.permission.RECORD_AUDIO)
    /**
     * The reference targeted API 30 and only checked `BLUETOOTH`. From API 31 that permission is no
     * longer granted at install time and the equivalent capability is the runtime `BLUETOOTH_CONNECT`,
     * so either one counts here — otherwise Bluetooth routing is dead on every modern device.
     */
    val bluetooth: Boolean = context.holds(android.Manifest.permission.BLUETOOTH) ||
        context.holds(BLUETOOTH_CONNECT)

    override fun toString(): String =
        "AudioPermissions(modifyAudioSettings=$modifyAudioSettings, " +
            "recordAudio=$recordAudio, bluetooth=$bluetooth)"

    private companion object {
        /** Not a constant before API 31; referenced by name so the project still builds on older SDKs. */
        private const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"

        private fun Context.holds(permission: String): Boolean =
            checkPermission(permission, Process.myPid(), Process.myUid()) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

/**
 * The two `BluetoothHeadset` members that are public API on some releases and hidden on others.
 *
 * Both are resolved reflectively and cached as `null` on failure, so a platform that hides them
 * simply loses the "which headset is active" refinement instead of failing to route at all.
 *
 * Reference: `b.a.q.k0.d`.
 */
internal object HiddenBluetoothApi {

    /** `BluetoothHeadset.ACTION_ACTIVE_DEVICE_CHANGED`, or `null` where it does not exist. */
    val activeDeviceChangedAction: String? by lazy {
        runCatching {
            BluetoothHeadset::class.java.getField("ACTION_ACTIVE_DEVICE_CHANGED").get(null) as? String
        }.getOrNull()
    }

    /** `BluetoothHeadset.getActiveDevice()`, or `null` where it does not exist. */
    private val getActiveDeviceMethod: Method? by lazy {
        runCatching { BluetoothHeadset::class.java.getMethod("getActiveDevice") }.getOrNull()
    }

    fun getActiveDevice(headsetProfile: BluetoothHeadset): BluetoothDevice? =
        runCatching { getActiveDeviceMethod?.invoke(headsetProfile) as? BluetoothDevice }.getOrNull()
}

/** Reference: `b.a.q.k0.h`. */
internal interface AudioManagerBroadcastListener {
    fun onAudioBecomingNoisy(context: Context)
    fun onMicrophoneMuteChanged(context: Context, muted: Boolean)
    fun onScoAudioStateUpdate(context: Context, update: ScoAudioState.Update)
    fun onSpeakerphoneStateChanged(context: Context, enabled: Boolean)
    fun onWiredHeadsetPlug(context: Context, wiredHeadsetState: WiredHeadsetState)
}

/** Reference: `b.a.q.k0.i`. */
internal interface BluetoothBroadcastListener {
    fun onActiveDeviceChanged(device: BluetoothDevice?)
    fun onHeadsetAudioStateChanged(context: Context, update: BluetoothHeadsetAudioState.Update)
    fun onHeadsetConnectionStateChanged(context: Context, update: BluetoothProfileConnectionState.Update)
    fun onHeadsetProfileConnected(headsetProfile: BluetoothHeadset?)
}

/**
 * Turns `AudioManager`'s broadcasts into typed callbacks.
 *
 * Reference: `b.a.q.k0.a`.
 */
internal class AudioManagerBroadcastReceiver(
    private val context: Context,
    private val listener: AudioManagerBroadcastListener,
) : BroadcastReceiver() {

    /**
     * No `RECEIVER_EXPORTED` / `RECEIVER_NOT_EXPORTED` flag: every action registered here is a
     * protected system broadcast, which API 34's export requirement explicitly exempts. Adding a
     * non-system action to this filter would make the flag mandatory.
     */
    fun register(actions: List<AudioManagerBroadcastAction>) {
        ThreadUtils.checkIsOnMainThread()
        AppLog.i(TAG, "registering for broadcasts with actions: $actions")
        val filter = IntentFilter()
        actions.forEach { filter.addAction(it.action) }
        context.registerReceiver(this, filter)
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(this) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (AudioManagerBroadcastAction.from(intent.action)) {
            AudioManagerBroadcastAction.AudioBecomingNoisy ->
                listener.onAudioBecomingNoisy(context)
            AudioManagerBroadcastAction.HeadsetPlug ->
                listener.onWiredHeadsetPlug(context, WiredHeadsetState.fromIntent(intent))
            AudioManagerBroadcastAction.ScoAudioStateUpdated ->
                listener.onScoAudioStateUpdate(context, ScoAudioState.fromIntent(intent))
            AudioManagerBroadcastAction.MicrophoneMuteChanged ->
                listener.onMicrophoneMuteChanged(context, intent.getBooleanExtra(EXTRA_STATE, false))
            AudioManagerBroadcastAction.SpeakerphoneStateChanged ->
                listener.onSpeakerphoneStateChanged(context, intent.getBooleanExtra(EXTRA_STATE, false))
            null -> Unit
        }
    }

    private companion object {
        private const val TAG = "AudioManagerBroadcastReceiver"
        private const val EXTRA_STATE = "state"
    }
}

/**
 * Turns the Bluetooth headset profile's broadcasts into typed callbacks, and holds the profile proxy.
 *
 * The proxy is asynchronous — `getProfileProxy` returns before `onServiceConnected` — so the router
 * cannot assume a headset is knowable at construction time and must re-derive availability whenever
 * the proxy arrives.
 *
 * Reference: `b.a.q.k0.c`.
 */
internal class BluetoothBroadcastReceiver(
    private val context: Context,
    private val listener: BluetoothBroadcastListener,
) : BroadcastReceiver(), BluetoothProfile.ServiceListener {

    private var registered = false
    private var headsetProfile: BluetoothHeadset? = null

    @Suppress("DEPRECATION")
    fun register() {
        ThreadUtils.checkIsOnMainThread()
        if (registered) return

        AppLog.i(TAG, "registering for broadcasts with actions: $ACTIONS")
        val filter = IntentFilter()
        ACTIONS.forEach { filter.addAction(it.action) }
        context.registerReceiver(this, filter)

        // The active-device broadcast is hidden API on some releases; registering for it is a
        // refinement, so a platform that does not define the action must not take the rest down.
        HiddenBluetoothApi.activeDeviceChangedAction?.let { action ->
            runCatching { context.registerReceiver(this, IntentFilter(action)) }
        }

        val listening = try {
            BluetoothAdapter.getDefaultAdapter()
                ?.getProfileProxy(context, this, BluetoothProfile.HEADSET) ?: false
        } catch (e: SecurityException) {
            AppLog.w(TAG, "failed to get BluetoothHeadset profile: $e")
            false
        }
        if (listening) {
            AppLog.i(TAG, "listening for HeadsetProfile proxy")
        } else {
            AppLog.w(TAG, "listening for HeadsetProfile proxy failed")
        }
        registered = true
    }

    @Suppress("DEPRECATION")
    fun unregister() {
        if (!registered) return
        runCatching { context.unregisterReceiver(this) }
        headsetProfile?.let { profile ->
            runCatching {
                BluetoothAdapter.getDefaultAdapter()
                    ?.closeProfileProxy(BluetoothProfile.HEADSET, profile)
            }
        }
        headsetProfile = null
        registered = false
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == HiddenBluetoothApi.activeDeviceChangedAction) {
            @Suppress("DEPRECATION")
            listener.onActiveDeviceChanged(intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE))
            return
        }
        when (BluetoothBroadcastAction.from(intent.action)) {
            BluetoothBroadcastAction.HeadsetAudioStateChanged ->
                listener.onHeadsetAudioStateChanged(
                    context,
                    BluetoothHeadsetAudioState.fromIntent(intent),
                )
            BluetoothBroadcastAction.HeadsetConnectionStateChanged ->
                listener.onHeadsetConnectionStateChanged(
                    context,
                    BluetoothProfileConnectionState.fromIntent(intent),
                )
            else -> Unit
        }
    }

    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
        if (profile != BluetoothProfile.HEADSET) return
        headsetProfile = proxy as? BluetoothHeadset
        listener.onHeadsetProfileConnected(headsetProfile)
    }

    override fun onServiceDisconnected(profile: Int) {
        if (profile != BluetoothProfile.HEADSET) return
        headsetProfile = null
        listener.onHeadsetProfileConnected(null)
    }

    private companion object {
        private const val TAG = "BluetoothBroadcastReceiver"

        private val ACTIONS: Set<BluetoothBroadcastAction> =
            BluetoothBroadcastAction.values().toSet()
    }
}
