package com.discord.views

import com.discord.api.premium.PremiumTier
import com.discord.api.voice.state.VoiceState
import com.discord.models.user.User
import com.discord.stores.StoreVoiceParticipants
import com.discord.views.calls.ZoomedDraw
import com.discord.views.calls.rotatedFrameSize
import com.discord.views.calls.zoomedDraw
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceVideoLeafBehaviorTest {
    @Test
    fun voiceStatePriorityMatchesReference() {
        assertEquals(VoiceDisplayState.SPEAKING, voiceDisplayState(voiceUser(speaking = true, ringing = true)))
        assertEquals(VoiceDisplayState.RINGING, voiceDisplayState(voiceUser(ringing = true, connected = false)))
        assertEquals(VoiceDisplayState.DISCONNECTED, voiceDisplayState(voiceUser(connected = false, muted = true)))
        assertEquals(VoiceDisplayState.MUTED, voiceDisplayState(voiceUser(muted = true)))
        assertEquals(VoiceDisplayState.MUTED, voiceDisplayState(voiceUser(speaking = true, muted = true)))
        assertEquals(VoiceDisplayState.CONNECTED, voiceDisplayState(voiceUser()))
    }

    @Test
    fun rotatedResolutionSwapsQuarterTurns() {
        assertEquals(1920, rotatedFrameSize(1920, 1080, 0).first)
        assertEquals(1080, rotatedFrameSize(1920, 1080, 90).first)
        assertEquals(1920, rotatedFrameSize(1920, 1080, -180).first)
    }

    @Test
    fun unzoomedLandscapeIsLetterboxedOnATallScreen() {
        val draw = zoomedDraw(1920, 1080, 0, 0, 1080, 2400, 1f, 0.5f, 0.5f)
        // Full width, centred vertically at its own shape.
        assertEquals(0, draw.x)
        assertEquals(896, draw.y)
        assertEquals(1080, draw.width)
        assertEquals(608, draw.height)
        // Edge to edge of the drawn area is edge to edge of the picture.
        assertEquals(0f, pictureX(draw, 1920, 1080, 1080, 2400, 0f), 1e-4f)
        assertEquals(1f, pictureX(draw, 1920, 1080, 1080, 2400, 1f), 1e-4f)
    }

    @Test
    fun zoomedPictureCoversTheScreenAndFollowsItsCentre() {
        val draw = zoomedDraw(1920, 1080, 0, 0, 1080, 2400, 4f, 0.25f, 0.25f)
        // 4x of a 1080-wide fit is 4320 wide and 2430 tall: it now covers the whole screen.
        assertEquals(0, draw.x)
        assertEquals(0, draw.y)
        assertEquals(1080, draw.width)
        assertEquals(2400, draw.height)
        // A quarter of the width, centred on the requested x.
        assertEquals(0.125f, pictureX(draw, 1920, 1080, 1080, 2400, 0f), 1e-4f)
        assertEquals(0.375f, pictureX(draw, 1920, 1080, 1080, 2400, 1f), 1e-4f)
        // Vertically the window is almost all of the picture, so its centre is held inside it.
        val shownFraction = 2400f / 2430f
        assertEquals(1f - shownFraction, pictureY(draw, 1920, 1080, 1080, 2400, 0f), 1e-4f)
        assertEquals(1f, pictureY(draw, 1920, 1080, 1080, 2400, 1f), 1e-4f)
    }

    /**
     * The picture coordinate WebRTC samples at drawn-area position [v]: the zoom window first, then
     * the renderer's own fill crop for a viewport of that size.
     */
    private fun pictureX(draw: ZoomedDraw, frameW: Int, frameH: Int, viewW: Int, viewH: Int, v: Float): Float {
        val frameAspect = frameW.toFloat() / frameH
        val viewAspect = viewW.toFloat() / viewH
        val fill = if (frameAspect > viewAspect) viewAspect / frameAspect else 1f
        return 0.5f + fill * (draw.scaleX * v + draw.offsetX - 0.5f)
    }

    private fun pictureY(draw: ZoomedDraw, frameW: Int, frameH: Int, viewW: Int, viewH: Int, v: Float): Float {
        val frameAspect = frameW.toFloat() / frameH
        val viewAspect = viewW.toFloat() / viewH
        val fill = if (frameAspect > viewAspect) 1f else frameAspect / viewAspect
        return 0.5f + fill * (draw.scaleY * v + draw.offsetY - 0.5f)
    }

    /**
     * `VoiceUser` is the reference data class again, not an interface, so `isConnected`,
     * `isSpeaking` and `isMuted` are derived rather than supplied: a disconnected participant is one
     * with no voice state at all.
     */
    private fun voiceUser(
        speaking: Boolean = false,
        ringing: Boolean = false,
        connected: Boolean = true,
        muted: Boolean = false,
    ) = StoreVoiceParticipants.VoiceUser(
        user = TestUser,
        voiceState = if (connected) {
            VoiceState(1, false, null, muted, false, false, false, false, 1, false, null, null, 1)
        } else {
            null
        },
        isRinging = ringing,
        streams = null,
        isMe = false,
        guildMember = null,
        watchingStream = null,
        streamContext = null,
        isBooster = false,
        voiceConfiguration = null,
        _isSpeaking = speaking,
    )

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
