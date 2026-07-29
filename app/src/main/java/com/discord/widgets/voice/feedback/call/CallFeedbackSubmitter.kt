package com.discord.widgets.voice.feedback.call

import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.widgets.voice.feedback.FeedbackSubmitter
import com.discord.widgets.voice.feedback.PendingFeedback

class CallFeedbackSubmitter(
    var pendingCallFeedback: PendingFeedback.CallFeedback,
    private val report: (PendingFeedback.CallFeedback) -> Unit = ::report,
) : FeedbackSubmitter {
    override fun submit(issueDetails: String?) {
        if (issueDetails != null) pendingCallFeedback = pendingCallFeedback.copy(issueDetails = issueDetails)
        report(pendingCallFeedback)
    }

    companion object {
        private fun report(feedback: PendingFeedback.CallFeedback) {
            val properties = mutableMapOf<String, Any>(
                "channel_id" to feedback.channelId,
                "rating" to feedback.feedbackRating.analyticsValue,
            )
            feedback.rtcConnectionId?.let { properties["rtc_connection_id"] = it }
            feedback.durationMs?.let { properties["duration"] = it }
            feedback.mediaSessionId?.let { properties["media_session_id"] = it }
            feedback.reasonCode?.let { properties["reason_code"] = it }
            feedback.reasonDescription?.let { properties["reason_description"] = it }
            feedback.issueDetails?.let { properties["feedback"] = it }
            AnalyticsTracker.tracker.track("call_report_problem", properties)
        }
    }
}
