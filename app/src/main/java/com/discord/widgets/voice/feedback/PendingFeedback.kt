package com.discord.widgets.voice.feedback

import com.discord.models.domain.ModelApplicationStream
import java.io.Serializable
import com.discord.widgets.feedback.PendingFeedback as FeedbackSheetPendingFeedback

sealed class PendingFeedback : FeedbackSheetPendingFeedback, Serializable {
    data class CallFeedback(
        val channelId: Long,
        val rtcConnectionId: String? = null,
        val durationMs: Long? = null,
        val mediaSessionId: String? = null,
        val feedbackRating: FeedbackRating = FeedbackRating.NO_RESPONSE,
        val reasonCode: Int? = null,
        val reasonDescription: String? = null,
        val issueDetails: String? = null,
    ) : PendingFeedback()

    data class GuildDeleteFeedback(
        val guildId: Long,
        val reason: FeedbackIssue? = null,
        val issueDetails: String? = null,
    ) : PendingFeedback()

    data class StreamFeedback(
        val stream: ModelApplicationStream,
        val feedbackRating: FeedbackRating = FeedbackRating.NO_RESPONSE,
        val issue: FeedbackIssue? = null,
        val mediaSessionId: String? = null,
        val issueDetails: String? = null,
    ) : PendingFeedback()
}
