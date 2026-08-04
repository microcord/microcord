package com.discord.utilities.presence

import com.discord.api.activity.Activity
import com.discord.api.activity.ActivityParty
import com.discord.api.activity.ActivityTimestamps
import com.discord.api.activity.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityBadgesTest {

    private val sizeTemplate = "{count} of {max}"
    private val noMaxTemplate = "{count}"
    private val episodeTemplate = "S{seasonNum}E{episodeNum}"

    @Test fun `party text pairs the state with the size it declared`() {
        assertEquals("In Game (1 of 4)", state("In Game", listOf(1L, 4L)))
        assertEquals("In Game (2)", state("In Game", listOf(2L, 0L)))
        assertEquals("1 of 4", state(null, listOf(1L, 4L)))
        assertEquals("In Game", state("In Game", null))
        assertNull(state(null, null))
        assertNull(state(null, listOf(0L, 0L)))
    }

    @Test fun `episode text only survives a season and episode pair`() {
        assertEquals("S1E2", ActivityBadges.seasonEpisodeText("Season 1, Episode 2", episodeTemplate))
        assertNull(ActivityBadges.seasonEpisodeText("Just a caption", episodeTemplate))
        assertNull(ActivityBadges.seasonEpisodeText(null, episodeTemplate))
    }

    @Test fun `a declared start and end means the seek bar owns the elapsed time`() {
        assertTrue(ActivityBadges.showsTimeBar(activity(ActivityType.LISTENING, start = "1000", end = "2000")))
        assertFalse(ActivityBadges.showsTimeBar(activity(ActivityType.LISTENING, start = "1000")))
        assertFalse(ActivityBadges.showsTimeBar(activity(ActivityType.PLAYING, start = "1000", end = "2000")))
    }

    private fun state(state: String?, size: List<Long>?) = ActivityBadges.gameStateText(
        state,
        size?.let { ActivityParty("p", it) },
        sizeTemplate,
        noMaxTemplate,
    )

    private fun activity(
        type: ActivityType,
        start: String? = null,
        end: String? = null,
    ) = Activity(
        name = "activity", type = type, url = null, createdAt = 0L,
        timestamps = ActivityTimestamps(start, end),
        applicationId = null, details = null, state = null, emoji = null, party = null,
        assets = null, flags = null, syncId = null, sessionId = null, metadata = null,
        platform = null, supportedPlatforms = null, buttons = null,
    )
}
