package com.discord.views

import com.discord.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayMenuViewTest {
    @Test
    fun mapsInvitePermissionAndRtcQuality() {
        assertFalse(OverlayMenuView.canCreateInvite(null))
        assertFalse(OverlayMenuView.canCreateInvite(0L))
        assertTrue(OverlayMenuView.canCreateInvite(1L))
        assertEquals(
            R.drawable.ic_voice_quality_bad,
            OverlayMenuView.qualityIndicator(OverlayMenuView.Quality.BAD),
        )
    }
}
