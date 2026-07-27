package com.discord.rtcconnection.enums

import android.bluetooth.BluetoothDevice
import android.content.Intent

/**
 * The platform broadcasts the audio router listens to.
 *
 * Declared here rather than inline at the registration site because the same action strings are used
 * three times: to build the [android.content.IntentFilter], to route an incoming intent back to a
 * typed callback, and in the log line that records what was registered.
 *
 * Declaration order matches the reference's `$VALUES` array, so ordinals are preserved.
 * Reference: `com.discord.rtcconnection.enums.AudioManagerBroadcastAction`.
 */
enum class AudioManagerBroadcastAction(val action: String) {
    AudioBecomingNoisy("android.media.AUDIO_BECOMING_NOISY"),
    HeadsetPlug("android.intent.action.HEADSET_PLUG"),
    ScoAudioStateUpdated("android.media.ACTION_SCO_AUDIO_STATE_UPDATED"),
    MicrophoneMuteChanged("android.media.action.MICROPHONE_MUTE_CHANGED"),
    SpeakerphoneStateChanged("android.media.action.SPEAKERPHONE_STATE_CHANGED"),
    ;

    companion object {
        fun from(action: String?): AudioManagerBroadcastAction? =
            values().firstOrNull { it.action == action }
    }
}

/** Reference: `com.discord.rtcconnection.enums.BluetoothBroadcastAction`. */
enum class BluetoothBroadcastAction(val action: String) {
    HeadsetConnectionStateChanged("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED"),
    HeadsetAudioStateChanged("android.bluetooth.headset.profile.action.AUDIO_STATE_CHANGED"),
    AdapterConnectionStateChanged("android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED"),
    AdapterStateChanged("android.bluetooth.adapter.action.STATE_CHANGED"),
    ActiveDeviceChanged("android.bluetooth.headset.profile.action.ACTIVE_DEVICE_CHANGED"),
    ;

    companion object {
        fun from(action: String?): BluetoothBroadcastAction? =
            values().firstOrNull { it.action == action }
    }
}

/**
 * `AudioManager.EXTRA_SCO_AUDIO_STATE`.
 *
 * This is the *system's* view of the SCO link, which is not the same as
 * `DiscordAudioManager.BluetoothScoState` — that one also records what we asked for, so a request
 * that never completes can be told apart from one that was never made.
 *
 * Reference: `com.discord.rtcconnection.enums.ScoAudioState`.
 */
enum class ScoAudioState(val value: Int) {
    Disconnected(0),
    Connected(1),
    Connecting(2),
    Error(-1),
    ;

    /** Both halves of a `ACTION_SCO_AUDIO_STATE_UPDATED` broadcast. */
    data class Update(val state: ScoAudioState?, val previousState: ScoAudioState?)

    companion object {
        fun from(value: Int): ScoAudioState? = values().firstOrNull { it.value == value }

        fun fromIntent(intent: Intent): Update {
            require(intent.action == AudioManagerBroadcastAction.ScoAudioStateUpdated.action) {
                "Failed requirement."
            }
            return Update(
                state = intent.optIntExtra(EXTRA_SCO_AUDIO_STATE)?.let(::from),
                previousState = intent.optIntExtra(EXTRA_PREVIOUS_SCO_AUDIO_STATE)?.let(::from),
            )
        }

        private const val EXTRA_SCO_AUDIO_STATE = "android.media.extra.SCO_AUDIO_STATE"
        private const val EXTRA_PREVIOUS_SCO_AUDIO_STATE =
            "android.media.extra.SCO_AUDIO_PREVIOUS_STATE"
    }
}

/**
 * `BluetoothHeadset.EXTRA_STATE` on `ACTION_AUDIO_STATE_CHANGED` — whether audio is actually flowing
 * over the headset, as opposed to the headset merely being paired.
 *
 * Reference: `com.discord.rtcconnection.enums.BluetoothHeadsetAudioState`.
 */
