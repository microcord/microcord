package com.discord.widgets.voice.feedback

import com.discord.widgets.guild_delete_feedback.GuildDeleteFeedback
import com.discord.widgets.guild_delete_feedback.GuildDeleteFeedbackSubmitter
import com.discord.widgets.voice.feedback.call.CallFeedbackSubmitter
import com.discord.widgets.voice.feedback.stream.StreamFeedbackSubmitter
import com.discord.widgets.feedback.PendingFeedback as FeedbackSheetPendingFeedback

object FeedbackSubmitterFactory {
    fun create(pendingFeedback: FeedbackSheetPendingFeedback): FeedbackSubmitter = when (pendingFeedback) {
        is PendingFeedback.CallFeedback -> CallFeedbackSubmitter(pendingFeedback)
        is PendingFeedback.StreamFeedback -> StreamFeedbackSubmitter(pendingFeedback)
        is PendingFeedback.GuildDeleteFeedback -> guildDeleteSubmitter(
            GuildDeleteFeedback(pendingFeedback.guildId, pendingFeedback.reason, pendingFeedback.issueDetails),
        )
        is GuildDeleteFeedback -> guildDeleteSubmitter(pendingFeedback)
        else -> error("Unsupported feedback type: ${pendingFeedback.javaClass.name}")
    }

    private fun guildDeleteSubmitter(pendingFeedback: GuildDeleteFeedback): FeedbackSubmitter {
        val submitter = GuildDeleteFeedbackSubmitter(pendingFeedback)
        return object : FeedbackSubmitter {
            override fun submit(issueDetails: String?) = submitter.submit(issueDetails)
        }
    }
}
