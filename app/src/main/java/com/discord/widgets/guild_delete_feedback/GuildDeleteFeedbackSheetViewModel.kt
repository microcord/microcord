package com.discord.widgets.guild_delete_feedback

import com.discord.R
import com.discord.app.AppViewModel
import com.discord.widgets.feedback.FeedbackSheetViewModel
import com.discord.widgets.voice.feedback.FeedbackIssue
import com.discord.widgets.voice.feedback.FeedbackRating
import rx.Observable
import rx.subjects.PublishSubject

/**
 * "Why are you deleting this server?" — the [FeedbackSheetViewModel] behind the guild-delete
 * variant of [com.discord.widgets.feedback.WidgetFeedbackSheet].
 *
 * Two details are load-bearing:
 *  - the six real reasons are **shuffled per showing** and "Other" is pinned last, so no reason
 *    gets a permanent position advantage;
 *  - there are no face ratings (`promptTextResId` is null), so the sheet renders reasons only.
 *
 * [onCleared] submits. Dismissing the sheet is a valid outcome — the report goes out with
 * `skipped = true` — and [submitted] makes that idempotent so an explicit tap followed by the
 * dismissal does not report twice.
 */
class GuildDeleteFeedbackSheetViewModel(guildId: Long) : AppViewModel<FeedbackSheetViewModel.ViewState>(
    FeedbackSheetViewModel.ViewState(
        selectedFeedbackRating = FeedbackRating.NO_RESPONSE,
        feedbackIssues = REASONS.shuffled() + FeedbackIssue.GUILD_DELETE_OTHER,
        titleTextResId = R.string.guild_delete_feedback_header,
        issuesHeaderTextResId = R.string.guild_delete_feedback_body,
    ),
),
    FeedbackSheetViewModel {

    private val eventSubject: PublishSubject<FeedbackSheetViewModel.Event> = PublishSubject.create()
    private var pendingFeedback = GuildDeleteFeedback(guildId)
    private var submitted = false

    override fun observeEvents(): Observable<FeedbackSheetViewModel.Event> = eventSubject

    override fun selectIssue(feedbackIssue: FeedbackIssue?, reasonDescription: String?) {
        val updated = pendingFeedback.copy(reason = feedbackIssue)
        pendingFeedback = updated
        if (feedbackIssue == FeedbackIssue.GUILD_DELETE_OTHER) {
            // "Other" is the only reason that needs free text.
            eventSubject.onNext(FeedbackSheetViewModel.Event.NavigateToIssueDetails(updated, false))
        } else {
            submitForm()
        }
    }

    override fun submitForm() {
        if (submitted) return
        submitted = true
        val hasReason = pendingFeedback.reason != null
        val submitter = GuildDeleteFeedbackSubmitter(pendingFeedback)
        if (hasReason) submitter.submit(null) else submitter.skip()
        // Only a real answer earns the thank-you confirmation.
        eventSubject.onNext(FeedbackSheetViewModel.Event.Submitted(hasReason))
    }

    override fun onCleared() {
        super.onCleared()
        submitForm()
    }

    companion object {
        /** "Other" is appended separately so it always sorts last. */
        private val REASONS = listOf(
            FeedbackIssue.GUILD_DELETE_TOO_HARD,
            FeedbackIssue.GUILD_DELETE_TEST,
            FeedbackIssue.GUILD_DELETE_ACCIDENT,
            FeedbackIssue.GUILD_DELETE_TEMPLATE,
            FeedbackIssue.GUILD_DELETE_LONELY,
            FeedbackIssue.GUILD_DELETE_INACTIVE,
        )
    }
}
