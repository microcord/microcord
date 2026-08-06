package com.discord.utilities.attachments

import com.discord.api.guild.GuildFeature
import com.discord.models.guild.Guild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val MB = 1024L * 1024L

class AttachmentLimitsTest {

    @Test
    fun `a file at the limit is allowed and one past it is not`() {
        assertNull(AttachmentLimits.validate(listOf(10 * MB), 10 * MB))
        assertEquals(
            AttachmentLimits.Violation.FileTooLarge(10 * MB + 1, 10 * MB),
            AttachmentLimits.validate(listOf(10 * MB + 1), 10 * MB),
        )
    }

    @Test
    fun `the per-file limit applies to each file, not to their sum`() {
        // Five files that together exceed the per-file ceiling are still fine — the old check
        // summed them and rejected this.
        assertNull(AttachmentLimits.validate(List(5) { 8 * MB }, 10 * MB))
    }

    @Test
    fun `the message total is its own ceiling that no entitlement raises`() {
        val huge = List(3) { 200 * MB }
        assertEquals(
            AttachmentLimits.Violation.TotalTooLarge(600 * MB),
            AttachmentLimits.validate(huge, 500 * MB),
        )
    }

    @Test
    fun `too many files is reported before any size is looked at`() {
        assertEquals(
            AttachmentLimits.Violation.TooManyFiles(11),
            AttachmentLimits.validate(List(11) { 1L }, 10 * MB),
        )
    }

    @Test
    fun `the guild contributes the largest size powerup it holds`() {
        val guild = Guild(
            id = 1,
            features = setOf(GuildFeature.MAX_FILE_SIZE_50_MB, GuildFeature.MAX_FILE_SIZE_250_MB),
        )
        assertEquals(250 * MB, AttachmentLimits.guildMaxFileSize(guild))
        assertEquals(10 * MB, AttachmentLimits.guildMaxFileSize(Guild(id = 1)))
    }

    @Test
    fun `a dm gets no guild allowance`() {
        assertEquals(10 * MB, AttachmentLimits.maxFileSize(null, null))
    }
}
