package com.discord.utilities.presence

import com.discord.api.activity.Activity
import com.discord.api.activity.ActivityParty
import com.discord.api.activity.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The stage party id is attacker-influenced text from another user's client, so the decoder's
 * contract is "decode or return null" — never throw. These pin both halves of that.
 */
class StageCallRichPresencePartyDataTest {

    private fun activityWithParty(id: String?, size: List<Long>?): Activity = Activity(
        name = "Stage",
        type = ActivityType.LISTENING,
        url = null,
        createdAt = 0L,
        timestamps = null,
        applicationId = STAGE_PRESENCE_APPLICATION_ID,
        details = null,
        state = null,
        emoji = null,
        party = ActivityParty(id, size),
        assets = null,
        flags = null,
        syncId = null,
        sessionId = null,
        metadata = null,
        platform = null,
        supportedPlatforms = null,
        buttons = null,
    )

    @Test
    fun `decodes guild channel instance and flags`() {
        // flags 0x7 sets speaker (1), partnered (2) and verified (4) together.
        val party = activityWithParty("stage:111:222:7:333", listOf(4L, 10L))
            .stageChannelRichPresencePartyData

        assertEquals(111L, party?.guildId)
        assertEquals(222L, party?.channelId)
        assertEquals(333L, party?.stageInstanceId)
        assertEquals(true, party?.userIsSpeaker)
        assertEquals(true, party?.guildIsPartnered)
        assertEquals(true, party?.guildIsVerified)
        assertEquals(4L, party?.speakerCount)
        // Audience is derived: participants minus speakers.
        assertEquals(6L, party?.audienceSize)
    }

    @Test
    fun `flags are read as hexadecimal`() {
        // "a" is 10 == verified|speaker, and is a different value read as decimal.
        val party = activityWithParty("stage:1:2:a:3", listOf(0L, 0L))
            .stageChannelRichPresencePartyData

        assertEquals(true, party?.userIsSpeaker)
        assertEquals(false, party?.guildIsPartnered)
        assertEquals(true, party?.guildIsVerified)
    }

    @Test
    fun `audience never goes negative when the sizes disagree`() {
        val party = activityWithParty("stage:1:2:0:3", listOf(9L, 4L))
            .stageChannelRichPresencePartyData

        assertEquals(9L, party?.speakerCount)
        assertEquals(0L, party?.audienceSize)
    }

    @Test
    fun `a size list that is not a pair is treated as an empty stage`() {
        val party = activityWithParty("stage:1:2:0:3", listOf(7L))
            .stageChannelRichPresencePartyData

        assertEquals(0L, party?.speakerCount)
        assertEquals(0L, party?.audienceSize)
    }

    @Test
    fun `malformed party ids decode to null rather than throwing`() {
        val malformed = listOf(
            null,
            "",
            "notastage:1:2:0:3",
            "stage:1:2:0",
            "stage:notanumber:2:0:3",
            "stage:1:2:zz:3",
        )
        malformed.forEach { id ->
            assertNull(id, activityWithParty(id, listOf(0L, 0L)).stageChannelRichPresencePartyData)
        }
    }

    @Test
    fun `an activity with no party at all is not a stage`() {
        val activity = activityWithParty("stage:1:2:0:3", listOf(0L, 0L)).copy(party = null)
        assertNull(activity.stageChannelRichPresencePartyData)
    }
}
