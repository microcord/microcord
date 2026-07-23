package com.discord.views

import com.discord.views.calls.StageSpeakerIndicators
import com.discord.views.calls.stageSpeakerIndicators
import com.discord.views.stages.stageCardCounts
import org.junit.Assert.assertEquals
import org.junit.Test

class StageSpeakerViewsTest {
    @Test
    fun deafenedSpeakerHidesRedundantMuteIndicator() {
        assertEquals(StageSpeakerIndicators(false, true, true, true), stageSpeakerIndicators(true, true, true, true))
    }

    @Test
    fun stageCardShowsThreeSpeakersThenRemainder() {
        assertEquals(3 to 0, stageCardCounts(3))
        assertEquals(3 to 4, stageCardCounts(7))
    }
}
