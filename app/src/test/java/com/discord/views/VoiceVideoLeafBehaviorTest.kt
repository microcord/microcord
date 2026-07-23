package com.discord.views

import com.discord.api.premium.PremiumTier
import com.discord.api.voice.state.VoiceState
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.stores.StoreVoiceParticipants
import com.discord.views.calls.rotatedFrameSize
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceVideoLeafBehaviorTest {
    @Test
    fun voiceStatePriorityMatchesReference() {
        assertEquals(VoiceDisplayState.SPEAKING, voiceDisplayState(voiceUser(speaking = true, ringing = true)))
        assertEquals(VoiceDisplayState.RINGING, voiceDisplayState(voiceUser(ringing = true, connected = false)))
        assertEquals(VoiceDisplayState.DISCONNECTED, voiceDisplayState(voiceUser(connected = false, muted = true)))
        assertEquals(VoiceDisplayState.MUTED, voiceDisplayState(voiceUser(muted = true)))
        assertEquals(VoiceDisplayState.CONNECTED, voiceDisplayState(voiceUser()))
    }

    @Test
    fun rotatedResolutionSwapsQuarterTurns() {
        assertEquals(1920, rotatedFrameSize(1920, 1080, 0).first)
        assertEquals(1080, rotatedFrameSize(1920, 1080, 90).first)
        assertEquals(1920, rotatedFrameSize(1920, 1080, -180).first)
    }

    private fun voiceUser(
        speaking: Boolean = false,
        ringing: Boolean = false,
        connected: Boolean = true,
        muted: Boolean = false,
    ) = object : StoreVoiceParticipants.VoiceUser {
        override val user = TestUser
        override val voiceState = VoiceState(1, false, null, muted, false, false, false, false, 1, false, null, null, 1)
        override val guildMember: GuildMember? = null
        override val displayName = "Test"
        override val isSpeaking = speaking
        override val isRinging = ringing
        override val isConnected = connected
    }

    private object TestUser : User {
        override val isSystemUser = false
        override val flags = 0
        override val publicFlags = 0
        override val avatar: String? = null
        override val bio: String? = null
        override val id = 1L
        override val bannerColor: String? = null
        override val banner: String? = null
        override val discriminator = 1
        override val username = "Test"
        override val isBot = false
        override val premiumTier = PremiumTier.NONE
    }
}
