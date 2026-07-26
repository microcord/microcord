package com.discord.widgets.guild_delete_feedback

import com.discord.widgets.voice.feedback.FeedbackIssue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GuildDeleteFeedbackSubmitterTest {

    private val reports = mutableListOf<GuildDeleteFeedbackSubmitter.Report>()

    @Before
    fun installSink() {
        reports.clear()
        GuildDeleteFeedbackSubmitter.reportSink = { reports += it }
    }

    @After
    fun removeSink() {
        GuildDeleteFeedbackSubmitter.reportSink = { }
    }

    /**
     * These labels are analytics dimension values, not user-facing copy — they are deliberately
     * fixed English and must not be swapped for the localised row text.
     */
    @Test
    fun `every guild-delete reason maps to its reference analytics label`() {
        val labels = listOf(
            FeedbackIssue.GUILD_DELETE_TOO_HARD to "Too hard",
            FeedbackIssue.GUILD_DELETE_TEST to "Testing purposes",
            FeedbackIssue.GUILD_DELETE_ACCIDENT to "Created on accident",
            FeedbackIssue.GUILD_DELETE_TEMPLATE to "Curious about server/template",
            FeedbackIssue.GUILD_DELETE_LONELY to "Empty server",
            FeedbackIssue.GUILD_DELETE_INACTIVE to "Inactive server",
            FeedbackIssue.GUILD_DELETE_OTHER to "Other",
        )
        for ((issue, expected) in labels) {
            reports.clear()
            GuildDeleteFeedbackSubmitter(GuildDeleteFeedback(1L, issue)).submit(null)
            assertEquals(expected, reports.single().reason)
        }
    }

    /** A call-quality reason can never reach this submitter; it must not be mislabelled. */
    @Test
    fun `an unrelated issue produces no reason`() {
        GuildDeleteFeedbackSubmitter(GuildDeleteFeedback(1L, FeedbackIssue.AUDIO_ECHOS)).submit(null)
        assertNull(reports.single().reason)
    }

    @Test
    fun `skipping still reports, flagged as skipped`() {
        GuildDeleteFeedbackSubmitter(GuildDeleteFeedback(77L)).skip()
        val report = reports.single()
        assertEquals("Guild Deletion", report.reportType)
        assertEquals(77L, report.guildId)
        assertTrue(report.skipped)
        assertNull(report.reason)
        assertNull(report.issueDetails)
    }

    @Test
    fun `submitting carries the free-text details and is not flagged as skipped`() {
        GuildDeleteFeedbackSubmitter(GuildDeleteFeedback(5L, FeedbackIssue.GUILD_DELETE_OTHER))
            .submit("it kept crashing")
        val report = reports.single()
        assertEquals("it kept crashing", report.issueDetails)
        assertEquals(false, report.skipped)
    }
}
