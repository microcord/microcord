package com.discord.utilities.media

import android.media.AudioAttributes
import androidx.annotation.RawRes

/**
 * One of the app's short feedback sounds, together with the audio routing it must use.
 *
 * [contentType] and [usage] are `AudioAttributes` constants and they are load-bearing: the
 * telephony-style sounds ([SOUND_CALL_RINGING], [SOUND_CALL_CALLING], [SOUND_RECONNECT]) declare a
 * communication usage so the system ducks media and routes them to the call stream, while every
 * other sound is plain sonification.
 *
 * Reference: `com.discord.utilities.media.AppSound`.
 */
data class AppSound(
    @RawRes val resId: Int,
    val shouldLoop: Boolean = false,
    val contentType: Int,
    val usage: Int,
) {
    companion object {
        private const val SONIFICATION = AudioAttributes.CONTENT_TYPE_SONIFICATION

        // Voice-state feedback: self mute/deafen and stream lifecycle.
        @JvmField val SOUND_DEAFEN = sonification(com.discord.R.raw.deafen)
        @JvmField val SOUND_UNDEAFEN = sonification(com.discord.R.raw.undeafen)
        @JvmField val SOUND_MUTE = sonification(com.discord.R.raw.mute)
        @JvmField val SOUND_UNMUTE = sonification(com.discord.R.raw.unmute)
        @JvmField val SOUND_STREAM_ENDED = sonification(com.discord.R.raw.stream_ended)
        @JvmField val SOUND_STREAM_STARTED = sonification(com.discord.R.raw.stream_started)
        @JvmField val SOUND_STREAM_USER_JOINED = sonification(com.discord.R.raw.stream_user_joined)
        @JvmField val SOUND_STREAM_USER_LEFT = sonification(com.discord.R.raw.stream_user_left)

        // Other participants entering, leaving or being moved between channels.
        @JvmField val SOUND_USER_JOINED = sonification(com.discord.R.raw.user_join)
        @JvmField val SOUND_USER_LEFT = sonification(com.discord.R.raw.user_leave)
        @JvmField val SOUND_USER_MOVED = sonification(com.discord.R.raw.user_moved)

        /** Incoming call. Loops until the call is answered or cancelled. */
        @JvmField val SOUND_CALL_RINGING = AppSound(
            resId = com.discord.R.raw.call_ringing,
            shouldLoop = true,
            contentType = SONIFICATION,
            usage = AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
        )

        /** Outgoing call. Loops until the other side picks up. */
        @JvmField val SOUND_CALL_CALLING = AppSound(
            resId = com.discord.R.raw.call_calling,
            shouldLoop = true,
            contentType = SONIFICATION,
            usage = AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
        )

        /** Voice connection re-established. Signalling usage, but does not loop. */
        @JvmField val SOUND_RECONNECT = AppSound(
            resId = com.discord.R.raw.reconnect,
            shouldLoop = false,
            contentType = SONIFICATION,
            usage = AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
        )

        private fun sonification(@RawRes resId: Int) = AppSound(
            resId = resId,
            contentType = SONIFICATION,
            usage = AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
        )
    }
}
