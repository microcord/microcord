package com.discord.widgets.stage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageRolesTest {
    @Test
    fun preservesRoleBitSemantics() {
        assertTrue(StageRoles.AUDIENCE.isAudience)
        assertFalse(StageRoles.AUDIENCE.isSpeaker)

        val moderator = StageRoles(isModerator = true, isInvitedToSpeak = true)
        assertTrue(moderator.isModerator)
        assertTrue(moderator.isInvitedToSpeak)
        assertFalse(moderator.isAudience)

        val speaker = StageRoles(isSpeaker = true)
        assertTrue(speaker.isSpeaker)
        assertFalse(speaker.isAudience)
    }
}
