package com.discord.utilities.presence

import com.discord.api.activity.Activity
import com.discord.api.activity.ActivityAssets
import com.discord.api.activity.ActivityParty
import com.discord.api.activity.ActivityPlatform
import com.discord.api.activity.ActivityType
import com.discord.api.activity.StatusDisplayType
import com.discord.api.presence.ClientStatus
import com.discord.api.presence.ClientStatuses
import com.discord.models.presence.Presence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceUtilsTest {

    @Test fun `primary activity skips the custom status`() = with(PresenceUtils) {
        val custom = activity(ActivityType.CUSTOM_STATUS, state = "brb")
        val game = activity(ActivityType.PLAYING, name = "Celeste")
        val presence = presence(custom, game)

        assertEquals(game, presence.primaryActivity)
        assertEquals(custom, presence.customStatusActivity)
        assertEquals(game, presence.playingActivity)
        assertNull(presence(custom).primaryActivity)
    }

    @Test fun `activity lookups by kind`() = with(PresenceUtils) {
        val streaming = activity(ActivityType.STREAMING, name = "Twitch")
        val spotify = spotifyActivity()
        val stage = activity(
            ActivityType.LISTENING, name = "Stage", applicationId = STAGE_PRESENCE_APPLICATION_ID,
        )
        val presence = presence(streaming, spotify, stage)

        assertEquals(streaming, presence.streamingActivity)
        assertEquals(spotify, presence.spotifyListeningActivity)
        assertEquals(stage, presence.stageChannelActivity)
        assertNull(presence(streaming).spotifyListeningActivity)
    }

    @Test fun `rich presence needs more than a bare name`() = with(PresenceUtils) {
        assertFalse(shouldShowRichPresenceIcon(presence(activity(ActivityType.PLAYING))))
        assertTrue(
            shouldShowRichPresenceIcon(
                presence(activity(ActivityType.PLAYING, details = "Chapter 3")),
            ),
        )
        assertTrue(
            shouldShowRichPresenceIcon(
                presence(activity(ActivityType.PLAYING, assets = ActivityAssets(null, null, null, null))),
            ),
        )
        // A custom status is never rich presence, even though it carries state.
        assertFalse(
            shouldShowRichPresenceIcon(
                presence(activity(ActivityType.CUSTOM_STATUS, state = "brb")),
            ),
        )
        assertFalse(shouldShowRichPresenceIcon(null))
    }

    @Test fun `activity comparator ranks the richest newest activity last`() = with(PresenceUtils) {
        val plainOld = activity(ActivityType.PLAYING, createdAt = 1)
        val plainNew = activity(ActivityType.PLAYING, createdAt = 2)
        val rich = activity(ActivityType.PLAYING, createdAt = 1, details = "boss fight")
        val sorted = listOf(rich, plainNew, plainOld).sortedWith(ACTIVITY_COMPARATOR)
        assertEquals(listOf(plainOld, plainNew, rich), sorted)
    }

    @Test fun `mobile indicator needs mobile online and no desktop or web`() = with(PresenceUtils) {
        assertTrue(statuses(mobile = ClientStatus.ONLINE).isMobile)
        assertFalse(
            statuses(mobile = ClientStatus.ONLINE, desktop = ClientStatus.ONLINE).isMobile,
        )
        assertFalse(statuses(mobile = ClientStatus.ONLINE, web = ClientStatus.ONLINE).isMobile)
        assertFalse(statuses(mobile = ClientStatus.IDLE).isMobile)
        assertFalse(statuses().isMobile)
    }

    @Test fun `party sizes read the first and last entries`() = with(PresenceUtils) {
        val party = ActivityParty("id", listOf(2L, 5L))
        assertEquals(2L, party.currentSize)
        assertEquals(5L, party.maxSize)
        assertEquals(3L, party.numOpenSlots)
        val empty = ActivityParty("id", null)
        assertEquals(0L, empty.currentSize)
        assertEquals(0L, empty.maxSize)
    }

    @Test fun `platform and application predicates`() {
        assertTrue(activity(ActivityType.PLAYING, platform = ActivityPlatform.XBOX).isXboxActivity())
        assertTrue(activity(ActivityType.PLAYING, platform = ActivityPlatform.PS5).isGamePlatform())
        assertFalse(activity(ActivityType.PLAYING, platform = ActivityPlatform.DESKTOP).isGamePlatform())
        assertEquals(
            ActivityPlatform.DESKTOP,
            activity(ActivityType.PLAYING).getGamePlatform(),
        )
        assertNull(activity(ActivityType.LISTENING).getGamePlatform())
        assertTrue(activity(ActivityType.PLAYING, platform = ActivityPlatform.ANDROID).isCurrentPlatform())
        assertFalse(activity(ActivityType.PLAYING, platform = ActivityPlatform.IOS).isCurrentPlatform())
    }

    @Test fun `spotify needs a listening session, not just the name`() {
        assertTrue(spotifyActivity().isSpotifyActivity())
        assertFalse(activity(ActivityType.LISTENING, name = "Spotify").isSpotifyActivity())
        assertFalse(spotifyActivity(partyId = "not-spotify").isSpotifyActivity())
        assertFalse(spotifyActivity(type = ActivityType.PLAYING).isSpotifyActivity())
        assertFalse(activity(ActivityType.LISTENING, name = "Apple Music").isSpotifyActivity())
    }

    @Test fun `spotify artists come from the semicolon separated state`() {
        assertEquals("A, B", spotifyActivity(state = "A; B").spotifyArtists())
        assertNull(spotifyActivity(state = "  ").spotifyArtists())
    }

    @Test fun `status display type picks the field it names`() {
        val a = activity(ActivityType.PLAYING, name = "Game", details = "Level 3", state = "In a match")
        assertEquals("Game", a.statusDisplayText())
        assertEquals("In a match", a.copy(statusDisplayType = StatusDisplayType.STATE).statusDisplayText())
        assertEquals("Level 3", a.copy(statusDisplayType = StatusDisplayType.DETAILS).statusDisplayText())
        // Only streaming prefers details when nothing is declared.
        assertEquals("Level 3", a.copy(type = ActivityType.STREAMING).statusDisplayText())
        // A named field that is absent falls back rather than blanking the line.
        assertEquals("Game", a.copy(state = null, statusDisplayType = StatusDisplayType.STATE).statusDisplayText())
    }

    private fun presence(vararg activities: Activity) =
        Presence(ClientStatus.ONLINE, null, activities.toList())

    private fun statuses(
        desktop: ClientStatus? = null,
        web: ClientStatus? = null,
        mobile: ClientStatus? = null,
    ) = ClientStatuses(desktop, web, mobile)

    private fun spotifyActivity(
        type: ActivityType = ActivityType.LISTENING,
        state: String? = null,
        partyId: String? = "spotify:abc",
    ) = activity(type, name = "Spotify", state = state).copy(party = ActivityParty(partyId, null))

    private fun activity(
        type: ActivityType,
        name: String = "activity",
        createdAt: Long = 0,
        applicationId: Long? = null,
        details: String? = null,
        state: String? = null,
        assets: ActivityAssets? = null,
        platform: ActivityPlatform? = null,
    ) = Activity(
        name = name, type = type, url = null, createdAt = createdAt, timestamps = null,
        applicationId = applicationId, details = details, state = state, emoji = null,
        party = null, assets = assets, flags = null, syncId = null, sessionId = null,
        metadata = null, platform = platform, supportedPlatforms = null, buttons = null,
    )
}
