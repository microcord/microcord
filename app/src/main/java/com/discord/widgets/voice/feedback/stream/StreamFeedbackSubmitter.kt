package com.discord.widgets.voice.feedback.stream

import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.widgets.voice.feedback.FeedbackIssue
import com.discord.widgets.voice.feedback.FeedbackSubmitter
import com.discord.widgets.voice.feedback.PendingFeedback

class StreamFeedbackSubmitter(
    var pendingStreamFeedback: PendingFeedback.StreamFeedback,
    private val report: (PendingFeedback.StreamFeedback) -> Unit = ::report,
) : FeedbackSubmitter {
    override fun submit(issueDetails: String?) {
        if (issueDetails != null) pendingStreamFeedback = pendingStreamFeedback.copy(issueDetails = issueDetails)
        report(pendingStreamFeedback)
    }

    companion object {
        private fun report(feedback: PendingFeedback.StreamFeedback) {
            val properties = mutableMapOf<String, Any>(
                "streamer_user_id" to feedback.stream.ownerId.toString(),
                "stream_channel_id" to feedback.stream.channelId.toString(),
                "rating" to feedback.feedbackRating.analyticsValue,
            )
            (feedback.stream as? com.discord.models.domain.ModelApplicationStream.GuildStream)?.let {
                properties["guild_id"] = it.guildId.toString()
            }
            feedback.issue?.let { properties["reason"] = reason(it) }
            feedback.mediaSessionId?.let { properties["media_session_id"] = it }
            feedback.issueDetails?.let { properties["feedback"] = it }
            AnalyticsTracker.tracker.track("stream_report_problem", properties)
        }

        internal fun reason(issue: FeedbackIssue): String = when (issue) {
            FeedbackIssue.STREAM_REPORT_ENDED_BLACK -> "BLACK_SCREEN"
            FeedbackIssue.STREAM_REPORT_ENDED_BLURRY -> "BLURRY"
            FeedbackIssue.STREAM_REPORT_ENDED_LAGGING -> "LAGGING"
            FeedbackIssue.STREAM_REPORT_ENDED_OUT_OF_SYNC -> "OUT_OF_SYNC"
            FeedbackIssue.STREAM_REPORT_ENDED_AUDIO_MISSING -> "AUDIO_MISSING"
            FeedbackIssue.STREAM_REPORT_ENDED_AUDIO_POOR -> "AUDIO_POOR"
            FeedbackIssue.STREAM_REPORT_ENDED_STREAM_STOPPED_UNEXPECTEDLY -> "STREAM_STOPPED_UNEXPECTEDLY"
            else -> "OTHER"
        }
    }
}
