package com.discord.rtcconnection

import com.discord.api.voice.state.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePropsTrackerTest {

    @Test
    fun tracksReferenceVoiceSpeakerAndListenerProperties() {
        val tracker = VoicePropsTracker(
            guildId = GUILD_ID,
            channelId = CHANNEL_ID,
            localUserId = LOCAL_USER_ID,
            voiceStates = mapOf(
                LOCAL_USER_ID to voiceState(LOCAL_USER_ID),
                2L to voiceState(2L),
                3L to voiceState(3L, selfDeaf = true),
            ),
        )

        assertEquals(zeroProps(), tracker.props())

        tracker.handleOnSpeaking(LOCAL_USER_ID, true)
        tracker.handleOnSpeaking(2L, true)
        tracker.handleVoiceStateUpdate(voiceState(4L))
        tracker.handleVoiceStateUpdate(voiceState(2L, channelId = null))

        assertEquals(
            mapOf(
                "max_voice_state_count" to 4,
                "total_voice_state_count" to 1,
                "max_listener_count" to 3,
                "total_listener_count" to 3,
                "max_speaker_count" to 2,
                "total_speaker_count" to 2,
            ),
            tracker.props(),
        )
    }

    @Test
    fun ignoresOtherGuildsAndChannels() {
        val tracker = VoicePropsTracker(GUILD_ID, CHANNEL_ID, LOCAL_USER_ID, emptyMap())

        tracker.handleVoiceStateUpdate(voiceState(2L, guildId = 99L))
        tracker.handleVoiceStateUpdate(voiceState(3L, channelId = 999L))

        assertEquals(zeroProps(), tracker.props())
    }

    private fun VoicePropsTracker.props(): Map<String, Any> =
        linkedMapOf<String, Any>().also(::getProps)

    private fun zeroProps(): Map<String, Any> = mapOf(
        "max_voice_state_count" to 0,
        "total_voice_state_count" to 0,
        "max_listener_count" to 0,
        "total_listener_count" to 0,
        "max_speaker_count" to 0,
        "total_speaker_count" to 0,
    )

    private fun voiceState(
        userId: Long,
        guildId: Long = GUILD_ID,
        channelId: Long? = CHANNEL_ID,
        selfDeaf: Boolean = false,
    ) = VoiceState(
        userId = userId,
        suppress = false,
        sessionId = "session-$userId",
        selfMute = false,
        selfDeaf = selfDeaf,
        selfVideo = false,
        selfStream = false,
        mute = false,
        guildId = guildId,
        deaf = false,
        member = null,
        requestToSpeakTimestamp = null,
        channelId = channelId,
    )

    private companion object {
        private const val GUILD_ID = 10L
        private const val CHANNEL_ID = 20L
        private const val LOCAL_USER_ID = 1L
    }
}