enum class BluetoothHeadsetAudioState(val value: Int) {
    Disconnected(10),
    Connecting(11),
    Connected(12),
    ;

    data class Update(
        val state: BluetoothHeadsetAudioState?,
        val previousState: BluetoothHeadsetAudioState?,
        val device: BluetoothDevice?,
    )

    companion object {
        fun from(value: Int): BluetoothHeadsetAudioState? = values().firstOrNull { it.value == value }

        fun fromIntent(intent: Intent): Update {
            require(intent.action == BluetoothBroadcastAction.HeadsetAudioStateChanged.action) {
                "Failed requirement."
            }
            return Update(
                state = intent.optIntExtra(EXTRA_STATE)?.let(::from),
                previousState = intent.optIntExtra(EXTRA_PREVIOUS_STATE)?.let(::from),
                device = intent.bluetoothDeviceExtra(),
            )
        }
    }
}

/**
 * `BluetoothProfile.EXTRA_STATE` on the headset profile's `ACTION_CONNECTION_STATE_CHANGED` — the
 * pairing state.
 *
 * Reference: `com.discord.rtcconnection.enums.BluetoothProfileConnectionState`.
 */
enum class BluetoothProfileConnectionState(val value: Int) {
    Disconnected(0),
    Connecting(1),
    Connected(2),
    Disconnecting(3),
    ;

    data class Update(
        val previousState: BluetoothProfileConnectionState?,
        val state: BluetoothProfileConnectionState?,
        val device: BluetoothDevice?,
    )

    companion object {
        fun from(value: Int): BluetoothProfileConnectionState? =
            values().firstOrNull { it.value == value }

        fun fromIntent(intent: Intent): Update {
            require(intent.action == BluetoothBroadcastAction.HeadsetConnectionStateChanged.action) {
                "Failed requirement."
            }
            return Update(
                previousState = intent.optIntExtra(EXTRA_PREVIOUS_STATE)?.let(::from),
                state = intent.optIntExtra(EXTRA_STATE)?.let(::from),
                device = intent.bluetoothDeviceExtra(),
            )
        }
    }
}

/**
 * `ACTION_HEADSET_PLUG`.
 *
 * A sealed type rather than a boolean because the plugged case carries the accessory's name and
 * whether it has a microphone, and the router only forces the earpiece back on for a headset that
 * cannot capture.
 *
 * Reference: `b.a.q.l0.a` (`WiredHeadsetState`).
 */
sealed class WiredHeadsetState(val isPlugged: Boolean) {
    data class Plugged(val name: String?, val hasMicrophone: Boolean) : WiredHeadsetState(true)

    object Unplugged : WiredHeadsetState(false) {
        override fun toString(): String = "Unplugged"
    }

    companion object {
        fun fromIntent(intent: Intent): WiredHeadsetState {
            require(intent.action == AudioManagerBroadcastAction.HeadsetPlug.action) {
                "Failed requirement."
            }
            return if (intent.getIntExtra("state", 0) == 1) {
                Plugged(
                    name = intent.getStringExtra("name"),
                    hasMicrophone = intent.getIntExtra("microphone", 0) == 1,
                )
            } else {
                Unplugged
            }
        }
    }
}

private const val EXTRA_STATE = "android.bluetooth.profile.extra.STATE"
private const val EXTRA_PREVIOUS_STATE = "android.bluetooth.profile.extra.PREVIOUS_STATE"

/**
 * `getIntExtra` cannot distinguish "absent" from "present and equal to the default", and the
 * difference matters: an absent state must stay `null` so the caller falls back to `Disconnected`
 * rather than believing the system reported a real value.
 */
private fun Intent.optIntExtra(key: String): Int? =
    if (hasExtra(key)) getIntExtra(key, 0) else null

@Suppress("DEPRECATION")
private fun Intent.bluetoothDeviceExtra(): BluetoothDevice? =
    getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
