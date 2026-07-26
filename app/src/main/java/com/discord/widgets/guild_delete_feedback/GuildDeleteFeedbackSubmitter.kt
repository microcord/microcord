package com.discord.widgets.guild_delete_feedback

import com.discord.widgets.feedback.FeedbackSubmitter
import com.discord.widgets.feedback.PendingFeedback
import com.discord.widgets.voice.feedback.FeedbackIssue

/**
 * The half-filled "why did you delete this server?" form.
 *
 * The reference declares this as `PendingFeedback.GuildDeleteFeedback` inside the sealed class in
 * `widgets/voice/feedback`. **Fold it back in when that file lands**; the field set is unchanged.
 */
data class GuildDeleteFeedback(
    val guildId: Long,
    val reason: FeedbackIssue? = null,
    val issueDetails: String? = null,
) : PendingFeedback

/**
 * Reports the outcome of a server-deletion feedback prompt.
 *
 * There is no REST endpoint here — the report is an analytics event either way, and skipping is
 * itself reported (with `skipped = true`) so the drop-off rate is measurable.
 */
class GuildDeleteFeedbackSubmitter(
    val pendingFeedback: GuildDeleteFeedback,
) : FeedbackSubmitter {

    override fun submit(issueDetails: String?) {
        reportSink(
            Report(
                reportType = REPORT_TYPE,
                guildId = pendingFeedback.guildId,
                reason = getReasonFromFeedbackIssue(pendingFeedback.reason),
                issueDetails = issueDetails,
                skipped = false,
            ),
        )
    }

    /** The user dismissed the sheet without picking a reason. */
    fun skip() {
        reportSink(
            Report(
                reportType = REPORT_TYPE,
                guildId = pendingFeedback.guildId,
                reason = null,
                issueDetails = null,
                skipped = true,
            ),
        )
    }

    /**
     * The analytics labels are fixed English strings, deliberately not the localised row text —
     * they are dimension values, so they must not vary by locale.
     */
    private fun getReasonFromFeedbackIssue(issue: FeedbackIssue?): String? = when (issue) {
        FeedbackIssue.GUILD_DELETE_TOO_HARD -> "Too hard"
        FeedbackIssue.GUILD_DELETE_TEST -> "Testing purposes"
        FeedbackIssue.GUILD_DELETE_ACCIDENT -> "Created on accident"
        FeedbackIssue.GUILD_DELETE_TEMPLATE -> "Curious about server/template"
        FeedbackIssue.GUILD_DELETE_LONELY -> "Empty server"
        FeedbackIssue.GUILD_DELETE_INACTIVE -> "Inactive server"
        FeedbackIssue.GUILD_DELETE_OTHER -> "Other"
        else -> null
    }

    /** One submitted (or skipped) report, as the reference's analytics call would carry it. */
    data class Report(
        val reportType: String,
        val guildId: Long,
        val reason: String?,
        val issueDetails: String?,
        val skipped: Boolean,
    )

    companion object {
        private const val REPORT_TYPE = "Guild Deletion"

        /**
         * Reference: `AnalyticsTracker.userReportSubmitted`.
         * `com.discord.utilities.analytics.AnalyticsTracker` is not reconstructed, so the report is
         * assembled and handed here rather than invented onto another transport.
         */
        @JvmStatic
        var reportSink: (Report) -> Unit = { }
    }
}
